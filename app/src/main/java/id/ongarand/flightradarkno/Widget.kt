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

abstract class FlightWidgetBase : AppWidgetProvider() {
    abstract val dir: String   // "ARR" atau "DEP"

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        AppCtx.set(c)
        ids.forEach { WidgetUtil.update(c, m, it, dir) }
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

class FlightWidgetArr : FlightWidgetBase() { override val dir = "ARR" }
class FlightWidgetDep : FlightWidgetBase() { override val dir = "DEP" }

object WidgetUtil {
    const val ACTION_REFRESH = "id.ongarand.flightradarkno.REFRESH"

    private fun kinds(): List<Pair<Class<*>, String>> =
        listOf(FlightWidgetArr::class.java to "ARR", FlightWidgetDep::class.java to "DEP")

    fun hasWidgets(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        return kinds().any { m.getAppWidgetIds(ComponentName(c, it.first)).isNotEmpty() }
    }

    fun dirOf(c: Context, id: Int): String {
        val info = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)
        return if (info?.provider?.className?.endsWith("FlightWidgetDep") == true) "DEP" else "ARR"
    }

    fun refreshAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        for ((cls, dir) in kinds()) {
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isEmpty()) continue
            ids.forEach { update(c, m, it, dir) }
            m.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        }
    }

    fun update(c: Context, m: AppWidgetManager, id: Int, dir: String) {
        val cfg = Store.widget(c, id)
        val v = RemoteViews(c.packageName, R.layout.widget_flight)
        val label = if (dir == "ARR") "🛬 KNO Kedatangan" else "🛫 KNO Keberangkatan"
        v.setTextViewText(R.id.w_title, "$label · ${if (cfg.intl) "Int'l" else "Semua"}")
        v.setTextViewText(R.id.w_sub, Fmt.dateLabel(Fmt.now()))
        v.setTextViewText(R.id.w_empty, "Tidak ada flight")
        v.setInt(R.id.w_header, "setBackgroundColor", Store.accent(c))

        val svc = Intent(c, WidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra("dir", dir)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        v.setRemoteAdapter(R.id.w_list, svc)
        v.setEmptyView(R.id.w_list, R.id.w_empty)

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        v.setOnClickPendingIntent(R.id.w_head_text,
            PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), flags))
        v.setOnClickPendingIntent(R.id.w_refresh,
            PendingIntent.getBroadcast(c, 1, Intent(c, FlightWidgetArr::class.java).setAction(ACTION_REFRESH), flags))
        val cfgIntent = Intent(c, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse("kno://widget-config/$id")
        }
        v.setOnClickPendingIntent(R.id.w_cfg, PendingIntent.getActivity(c, id, cfgIntent, flags))
        m.updateAppWidget(id, v)
    }
}

/**
 * 5 flight terdekat yang belum landing (kedatangan) / belum berangkat (keberangkatan),
 * ditambah semua flight delay yang masih belum landing / berangkat. Urut menurut jam estimasi.
 */
fun pickUpcoming(all: List<Flight>): List<Flight> {
    val now = Fmt.now()
    fun eff(f: Flight) = if (f.actual > 0L) f.actual else f.sched
    val pending = all.filter { !it.cancelled && !it.done && !it.departed && it.real == 0L }
    val upcoming = pending.filter { eff(it) >= now - 1800L }.sortedBy { eff(it) }.take(5)
    val keys = upcoming.map { it.key }.toSet()
    val delayed = pending.filter { it.red && it.key !in keys }
    return (upcoming + delayed).sortedBy { eff(it) }
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory {
        AppCtx.set(applicationContext)
        return Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
            intent.getStringExtra("dir") ?: "ARR")
    }

    class Factory(private val c: Context, private val id: Int, private val dir: String) :
        RemoteViewsService.RemoteViewsFactory {
        private var items = listOf<Flight>()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            val cfg = Store.widget(c, id)
            try {
                val all = Fr24.fetch(dir == "ARR", 2).filter { !cfg.intl || it.international }
                items = pickUpcoming(all)
                items.map { it.airlineIata to it.airlineIcao }.distinct().forEach { Logos.bitmap(c, it.first, it.second) }
            } catch (e: Exception) { /* pertahankan data lama */ }
        }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_item)
            val f = items.getOrNull(position) ?: return v
            val day = Fmt.dayLabel(f.sched)
            v.setTextViewText(R.id.w_time, Fmt.time(f.sched) + if (day.isNotEmpty()) "\n$day" else "")

            // estimasi; tampilkan tanggal bila delay melewati tengah malam
            val est = if (f.actual > 0L)
                Fmt.time(f.actual) + if (Fmt.dayKey(f.actual) != Fmt.dayKey(f.sched)) " (${Fmt.dateShort(f.actual)})" else ""
            else ""
            val status = when {
                f.red -> "DELAY" + (if (f.delayMin >= 10) " +${f.delayMin} mnt" else "") +
                    (if (est.isNotEmpty()) " · est $est" else "")
                f.status.isNotEmpty() -> (if (f.live) "● " else "") + Fmt.idStatus(f.status)
                else -> "Terjadwal"
            }
            val line1 = "${f.number} · ${f.city} (${f.iata})" + if (f.aircraft.isNotEmpty()) " · ${f.aircraft}" else ""
            v.setTextViewText(R.id.w_info, "$line1\n$status")
            // merah hanya untuk delay yang belum berangkat / belum landing
            val color = if (f.red) 0xFFFF6B6B.toInt() else 0xFFFFFFFF.toInt()
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
        val dir = WidgetUtil.dirOf(this, id)
        val old = Store.widget(this, id)
        setContent {
            AppTheme {
                ConfigScreen(old, if (dir == "ARR") "Kedatangan" else "Keberangkatan") { cfg ->
                    Store.saveWidget(this, id, cfg)
                    val m = AppWidgetManager.getInstance(this)
                    WidgetUtil.update(this, m, id, dir)
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
fun ConfigScreen(init: WidgetCfg, dirLabel: String, onSave: (WidgetCfg) -> Unit) {
    var intl by remember { mutableStateOf(init.intl) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Widget KNO — $dirLabel", style = MaterialTheme.typography.titleLarge)
            Text("Jenis flight")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(intl, { intl = true }, { Text("Internasional saja") })
                FilterChip(!intl, { intl = false }, { Text("Semua flight") })
            }
            Button(onClick = { onSave(WidgetCfg(intl)) }) { Text("Simpan") }
        }
    }
}
