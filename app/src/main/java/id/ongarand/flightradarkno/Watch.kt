package id.ongarand.flightradarkno

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject

/** Satu flight yang dipantau: notif 15 menit sebelum (pre) dan/atau saat landing/berangkat (at). */
data class Watch(
    val key: String, val number: String, val city: String, val iata: String, val arrival: Boolean,
    val sched: Long, val eta: Long, val pre: Boolean, val at: Boolean, val firedPre: Boolean, val firedAt: Boolean
)

object Notif {
    const val CH = "flights"

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Notifikasi flight KNO", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun show(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channel(c)
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

object WatchManager {
    private const val ACTION = "id.ongarand.flightradarkno.WATCH"
    private const val PRE = 900L   // 15 menit

    fun all(c: Context): List<Watch> {
        val a = JSONArray(Store.p(c).getString("watch", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Watch(o.getString("key"), o.getString("number"), o.getString("city"), o.getString("iata"),
                o.getBoolean("arrival"), o.getLong("sched"), o.getLong("eta"), o.getBoolean("pre"),
                o.getBoolean("at"), o.getBoolean("fpre"), o.getBoolean("fat"))
        }
    }

    private fun save(c: Context, l: List<Watch>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("key", it.key).put("number", it.number).put("city", it.city).put("iata", it.iata)
                .put("arrival", it.arrival).put("sched", it.sched).put("eta", it.eta).put("pre", it.pre)
                .put("at", it.at).put("fpre", it.firedPre).put("fat", it.firedAt))
        }
        Store.p(c).edit().putString("watch", a.toString()).apply()
    }

    fun eta(f: Flight): Long = if (f.actual > 0L) f.actual else f.sched

    /** Dipanggil saat centang diubah. */
    fun set(c: Context, f: Flight, pre: Boolean, at: Boolean) {
        val l = all(c).filter { it.key != f.key }.toMutableList()
        if (pre || at) l += Watch(f.key, f.number, f.city, f.iata, f.arrival, f.sched, eta(f), pre, at, false, false)
        save(c, l)
        if (!pre && !at) { cancelAlarm(c, f.key, "PRE"); cancelAlarm(c, f.key, "AT") }
        reschedule(c)
    }

    private fun pending(c: Context, key: String, type: String): PendingIntent {
        val i = Intent(c, WatchReceiver::class.java).setAction(ACTION)
            .putExtra("key", key).putExtra("type", type)
        return PendingIntent.getBroadcast(
            c, (key + type).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun cancelAlarm(c: Context, key: String, type: String) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, key, type))
    }

    private fun setAlarm(c: Context, atSec: Long, pi: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        val ms = atSec * 1000L
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)      // kurang presisi
        else
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    /** Pasang ulang alarm sesuai ETA terbaru, bersihkan yang sudah selesai. */
    fun reschedule(c: Context) {
        val now = Fmt.now()
        val out = mutableListOf<Watch>()
        for (w0 in all(c)) {
            var w = w0
            if (w.eta < now - 3600L) { cancelAlarm(c, w.key, "PRE"); cancelAlarm(c, w.key, "AT"); continue }
            if (w.pre && !w.firedPre) {
                val t = w.eta - PRE
                if (t > now) setAlarm(c, t, pending(c, w.key, "PRE"))
                else { cancelAlarm(c, w.key, "PRE"); w = w.copy(firedPre = true) }   // sudah kurang dari 15 menit
            }
            if (w.at && !w.firedAt && w.eta > now) setAlarm(c, w.eta, pending(c, w.key, "AT"))
            if ((!w.pre || w.firedPre) && (!w.at || w.firedAt)) {
                cancelAlarm(c, w.key, "AT"); continue
            }
            out += w
        }
        save(c, out)
    }

    private fun place(w: Watch) = (if (w.arrival) "dari " else "ke ") + "${w.city} (${w.iata})"

    /** Dipanggil receiver saat alarm berbunyi. */
    fun fire(c: Context, key: String, type: String) {
        val l = all(c).toMutableList()
        val i = l.indexOfFirst { it.key == key }
        if (i < 0) return
        val w = l[i]
        val tag = if (w.arrival) "ETA" else "ETD"
        if (type == "PRE" && w.pre && !w.firedPre) {
            Notif.show(c, (key + "P").hashCode(),
                "✈ ${w.number} ${if (w.arrival) "mendarat" else "berangkat"} ±15 menit lagi",
                "${place(w)} · $tag ${Fmt.time(w.eta)} WIB")
            l[i] = w.copy(firedPre = true)
        } else if (type == "AT" && w.at && !w.firedAt) {
            Notif.show(c, (key + "A").hashCode(),
                "✈ ${w.number} ${if (w.arrival) "mendarat" else "berangkat"} sekarang",
                "${place(w)} · $tag ${Fmt.time(w.eta)} WIB")
            l[i] = w.copy(firedAt = true, firedPre = true)
        }
        save(c, l)
        reschedule(c)
    }

    /** Ambil data terbaru: perbarui ETA, kirim notif jika sudah mendarat/berangkat, atau dibatalkan. */
    fun sync(c: Context) {
        val ws = all(c)
        if (ws.isEmpty()) return
        val flights = mutableListOf<Flight>()
        if (ws.any { it.arrival }) flights += Fr24.fetch(true, 2)
        if (ws.any { !it.arrival }) flights += Fr24.fetch(false, 2)
        val map = flights.associateBy { it.key }
        val out = mutableListOf<Watch>()
        for (w0 in ws) {
            var w = w0
            val f = map[w.key]
            if (f != null) {
                w = w.copy(eta = eta(f))
                if (f.cancelled) {
                    Notif.show(c, (w.key + "X").hashCode(), "✈ ${w.number} dibatalkan", "${w.city} (${w.iata})")
                    cancelAlarm(c, w.key, "PRE"); cancelAlarm(c, w.key, "AT")
                    continue
                }
                val happened = if (w.arrival) (f.real > 0L || f.status.contains("landed", true)) else f.departed
                if (happened) {
                    if (w.at && !w.firedAt) {
                        Notif.show(c, (w.key + "A").hashCode(),
                            "✈ ${w.number} ${if (w.arrival) "telah mendarat" else "telah berangkat"}",
                            "${place(w)} · ${Fmt.time(if (f.real > 0L) f.real else w.eta)} WIB")
                    }
                    w = w.copy(firedPre = true, firedAt = true)
                }
            }
            out += w
        }
        save(c, out)
        reschedule(c)
    }
}

class WatchReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        AppCtx.set(c)
        val key = i.getStringExtra("key") ?: return
        val type = i.getStringExtra("type") ?: return
        WatchManager.fire(c, key, type)
        // segera perbarui ETA / status dari data terbaru
        WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<RefreshWorker>().build())
    }
}

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            AppCtx.set(c)
            WatchManager.reschedule(c)
            Refresh.schedule(c)
        }
    }
}
