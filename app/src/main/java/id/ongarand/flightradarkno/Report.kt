package id.ongarand.flightradarkno

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Format jam shift di teks laporan: tetap, tidak peduli jam berapa data ditarik. */
object ShiftFmt {
    fun hours(code: String): Pair<String, String> = when (code) {
        "P" -> "06.00" to "14.00"
        "S" -> "14.00" to "22.00"
        else -> "22.00" to "06.00"
    }
    fun salam(code: String) = when (code) { "P" -> "Selamat pagi"; "S" -> "Selamat siang"; else -> "Selamat malam" }
    /** Rentang tarik data bawaan (menit dalam sehari) untuk tiap shift; malam melewati tengah malam. */
    fun range(code: String): Pair<Int, Int> = when (code) {
        "P" -> 360 to 840
        "S" -> 840 to 1320
        else -> 1320 to 360
    }
}

/** Flight yang dikecualikan dari laporan. scope: "ARR", "DEP", atau "BOTH". */
data class Excl(val text: String, val scope: String)

object ReportEx {
    fun load(c: Context): List<Excl> {
        val a = JSONArray(Store.p(c).getString("rep_excl", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Excl(o.getString("t"), o.getString("s")) }
    }
    fun save(c: Context, l: List<Excl>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.text).put("s", it.scope)) }
        Store.p(c).edit().putString("rep_excl", a.toString()).apply()
    }
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Cocok jika sama dengan nomor flight, kode IATA/ICAO maskapai, atau nama maskapai memuat teksnya. */
    fun matches(e: Excl, f: Flight): Boolean {
        val applies = e.scope == "BOTH" || (e.scope == "ARR" && f.arrival) || (e.scope == "DEP" && !f.arrival)
        if (!applies) return false
        val n = norm(e.text)
        if (n.isEmpty()) return false
        return n == norm(f.number) || n == f.airlineIata.uppercase() || n == f.airlineIcao.uppercase() ||
            (n.length >= 3 && norm(f.airline).contains(n))
    }
}

object Report {
    val DEFAULT_TEMPLATE =
        "{SALAM} Bapak Kaunit, Bapak/Ibu Ast. Spv dan rekan sekalian.\n" +
        "Mohon izin menyampaikan jadwal penerbangan shift {SHIFT} pukul {JAM_MULAI} s/d {JAM_SELESAI} WIB\n" +
        "{TANGGAL}\n\n" +
        "*Kedatangan*\n{KEDATANGAN}\n\n" +
        "*Keberangkatan*\n{KEBERANGKATAN}\n\n" +
        "Terima kasih."
    const val DEFAULT_ARR = "{NO}. {MASKAPAI} {NOMOR} ETA {JAM} ({KOTA}/{IATA})"
    const val DEFAULT_DEP = "{NO}. {MASKAPAI} {NOMOR} ETD {JAM} ({KOTA}/{IATA})"

    private val AIRLINES = mapOf(
        "AK" to "Air Asia", "QZ" to "Air Asia", "D7" to "Air Asia X", "FD" to "Thai Air Asia", "MH" to "Malaysia Airlines",
        "TR" to "Scoot", "JT" to "Lion Air", "SQ" to "Singapore Airlines", "ID" to "Batik Air", "OD" to "Batik Air Malaysia",
        "GA" to "Garuda Indonesia", "QG" to "Citilink", "IU" to "Super Air Jet", "IW" to "Wings Air", "SJ" to "Sriwijaya Air",
        "TG" to "Thai Airways", "SL" to "Thai Lion Air", "SV" to "Saudia", "QR" to "Qatar Airways", "EK" to "Emirates",
        "EY" to "Etihad", "WY" to "Oman Air", "TK" to "Turkish Airlines", "CX" to "Cathay Pacific", "CZ" to "China Southern",
        "MU" to "China Eastern", "FY" to "Firefly", "3K" to "Jetstar Asia", "AI" to "Air India", "6E" to "IndiGo",
        "KE" to "Korean Air", "GF" to "Gulf Air", "KU" to "Kuwait Airways", "XY" to "Flynas", "VZ" to "Thai Vietjet"
    )
    private val CITIES = mapOf(
        "KUL" to "Kuala Lumpur", "SIN" to "Singapura", "PEN" to "Penang", "DMK" to "Don Mueang", "BKK" to "Suvarnabhumi",
        "LGK" to "Langkawi", "JHB" to "Johor Bahru", "SZB" to "Subang", "KCH" to "Kuching", "BKI" to "Kota Kinabalu",
        "MCT" to "Muscat", "JED" to "Jeddah", "MED" to "Madinah", "DOH" to "Doha", "DXB" to "Dubai", "AUH" to "Abu Dhabi",
        "SHJ" to "Sharjah", "RUH" to "Riyadh", "KWI" to "Kuwait", "IST" to "Istanbul", "HKG" to "Hong Kong",
        "CAN" to "Guangzhou", "PVG" to "Shanghai", "MAA" to "Chennai", "CCU" to "Kolkata", "DEL" to "Delhi",
        "BOM" to "Mumbai", "TPE" to "Taipei", "ICN" to "Seoul", "NRT" to "Tokyo", "PNH" to "Phnom Penh",
        "HAN" to "Hanoi", "SGN" to "Ho Chi Minh", "DAC" to "Dhaka", "CMB" to "Kolombo"
    )

    private fun eta(f: Flight): Long = if (f.actual > 0L) f.actual else f.sched
    private fun airline(f: Flight) = AIRLINES[f.airlineIata] ?: f.airline
    private fun city(f: Flight) = CITIES[f.iata] ?: f.city

    private fun line(fmt: String, no: Int, f: Flight, startDay: Long): String {
        val e = eta(f)
        var jam = Fmt.timeDot(e)
        if (Fmt.dayKey(e) != startDay) jam += " (${Fmt.dayMonth(e)})"
        return fmt
            .replace("{NO}", no.toString())
            .replace("{MASKAPAI}", airline(f))
            .replace("{NOMOR}", f.number)
            .replace("{JAM}", jam)
            .replace("{JADWAL}", Fmt.timeDot(f.sched))
            .replace("{KOTA}", city(f))
            .replace("{IATA}", f.iata)
            .replace("{NEGARA}", f.country)
            .replace("{PESAWAT}", f.aircraft)
            .replace("{DELAY}", if (f.delayMin >= 10) "DELAY +${f.delayMin}m" else "")
            .trimEnd()
    }

    /** Hanya flight internasional (tidak dibatalkan, tidak dikecualikan) yang ETA/ETD-nya jatuh di rentang [from, to]. */
    fun build(
        tpl: String, arrFmt: String, depFmt: String, shiftName: String, shiftCode: String,
        from: Long, to: Long, arrivals: List<Flight>, departures: List<Flight>, excl: List<Excl>
    ): String {
        fun pick(l: List<Flight>) = l.filter { f ->
            f.international && !f.cancelled && eta(f) in from..to && excl.none { ReportEx.matches(it, f) }
        }.sortedBy { eta(it) }
        val a = pick(arrivals)
        val d = pick(departures)
        val startDay = Fmt.dayKey(from)
        val tanggal = if (Fmt.dayKey(from) == Fmt.dayKey(to)) Fmt.dateFull(from)
        else "${Fmt.dateFull(from)} s/d ${Fmt.dateFull(to)}"
        val arrText = if (a.isEmpty()) "-" else a.mapIndexed { i, f -> line(arrFmt, i + 1, f, startDay) }.joinToString("\n")
        val depText = if (d.isEmpty()) "-" else d.mapIndexed { i, f -> line(depFmt, i + 1, f, startDay) }.joinToString("\n")
        val h = ShiftFmt.hours(shiftCode)
        return tpl
            .replace("{SALAM}", ShiftFmt.salam(shiftCode))
            .replace("{SHIFT}", shiftName)
            .replace("{JAM_MULAI}", h.first)
            .replace("{JAM_SELESAI}", h.second)
            .replace("{JAM_TARIK_MULAI}", Fmt.timeDot(from))
            .replace("{JAM_TARIK_SELESAI}", Fmt.timeDot(to))
            .replace("{TANGGAL_MULAI}", Fmt.dateFull(from))
            .replace("{TANGGAL_SELESAI}", Fmt.dateFull(to))
            .replace("{TANGGAL}", tanggal)
            .replace("{JML_DATANG}", a.size.toString())
            .replace("{JML_BERANGKAT}", d.size.toString())
            .replace("{KEDATANGAN}", arrText)
            .replace("{KEBERANGKATAN}", depText)
    }
}

@Composable
fun ExclusionDialog(list: List<Excl>, onChange: (List<Excl>) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("BOTH") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pengecualian flight") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Maskapai / kode / nomor (mis. My Indo)") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(scope == "ARR", { scope = "ARR" }, { Text("Datang") })
                    FilterChip(scope == "DEP", { scope = "DEP" }, { Text("Berangkat") })
                    FilterChip(scope == "BOTH", { scope = "BOTH" }, { Text("Keduanya") })
                }
                Button(enabled = text.trim().length >= 2, onClick = {
                    onChange(list + Excl(text.trim(), scope)); text = ""
                }) { Text("Tambah") }
                list.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${e.text} · " + when (e.scope) { "ARR" -> "Datang"; "DEP" -> "Berangkat"; else -> "Keduanya" },
                            Modifier.weight(1f)
                        )
                        TextButton(onClick = { onChange(list.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Selesai") } }
    )
}

@Composable
fun ReportTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sp = remember { Store.p(ctx) }
    var tpl by remember { mutableStateOf(sp.getString("rep_tpl", null) ?: Report.DEFAULT_TEMPLATE) }
    var arrFmt by remember { mutableStateOf(sp.getString("rep_arr", null) ?: Report.DEFAULT_ARR) }
    var depFmt by remember { mutableStateOf(sp.getString("rep_dep", null) ?: Report.DEFAULT_DEP) }
    var shiftName by remember { mutableStateOf(sp.getString("rep_shift", null) ?: "siang") }
    var stype by remember { mutableStateOf(sp.getString("rep_stype", null) ?: "S") }
    var excl by remember { mutableStateOf(ReportEx.load(ctx)) }
    var showEx by remember { mutableStateOf(false) }
    val today = Fmt.dayKey(Fmt.now())
    var fromDay by remember { mutableLongStateOf(today) }
    var toDay by remember { mutableLongStateOf(today) }
    var fromMin by remember { mutableIntStateOf(ShiftFmt.range(stype).first) }
    var toMin by remember { mutableIntStateOf(ShiftFmt.range(stype).second) }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showFmt by remember { mutableStateOf(false) }

    // jam selesai <= jam mulai berarti selesai di hari berikutnya
    fun fixEnd() { if (toDay <= fromDay + 1) toDay = fromDay + (if (toMin <= fromMin) 1 else 0) }

    LaunchedEffect(Unit) { fixEnd() }

    fun pickDate(day: Long, onPick: (Long) -> Unit) {
        val (y, m, d) = Fmt.ymd(day)
        DatePickerDialog(ctx, { _, yy, mm, dd -> onPick(Fmt.dayKeyOf(yy, mm, dd)) }, y, m, d).show()
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Laporan jadwal (internasional)", fontWeight = FontWeight.Bold)

        Text("Shift", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("P" to "Pagi", "S" to "Siang", "M" to "Malam").forEach { (code, name) ->
                FilterChip(stype == code, {
                    stype = code
                    shiftName = name.lowercase()
                    sp.edit().putString("rep_stype", code).putString("rep_shift", shiftName).apply()
                    val r = ShiftFmt.range(code)
                    fromMin = r.first; toMin = r.second
                    toDay = fromDay + (if (toMin <= fromMin) 1 else 0)
                }, { Text(name) })
            }
        }
        OutlinedTextField(
            shiftName, { shiftName = it; sp.edit().putString("rep_shift", it).apply() },
            Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nama shift") }
        )

        Text("Tarik data mulai", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickDate(fromDay) { fromDay = it; toDay = it; fixEnd() } }) {
                Text("📅 ${Fmt.dateShort(Fmt.dayStart(fromDay) + 3600)}")
            }
            TimeButton("", fromMin) { fromMin = it; fixEnd() }
        }
        Text("Tarik data sampai", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickDate(toDay) { toDay = it } }) {
                Text("📅 ${Fmt.dateShort(Fmt.dayStart(toDay) + 3600)}")
            }
            TimeButton("", toMin) { toMin = it; fixEnd() }
        }

        OutlinedButton(onClick = { showEx = true }) {
            Text("🚫 Pengecualian flight" + if (excl.isNotEmpty()) " (${excl.size})" else "")
        }

        Button(enabled = !loading, onClick = {
            scope.launch {
                loading = true; err = null
                try {
                    val f = Fmt.dayStart(fromDay) + fromMin * 60L
                    var t = Fmt.dayStart(toDay) + toMin * 60L
                    if (t <= f) t += 86400L
                    val earlier = if (f < Fmt.now()) 5 else 0
                    val a = withContext(Dispatchers.IO) { Fr24.fetch(true, earlier) }
                    val d = withContext(Dispatchers.IO) { Fr24.fetch(false, earlier) }
                    result = Report.build(tpl, arrFmt, depFmt, shiftName, stype, f, t, a, d, excl)
                } catch (e: Exception) {
                    err = "Gagal memuat data: ${e.message}"
                }
                loading = false
            }
        }) { Text(if (loading) "Memuat…" else "Buat laporan") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, color = AlertRed, fontSize = 13.sp) }

        OutlinedTextField(result, { result = it }, Modifier.fillMaxWidth(), minLines = 10, label = { Text("Hasil (bisa diedit)") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = result.isNotBlank(), onClick = {
                ctx.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Jadwal KNO", result))
                Toast.makeText(ctx, "Tersalin", Toast.LENGTH_SHORT).show()
            }) { Text("📋 Salin") }
            Button(enabled = result.isNotBlank(), onClick = {
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, result) }
                ctx.startActivity(Intent.createChooser(i, "Bagikan jadwal"))
            }) { Text("📤 Bagikan") }
        }

        OutlinedButton(onClick = { showFmt = !showFmt }) { Text(if (showFmt) "Tutup format" else "✎ Ubah format teks") }
        if (showFmt) {
            OutlinedTextField(
                tpl, { tpl = it; sp.edit().putString("rep_tpl", it).apply() },
                Modifier.fillMaxWidth(), minLines = 8, label = { Text("Format laporan") }
            )
            OutlinedTextField(
                arrFmt, { arrFmt = it; sp.edit().putString("rep_arr", it).apply() },
                Modifier.fillMaxWidth(), label = { Text("Format baris kedatangan") }
            )
            OutlinedTextField(
                depFmt, { depFmt = it; sp.edit().putString("rep_dep", it).apply() },
                Modifier.fillMaxWidth(), label = { Text("Format baris keberangkatan") }
            )
            Text(
                "Kode laporan: {SALAM} {SHIFT} {JAM_MULAI} {JAM_SELESAI} {JAM_TARIK_MULAI} {JAM_TARIK_SELESAI} {TANGGAL} " +
                    "{TANGGAL_MULAI} {TANGGAL_SELESAI} {KEDATANGAN} {KEBERANGKATAN} {JML_DATANG} {JML_BERANGKAT}\n" +
                    "Kode baris: {NO} {MASKAPAI} {NOMOR} {JAM} {JADWAL} {KOTA} {IATA} {NEGARA} {PESAWAT} {DELAY}",
                fontSize = 11.sp
            )
            OutlinedButton(onClick = {
                tpl = Report.DEFAULT_TEMPLATE; arrFmt = Report.DEFAULT_ARR; depFmt = Report.DEFAULT_DEP
                sp.edit().putString("rep_tpl", tpl).putString("rep_arr", arrFmt).putString("rep_dep", depFmt).apply()
            }) { Text("Kembalikan format awal") }
        }
    }

    if (showEx) ExclusionDialog(
        list = excl,
        onChange = { excl = it; ReportEx.save(ctx, it) },
        onClose = { showEx = false }
    )
}
