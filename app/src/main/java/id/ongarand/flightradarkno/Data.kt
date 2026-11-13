package id.ongarand.flightradarkno

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
class HttpErr(val code: Int) : RuntimeException("HTTP $code")

const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

data class Flight(
    val id: String, val number: String,
    val airline: String, val airlineIata: String, val airlineIcao: String,
    val city: String, val iata: String, val country: String,
    val arrival: Boolean, val international: Boolean,
    val sched: Long, val est: Long, val real: Long, val destEta: Long,
    val aircraft: String, val reg: String, val status: String, val live: Boolean
) {
    val actual: Long get() = if (real > 0) real else est
    val delayMin: Int get() = if (actual > 0 && sched > 0) ((actual - sched) / 60).toInt() else 0
    val cancelled: Boolean get() = status.contains("cancel", true)
    val departed: Boolean get() = !arrival && (real > 0 || status.contains("departed", true))

    /** Penerbangan sudah selesai (kedatangan: sudah mendarat; keberangkatan: sudah tiba di tujuan). */
    val done: Boolean
        get() {
            val now = Fmt.now()
            return if (arrival) real > 0 || status.contains("landed", true)
            else departed && (status.contains("landed", true) || (destEta in 1L until now))
        }
    val delayed: Boolean get() = !cancelled && (delayMin >= 10 || status.contains("delay", true))

    /** Merah HANYA untuk flight delay yang belum terbang atau sedang terbang. */
    val red: Boolean get() = delayed && !done

    /** Kunci unik flight (stabil walau jam estimasi berubah). */
    val key: String get() = "$number|$sched|${if (arrival) "A" else "D"}"
}

object Fmt {
    const val TZ_OFFSET = 7 * 3600L
    private val tz = TimeZone.getTimeZone("Asia/Jakarta")
    private val idLoc = Locale("id", "ID")

    fun now(): Long = System.currentTimeMillis() / 1000
    fun time(epoch: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(Date(epoch * 1000))
    fun dateLabel(epoch: Long): String =
        SimpleDateFormat("EEEE, dd MMMM yyyy", idLoc).apply { timeZone = tz }.format(Date(epoch * 1000))
    fun dateShort(epoch: Long): String =
        SimpleDateFormat("EEE, dd MMM", idLoc).apply { timeZone = tz }.format(Date(epoch * 1000))
    fun dateFull(epoch: Long): String =
        SimpleDateFormat("EEEE, d MMMM yyyy", idLoc).apply { timeZone = tz }.format(Date(epoch * 1000))
    fun dayMonth(epoch: Long): String =
        SimpleDateFormat("d/M", Locale.US).apply { timeZone = tz }.format(Date(epoch * 1000))
    fun timeDot(epoch: Long): String = time(epoch).replace(':', '.')
    fun dayKey(epoch: Long): Long = Math.floorDiv(epoch + TZ_OFFSET, 86400L)
    fun dayStart(dayKey: Long): Long = dayKey * 86400L - TZ_OFFSET
    fun minuteOfDay(epoch: Long): Int = (Math.floorMod(epoch + TZ_OFFSET, 86400L) / 60L).toInt()
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    fun dayOffset(epoch: Long): Int = (dayKey(epoch) - dayKey(now())).toInt()
    fun range(s: Long, e: Long): String =
        "${dateShort(s)} ${time(s)} → ${dateShort(e)} ${time(e)}"
    fun dayLabel(epoch: Long): String = when (dayOffset(epoch)) { 1 -> "Besok"; -1 -> "Kemarin"; else -> "" }

    fun ymd(dayKey: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance(tz).apply { timeInMillis = (dayStart(dayKey) + 12 * 3600) * 1000 }
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }
    fun dayKeyOf(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance(tz)
        c.clear(); c.set(y, m, d, 12, 0, 0)
        return dayKey(c.timeInMillis / 1000)
    }

    fun idStatus(s: String): String = s
        .replace("Estimated dep", "Est. berangkat", true)
        .replace("Estimated", "Estimasi", true)
        .replace("Landed", "Mendarat", true)
        .replace("Departed", "Berangkat", true)
        .replace("Delayed", "Delay", true)
        .replace("Scheduled", "Terjadwal", true)
        .replace("Canceled", "Dibatalkan", true)
        .replace("Cancelled", "Dibatalkan", true)
        .replace("Diverted", "Dialihkan", true)
}

/** Posisi pesawat live (feed Flightradar24). */
data class Aircraft(
    val id: String, val lat: Double, val lon: Double, val track: Int, val alt: Int, val spd: Int,
    val type: String, val reg: String, val from: String, val to: String, val number: String,
    val onGround: Boolean, val vrate: Int, val callsign: String
)

data class TrailPt(val lat: Double, val lon: Double, val alt: Int, val spd: Int, val hd: Int, val ts: Long)

/**
 * Sumber data: Flightradar24. Semua endpoint di bawah TIDAK resmi/publik -- lihat README.
 */
object Fr24 {
    private fun s(o: JSONObject?, k: String) = if (o == null || o.isNull(k)) "" else o.optString(k)

    @Volatile private var webUntil = 0L

    /** Langsung via HTTP; jika diblokir (403/429/503) beralih ke WebView selama 30 menit. */
    private fun http(url: String): String {
        if (Fmt.now() >= webUntil) {
            try {
                return direct(url)
            } catch (e: HttpErr) {
                if (e.code != 403 && e.code != 429 && e.code != 503) throw e
                webUntil = Fmt.now() + 1800
            }
        }
        return try {
            WebFetch.get(url)
        } catch (e: Exception) {
            throw RuntimeException("Diblokir Flightradar24 (HTTP 403); cadangan WebView gagal: ${e.message}")
        }
    }

    private fun direct(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA_DESKTOP)
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Cache-Control", "max-age=0")
        c.setRequestProperty("Referer", "https://www.flightradar24.com/")
        c.setRequestProperty("Origin", "https://www.flightradar24.com")
        c.setRequestProperty("Sec-Fetch-Dest", "empty")
        c.setRequestProperty("Sec-Fetch-Mode", "cors")
        c.setRequestProperty("Sec-Fetch-Site", "same-site")
        if (c.responseCode != 200) throw HttpErr(c.responseCode)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * @param earlier jumlah halaman "flight sebelumnya" yang ikut dimuat
     * (tiap halaman = 20 flight sebelum waktu sekarang; halaman negatif di FR24).
     */
    fun fetch(arrival: Boolean, earlier: Int = 0): List<Flight> {
        val out = mutableListOf<Flight>()
        for (page in 1..3) {
            val (list, total) = load(arrival, page, 100)
            out += list
            if (list.isEmpty() || page >= total) break
        }
        for (e in 1..earlier) {
            val list = try { load(arrival, -e, 20).first } catch (ex: Exception) { break }
            if (list.isEmpty()) break
            out += list
        }
        return out.distinctBy { it.number + it.sched }
    }

    private fun load(arrival: Boolean, page: Int, limit: Int): Pair<List<Flight>, Int> {
        val mode = if (arrival) "arrivals" else "departures"
        val url = "https://api.flightradar24.com/common/v1/airport.json?code=kno" +
            "&plugin%5B%5D=schedule&plugin-setting%5Bschedule%5D%5Bmode%5D=$mode" +
            "&plugin-setting%5Bschedule%5D%5Btimestamp%5D=${Fmt.now()}" +
            "&page=$page&limit=$limit"
        val sched = JSONObject(http(url)).optJSONObject("result")?.optJSONObject("response")
            ?.optJSONObject("airport")?.optJSONObject("pluginData")
            ?.optJSONObject("schedule")?.optJSONObject(mode) ?: return Pair(emptyList(), 0)
        val data: JSONArray = sched.optJSONArray("data") ?: return Pair(emptyList(), 0)
        val res = (0 until data.length()).mapNotNull { parse(data.getJSONObject(it), arrival) }
        val total = sched.optJSONObject("page")?.optInt("total", 1) ?: 1
        return Pair(res, total)
    }

    private fun parse(item: JSONObject, arrival: Boolean): Flight? {
        val f = item.optJSONObject("flight") ?: return null
        val ident = f.optJSONObject("identification")
        val number = s(ident?.optJSONObject("number"), "default")
        if (number.isEmpty()) return null
        val ap = f.optJSONObject("airport")?.optJSONObject(if (arrival) "origin" else "destination")
        val pos = ap?.optJSONObject("position")?.optJSONObject("country")
        val cc = s(pos, "code"); val cn = s(pos, "name")
        val intl = if (cc.isNotEmpty()) !cc.equals("ID", true)
        else cn.isNotEmpty() && !cn.equals("Indonesia", true)
        val al = f.optJSONObject("airline")
        val alCode = al?.optJSONObject("code")
        val t = f.optJSONObject("time")
        fun ts(group: String, key: String): Long =
            t?.optJSONObject(group)?.let { if (it.isNull(key)) 0L else it.optLong(key, 0L) } ?: 0L
        val key = if (arrival) "arrival" else "departure"
        val destEta = ts("real", "arrival").takeIf { it > 0L }
            ?: ts("estimated", "arrival").takeIf { it > 0L } ?: ts("scheduled", "arrival")
        val st = f.optJSONObject("status")
        val ac = f.optJSONObject("aircraft")
        return Flight(
            id = s(ident, "id"), number = number,
            airline = s(al, "name"), airlineIata = s(alCode, "iata"), airlineIcao = s(alCode, "icao"),
            city = s(ap, "name").replace(" Airport", "").replace(" International", ""),
            iata = s(ap?.optJSONObject("code"), "iata"),
            country = cn, arrival = arrival, international = intl,
            sched = ts("scheduled", key), est = ts("estimated", key), real = ts("real", key),
            destEta = if (arrival) 0L else destEta,
            aircraft = s(ac?.optJSONObject("model"), "code"), reg = s(ac, "registration"),
            status = s(st, "text"), live = st?.optBoolean("live", false) ?: false
        )
    }

    /** Pesawat di sekitar KNO. Indeks array mengikuti feed FR24. */
    fun live(north: Double, south: Double, west: Double, east: Double): List<Aircraft> {
        val url = "https://data-cloud.flightradar24.com/zones/fcgi/feed.js?faa=1&bounds=$north,$south,$west,$east" +
            "&satellite=1&mlat=1&flarm=1&adsb=1&gnd=0&air=1&vehicles=0&estimated=1&maxage=14400&gliders=0&stats=0"
        val o = JSONObject(http(url))
        val out = mutableListOf<Aircraft>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = o.optJSONArray(k) ?: continue
            if (a.length() < 17) continue
            out += Aircraft(
                id = k, lat = a.optDouble(1), lon = a.optDouble(2), track = a.optInt(3), alt = a.optInt(4),
                spd = a.optInt(5), type = a.optString(8), reg = a.optString(9), from = a.optString(11),
                to = a.optString(12), number = a.optString(13), onGround = a.optInt(14) == 1,
                vrate = a.optInt(15), callsign = a.optString(16)
            )
        }
        return out
    }

    /** Jejak posisi sebuah flight, urut dari paling lama ke paling baru (untuk playback). */
    fun trail(id: String): List<TrailPt> {
        val o = JSONObject(http("https://data-live.flightradar24.com/clickhandler/?version=1.5&flight=$id"))
        val arr = o.optJSONArray("trail") ?: return emptyList()
        return (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            TrailPt(p.optDouble("lat"), p.optDouble("lng"), p.optInt("alt"), p.optInt("spd"), p.optInt("hd"), p.optLong("ts"))
        }.reversed()
    }
}

/** Logo maskapai. Dicoba beberapa sumber berurutan. */
object Logos {
    fun urls(iata: String, icao: String): List<String> = listOfNotNull(
        if (iata.isNotEmpty() && icao.isNotEmpty()) "https://cdn.flightradar24.com/assets/airlines/logotypes/${iata}_${icao}.png" else null,
        if (icao.isNotEmpty()) "https://www.flightradar24.com/static/images/data/operators/${icao}_logo0.png" else null,
        if (iata.isNotEmpty()) "https://pics.avs.io/200/200/$iata.png" else null
    )

    /** Untuk widget: unduh + simpan di cache disk. */
    fun bitmap(c: Context, iata: String, icao: String): Bitmap? {
        val key = "${iata}_$icao"
        if (key == "_") return null
        val f = File(c.cacheDir, "logo_$key.png")
        if (f.exists() && f.length() > 0) return BitmapFactory.decodeFile(f.path)
        for (u in urls(iata, icao)) {
            try {
                val con = URL(u).openConnection() as HttpURLConnection
                con.connectTimeout = 6000; con.readTimeout = 8000
                con.setRequestProperty("User-Agent", UA)
                if (con.responseCode != 200) continue
                val bytes = con.inputStream.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val sc = 96f / maxOf(bmp.width, bmp.height)
                val scaled = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true
                )
                FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return scaled
            } catch (e: Exception) { /* coba sumber berikutnya */ }
        }
        return null
    }
}

fun inWindow(f: Flight, start: Int, end: Int): Boolean {
    val m = Fmt.minuteOfDay(f.sched)
    return if (start <= end) m in start..end else (m >= start || m <= end)
}

data class WidgetCfg(val intl: Boolean)

object Store {
    const val DEFAULT_ACCENT = 0xFF1565C0.toInt()
    fun p(c: Context) = c.getSharedPreferences("kno", 0)
    fun accent(c: Context) = p(c).getInt("accent", DEFAULT_ACCENT)

    fun widget(c: Context, id: Int): WidgetCfg {
        val s = p(c).getString("w$id", null) ?: return WidgetCfg(true)
        val o = JSONObject(s)
        return WidgetCfg(o.optBoolean("intl", true))
    }
    fun saveWidget(c: Context, id: Int, w: WidgetCfg) {
        p(c).edit().putString("w$id", JSONObject().put("intl", w.intl).toString()).apply()
    }
    fun deleteWidget(c: Context, id: Int) { p(c).edit().remove("w$id").apply() }
}
