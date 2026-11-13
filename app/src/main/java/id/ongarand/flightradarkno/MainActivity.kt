package id.ongarand.flightradarkno

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.set(this)
        ThemeState.load(this)
        Notif.channel(this)
        Refresh.schedule(this)
        setContent { AppTheme { App() } }
    }
}

private val Amber = Color(0xFFFFA000)
private val Orange = Color(0xFFEF6C00)
private val Green = Color(0xFF2E7D32)

fun pickTime(ctx: Context, initial: Int, onPick: (Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, initial / 60, initial % 60, true).show()
}

/** Tombol dengan ikon jam; ketuk untuk memilih jam lewat dialog jam. */
@Composable
fun TimeButton(label: String, minutes: Int, suffix: String = "", onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = { pickTime(ctx, minutes, onPick) }) {
        Text("🕒 " + (if (label.isNotEmpty()) "$label " else "") + Fmt.hm(minutes) + suffix)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var intl by remember { mutableStateOf(true) }          // default: internasional saja
    var earlier by remember { mutableIntStateOf(0) }
    var arr by remember { mutableStateOf(listOf<Flight>()) }
    var dep by remember { mutableStateOf(listOf<Flight>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var updated by remember { mutableStateOf("") }
    var liveSel by remember { mutableStateOf<LiveSel?>(null) }
    var watches by remember { mutableStateOf(WatchManager.all(ctx).associateBy { it.key }) }
    val scope = rememberCoroutineScope()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= 31 &&
            !ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() &&
            !Store.p(ctx).getBoolean("asked_exact", false)) {
            Store.p(ctx).edit().putBoolean("asked_exact", true).apply()
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            } catch (e: Exception) { }
        }
    }

    suspend fun refresh() {
        loading = true
        try {
            val e = earlier
            val a = withContext(Dispatchers.IO) { Fr24.fetch(true, e) }
            val d = withContext(Dispatchers.IO) { Fr24.fetch(false, e) }
            arr = a; dep = d; error = null
            updated = Fmt.time(Fmt.now())
        } catch (ex: Exception) {
            error = "Gagal memuat data: ${ex.message}"
        }
        loading = false
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(60_000) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FlightRadar KNO", fontWeight = FontWeight.Bold)
                        Text("Bandara Kualanamu · developed by Ongarand", fontSize = 11.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Text("🛬") }, label = { Text("Datang", fontSize = 10.sp) })
                NavigationBarItem(tab == 1, { tab = 1 }, { Text("🛫") }, label = { Text("Berangkat", fontSize = 10.sp) })
                NavigationBarItem(tab == 2, { tab = 2 }, { Text("📡") }, label = { Text("Live", fontSize = 10.sp) })
                NavigationBarItem(tab == 3, { tab = 3 }, { Text("📝") }, label = { Text("Laporan", fontSize = 10.sp) })
                NavigationBarItem(tab == 4, { tab = 4 }, { Text("⚙️") }, label = { Text("Atur", fontSize = 10.sp) })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0, 1 -> {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(intl, { intl = true }, { Text("Internasional saja") })
                        FilterChip(!intl, { intl = false }, { Text("Semua flight") })
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val list = (if (tab == 0) arr else dep).filter { !intl || it.international }.sortedBy { it.sched }
                    Text(
                        error ?: "${list.size} flight · diperbarui $updated WIB",
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        fontSize = 12.sp, color = if (error != null) AlertRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlightList(
                        list, earlier, loading, watches,
                        onEarlier = { earlier += 1; scope.launch { refresh() } },
                        onTrack = { f -> liveSel = LiveSel(f.id, f.number); tab = 2 },
                        onToggle = { f, pre, at ->
                            val cur = watches[f.key]
                            val np = pre ?: (cur?.pre ?: false)
                            val na = at ?: (cur?.at ?: false)
                            if (np || na) askPermissions()
                            WatchManager.set(ctx, f, np, na)
                            watches = WatchManager.all(ctx).associateBy { it.key }
                        }
                    )
                }
                2 -> LiveTab(liveSel) { liveSel = it }
                3 -> ReportTab()
                else -> SettingsTab()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlightList(
    flights: List<Flight>, earlier: Int, loading: Boolean, watches: Map<String, Watch>,
    onEarlier: () -> Unit, onTrack: (Flight) -> Unit, onToggle: (Flight, Boolean?, Boolean?) -> Unit
) {
    val groups = flights.groupBy { Fmt.dayKey(it.sched) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "airport") { AirportInfo() }
        item(key = "earlier") {
            OutlinedButton(onClick = onEarlier, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text("⏪ Muat flight sebelumnya" + if (earlier > 0) " (sudah ${earlier}×)" else "")
            }
        }
        groups.forEach { (day, list) ->
            stickyHeader(key = "d$day") { DateHeader(Fmt.dateLabel(list.first().sched)) }
            items(list, key = { it.key }) { f ->
                FlightRow(f, watches[f.key], onTrack) { pre, at -> onToggle(f, pre, at) }
            }
        }
    }
}

@Composable
fun AirportInfo() {
    Surface(color = Color(0xFF2B2B2B), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Medan Kualanamu Int'l Airport", color = Color(0xFFFFC107), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "KNO / WIMM · ${Fmt.time(Fmt.now())} WIB · ${Fmt.dateShort(Fmt.now())}",
                color = Color.White, fontSize = 13.sp
            )
        }
    }
}

@Composable
fun DateHeader(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

fun statusText(f: Flight): String = when {
    f.cancelled -> "Dibatalkan"
    f.red -> "Delay ${Fmt.time(if (f.actual > 0L) f.actual else f.sched)}" + if (f.delayMin >= 10) " (+${f.delayMin}m)" else ""
    f.done && f.delayMin >= 10 -> Fmt.idStatus(f.status) + " (+${f.delayMin}m)"
    f.status.isEmpty() -> "Terjadwal"
    else -> Fmt.idStatus(f.status)
}

@Composable
fun AirlineLogo(iata: String, icao: String, size: Dp) {
    val urls = remember(iata, icao) { Logos.urls(iata, icao) }
    var idx by remember(urls) { mutableIntStateOf(0) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        if (idx < urls.size) {
            AsyncImage(
                model = urls[idx], contentDescription = iata, contentScale = ContentScale.Fit,
                onError = { idx++ }, modifier = Modifier.fillMaxSize()
            )
        } else Text(iata.ifEmpty { "✈" }, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CodeBadge(text: String) {
    Surface(color = Color.Transparent, shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant)) {
        Text(text, Modifier.padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 12.sp)
    }
}

@Composable
fun CheckLabel(on: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "☑" else "☐", fontSize = 20.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun FlightRow(f: Flight, watch: Watch?, onTrack: (Flight) -> Unit, onToggle: (Boolean?, Boolean?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val dot = when {
        f.red -> AlertRed
        f.cancelled -> Orange
        f.delayed -> Amber        // terlambat tapi sudah selesai: bukan merah
        else -> Green
    }
    Card(
        Modifier.fillMaxWidth().clickable { open = !open },
        colors = CardDefaults.cardColors(
            containerColor = if (f.red) AlertRed.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (f.red) BorderStroke(1.5.dp, AlertRed) else null
    ) {
        Column {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(92.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(5.dp))
                        Text(Fmt.time(f.sched), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        statusText(f), fontSize = 11.sp, maxLines = 2,
                        color = if (f.red) AlertRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (f.red) FontWeight.Bold else FontWeight.Normal
                    )
                }
                AirlineLogo(f.airlineIata, f.airlineIcao, 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.city, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        CodeBadge(f.iata)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.number, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.aircraft.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(5.dp)) {
                                Text(f.aircraft, Modifier.padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (f.live) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = Color(0xFFFFC107), shape = RoundedCornerShape(5.dp)) {
                                Text("● LIVE", Modifier.padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }
                }
                Text(if (open) "▲" else "▼", fontSize = 12.sp)
            }

            // centang notifikasi: 15 menit sebelum dan saat landing / berangkat
            if (!f.done && !f.cancelled) {
                val verb = if (f.arrival) "landing" else "berangkat"
                Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CheckLabel(watch?.pre == true, "15 mnt sebelum $verb") { onToggle(!(watch?.pre == true), null) }
                    CheckLabel(watch?.at == true, "Saat $verb") { onToggle(null, !(watch?.at == true)) }
                }
            }

            if (open) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (f.airline.isNotEmpty()) Text(f.airline, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (f.international) "Internasional" else "Domestik") +
                            if (f.country.isNotEmpty()) " · ${f.country}" else "",
                        fontSize = 13.sp
                    )
                    if (f.aircraft.isNotEmpty() || f.reg.isNotEmpty())
                        Text("Pesawat: ${f.aircraft} ${f.reg}".trim(), fontSize = 13.sp)
                    Text(
                        "Jadwal ${Fmt.time(f.sched)}" +
                            (if (f.est > 0L) " · Estimasi ${Fmt.time(f.est)}" else "") +
                            (if (f.real > 0L) " · Aktual ${Fmt.time(f.real)}" else ""),
                        fontSize = 13.sp
                    )
                    if (!f.arrival && f.destEta > 0L) Text("Tiba di tujuan ±${Fmt.time(f.destEta)}", fontSize = 13.sp)
                    Button(onClick = { onTrack(f) }) { Text("📡 Lacak live & playback") }
                }
            }
        }
    }
}

// ---------------- PENGATURAN ----------------

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    var hex by remember { mutableStateOf("") }
    val palette = listOf(0xFF1565C0, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00, 0xFF00838F, 0xFFAD1457, 0xFF37474F).map { it.toInt() }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mode tampilan", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Ikuti sistem", "Terang", "Gelap").forEachIndexed { i, t ->
                FilterChip(ThemeState.mode == i, { ThemeState.mode = i; ThemeState.save(ctx) }, { Text(t) })
            }
        }
        Text("Warna aplikasi", fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            palette.forEach { col ->
                val sel = ThemeState.accent == col
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(col))
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { ThemeState.accent = col; ThemeState.save(ctx) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(hex, { hex = it }, Modifier.weight(1f), singleLine = true, label = { Text("Kode warna, mis. #FF5722") })
            Button(onClick = {
                try {
                    val h = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
                    ThemeState.accent = android.graphics.Color.parseColor(h)
                    ThemeState.save(ctx)
                    WidgetUtil.refreshAll(ctx)
                } catch (e: Exception) { }
            }) { Text("Terapkan") }
        }

        HorizontalDivider()
        Text("Notifikasi flight", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                } catch (e: Exception) { }
            }) { Text("🔔 Izin notifikasi") }
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    } catch (e: Exception) { }
                }
            }) { Text("⏰ Alarm tepat waktu") }
        }
    }
}
