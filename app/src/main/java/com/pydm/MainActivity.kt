package com.pydm

import android.Manifest
import android.animation.ValueAnimator
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import com.chaquo.python.Python
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

const val HOME_UI = "http://127.0.0.1:8080/"
const val HOME_WEB = "https://www.google.com/"

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


class Tab(val web: WebView) {
    var title = ""
    val found = LinkedHashSet<String>()
}

// Panel geser (kiri = tab, kanan = bookmark)
class SwipePanel(ctx: Context, private val content: View, private val left: Boolean, val w: Int) {
    val root = FrameLayout(ctx)
    private val scrim = View(ctx)
    private var anim: ValueAnimator? = null
    var progress = 0f
        private set
    val showing: Boolean get() = root.visibility == View.VISIBLE

    init {
        scrim.setBackgroundColor(Color.BLACK)
        content.isClickable = true
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(w, -1, if (left) Gravity.START else Gravity.END))
        moveTo(0f)
    }

    fun moveTo(p: Float) {
        progress = p.coerceIn(0f, 1f)
        val dist = (1f - progress) * w
        content.translationX = if (left) -dist else dist
        scrim.alpha = progress * 0.55f
        root.visibility = if (progress > 0f) View.VISIBLE else View.GONE
    }

    fun animateTo(target: Float) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(progress, target)
        a.setDuration(180)
        a.addUpdateListener { moveTo(it.animatedValue as Float) }
        anim = a
        a.start()
    }

    fun open() = animateTo(1f)
    fun close() = animateTo(0f)
}

class MainActivity : Activity() {
    private lateinit var ui: WebView
    private lateinit var cur: Tab
    private val tabs = mutableListOf<Tab>()
    private lateinit var box: LinearLayout
    private lateinit var tabHost: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var badge: Button
    private lateinit var adBtn: Button
    private lateinit var starBtn: Button
    private lateinit var tabBtn: Button
    private lateinit var stripL: View
    private lateinit var stripR: View
    private lateinit var tabsList: LinearLayout
    private lateinit var bmList: LinearLayout
    private lateinit var tabsDrawer: SwipePanel
    private lateinit var bmDrawer: SwipePanel
    @Volatile private var blockAds = true
    private var askedN = false
    private var askedS = false
    private val media = Regex("\\.(mp4|mkv|webm|mp3|m4a|flac|zip|rar|7z|apk|iso|cso|pdf)(\\?|$)", RegexOption.IGNORE_CASE)

    inner class Bridge {
        @JavascriptInterface fun openBrowser() { runOnUiThread { show(true) } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        setAllCaps(false); setMinHeight(0); setMinimumHeight(0)
        minimumWidth = 0; setPadding(18, 0, 18, 0); setOnClickListener { f() }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 18f
        setPadding(dp(16), dp(16), dp(16), dp(12))
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

        // ---------- bar bawah: baris alamat + baris navigasi ----------
        urlBar = EditText(this).apply {
            setSingleLine(); setTextColor(Color.WHITE); textSize = 14f
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO; hint = "Ketik alamat atau cari"
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(text.toString()); true } else false }
        }
        tabBtn = btn("1") { openTabsPanel() }
        val boxBg = GradientDrawable()
        boxBg.setStroke(dp(1) + 1, Color.WHITE); boxBg.cornerRadius = dp(4).toFloat(); boxBg.setColor(Color.TRANSPARENT)
        tabBtn.background = boxBg
        starBtn = btn("☆") { toggleStar() }
        starBtn.setOnLongClickListener { openBmPanel(); true }
        val urlRow = LinearLayout(this)
        urlRow.gravity = Gravity.CENTER_VERTICAL
        urlRow.addView(tabBtn)
        urlRow.addView(urlBar, LinearLayout.LayoutParams(0, -2, 1f))
        urlRow.addView(starBtn)
        urlRow.addView(btn("⟳") { cur.web.reload() })

        adBtn = btn("AD") { toggleAds() }; paintAd()
        badge = btn("⬇ 0") { showFound() }
        val navRow = LinearLayout(this)
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.addView(btn("←") { if (cur.web.canGoBack()) cur.web.goBack() }, LinearLayout.LayoutParams(0, -2, 1f))
        navRow.addView(btn("→") { if (cur.web.canGoForward()) cur.web.goForward() }, LinearLayout.LayoutParams(0, -2, 1f))
        navRow.addView(btn("🏠") { go(HOME_WEB) }, LinearLayout.LayoutParams(0, -2, 1f))
        navRow.addView(adBtn, LinearLayout.LayoutParams(0, -2, 1f))
        navRow.addView(badge, LinearLayout.LayoutParams(0, -2, 1f))
        navRow.addView(btn("Unduhan") { show(false) }, LinearLayout.LayoutParams(0, -2, 1.3f))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(Color.parseColor("#212121"))
        bottom.addView(urlRow, -1, -2)
        bottom.addView(navRow, -1, -2)

        tabHost = FrameLayout(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.visibility = View.GONE
        box.setBackgroundColor(Color.parseColor("#121212"))
        box.addView(tabHost, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(bottom, -1, -2)

        // ---------- panel kiri: tab ----------
        val dw = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        tabsList = LinearLayout(this)
        tabsList.orientation = LinearLayout.VERTICAL
        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.setBackgroundColor(Color.parseColor("#2B2B2B"))
        tcol.addView(header("Tab"), -1, -2)
        val tscroll = ScrollView(this)
        tscroll.addView(tabsList, -1, -2)
        tcol.addView(tscroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tcol.addView(btn("＋  Tab baru") { openTab(HOME_WEB); tabsDrawer.close() }, -1, -2)
        tabsDrawer = SwipePanel(this, tcol, true, dw)

        // ---------- panel kanan: bookmark ----------
        bmList = LinearLayout(this)
        bmList.orientation = LinearLayout.VERTICAL
        val bcol = LinearLayout(this)
        bcol.orientation = LinearLayout.VERTICAL
        bcol.setBackgroundColor(Color.parseColor("#2B2B2B"))
        bcol.addView(header("⭐  Bookmark"), -1, -2)
        val bscroll = ScrollView(this)
        bscroll.addView(bmList, -1, -2)
        bcol.addView(bscroll, LinearLayout.LayoutParams(-1, 0, 1f))
        bmDrawer = SwipePanel(this, bcol, false, dw)

        // ---------- strip tepi layar untuk geser ----------
        stripL = View(this)
        stripL.setOnTouchListener(dragListener(tabsDrawer, true, false) { rebuildTabs() })
        stripR = View(this)
        stripR.setOnTouchListener(dragListener(bmDrawer, false, false) { rebuildBm() })
        tabsDrawer.root.getChildAt(0).setOnTouchListener(dragListener(tabsDrawer, true, true))
        bmDrawer.root.getChildAt(0).setOnTouchListener(dragListener(bmDrawer, false, true))

        setContentView(FrameLayout(this).apply {
            addView(ui, -1, -1)
            addView(box, -1, -1)
            addView(tabsDrawer.root, -1, -1)
            addView(bmDrawer.root, -1, -1)
        })

        openTab(null)   // tab pertama; halaman dimuat saat browser dibuka
    }

    // Geser untuk buka/tutup panel. startOpen=true: dipasang di scrim (ketuk/geser = tutup)
    private fun dragListener(d: SwipePanel, left: Boolean, startOpen: Boolean, pre: () -> Unit = {}): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var x0 = 0f
        var base = 0f
        var moved = false
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; base = d.progress; moved = false; if (!startOpen) pre() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    if (abs(dx) > slop) moved = true
                    if (moved) d.moveTo(base + (if (left) dx else -dx) / d.w)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && startOpen) d.close() else d.animateTo(if (d.progress > 0.4f) 1f else 0f)
                }
            }
            true
        }
    }

    // ---------- tab ----------
    private fun makeTab(): Tab {
        val w = WebView(this)
        val t = Tab(w)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                t.found.clear()
                if (t === cur) { refresh(); urlBar.setText(u); paintStar() }
            }
            override fun onPageFinished(v: WebView, u: String) {
                if (blockAds) v.evaluateJavascript(HIDE_JS, null)
                t.title = v.title ?: u
                if (t === cur) paintStar()
                rebuildTabs()
            }
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
                if (media.containsMatchIn(u)) runOnUiThread { t.found.add(u); if (t === cur) refresh() }
                return null
            }
        }
        w.setDownloadListener { u, ua, cd, mime, len -> ask(u, ua, cd, mime, len) }
        w.settings.setSupportMultipleWindows(true)
        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, ti: String?) {
                t.title = ti ?: ""
                rebuildTabs()
            }
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: Message): Boolean {
                if (blockAds && !g) { toast("Popup diblokir"); return false }  // popup tanpa ketukan
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(w2: WebView, r2: WebResourceRequest): Boolean {
                        if (blockAds && isAd(r2.url.host)) toast("Popup iklan diblokir") else openTab(r2.url.toString())
                        w2.destroy(); return true
                    }
                }
                (m.obj as WebView.WebViewTransport).webView = tmp; m.sendToTarget(); return true
            }
        }
        return t
    }

    private fun openTab(u: String?) {
        val t = makeTab()
        tabs.add(t)
        switchTo(t)
        if (u != null) t.web.loadUrl(u)
    }

    private fun switchTo(t: Tab) {
        cur = t
        tabHost.removeAllViews()
        tabHost.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        tabHost.addView(stripL, FrameLayout.LayoutParams(dp(20), -1, Gravity.START))
        tabHost.addView(stripR, FrameLayout.LayoutParams(dp(20), -1, Gravity.END))
        urlBar.setText(t.web.url ?: "")
        refresh(); paintStar(); updateCount(); rebuildTabs()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        tabHost.removeView(t.web)
        t.web.destroy()
        when {
            tabs.isEmpty() -> openTab(HOME_WEB)
            t === cur -> switchTo(tabs[minOf(i, tabs.size - 1)])
            else -> { updateCount(); rebuildTabs() }
        }
    }

    private fun updateCount() { tabBtn.text = tabs.size.toString() }

    private fun openTabsPanel() { rebuildTabs(); tabsDrawer.open() }
    private fun openBmPanel() { rebuildBm(); bmDrawer.open() }

    private fun rebuildTabs() {
        if (!::tabsList.isInitialized) return
        tabsList.removeAllViews()
        for (t in tabs) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            if (t === cur) row.setBackgroundColor(Color.parseColor("#3C3C3C"))
            val tv = TextView(this)
            tv.text = if (t.title.isBlank()) "Tab baru" else t.title
            tv.setTextColor(Color.WHITE); tv.textSize = 15f
            tv.setSingleLine(); tv.setEllipsize(TextUtils.TruncateAt.END)
            tv.setPadding(dp(16), dp(14), dp(8), dp(14))
            tv.setOnClickListener { switchTo(t); tabsDrawer.close() }
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(btn("✕") { closeTab(t) })
            tabsList.addView(row, -1, -2)
        }
    }

    // ---------- bookmark ----------
    private fun loadBm(): MutableList<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val a = JSONArray(getSharedPreferences("pydm", MODE_PRIVATE).getString("bm", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pair(o.optString("t"), o.optString("u")))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveBm(l: List<Pair<String, String>>) {
        val a = JSONArray()
        for (p in l) a.put(JSONObject().put("t", p.first).put("u", p.second))
        getSharedPreferences("pydm", MODE_PRIVATE).edit().putString("bm", a.toString()).apply()
    }

    private fun toggleStar() {
        val u = cur.web.url ?: return
        val l = loadBm()
        val idx = l.indexOfFirst { it.second == u }
        if (idx >= 0) { l.removeAt(idx); toast("Bookmark dihapus") }
        else { l.add(0, Pair(if (cur.title.isBlank()) u else cur.title, u)); toast("Bookmark disimpan") }
        saveBm(l); paintStar(); rebuildBm()
    }

    private fun paintStar() {
        val u = cur.web.url
        val on = u != null && loadBm().any { it.second == u }
        starBtn.text = if (on) "★" else "☆"
        starBtn.setTextColor(if (on) Color.parseColor("#FFC107") else Color.WHITE)
    }

    private fun rebuildBm() {
        if (!::bmList.isInitialized) return
        bmList.removeAllViews()
        val l = loadBm()
        if (l.isEmpty()) {
            val e = TextView(this)
            e.text = "Belum ada bookmark.\nKetuk ☆ di bar alamat untuk menyimpan situs."
            e.setTextColor(Color.GRAY); e.setPadding(dp(16), dp(16), dp(16), dp(16))
            bmList.addView(e, -1, -2)
            return
        }
        for ((i, b) in l.withIndex()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(16), dp(10), dp(16), dp(10))
            val t1 = TextView(this)
            t1.text = if (b.first.isBlank()) b.second else b.first
            t1.setTextColor(Color.WHITE); t1.textSize = 15f
            t1.setSingleLine(); t1.setEllipsize(TextUtils.TruncateAt.END)
            val t2 = TextView(this)
            t2.text = b.second
            t2.setTextColor(Color.GRAY); t2.textSize = 12f
            t2.setSingleLine(); t2.setEllipsize(TextUtils.TruncateAt.END)
            col.addView(t1, -1, -2)
            col.addView(t2, -1, -2)
            col.setOnClickListener { cur.web.loadUrl(b.second); bmDrawer.close() }
            col.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("Hapus bookmark ini?")
                    .setPositiveButton("Hapus") { _, _ ->
                        val cp = loadBm()
                        if (i < cp.size) { cp.removeAt(i); saveBm(cp); paintStar(); rebuildBm() }
                    }
                    .setNegativeButton("Batal", null).show()
                true
            }
            bmList.addView(col, -1, -2)
        }
    }

    // ---------- umum ----------
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
        if (!on) { tabsDrawer.moveTo(0f); bmDrawer.moveTo(0f) }
        if (on && cur.web.url == null) go(HOME_WEB)
    }

    private fun go(v: String) {
        val t = v.trim()
        val u = when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t) -> t
            !t.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+([/:?#].*)?$").matches(t) -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        cur.web.loadUrl(u); cur.web.requestFocus()
    }

    private fun paintAd() {
        adBtn.text = if (blockAds) "AD ✓" else "AD ✗"
        adBtn.setTextColor(if (blockAds) Color.parseColor("#6ba19a") else Color.GRAY)
    }

    private fun toggleAds() {
        blockAds = !blockAds
        getSharedPreferences("pydm", MODE_PRIVATE).edit().putBoolean("ads", blockAds).apply()
        paintAd(); toast(if (blockAds) "Block Ads: aktif" else "Block Ads: nonaktif"); cur.web.reload()
    }

    private fun refresh() { badge.text = "⬇ ${cur.found.size}" }
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private fun showFound() {
        if (cur.found.isEmpty()) { toast("Belum ada media terdeteksi di halaman ini"); return }
        val l = cur.found.toList()
        AlertDialog.Builder(this).setTitle("Berkas terdeteksi")
            .setItems(l.map { Uri.decode(it.substringBefore('?').substringAfterLast('/')) }.toTypedArray()) { _, i -> ask(l[i], null, null, null, -1) }
            .setNeutralButton("Unduh semua") { _, _ -> l.forEach { add(it, URLUtil.guessFileName(it, null, null), cur.web.settings.userAgentString) } }
            .show()
    }

    private fun ask(u: String, ua: String?, cd: String?, mime: String?, len: Long) {
        val et = EditText(this).apply { setText(URLUtil.guessFileName(u, cd, mime)) }
        AlertDialog.Builder(this).setTitle("Unduh berkas?")
            .setMessage(if (len > 0) Formatter.formatFileSize(this, len) else "Ukuran tidak diketahui")
            .setView(et)
            .setPositiveButton("Unduh") { _, _ -> add(u, et.text.toString(), ua ?: cur.web.settings.userAgentString) }
            .setNegativeButton("Batal", null).show()
    }

    private fun add(u: String, name: String, ua: String) {
        val ref = cur.web.url ?: ""
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
            tabsDrawer.showing -> tabsDrawer.close()
            bmDrawer.showing -> bmDrawer.close()
            box.visibility == View.VISIBLE -> if (cur.web.canGoBack()) cur.web.goBack() else show(false)
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
