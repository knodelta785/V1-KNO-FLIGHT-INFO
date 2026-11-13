package id.ongarand.flightradarkno

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class FlightWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        AppCtx.set(c)
        ids.forEach { WidgetUtil.update(c, m, it) }
        m.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
    }
    override fun onEnabled(c: Context) { Refresh.schedule(c) }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { Store.deleteWidget(c, it) } }
    override fun onReceive(c: Context, i: Intent) {
        AppCtx.set(c)
        super.onReceive(c, i)
        if (i.action == WidgetUtil.ACTION_REFRESH) WidgetUtil.refreshAll(c)
    }
}

object WidgetUtil {
    const val ACTION_REFRESH = "id.ongarand.flightradarkno.REFRESH"

    private fun ids(c: Context): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, FlightWidget::class.java))

    fun hasWidgets(c: Context) = ids(c).isNotEmpty()

    fun refreshAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        val ids = ids(c)
        if (ids.isEmpty()) return
        ids.forEach { update(c, m, it) }
        m.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
    }

    fun update(c: Context, m: AppWidgetManager, id: Int) {
        val cfg = Store.widget(c, id)
        val v = RemoteViews(c.packageName, R.layout.widget_flight)
        v.setTextViewText(R.id.w_title, "🛰 KNO Live · ${if (cfg.intl) "Int'l" else "Semua"}")
        v.setTextViewText(R.id.w_sub, Fmt.dateLabel(Fmt.now()))
        v.setTextViewText(R.id.w_empty, "Tidak ada flight")
        v.setInt(R.id.w_header, "setBackgroundColor", Store.accent(c))

        val svc = Intent(c, WidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        v.setRemoteAdapter(R.id.w_list, svc)
        v.setEmptyView(R.id.w_list, R.id.w_empty)

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        v.setOnClickPendingIntent(R.id.w_head_text,
            PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), flags))
        v.setOnClickPendingIntent(R.id.w_refresh,
            PendingIntent.getBroadcast(c, 1, Intent(c, FlightWidget::class.java).setAction(ACTION_REFRESH), flags))
        val cfgIntent = Intent(c, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse("kno://widget-config/$id")
        }
        v.setOnClickPendingIntent(R.id.w_cfg, PendingIntent.getActivity(c, id, cfgIntent, flags))
        m.updateAppWidget(id, v)
    }
}

/**
 * Pilih flight untuk widget: yang sedang live (kedatangan belum mendarat & live, keberangkatan sudah berangkat
 * tapi belum tiba) dan yang baru saja mendarat / berangkat (2 jam terakhir). Dibagi seimbang antar arah.
 */
fun pickLive(all: List<Flight>, n: Int): List<Flight> {
    val now = Fmt.now()
    fun isLive(f: Flight) = !f.cancelled && !f.done &&
        ((f.arrival && f.real == 0L && f.live) || (!f.arrival && f.departed))
    fun isRecent(f: Flight) = f.real > 0L && f.real >= now - 7200L && !f.cancelled
    fun order(l: List<Flight>): List<Flight> {
        val live = l.filter { isLive(it) }.sortedBy { if (it.actual > 0L) it.actual else it.sched }
        val recent = l.filter { !isLive(it) && isRecent(it) }.sortedByDescending { it.real }
        return live + recent
    }
    val arr = order(all.filter { it.arrival })
    val dep = order(all.filter { !it.arrival })
    val aTake = minOf(arr.size, maxOf(n / 2, n - dep.size))
    val dTake = minOf(dep.size, n - aTake)
    val chosen = arr.take(aTake) + dep.take(dTake)
    val live = chosen.filter { isLive(it) }.sortedBy { if (it.actual > 0L) it.actual else it.sched }
    val rest = chosen.filter { !isLive(it) }.sortedByDescending { it.real }
    return live + rest
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory {
        AppCtx.set(applicationContext)
        return Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
    }

    class Factory(private val c: Context, private val id: Int) : RemoteViewsService.RemoteViewsFactory {
        private var items = listOf<Flight>()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            val cfg = Store.widget(c, id)
            try {
                val all = Fr24.fetch(true, 2) + Fr24.fetch(false, 2)
                items = pickLive(all.filter { !cfg.intl || it.international }, cfg.rows)
                items.map { it.airlineIata to it.airlineIcao }.distinct().forEach { Logos.bitmap(c, it.first, it.second) }
            } catch (e: Exception) { /* pertahankan data lama */ }
        }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_item)
            val f = items.getOrNull(position) ?: return v
            val t = if (f.real > 0L) f.real else if (f.actual > 0L) f.actual else f.sched
            val day = Fmt.dayLabel(t)
            v.setTextViewText(R.id.w_time, Fmt.time(t) + if (day.isNotEmpty()) "\n$day" else "")

            val late = if (f.delayMin >= 10) " (+${f.delayMin} mnt)" else ""
            val status = when {
                f.cancelled -> "Dibatalkan"
                f.arrival && f.real > 0L -> "Mendarat ${Fmt.time(f.real)}$late"
                f.arrival -> if (f.red) "DELAY${if (f.delayMin >= 10) " +${f.delayMin} mnt" else ""} · ETA ${Fmt.time(t)}"
                             else "● Live · ETA ${Fmt.time(t)}"
                f.done -> "Berangkat ${Fmt.time(f.real)}$late"
                else -> (if (f.red) "DELAY$late · berangkat ${Fmt.time(f.real)}" else "● Terbang · berangkat ${Fmt.time(f.real)}") +
                    (if (f.destEta > 0L) " · tiba ±${Fmt.time(f.destEta)}" else "")
            }
            val line1 = "${if (f.arrival) "🛬" else "🛫"} ${f.number} · ${f.city} (${f.iata})" +
                if (f.aircraft.isNotEmpty()) " · ${f.aircraft}" else ""
            v.setTextViewText(R.id.w_info, "$line1\n$status")
            // merah hanya untuk delay yang belum terbang / sedang terbang
            val color = when {
                f.red -> 0xFFFF6B6B.toInt()
                f.done || (f.arrival && f.real > 0L) -> 0xFFA7B4BC.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            v.setTextColor(R.id.w_info, color)
            v.setTextColor(R.id.w_time, color)
            val logo = Logos.bitmap(c, f.airlineIata, f.airlineIcao)
            if (logo != null) {
                v.setImageViewBitmap(R.id.w_logo, logo)
                v.setViewVisibility(R.id.w_logo, View.VISIBLE)
            } else v.setViewVisibility(R.id.w_logo, View.INVISIBLE)
            return v
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        AppCtx.set(this)
        ThemeState.load(this)
        val old = Store.widget(this, id)
        setContent {
            AppTheme {
                ConfigScreen(old) { cfg ->
                    Store.saveWidget(this, id, cfg)
                    val m = AppWidgetManager.getInstance(this)
                    WidgetUtil.update(this, m, id)
                    m.notifyAppWidgetViewDataChanged(id, R.id.w_list)
                    Refresh.schedule(this)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        }
    }
}

@Composable
fun ConfigScreen(init: WidgetCfg, onSave: (WidgetCfg) -> Unit) {
    var intl by remember { mutableStateOf(init.intl) }
    var rows by remember { mutableIntStateOf(init.rows) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Widget KNO Live", style = MaterialTheme.typography.titleLarge)
            Text("Jenis flight")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(intl, { intl = true }, { Text("Internasional saja") })
                FilterChip(!intl, { intl = false }, { Text("Semua flight") })
            }
            Text("Jumlah baris")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 8, 10).forEach { n -> FilterChip(rows == n, { rows = n }, { Text("$n") }) }
            }
            Button(onClick = { onSave(WidgetCfg(intl, rows)) }) { Text("Simpan") }
        }
    }
}
