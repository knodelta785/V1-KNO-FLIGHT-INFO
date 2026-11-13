package id.ongarand.flightradarkno

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

data class LiveSel(val id: String, val number: String)

private val KNO = GeoPoint(3.6422, 98.8853)

private val PLANE = listOf(
    50 to 5, 55 to 15, 55 to 38, 95 to 60, 95 to 68, 55 to 55, 55 to 80, 68 to 90, 68 to 95,
    50 to 91, 32 to 95, 32 to 90, 45 to 80, 45 to 55, 5 to 68, 5 to 60, 45 to 38, 45 to 15
)

/** Ikon pesawat (hidung ke atas) yang diputar sesuai arah terbang, plus label nomor flight. */
private object PlaneIcons {
    private val cache = HashMap<String, Bitmap>()

    private fun plain(heading: Int, color: Int, size: Int): Bitmap {
        val h = ((heading + 5) / 10 * 10) % 360
        return cache.getOrPut("$h-$color-$size") {
            val base = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(base)
            val s = size / 100f
            val path = Path()
            PLANE.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s) }
            path.close()
            cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
            cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = size / 28f
            })
            val m = Matrix().apply { postRotate(h.toFloat(), size / 2f, size / 2f) }
            Bitmap.createBitmap(base, 0, 0, size, size, m, true)
        }
    }

    /** @return ikon dan posisi anchor Y (pusat pesawat), agar pesawat tepat di koordinatnya. */
    fun build(ctx: Context, heading: Int, color: Int, size: Int, label: String): Pair<BitmapDrawable, Float> {
        val plane = plain(heading, color, size)
        if (label.isEmpty()) return BitmapDrawable(ctx.resources, plane) to 0.5f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.42f
            this.color = 0xFF000000.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, 0xFFFFFFFF.toInt())
        }
        val w = maxOf(plane.width, (tp.measureText(label) + 10).toInt())
        val lh = (tp.textSize + 8).toInt()
        val out = Bitmap.createBitmap(w, plane.height + lh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawBitmap(plane, (w - plane.width) / 2f, 0f, null)
        cv.drawText(label, w / 2f, plane.height + tp.textSize, tp)
        return BitmapDrawable(ctx.resources, out) to (plane.height / 2f) / out.height
    }
}

@Composable
fun LiveTab(sel: LiveSel?, onSel: (LiveSel?) -> Unit) {
    var aircraft by remember { mutableStateOf(listOf<Aircraft>()) }
    var knoOnly by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var trail by remember { mutableStateOf(listOf<TrailPt>()) }
    var trailLoading by remember { mutableStateOf(false) }
    var pb by remember { mutableStateOf(false) }          // mode playback
    var playing by remember { mutableStateOf(false) }
    var idx by remember { mutableIntStateOf(0) }
    var speed by remember { mutableIntStateOf(4) }
    var follow by remember { mutableStateOf(true) }

    // posisi live: diperbarui tiap 10 detik selama tab ini terbuka
    LaunchedEffect(Unit) {
        while (true) {
            try {
                aircraft = withContext(Dispatchers.IO) { Fr24.live(5.6, 1.6, 96.9, 100.9) }
                err = null
            } catch (e: Exception) {
                err = "Gagal memuat posisi: ${e.message}"
            }
            delay(10_000)
        }
    }

    val selected = sel?.let { s ->
        aircraft.firstOrNull { it.id == s.id || (s.number.isNotEmpty() && it.number == s.number) }
    }
    val shown = aircraft.filter { !knoOnly || it.from == "KNO" || it.to == "KNO" }

    // jejak penerbangan untuk garis rute dan playback
    val trailId: String? = selected?.id ?: sel?.id?.takeIf { it.isNotEmpty() }
    LaunchedEffect(trailId) {
        trail = emptyList(); pb = false; playing = false; idx = 0
        if (trailId != null) {
            trailLoading = true
            try { trail = withContext(Dispatchers.IO) { Fr24.trail(trailId) } } catch (e: Exception) { }
            trailLoading = false
        }
    }

    // animasi playback
    LaunchedEffect(playing, speed, trail) {
        while (playing && trail.isNotEmpty()) {
            delay((400L / speed).coerceAtLeast(30L))
            if (idx < trail.lastIndex) idx++ else playing = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(knoOnly, { knoOnly = true }, { Text("Flight KNO") })
            FilterChip(!knoOnly, { knoOnly = false }, { Text("Semua pesawat") })
            Spacer(Modifier.weight(1f))
            Text("${shown.size} pesawat", fontSize = 12.sp)
        }
        err?.let { Text(it, color = AlertRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            OsmMap(
                shown = shown, selected = selected, trail = trail, pb = pb, idx = idx,
                follow = follow, focus = sel != null,
                onPick = { a -> onSel(LiveSel(a.id, a.number)) },
                modifier = Modifier.fillMaxSize()
            )
            if (sel != null) Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sel != null) {
                        val title = selected?.let { it.number.ifEmpty { it.callsign } } ?: sel.number
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "$title" + (selected?.let { " · ${it.from.ifEmpty { "?" }} → ${it.to.ifEmpty { "?" }}" } ?: ""),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    selected?.let {
                                        "${it.type} ${it.reg} · ${"%,d".format(it.alt)} ft · ${it.spd} kt · hdg ${it.track}°" +
                                            if (it.onGround) " · di darat" else ""
                                    } ?: "Belum terdeteksi di radar (belum terbang / sudah mendarat).",
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = { onSel(null) }) { Text("✕ Tutup") }
                        }
                        if (trailLoading) Text("Memuat jejak…", fontSize = 12.sp)
                        if (!pb) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(enabled = trail.size > 1, onClick = { pb = true; idx = 0; playing = true }) {
                                    Text("▶ Playback jejak")
                                }
                                Switch(follow, { follow = it })
                                Text("Ikuti", fontSize = 13.sp)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = {
                                    if (idx >= trail.lastIndex) idx = 0
                                    playing = !playing
                                }) { Text(if (playing) "⏸" else "▶") }
                                listOf(1, 4, 16).forEach { s -> FilterChip(speed == s, { speed = s }, { Text("${s}×") }) }
                                TextButton(onClick = { pb = false; playing = false }) { Text("Kembali ke live") }
                            }
                            Slider(
                                value = idx.toFloat(),
                                onValueChange = { idx = it.toInt(); playing = false },
                                valueRange = 0f..trail.lastIndex.toFloat().coerceAtLeast(1f)
                            )
                            val p = trail[idx.coerceIn(0, trail.lastIndex)]
                            Text(
                                "${Fmt.dateShort(p.ts)} ${Fmt.time(p.ts)} WIB · ${"%,d".format(p.alt)} ft · ${p.spd} kt · hdg ${p.hd}°",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OsmMap(
    shown: List<Aircraft>, selected: Aircraft?, trail: List<TrailPt>, pb: Boolean, idx: Int,
    follow: Boolean, focus: Boolean, onPick: (Aircraft) -> Unit, modifier: Modifier
) {
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.density
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = "FlightRadarKNO/1.2 (Ongarand)"
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            controller.setZoom(8.5)
            controller.setCenter(KNO)
        }
    }

    DisposableEffect(mapView) {
        val lifecycle = (ctx as? ComponentActivity)?.lifecycle
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle?.addObserver(obs)
        mapView.onResume()
        onDispose {
            lifecycle?.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    val accent = ThemeState.accent
    AndroidView(factory = { mapView }, modifier = modifier, update = { map ->
        map.overlays.clear()

        // jejak penerbangan terpilih
        if (trail.size > 1) {
            map.overlays.add(Polyline().apply {
                setPoints(trail.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = 0x991E88E5.toInt()
                outlinePaint.strokeWidth = 5f
            })
            if (pb && idx > 0) {
                map.overlays.add(Polyline().apply {
                    setPoints(trail.take(idx.coerceIn(1, trail.size)).map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = 0xFFFFC107.toInt()
                    outlinePaint.strokeWidth = 8f
                })
            }
        }

        // pin bandara
        map.overlays.add(Marker(map).apply {
            position = KNO
            title = "Bandara Kualanamu (KNO)"
        })

        // pesawat
        for (a in shown) {
            val isSel = selected != null && a.id == selected.id
            if (isSel && pb) continue
            val color = when {
                isSel -> 0xFFFFC107.toInt()
                a.from == "KNO" || a.to == "KNO" -> accent
                else -> 0xFF78909C.toInt()
            }
            val (icon, ay) = PlaneIcons.build(
                ctx, a.track, color, ((if (isSel) 38 else 30) * density).toInt(),
                a.number.ifEmpty { a.callsign }
            )
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(a.lat, a.lon)
                this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, ay)
                title = a.number.ifEmpty { a.callsign }
                setOnMarkerClickListener { _, _ -> onPick(a); true }
            })
        }

        // pesawat pada mode playback
        if (pb && trail.isNotEmpty()) {
            val p = trail[idx.coerceIn(0, trail.lastIndex)]
            val (icon, ay) = PlaneIcons.build(ctx, p.hd, 0xFFFFC107.toInt(), (40 * density).toInt(), "")
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, ay)
            })
        }

        // kamera mengikuti pesawat terpilih
        if (follow && focus) {
            val target: GeoPoint? = when {
                pb && trail.isNotEmpty() -> trail[idx.coerceIn(0, trail.lastIndex)].let { GeoPoint(it.lat, it.lon) }
                selected != null -> GeoPoint(selected.lat, selected.lon)
                else -> trail.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
            }
            if (target != null) {
                if (pb) map.controller.setCenter(target) else map.controller.animateTo(target)
            }
        }
        map.invalidate()
    })
}
