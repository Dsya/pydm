package com.pydm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import com.chaquo.python.Python
import java.io.ByteArrayInputStream
import java.io.File

const val HOME_UI = "http://127.0.0.1:8080/"

// Domain iklan/pelacak yang diblokir (daftar sendiri, bisa ditambah). Subdomain ikut diblokir.
val AD_HOSTS = ("doubleclick.net googlesyndication.com googleadservices.com adservice.google.com 2mdn.net " +
    "adnxs.com adsrvr.org taboola.com outbrain.com popads.net popcash.net propellerads.com exoclick.com " +
    "exdynsrv.com juicyads.com trafficjunky.com trafficstars.com adsterra.com hilltopads.net clickadu.com " +
    "onclickads.net admaven.com ad-maven.com adcash.com mgid.com adskeeper.com revcontent.com criteo.com " +
    "pubmatic.com rubiconproject.com openx.net amazon-adsystem.com media.net adform.net smartadserver.com " +
    "serving-sys.com moatads.com adsafeprotected.com zedo.com yllix.com a-ads.com bidvertiser.com " +
    "infolinks.com monetag.com realsrv.com magsrv.com tsyndicate.com onclkds.com adtng.com scorecardresearch.com " +
    "ero-advertising.com clickaine.com popunderjs.com adbrite.com").split(" ")

fun isAd(h: String?) = h != null && AD_HOSTS.any { h == it || h.endsWith(".$it") }

const val HIDE_JS = "(function(){var s=document.createElement('style');" +
    "s.textContent='ins.adsbygoogle,[id^=\"div-gpt-ad\"],[id*=\"google_ads\"],iframe[src*=\"doubleclick\"]," +
    "iframe[src*=\"googlesyndication\"],.adsbox,.ad-banner{display:none!important}';" +
    "document.documentElement.appendChild(s)})()"

fun storageOk(c: Context) =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

class MainActivity : Activity() {
    private lateinit var ui: WebView
    private lateinit var br: WebView
    private lateinit var box: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var badge: Button
    private lateinit var adBtn: Button
    @Volatile private var blockAds = true
    private val found = LinkedHashSet<String>()
    private var askedN = false
    private var askedS = false
    private val media = Regex("\\.(mp4|mkv|webm|mp3|m4a|flac|zip|rar|7z|apk|iso|cso|pdf)(\\?|$)", RegexOption.IGNORE_CASE)

    inner class Bridge {
        @JavascriptInterface fun openBrowser() { runOnUiThread { show(true) } }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        blockAds = getSharedPreferences("pydm", MODE_PRIVATE).getBoolean("ads", true)
        ui = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            setBackgroundColor(Color.parseColor("#121212"))
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) v.postDelayed({ v.loadUrl(HOME_UI) }, 1000)  // server belum siap
                }
            }
            setDownloadListener { _, _, _, _, _ -> toast("Berkas ada di folder unduhan") }
        }
        br = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { found.clear(); refresh(); urlBar.setText(u) }
                override fun onPageFinished(v: WebView, u: String) { if (blockAds) v.evaluateJavascript(HIDE_JS, null) }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (!blockAds) return false
                    val sc = r.url.scheme ?: ""
                    if (sc !in listOf("http", "https", "about", "blob", "data", "mailto", "tel")) { toast("Pengalihan ke aplikasi diblokir"); return true }
                    if (isAd(r.url.host)) { toast("Halaman iklan diblokir"); return true }
                    return false
                }
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    if (blockAds && isAd(r.url.host)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    val u = r.url.toString()
                    if (media.containsMatchIn(u)) runOnUiThread { found.add(u); refresh() }
                    return null
                }
            }
            setDownloadListener { u, ua, cd, mime, len -> ask(u, ua, cd, mime, len) }
            settings.setSupportMultipleWindows(true)
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: Message): Boolean {
                    if (blockAds && !g) { toast("Popup diblokir"); return false }  // popup tanpa ketukan
                    val t = WebView(this@MainActivity)
                    t.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(w: WebView, r: WebResourceRequest): Boolean {
                            if (blockAds && isAd(r.url.host)) toast("Popup iklan diblokir") else br.loadUrl(r.url.toString())
                            w.destroy(); return true
                        }
                    }
                    (m.obj as WebView.WebViewTransport).webView = t; m.sendToTarget(); return true
                }
            }
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
            minimumWidth = 0; setPadding(18, 0, 18, 0); setOnClickListener { f() }
        }
        urlBar = EditText(this).apply {
            setSingleLine(); setTextColor(Color.WHITE); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO; hint = "Ketik alamat atau cari"
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(text.toString()); true } else false }
        }
        badge = btn("⬇ 0") { showFound() }
        adBtn = btn("AD") { toggleAds() }; paintAd()
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#212121")); gravity = Gravity.CENTER_VERTICAL
            addView(btn("🏠") { go("https://www.google.com/") })
            addView(btn("←") { if (br.canGoBack()) br.goBack() })
            addView(btn("→") { if (br.canGoForward()) br.goForward() })
            addView(urlBar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("⟳") { br.reload() })
            addView(adBtn)
            addView(badge)
            addView(btn("✕") { show(false) })
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setBackgroundColor(Color.parseColor("#121212"))
            addView(bar, -1, -2)
            addView(br, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(FrameLayout(this).apply { addView(ui, -1, -1); addView(box, -1, -1) })
    }

    override fun onResume() { super.onResume(); step() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) { super.onRequestPermissionsResult(c, p, r); step() }

    private fun step() {
        if (Build.VERSION.SDK_INT >= 33 && !askedN &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedN = true; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1); return
        }
        if (!storageOk(this) && !askedS) {
            askedS = true
            if (Build.VERSION.SDK_INT >= 30)
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
            return
        }
        val i = Intent(this, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        if (ui.url == null) ui.loadUrl(HOME_UI)
    }

    private fun show(on: Boolean) {
        box.visibility = if (on) View.VISIBLE else View.GONE
        if (on && br.url == null) go("https://www.google.com/")
    }

    private fun go(v: String) {
        val t = v.trim()
        val u = when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t) -> t
            !t.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+([/:?#].*)?$").matches(t) -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        br.loadUrl(u); br.requestFocus()
    }

    private fun paintAd() {
        adBtn.text = if (blockAds) "AD ✓" else "AD ✗"
        adBtn.setTextColor(if (blockAds) Color.parseColor("#6ba19a") else Color.GRAY)
    }

    private fun toggleAds() {
        blockAds = !blockAds
        getSharedPreferences("pydm", MODE_PRIVATE).edit().putBoolean("ads", blockAds).apply()
        paintAd(); toast(if (blockAds) "Block Ads: aktif" else "Block Ads: nonaktif"); br.reload()
    }

    private fun refresh() { badge.text = "⬇ ${found.size}" }
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private fun showFound() {
        if (found.isEmpty()) { toast("Belum ada media terdeteksi di halaman ini"); return }
        val l = found.toList()
        AlertDialog.Builder(this).setTitle("Berkas terdeteksi")
            .setItems(l.map { Uri.decode(it.substringBefore('?').substringAfterLast('/')) }.toTypedArray()) { _, i -> ask(l[i], null, null, null, -1) }
            .setNeutralButton("Unduh semua") { _, _ -> l.forEach { add(it, URLUtil.guessFileName(it, null, null), br.settings.userAgentString) } }
            .show()
    }

    private fun ask(u: String, ua: String?, cd: String?, mime: String?, len: Long) {
        val et = EditText(this).apply { setText(URLUtil.guessFileName(u, cd, mime)) }
        AlertDialog.Builder(this).setTitle("Unduh berkas?")
            .setMessage(if (len > 0) Formatter.formatFileSize(this, len) else "Ukuran tidak diketahui")
            .setView(et)
            .setPositiveButton("Unduh") { _, _ -> add(u, et.text.toString(), ua ?: br.settings.userAgentString) }
            .setNegativeButton("Batal", null).show()
    }

    private fun add(u: String, name: String, ua: String) {
        val ref = br.url ?: ""
        val ck = CookieManager.getInstance().getCookie(u) ?: ""
        Thread {
            try {
                Python.getInstance().getModule("server").callAttr("add", u, name, ref, ck, ua)
                toast("Ditambahkan ke antrean")
            } catch (e: Exception) { toast("Gagal: " + (e.message ?: "").take(150)) }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            box.visibility == View.VISIBLE -> if (br.canGoBack()) br.goBack() else show(false)
            ui.canGoBack() -> ui.goBack()
            else -> super.onBackPressed()
        }
    }
}

class DownloadService : Service() {
    companion object { @Volatile var started = false }
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("dm", "PyDM", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "dm") else Notification.Builder(this)
        val n = b.setContentTitle("PyDM aktif").setContentText("Mesin unduhan berjalan")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        if (!started) {
            started = true
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pydm:dl").apply { setReferenceCounted(false); acquire() }
            Thread {
                assets.open("index.html").use { a -> File(filesDir, "index.html").outputStream().use { a.copyTo(it) } }
                val dl = if (storageOk(this)) File(Environment.getExternalStorageDirectory(), "Download/PyDM").path
                         else File(getExternalFilesDir(null) ?: filesDir, "PyDM").path
                Python.getInstance().getModule("server").callAttr("run", filesDir.path, dl, "127.0.0.1", 8080)
            }.start()
        }
        return START_STICKY
    }
}
