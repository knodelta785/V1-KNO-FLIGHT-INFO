package id.ongarand.flightradarkno

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object Refresh {
    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        wm.cancelUniqueWork("kno-check")   // worker versi lama
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("kno-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Latar belakang (±15 menit): sinkron notifikasi flight + perbarui widget. */
class RefreshWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        AppCtx.set(ctx)
        try { WatchManager.sync(ctx) } catch (e: Exception) { }
        if (WidgetUtil.hasWidgets(ctx)) WidgetUtil.refreshAll(ctx)
        return Result.success()
    }
}
