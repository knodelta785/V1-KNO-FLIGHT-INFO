package id.ongarand.flightradarkno

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Konteks aplikasi untuk komponen yang berjalan tanpa Activity (widget, worker). */
object AppCtx {
    @Volatile var app: Context? = null
    fun set(c: Context) { app = c.applicationContext }
}

/**
 * Cadangan jika HTTP langsung diblokir: membuka URL JSON di WebView (mesin Chromium HP)
 * lalu membaca teks halamannya. Harus dipanggil dari thread latar belakang, bukan thread utama.
 */
object WebFetch {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private val lock = Any()

    @SuppressLint("SetJavaScriptEnabled")
    fun get(url: String, timeoutMs: Long = 40000): String = synchronized(lock) {
        val ctx = AppCtx.app ?: throw RuntimeException("konteks belum siap")
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        val done = booleanArrayOf(false)
        fun finish(text: String?) {
            if (!done[0]) { done[0] = true; result[0] = text; latch.countDown() }
        }

        main.post {
            try {
                val w = web ?: WebView(ctx).also { it.settings.javaScriptEnabled = true; web = it }
                var tries = 0
                fun read(v: WebView) {
                    if (done[0]) return
                    v.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { raw ->
                        if (!done[0]) {
                            val t = try { (JSONTokener(raw).nextValue() as? String) ?: "" } catch (e: Exception) { "" }
                            val s = t.trimStart()
                            if (s.startsWith("{") || s.startsWith("[")) finish(t)
                            else if (++tries >= 12) finish(null)
                            else main.postDelayed({ read(v) }, 2500)
                        }
                    }
                }
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String?) { read(view) }
                }
                w.loadUrl(url)
            } catch (e: Throwable) {
                finish(null)
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) done[0] = true
        result[0] ?: throw RuntimeException("WebView gagal / tetap diblokir")
    }
}
