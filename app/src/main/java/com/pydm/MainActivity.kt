package com.pydm

import android.Manifest
import android.animation.ValueAnimator
import android.app.*
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
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
const val REQ_BM_EXPORT = 11
const val REQ_BM_IMPORT = 12

// Domain iklan/pelacak yang diblokir (daftar sendiri, bisa ditambah). Subdomain ikut diblokir.
val AD_HOSTS = ("doubleclick.net googlesyndication.com googleadservices.com adservice.google.com 2mdn.net " +
    "adnxs.com adsrvr.org taboola.com outbrain.com popads.net popcash.net propellerads.com exoclick.com " +
    "exdynsrv.com juicyads.com trafficjunky.com trafficstars.com adsterra.com hilltopads.net clickadu.com " +
    "onclickads.net admaven.com ad-maven.com adcash.com mgid.com adskeeper.com revcontent.com criteo.com " +
    "pubmatic.com rubiconproject.com openx.net amazon-adsystem.com media.net adform.net smartadserver.com " +
    "serving-sys.com moatads.com adsafeprotected.com zedo.com yllix.com a-ads.com bidvertiser.com " +
    "infolinks.com monetag.com realsrv.com magsrv.com tsyndicate.com onclkds.com adtng.com scorecardresearch.com " +
    "ero-advertising.com clickaine.com popunderjs.com adbrite.com").split(" ")

// Daftar blokir pribadi (ditambah dari Catatan blokir, disimpan di pengaturan)
@Volatile var USER_HOSTS: List<String> = emptyList()

fun isAd(h: String?) = h != null &&
    (AD_HOSTS.any { h == it || h.endsWith(".$it") } || USER_HOSTS.any { h == it || h.endsWith(".$it") })

// Situs umum: tidak boleh diblokir per domain dari catatan blokir
val PROTECTED_SITES = setOf("google.com", "gstatic.com", "googleapis.com", "youtube.com", "ytimg.com",
    "bing.com", "duckduckgo.com", "facebook.com", "github.com", "wikipedia.org")

// Kunci situs: "cdn.oppadrama.io" dan "www.oppadrama.io" sama-sama "oppadrama.io"
fun siteKey(h: String): String {
    val p = h.lowercase().trimEnd('.').split('.')
    if (p.size <= 2 || p.all { x -> x.isNotEmpty() && x.all { c -> c.isDigit() } }) return p.joinToString(".")
    val sl = p[p.size - 2]
    val two = setOf("co", "com", "net", "org", "ac", "go", "or", "web", "my", "sch", "gov", "edu")
    return if (p.last().length == 2 && sl in two) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
}

// Samakan bentuk link untuk dibandingkan (abaikan skema, #fragmen, dan / di ujung)
fun normLink(u: String) = u.substringBefore('#').substringAfter("://").trimEnd('/').lowercase()

class BlockEntry(val kind: String, val target: String, val from: String, var count: Int, var time: Long)

// Script penjaga: mencatat ketukan asli pada link/form, dan menetralkan lapisan transparan
// layar penuh yang menangkap ketukan pertama. @@N@@ diganti nama jembatan acak.
const val GUARD_JS = """(function(){
if(window.__pydmG)return;
var B=window['@@N@@'];if(!B)return;
window.__pydmG=1;
function tap(e){
  if(!e.isTrusted)return;
  var el=e.target,n=0;
  while(el&&el!==document&&n<12){
    if(el.tagName==='A'&&el.href){try{B.tap(String(el.href));}catch(x){}return;}
    el=el.parentNode;n++;
  }
}
document.addEventListener('click',tap,true);
document.addEventListener('submit',function(e){
  if(!e.isTrusted)return;
  var f=e.target;
  if(f&&typeof f.action==='string'&&f.action){try{B.tap(f.action);}catch(x){}}
},true);
var done=[];
function vw(){return window.innerWidth||document.documentElement.clientWidth||0}
function vh(){return window.innerHeight||document.documentElement.clientHeight||0}
function ghost(el){
  if(!el||el===document.body||el===document.documentElement)return false;
  var cs=getComputedStyle(el),pos=cs.position;
  if(pos!=='fixed'&&pos!=='absolute')return false;
  if(cs.display==='none'||cs.visibility==='hidden')return false;
  var r=el.getBoundingClientRect();
  if(r.width<vw()*0.85||r.height<vh()*0.85)return false;
  var op=parseFloat(cs.opacity);
  if(el.tagName==='IFRAME')return op<0.05;
  if((el.innerText||'').trim()!=='')return false;
  if(el.querySelector('img,video,audio,canvas,svg,input,textarea,select,button,object,embed,iframe'))return false;
  if(op<0.05)return true;
  var bg=cs.backgroundColor;
  if(!(bg==='transparent'||(bg.indexOf('rgba')===0&&/,\s*0\)/.test(bg)))||cs.backgroundImage!=='none')return false;
  if(el.getElementsByTagName('*').length>2)return false;
  if(pos==='absolute'){var p=el.parentElement;if(p&&p.querySelector('video,audio,iframe'))return false;}
  return true;
}
function neutralize(el){
  if(el.__pg)return;
  el.__pg=1;
  el.style.setProperty('pointer-events','none','important');
  done.push(el);
  var d=el.tagName.toLowerCase()+(el.id?'#'+el.id:'')+
    (typeof el.className==='string'&&el.className.trim()?'.'+el.className.trim().split(/\s+/)[0]:'');
  try{B.ghost(d);}catch(x){}
}
function restore(){
  for(var i=done.length-1;i>=0;i--){
    var el=done[i];
    if(!document.contains(el)||!ghost(el)){el.style.removeProperty('pointer-events');el.__pg=0;done.splice(i,1);}
  }
}
function at(x,y){
  var s=document.elementsFromPoint?document.elementsFromPoint(x,y):[];
  for(var i=0;i<s.length;i++){
    var el=s[i];
    if(el===document.body||el===document.documentElement)break;
    if(ghost(el))neutralize(el);
  }
}
function sweep(){
  try{
    restore();
    var w=vw(),h=vh(),k=[0.2,0.5,0.8];
    for(var i=0;i<3;i++)for(var j=0;j<3;j++)at(w*k[i],h*k[j]);
  }catch(x){}
}
document.addEventListener('touchstart',function(e){
  if(!e.isTrusted)return;
  var t=e.touches&&e.touches[0];
  if(t){try{at(t.clientX,t.clientY);}catch(x){}}
},{capture:true,passive:true});
sweep();setInterval(sweep,1500);
})()"""

const val HIDE_JS = "(function(){var s=document.createElement('style');" +
    "s.textContent='ins.adsbygoogle,[id^=\"div-gpt-ad\"],[id*=\"google_ads\"],iframe[src*=\"doubleclick\"]," +
    "iframe[src*=\"googlesyndication\"],.adsbox,.ad-banner{display:none!important}';" +
    "document.documentElement.appendChild(s)})()"

// Format berkas yang dideteksi (bisa diatur lewat ⚙️ di dialog "Berkas terdeteksi")
const val DEF_EXT = "mp4,mkv,webm,mp3,m4a,flac,zip,rar,7z,apk,iso,cso,pdf"
fun parseExt(s: String): List<String> =
    s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.distinct()
fun mediaRegex(l: List<String>) = Regex("\\.(" + l.joinToString("|") + ")(\\?|$)", RegexOption.IGNORE_CASE)

fun storageOk(c: Context) =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED


class Tab(val web: WebView) {
    var title = ""
    @Volatile var pageHost = ""     // host halaman yang sedang tampil (untuk catatan blokir)
    @Volatile var tapUrl = ""       // link terakhir yang benar-benar diketuk
    @Volatile var tapAt = 0L
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
    private lateinit var fsHolder: FrameLayout
    private lateinit var bmTabB: Button
    private lateinit var bmTabH: Button
    private lateinit var bmIo: LinearLayout
    private var bmMode = 0   // 0 = bookmark, 1 = riwayat
    private var fsView: View? = null
    private var fsCb: WebChromeClient.CustomViewCallback? = null
    private lateinit var bmDrawer: SwipePanel
    @Volatile private var blockAds = true
    private val guardName = "pg" + java.lang.Long.toHexString(java.util.Random().nextLong() ushr 8)
    private lateinit var guardBar: LinearLayout
    private lateinit var guardTxt: TextView
    private var guardAct: (() -> Unit)? = null
    private val hideGuardRun = Runnable { if (::guardBar.isInitialized) guardBar.visibility = View.GONE }
    private val blockLog = ArrayList<BlockEntry>()
    private var askedN = false
    private var askedS = false
    @Volatile private var media = mediaRegex(parseExt(DEF_EXT))

    inner class Bridge {
        @JavascriptInterface fun openBrowser() { runOnUiThread { show(true) } }
        @JavascriptInterface fun openFolder(path: String) { runOnUiThread { openFolderAt(path) } }
        @JavascriptInterface fun kick() { Engine.ensureService(applicationContext) }
    }

    // Jembatan per tab untuk penjaga (nama acak tiap peluncuran; hanya menerima ketukan asli dari script)
    inner class TabGuard(private val t: Tab) {
        @JavascriptInterface fun tap(h: String?) { if (h != null) { t.tapUrl = h; t.tapAt = SystemClock.uptimeMillis() } }
        @JavascriptInterface fun ghost(d: String?) { if (d != null) logBlock("Lapisan", d.take(80), t.pageHost) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Tombol bar: tinggi minimal 48dp (area sentuh), ada efek sentuh
    private fun btn(t: String, sp: Float = 20f, f: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setAllCaps(false); textSize = sp
        stateListAnimator = null
        val tv = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
        else setBackgroundColor(Color.TRANSPARENT)
        minimumHeight = dp(48); minHeight = dp(48)
        minimumWidth = dp(48); minWidth = dp(48)
        setPadding(dp(8), 0, dp(8), 0); setOnClickListener { f() }
    }

    // Parameter tata letak dengan jarak antar tombol
    private fun lp(w: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, dp(48), wt).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }

    private fun header(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 18f
        setPadding(dp(16), dp(16), dp(16), dp(12))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        blockAds = getSharedPreferences("pydm", MODE_PRIVATE).getBoolean("ads", true)
        val savedExt = parseExt(getSharedPreferences("pydm", MODE_PRIVATE).getString("ext", DEF_EXT) ?: DEF_EXT)
        if (savedExt.isNotEmpty()) media = mediaRegex(savedExt)
        USER_HOSTS = (getSharedPreferences("pydm", MODE_PRIVATE).getString("myblock", "") ?: "")
            .split(" ").filter { it.isNotEmpty() }
        loadBlockLog()
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
        tabBtn = btn("1", 16f) { openTabsPanel() }
        val boxBg = GradientDrawable()
        boxBg.setStroke(dp(2), Color.WHITE); boxBg.cornerRadius = dp(6).toFloat(); boxBg.setColor(Color.TRANSPARENT)
        tabBtn.background = InsetDrawable(boxBg, dp(9))   // kotak 30dp di dalam area sentuh 48dp
        starBtn = btn("☆", 26f) { toggleStar() }
        starBtn.setOnLongClickListener { openBmPanel(); true }
        urlBar.minimumHeight = dp(48); urlBar.minHeight = dp(48)
        val urlRow = LinearLayout(this)
        urlRow.gravity = Gravity.CENTER_VERTICAL
        urlRow.setPadding(dp(4), dp(2), dp(4), 0)
        urlRow.addView(tabBtn, lp(dp(48)))
        urlRow.addView(urlBar, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        urlRow.addView(starBtn, lp(dp(48)))
        urlRow.addView(btn("⟳", 28f) { cur.web.reload() }, lp(dp(52)))

        adBtn = btn("AD", 15f) { toggleAds() }; paintAd()
        adBtn.setOnLongClickListener { showBlockLog(); true }   // tekan lama = catatan blokir
        badge = btn("⬇ 0", 15f) { showFound() }
        val navRow = LinearLayout(this)
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.setPadding(dp(4), 0, dp(4), dp(2))
        navRow.addView(btn("←", 24f) { if (cur.web.canGoBack()) cur.web.goBack() }, lp(0, 1f))
        navRow.addView(btn("→", 24f) { if (cur.web.canGoForward()) cur.web.goForward() }, lp(0, 1f))
        navRow.addView(btn("🏠", 22f) { go(HOME_WEB) }, lp(0, 1f))
        navRow.addView(adBtn, lp(0, 1f))
        navRow.addView(badge, lp(0, 1.2f))
        navRow.addView(btn("📥", 22f) { show(false) }, lp(0, 1f))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(Color.parseColor("#212121"))
        bottom.addView(urlRow, -1, -2)
        bottom.addView(navRow, -1, -2)

        tabHost = FrameLayout(this)
        guardTxt = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        guardBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EE333333"))
            setPadding(dp(12), dp(2), dp(4), dp(2))
            visibility = View.GONE
            addView(guardTxt, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("Buka juga", 14f) {
                val a = guardAct; guardAct = null; visibility = View.GONE; a?.invoke()
            }, LinearLayout.LayoutParams(-2, dp(48)))
        }
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
        bmTabB = btn("⭐ Bookmark", 15f) { bmMode = 0; rebuildBm() }
        bmTabH = btn("🕘 Riwayat", 15f) { bmMode = 1; rebuildBm() }
        val bmTabs = LinearLayout(this)
        bmTabs.gravity = Gravity.CENTER_VERTICAL
        bmTabs.setPadding(dp(4), dp(4), dp(4), dp(4))
        bmTabs.addView(bmTabB, lp(0, 1f))
        bmTabs.addView(bmTabH, lp(0, 1f))
        bcol.addView(bmTabs, -1, -2)
        val bscroll = ScrollView(this)
        bscroll.addView(bmList, -1, -2)
        bcol.addView(bscroll, LinearLayout.LayoutParams(-1, 0, 1f))
        bmIo = LinearLayout(this)
        bmIo.gravity = Gravity.CENTER_VERTICAL
        bmIo.setPadding(dp(4), 0, dp(4), 0)
        bmIo.addView(btn("📥 Impor", 15f) { importBm() }, lp(0, 1f))
        bmIo.addView(btn("📤 Ekspor", 15f) { exportBm() }, lp(0, 1f))
        bcol.addView(bmIo, -1, -2)
        bcol.addView(btn("🧹  Hapus data", 15f) { clearDataDialog() }, -1, -2)
        bmDrawer = SwipePanel(this, bcol, false, dw)

        // ---------- strip tepi layar untuk geser ----------
        stripL = View(this)
        stripL.setOnTouchListener(dragListener(tabsDrawer, true, false) { rebuildTabs() })
        stripR = View(this)
        stripR.setOnTouchListener(dragListener(bmDrawer, false, false) { rebuildBm() })
        tabsDrawer.root.getChildAt(0).setOnTouchListener(dragListener(tabsDrawer, true, true))
        bmDrawer.root.getChildAt(0).setOnTouchListener(dragListener(bmDrawer, false, true))

        fsHolder = FrameLayout(this)
        fsHolder.setBackgroundColor(Color.BLACK)
        fsHolder.visibility = View.GONE
        setContentView(FrameLayout(this).apply {
            addView(ui, -1, -1)
            addView(box, -1, -1)
            addView(tabsDrawer.root, -1, -1)
            addView(bmDrawer.root, -1, -1)
            addView(fsHolder, -1, -1)
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
        w.addJavascriptInterface(TabGuard(t), guardName)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                t.found.clear()
                t.pageHost = Uri.parse(u).host ?: ""
                if (t === cur) { refresh(); urlBar.setText(u); paintStar() }
            }
            override fun onPageCommitVisible(v: WebView, u: String) { injectGuard(v) }
            override fun onPageFinished(v: WebView, u: String) {
                if (blockAds) { v.evaluateJavascript(HIDE_JS, null); injectGuard(v) }
                t.title = v.title ?: u
                addHist(t.title, u)
                if (t === cur) paintStar()
                rebuildTabs()
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (!blockAds) return false
                val sc = r.url.scheme ?: ""
                val from = Uri.parse(v.url ?: "").host ?: ""
                if (sc !in listOf("http", "https", "about", "blob", "data", "mailto", "tel")) {
                    logBlock("Aplikasi", r.url.toString().take(100), from)
                    toast("Pengalihan ke aplikasi diblokir"); return true
                }
                if (isAd(r.url.host)) { logBlock("Iklan", r.url.host ?: "", from); toast("Halaman iklan diblokir"); return true }
                // Penjaga pengalihan: halaman utama pindah ke situs lain tanpa ketukan pada link itu
                if (r.isForMainFrame && !r.isRedirect && !navAllowed(t, v.url, r.url)) {
                    val u = r.url.toString()
                    logBlock("Pengalihan", u.take(140), from)
                    showGuard("Pengalihan otomatis diblokir: ${r.url.host ?: u}") { v.loadUrl(u) }
                    return true
                }
                return false
            }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (blockAds && isAd(r.url.host)) {
                    logBlock("Iklan", r.url.host ?: "", t.pageHost)
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
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
                updateHistTitle(v.url, ti)
                rebuildTabs()
            }
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null || callback == null) return
                enterFs(view, callback)
            }
            override fun onHideCustomView() { exitFs() }
            override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: Message): Boolean {
                if (blockAds && !g) { logBlock("Popup", "tanpa ketukan", t.pageHost); toast("Popup diblokir"); return false }  // popup tanpa ketukan
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(w2: WebView, r2: WebResourceRequest): Boolean {
                        val tu = r2.url.toString()
                        if (blockAds && isAd(r2.url.host)) {
                            logBlock("Popup iklan", r2.url.host ?: "", t.pageHost); toast("Popup iklan diblokir")
                        } else if (blockAds && !navAllowed(t, v.url, r2.url)) {
                            // popup dari ketukan hanya dibuka bila satu situs (atau memang link yang diketuk)
                            logBlock("Popup", tu.take(140), t.pageHost)
                            showGuard("Popup ke situs lain ditahan: ${r2.url.host ?: tu}") { openTab(tu) }
                        } else openTab(tu)
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
        (guardBar.parent as? ViewGroup)?.removeView(guardBar)
        tabHost.addView(guardBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
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
        bmTabB.setTextColor(if (bmMode == 0) Color.parseColor("#FFC107") else Color.WHITE)
        bmTabH.setTextColor(if (bmMode == 1) Color.parseColor("#FFC107") else Color.WHITE)
        if (::bmIo.isInitialized) bmIo.visibility = if (bmMode == 0) View.VISIBLE else View.GONE
        if (bmMode == 1) { rebuildHist(); return }
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

    // ---------- impor / ekspor bookmark (.json) ----------
    private fun exportBm() {
        if (loadBm().isEmpty()) { toast("Belum ada bookmark untuk diekspor"); return }
        val name = "pydm-bookmark-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".json"
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("application/json")
            i.putExtra(Intent.EXTRA_TITLE, name)
            startActivityForResult(i, REQ_BM_EXPORT)
        } catch (e: Exception) { toast("Tidak ada aplikasi berkas untuk memilih lokasi simpan") }
    }

    private fun importBm() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("*/*")
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            startActivityForResult(i, REQ_BM_IMPORT)
        } catch (e: Exception) { toast("Tidak ada aplikasi berkas untuk memilih berkas") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        if (req == REQ_BM_EXPORT) writeBm(uri)
        else if (req == REQ_BM_IMPORT) readBm(uri)
    }

    private fun writeBm(uri: Uri) {
        try {
            val arr = JSONArray()
            for (b in loadBm()) arr.put(JSONObject().put("t", b.first).put("u", b.second))
            val root = JSONObject().put("app", "pydm").put("type", "bookmarks").put("version", 1)
                .put("exported", System.currentTimeMillis()).put("bookmarks", arr)
            val out = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Tidak bisa menulis ke lokasi itu")
            out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
            toast("${arr.length()} bookmark diekspor")
        } catch (e: Exception) { toast("Gagal mengekspor: " + (e.message ?: "").take(120)) }
    }

    // Impor menggabungkan: bookmark yang URL-nya sudah ada dilewati, bookmark lama tidak diubah
    private fun readBm(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Tidak bisa membuka berkas")
            val bytes = input.use { it.readBytes() }
            if (bytes.size > 5_000_000) throw IllegalArgumentException("Berkas terlalu besar")
            val txt = String(bytes, Charsets.UTF_8).trim().trimStart('\uFEFF')
            val arr = if (txt.startsWith("[")) JSONArray(txt)
                      else JSONObject(txt).optJSONArray("bookmarks") ?: throw IllegalArgumentException("Bukan berkas bookmark")
            if (arr.length() == 0) { toast("Berkas tidak berisi bookmark"); return }
            val list = loadBm()
            val have = list.map { it.second }.toHashSet()
            var added = 0
            var skipped = 0
            for (n in 0 until arr.length()) {
                val o = arr.optJSONObject(n)
                if (o == null) { skipped++; continue }
                val u = o.optString("u", o.optString("url", "")).trim()
                if (!u.contains("://") || u in have) { skipped++; continue }
                val t = o.optString("t", o.optString("title", "")).trim()
                list.add(Pair(if (t.isBlank()) u else t, u))
                have.add(u)
                added++
            }
            saveBm(list); paintStar(); rebuildBm()
            toast("$added bookmark ditambahkan" + if (skipped > 0) ", $skipped dilewati (sudah ada/tidak valid)" else "")
        } catch (e: Exception) { toast("Gagal mengimpor: berkas bukan JSON bookmark yang valid") }
    }

    // ---------- riwayat ----------
    private fun loadHist(): MutableList<Triple<String, String, Long>> {
        val out = mutableListOf<Triple<String, String, Long>>()
        try {
            val a = JSONArray(getSharedPreferences("pydm", MODE_PRIVATE).getString("hist", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Triple(o.optString("t"), o.optString("u"), o.optLong("ts")))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveHist(l: List<Triple<String, String, Long>>) {
        val a = JSONArray()
        for (h in l) a.put(JSONObject().put("t", h.first).put("u", h.second).put("ts", h.third))
        getSharedPreferences("pydm", MODE_PRIVATE).edit().putString("hist", a.toString()).apply()
    }

    private fun addHist(title: String, u: String) {
        if (!u.startsWith("http")) return
        val l = loadHist()
        if (l.isNotEmpty() && l[0].second == u) l.removeAt(0)
        l.add(0, Triple(if (title.isBlank()) u else title, u, System.currentTimeMillis()))
        while (l.size > 500) l.removeAt(l.size - 1)
        saveHist(l)
        if (bmMode == 1 && ::bmDrawer.isInitialized && bmDrawer.showing) rebuildBm()
    }

    // Judul sering baru tiba setelah halaman selesai: perbarui entri teratas bila URL-nya sama
    private fun updateHistTitle(u: String?, ti: String?) {
        if (u == null || ti.isNullOrBlank()) return
        val l = loadHist()
        if (l.isNotEmpty() && l[0].second == u && l[0].first != ti) {
            l[0] = Triple(ti, u, l[0].third)
            saveHist(l)
        }
    }

    private fun rebuildHist() {
        bmList.removeAllViews()
        val l = loadHist()
        if (l.isEmpty()) {
            val e = TextView(this)
            e.text = "Belum ada riwayat."
            e.setTextColor(Color.GRAY); e.setPadding(dp(16), dp(16), dp(16), dp(16))
            bmList.addView(e, -1, -2)
            return
        }
        val df = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("id", "ID"))
        for (h in l) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(16), dp(10), dp(16), dp(10))
            val t1 = TextView(this)
            t1.text = h.first
            t1.setTextColor(Color.WHITE); t1.textSize = 15f
            t1.setSingleLine(); t1.setEllipsize(TextUtils.TruncateAt.END)
            val t2 = TextView(this)
            t2.text = df.format(java.util.Date(h.third)) + "  •  " + (Uri.parse(h.second).host ?: h.second)
            t2.setTextColor(Color.GRAY); t2.textSize = 12f
            t2.setSingleLine(); t2.setEllipsize(TextUtils.TruncateAt.END)
            col.addView(t1, -1, -2)
            col.addView(t2, -1, -2)
            col.setOnClickListener { cur.web.loadUrl(h.second); bmDrawer.close() }
            col.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("Hapus dari riwayat?")
                    .setPositiveButton("Hapus") { _, _ ->
                        val cp = loadHist()
                        cp.removeAll { it.third == h.third && it.second == h.second }
                        saveHist(cp); rebuildBm()
                    }
                    .setNegativeButton("Batal", null).show()
                true
            }
            bmList.addView(col, -1, -2)
        }
    }

    // ---------- hapus data ----------
    private fun clearDataDialog() {
        val items = arrayOf("Cache (berkas halaman yang disimpan)", "Cookie & data situs (akan keluar dari akun)", "Riwayat penelusuran")
        val sel = booleanArrayOf(true, false, true)
        AlertDialog.Builder(this).setTitle("Hapus data")
            .setMultiChoiceItems(items, sel) { _, i, c -> sel[i] = c }
            .setPositiveButton("Hapus") { _, _ -> clearData(sel[0], sel[1], sel[2]) }
            .setNegativeButton("Batal", null).show()
    }

    private fun clearData(cache: Boolean, cookies: Boolean, hist: Boolean) {
        if (!cache && !cookies && !hist) { toast("Tidak ada yang dipilih"); return }
        if (cache) for (t in tabs) t.web.clearCache(true)
        if (cookies) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        if (hist) { getSharedPreferences("pydm", MODE_PRIVATE).edit().remove("hist").apply(); rebuildBm() }
        toast("Data dihapus")
    }

    // ---------- fullscreen video ----------
    private fun enterFs(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (fsView != null) { cb.onCustomViewHidden(); return }
        fsView = v; fsCb = cb
        fsHolder.addView(v, FrameLayout.LayoutParams(-1, -1))
        fsHolder.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        immersive(true)
    }

    private fun exitFs() {
        val v = fsView ?: return
        fsView = null
        fsHolder.removeView(v)
        fsHolder.visibility = View.GONE
        fsCb?.onCustomViewHidden()
        fsCb = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        immersive(false)
    }

    private fun immersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            else 0
        }
    }

    // ---------- penjaga pengalihan + catatan blokir ----------
    private fun injectGuard(v: WebView) {
        if (blockAds) v.evaluateJavascript(GUARD_JS.replace("@@N@@", guardName), null)
    }

    // Boleh pindah ke target? Ya bila satu situs, ketukan asli pada link itu, atau berkas unduhan
    private fun navAllowed(t: Tab, curUrl: String?, target: Uri): Boolean {
        val th = target.host ?: return true
        val ch = Uri.parse(curUrl ?: "").host ?: return true
        if (siteKey(th) == siteKey(ch)) return true
        if (media.containsMatchIn(target.toString())) return true
        return SystemClock.uptimeMillis() - t.tapAt < 8000 && normLink(t.tapUrl) == normLink(target.toString())
    }

    private fun showGuard(msg: String, act: () -> Unit) = runOnUiThread {
        guardTxt.text = msg
        guardAct = act
        guardBar.visibility = View.VISIBLE
        guardBar.removeCallbacks(hideGuardRun)
        guardBar.postDelayed(hideGuardRun, 8000)
    }

    private fun logBlock(kind: String, target: String, from: String?) {
        val f = from ?: ""
        synchronized(blockLog) {
            val e = blockLog.firstOrNull { it.kind == kind && it.target == target && it.from == f }
            if (e != null) { e.count++; e.time = System.currentTimeMillis() }
            else {
                blockLog.add(BlockEntry(kind, target, f, 1, System.currentTimeMillis()))
                if (blockLog.size > 150) blockLog.minByOrNull { it.time }?.let { blockLog.remove(it) }
            }
        }
    }

    private fun saveBlockLog() {
        val a = JSONArray()
        synchronized(blockLog) {
            blockLog.forEach { a.put(JSONObject().put("k", it.kind).put("t", it.target).put("f", it.from).put("c", it.count).put("d", it.time)) }
        }
        getSharedPreferences("pydm", MODE_PRIVATE).edit().putString("blocklog", a.toString()).apply()
    }

    private fun loadBlockLog() {
        try {
            val a = JSONArray(getSharedPreferences("pydm", MODE_PRIVATE).getString("blocklog", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                blockLog.add(BlockEntry(o.getString("k"), o.getString("t"), o.getString("f"), o.getInt("c"), o.getLong("d")))
            }
        } catch (_: Exception) {}
    }

    private fun entryLabel(e: BlockEntry): String {
        val t = if (e.target.length > 70) e.target.take(70) + "…" else e.target
        return "${e.kind}: $t\n← ${e.from.ifEmpty { "?" }}  ×${e.count}"
    }

    // Tekan lama tombol AD: apa saja yang diblokir dan dari halaman mana
    private fun showBlockLog() {
        val l = synchronized(blockLog) { blockLog.sortedByDescending { it.time } }.take(80)
        val b = AlertDialog.Builder(this).setTitle("Catatan blokir")
        if (l.isEmpty()) {
            b.setMessage("Belum ada yang diblokir.\nKetuk AD untuk menyalakan atau mematikan pemblokir.")
        } else {
            b.setItems(l.map { entryLabel(it) }.toTypedArray()) { _, i -> entryMenu(l[i]) }
            b.setPositiveButton("Kosongkan") { _, _ -> synchronized(blockLog) { blockLog.clear() }; saveBlockLog() }
        }
        b.setNeutralButton("Daftar pribadi (${USER_HOSTS.size})") { _, _ -> userListDialog() }
        b.setNegativeButton("Tutup", null)
        b.show()
    }

    private fun entryMenu(e: BlockEntry) {
        val host = (if (e.target.contains("://")) Uri.parse(e.target).host
            else e.target.takeIf { Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$").matches(it) })?.lowercase()
        var msg = "${e.kind}\n${e.target}\n\nDari halaman: ${e.from.ifEmpty { "?" }}\nDiblokir ${e.count}×"
        val key = host?.let { siteKey(it) } ?: ""
        val b = AlertDialog.Builder(this).setTitle("Detail blokir").setNegativeButton("Tutup", null)
        when {
            host == null -> msg += "\n\nTidak ada domain yang bisa diblokir; ini ditahan otomatis oleh penjaga."
            key in PROTECTED_SITES -> msg += "\n\n$key adalah situs umum, jadi tidak diblokir per domain. Pengalihan ke sana ditangani penjaga pengalihan."
            AD_HOSTS.any { host == it || host.endsWith(".$it") } -> msg += "\n\nDomain ini sudah ada di daftar bawaan."
            USER_HOSTS.any { host == it || host.endsWith(".$it") } -> msg += "\n\nDomain ini sudah ada di daftar pribadi."
            else -> b.setPositiveButton("Blokir $host") { _, _ -> addUserHost(host) }
        }
        b.setMessage(msg).show()
    }

    private fun addUserHost(h: String) {
        USER_HOSTS = (USER_HOSTS + h).distinct()
        getSharedPreferences("pydm", MODE_PRIVATE).edit().putString("myblock", USER_HOSTS.joinToString(" ")).apply()
        toast("$h masuk daftar blokir pribadi")
    }

    private fun userListDialog() {
        val l = USER_HOSTS
        val b = AlertDialog.Builder(this).setTitle("Daftar blokir pribadi")
        if (l.isEmpty()) b.setMessage("Masih kosong. Tambahkan dari catatan blokir (ketuk satu entri).")
        else b.setItems(l.toTypedArray()) { _, i ->
            AlertDialog.Builder(this).setMessage("Hapus ${l[i]} dari daftar?")
                .setPositiveButton("Hapus") { _, _ ->
                    USER_HOSTS = USER_HOSTS - l[i]
                    getSharedPreferences("pydm", MODE_PRIVATE).edit().putString("myblock", USER_HOSTS.joinToString(" ")).apply()
                    toast("Dihapus dari daftar pribadi")
                }
                .setNegativeButton("Batal", null).show()
        }
        b.setNegativeButton("Tutup", null).show()
    }

    override fun onPause() { super.onPause(); saveBlockLog() }

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
        Engine.start(this)
        if (ui.url == null) ui.loadUrl(HOME_UI)
    }

    private fun show(on: Boolean) {
        box.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) { exitFs(); tabsDrawer.moveTo(0f); bmDrawer.moveTo(0f) }
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
        val l = cur.found.toList()
        var dlg: AlertDialog? = null
        val title = LinearLayout(this)
        title.gravity = Gravity.CENTER_VERTICAL
        title.setPadding(dp(20), dp(8), dp(6), 0)
        val tt = TextView(this)
        tt.text = "Berkas terdeteksi"; tt.setTextColor(Color.WHITE); tt.textSize = 20f
        title.addView(tt, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(btn("⚙️", 22f) { dlg?.dismiss(); extSettings() }, LinearLayout.LayoutParams(dp(48), dp(48)))

        val b = AlertDialog.Builder(this).setCustomTitle(title)
        if (l.isEmpty()) {
            b.setMessage("Belum ada berkas terdeteksi di halaman ini.\nKetuk ⚙️ untuk mengatur format yang dideteksi.")
        } else {
            b.setItems(l.map { Uri.decode(it.substringBefore('?').substringAfterLast('/')) }.toTypedArray()) { _, i -> ask(l[i], null, null, null, -1) }
            b.setPositiveButton("Unduh semua") { _, _ -> addAll(l, false) }
            b.setNeutralButton("Tambah semua") { _, _ -> addAll(l, true) }
        }
        b.setNegativeButton("Tutup", null)
        val d = b.create()
        dlg = d
        d.show()
    }

    private fun extSettings() {
        val prefs = getSharedPreferences("pydm", MODE_PRIVATE)
        val et = EditText(this)
        et.setText(prefs.getString("ext", DEF_EXT) ?: DEF_EXT)
        et.setSingleLine(false); et.minLines = 2
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val pad = FrameLayout(this)
        pad.setPadding(dp(20), dp(4), dp(20), 0)
        pad.addView(et, -1, -2)
        AlertDialog.Builder(this).setTitle("Format yang dideteksi")
            .setMessage("Pisahkan dengan koma, contoh: mp4, mkv, zip, apk")
            .setView(pad)
            .setPositiveButton("Simpan") { _, _ -> saveExt(et.text.toString()) }
            .setNeutralButton("Bawaan") { _, _ -> saveExt(DEF_EXT) }
            .setNegativeButton("Batal", null).show()
    }

    private fun saveExt(raw: String) {
        val l = parseExt(raw)
        if (l.isEmpty()) { toast("Isi minimal satu format"); return }
        getSharedPreferences("pydm", MODE_PRIVATE).edit().putString("ext", l.joinToString(",")).apply()
        media = mediaRegex(l)
        toast("Format disimpan: " + l.joinToString(", "))
        cur.web.reload()
    }

    // Buka folder unduhan di aplikasi berkas; kalau tidak bisa, tampilkan lokasinya
    private fun openFolderAt(path: String) {
        val root = Environment.getExternalStorageDirectory().path
        if (path.startsWith("$root/")) {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + path.removePrefix("$root/"))
            for (type in listOf(DocumentsContract.Document.MIME_TYPE_DIR, "resource/folder")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type))
                    return
                } catch (e: Exception) { }
            }
        }
        toast("Folder: $path")
    }

    private fun ask(u: String, ua: String?, cd: String?, mime: String?, len: Long) {
        val et = EditText(this).apply { setText(URLUtil.guessFileName(u, cd, mime)) }
        val agent = ua ?: cur.web.settings.userAgentString
        AlertDialog.Builder(this).setTitle("Unduh berkas?")
            .setMessage(if (len > 0) Formatter.formatFileSize(this, len) else "Ukuran tidak diketahui")
            .setView(et)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Tambah ke daftar") { _, _ -> add(u, et.text.toString(), agent, true) }
            .setPositiveButton("Unduh") { _, _ -> add(u, et.text.toString(), agent, false) }
            .show()
    }

    // paused=true: masuk daftar Unduhan berstatus jeda, jalan setelah ▶ ditekan
    private fun add(u: String, name: String, ua: String, paused: Boolean) {
        val ref = cur.web.url ?: ""
        val ck = CookieManager.getInstance().getCookie(u) ?: ""
        Thread {
            try {
                Python.getInstance().getModule("server").callAttr("add", u, name, ref, ck, ua, paused)
                if (!paused) Engine.ensureService(applicationContext)
                toast(if (paused) "Ditambahkan ke daftar (jeda)" else "Ditambahkan ke antrean")
            } catch (e: Exception) { toast("Gagal: " + (e.message ?: "").take(150)) }
        }.start()
    }

    private fun addAll(urls: List<String>, paused: Boolean) {
        val ref = cur.web.url ?: ""
        val ua = cur.web.settings.userAgentString
        val cks = urls.map { CookieManager.getInstance().getCookie(it) ?: "" }
        Thread {
            var ok = 0
            val srv = Python.getInstance().getModule("server")
            for ((i, u) in urls.withIndex()) {
                try { srv.callAttr("add", u, URLUtil.guessFileName(u, null, null), ref, cks[i], ua, paused); ok++ }
                catch (e: Exception) { }
            }
            if (!paused && ok > 0) Engine.ensureService(applicationContext)
            toast("$ok dari ${urls.size} berkas " + if (paused) "ditambahkan ke daftar" else "masuk antrean")
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fsView != null -> exitFs()
            tabsDrawer.showing -> tabsDrawer.close()
            bmDrawer.showing -> bmDrawer.close()
            box.visibility == View.VISIBLE -> if (cur.web.canGoBack()) cur.web.goBack() else show(false)
            ui.canGoBack() -> ui.goBack()
            else -> super.onBackPressed()
        }
    }
}

// Mesin unduhan: server Python hidup di proses aplikasi. Layanan (notifikasi) hanya menyala saat ada unduhan.
object Engine {
    private var started = false

    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread {
            File(app.filesDir, "index.html").outputStream().use { o -> app.assets.open("index.html").use { it.copyTo(o) } }
            val dl = if (storageOk(app)) File(Environment.getExternalStorageDirectory(), "Download/PyDM").path
                     else File(app.getExternalFilesDir(null) ?: app.filesDir, "PyDM").path
            Python.getInstance().getModule("server").callAttr("run", app.filesDir.path, dl, "127.0.0.1", 8080)
        }.start()
        // Pemantau: bila ada unduhan berjalan/antre, pastikan layanan latar menyala
        Thread {
            while (true) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
                if (DownloadService.running) continue
                val s = stats()
                if (s[0] + s[1] > 0) ensureService(app)
            }
        }.apply { isDaemon = true; start() }
    }

    // [sedang mengunduh, antre, kecepatan total B/d]
    fun stats(): IntArray = try {
        val l = Python.getInstance().getModule("server").callAttr("stats").asList()
        intArrayOf(l[0].toInt(), l[1].toInt(), l[2].toInt())
    } catch (e: Exception) { intArrayOf(0, 0, 0) }

    fun ensureService(ctx: Context) {
        if (DownloadService.running) return
        try {
            val i = Intent(ctx, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) { }   // mis. aplikasi di latar belakang: dicoba lagi oleh pemantau
    }
}

class DownloadService : Service() {
    companion object { @Volatile var running = false }
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var alive = false
    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "dm") else Notification.Builder(this)
        return b.setContentTitle("PyDM").setContentText(text).setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(pi).build()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("dm", "PyDM", NotificationManager.IMPORTANCE_LOW))
        val first = notif("Menyiapkan unduhan…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, first)
        if (!alive) {
            alive = true; running = true
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pydm:dl").apply { setReferenceCounted(false); acquire() }
            Thread {
                var idle = 0
                var last = ""
                while (alive) {
                    val s = Engine.stats()
                    if (s[0] + s[1] == 0) idle++ else idle = 0
                    if (idle >= 3) break   // tidak ada unduhan lagi: matikan layanan dan notifikasi
                    var text = "${s[0]} mengunduh • ${Formatter.formatFileSize(this, s[2].toLong())}/d"
                    if (s[1] > 0) text += " • ${s[1]} antre"
                    if (text != last) { last = text; nm.notify(1, notif(text)) }
                    try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                }
                running = false
                stopSelf()
            }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false; running = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }
}
