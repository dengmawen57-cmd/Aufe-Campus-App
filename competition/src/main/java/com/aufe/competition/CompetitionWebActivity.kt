package com.aufe.competition

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import org.json.JSONObject

/**
 * 学科竞赛（创新创业管理平台）承载页。
 *
 * 设计取舍：平台是 ASP.NET WebForms，页面状态全在服务端 ViewState 里，
 * 每个"申报 / 报名 / 翻页 / 筛选"都是 __doPostBack 回发。用原生控件复刻
 * 等于重写整套表单状态机，既不现实也不抗震（学校一改版就废）。
 * 因此这里采用「WebView 承载 + 原生外壳」：
 *   原生负责 标题栏 / 进度 / 下载 / 上传 / 返回 / 错误兜底 / 外链分流，
 *   WebView 负责业务页面本身，功能 100% 与服务端一致。
 */
class CompetitionWebActivity : AppCompatActivity(), CompetitionFileChooserHost {

    companion object {
        private const val EXTRA_TAB = "extra_tab"

        /** 一次会话里最多自动救两次，再多就是服务端问题，别把账号打进风控 */
        private const val MAX_HEAL_ATTEMPTS = 2

        fun intent(context: Context, tab: Int): Intent =
            Intent(context, CompetitionWebActivity::class.java).putExtra(EXTRA_TAB, tab)
    }

    private lateinit var toolbar: Toolbar
    private lateinit var progress: ProgressBar
    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private var pendingTabLabel: String? = null
    private var tabApplied = false
    private var tabAttempts = 0
    private var loginHintShown = false

    /** 网页版会话自愈：最多自动救两次，避免和服务端互相弹来弹去 */
    private var healAttempts = 0
    private var healing = false

    /** 系统文件选择器（上传佐证材料时用） */
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback ?: return@registerForActivityResult
            pendingFileCallback = null

            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val clip = data.clipData
            val uris: Array<Uri>? = when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_competition_web)

        bindViews()
        setupToolbar()
        setupWebView()
        setupBackHandling()

        pendingTabLabel = CompetitionModule.tabLabel(
            intent.getIntExtra(EXTRA_TAB, CompetitionModule.TAB_AWARD_APPLY)
        )
        loadEntry()
    }

    // region 初始化

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.webView)
        errorView = findViewById(R.id.errorView)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)
        retryButton = findViewById(R.id.btnRetry)
    }

    private fun setupToolbar() {
        // 不接管 ActionBar，直接当普通 Toolbar 用，避免宿主主题差异导致标题不显示
        toolbar.setTitle(R.string.competition_title)
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_competition_web)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    retryOrReload()
                    true
                }
                else -> false
            }
        }
        retryButton.setOnClickListener { retryOrReload() }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true                 // 回发、筛选全靠 JS
            domStorageEnabled = true                 // 平台用到 localStorage
            databaseEnabled = true
            useWideViewPort = true                   // 老页面是固定宽度表格，必须按视口缩放
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = 100                           // 防止系统字体放大把表格挤崩
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false                  // 安全：不暴露本地文件
            allowContentAccess = true                // 上传需要读 content://
            loadsImagesAutomatically = true
        }

        // 页面里"项目详情"是 target=_blank，关掉多窗口让它在本 WebView 内打开
        // 注：原模块写成了 webView.setSupportMultipleWindows()，这是 WebSettings 上的方法，
        // WebView 本身没有，编译不过，这里修正
        webView.settings.setSupportMultipleWindows(false)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = CompetitionWebViewClient(
            onPageStarted = { onPageStarted() },
            onPageFinished = { url -> onPageFinished(url) },
            onMainFrameError = { code, desc -> showError(describeError(code, desc)) },
            onExternalIntent = ::openExternal,
            isInternalHost = CompetitionConfig::isInternalHost
        )

        webView.webChromeClient = CompetitionWebChromeClient(
            host = this,
            onProgress = { value -> progress.progress = value },
            onReceivedTitle = { /* 平台页面 title 为空，标题固定为"学科竞赛" */ }
        )

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            CompetitionDownloader.enqueue(
                context = this,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                onMessage = ::toast
            )
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    errorView.isVisible -> finish()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    // endregion

    // region 加载与状态

    private fun loadEntry() {
        if (!isNetworkAvailable()) {
            showError(getString(R.string.competition_no_network))
            return
        }
        errorView.isVisible = false
        progress.isVisible = true
        webView.loadUrl(CompetitionConfig.entryUrl())
    }

    private fun retryOrReload() {
        errorView.isVisible = false
        if (webView.url.isNullOrEmpty()) {
            loadEntry()
        } else {
            progress.isVisible = true
            webView.reload()
        }
    }

    private fun onPageStarted() {
        progress.isVisible = true
        errorView.isVisible = false
    }

    private fun onPageFinished(url: String) {
        progress.isVisible = false
        errorView.isVisible = false
        detectLoginPage(url)
        applyPendingTab()
    }

    /**
     * 会话过期时会被踢到 vpn.aufe.edu.cn/portal 登录页。
     *
     * v1.11.3 起不再只是"提示用户自己登"：网页版和原生界面是两套 Cookie 仓库，
     * 网页版这张票据过期时 App 侧通常还活着。这时请宿主把会话重登一遍、
     * 整仓重灌 Cookie，然后自己 reload —— 用户不需要退出 App 再登录。
     */
    private fun detectLoginPage(url: String) {
        val isLogin = url.contains("/portal") || url.contains("login", ignoreCase = true)
        if (isLogin) {
            if (healSession()) return
            if (!loginHintShown) {
                loginHintShown = true
                toast(getString(R.string.competition_login_hint))
            }
            return
        }
        // 地址看着正常，但页面内容可能已经写着"未检测到登录"。
        // 学校的 WebVPN 反代在票据失效时常常是 **HTTP 200 + 一句提示**，
        // 不跳转、不改 URL，只看地址永远发现不了（v1.11.4 补）。
        if (healing) return
        webView.postDelayed({ inspectPage() }, 900)
    }

    /** 问页面自己一句"登上了没有" */
    private fun inspectPage() {
        if (healing || webView.url.isNullOrBlank()) return
        webView.evaluateJavascript(LOGIN_SENSOR_JS) { raw ->
            if (looksUnauthenticated(raw)) {
                if (!healSession() && !loginHintShown) {
                    loginHintShown = true
                    toast(getString(R.string.competition_login_hint))
                }
            } else {
                // 回到业务页说明会话是好的，计数归零，下次过期还能再救
                healAttempts = 0
                loginHintShown = false
            }
        }
    }

    /**
     * 请宿主重登 + 重灌 Cookie。
     *
     * @return true 表示已经发起自愈（成功后本页会 reload），false 表示没有可用通道
     */
    private fun healSession(): Boolean {
        val refresher = CompetitionSession.refresher ?: return false
        if (healing || healAttempts >= MAX_HEAL_ATTEMPTS) return false
        healing = true
        healAttempts++
        progress.isVisible = true
        toast(getString(R.string.competition_login_healing))
        refresher { ok ->
            runOnUiThread {
                healing = false
                progress.isVisible = false
                if (ok) {
                    // Cookie 整仓重灌过：页签点击也要重新来过
                    tabApplied = false
                    tabAttempts = 0
                    loginHintShown = false
                    errorView.isVisible = false
                    webView.loadUrl(CompetitionConfig.entryUrl())
                } else {
                    toast(getString(R.string.competition_login_heal_failed))
                }
            }
        }
        return true
    }

    /**
     * 四个标签是同页面的 __doPostBack，没有独立 URL，只能等页面就绪后模拟点击。
     * 最多尝试 3 次（应对登录页/加载中），成功一次即停。
     */
    private fun applyPendingTab() {
        val label = pendingTabLabel ?: return
        if (tabApplied || tabAttempts >= 3) return
        tabAttempts++

        val js = """
            (function(){
              var t = ${JSONObject.quote(label)};
              var links = document.querySelectorAll('a');
              for (var i = 0; i < links.length; i++) {
                if ((links[i].innerText || '').trim() === t) { links[i].click(); return true; }
              }
              return false;
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result == "true") tabApplied = true
        }
    }

    private fun showError(message: String) {
        progress.isVisible = false
        errorView.isVisible = true
        errorTitle.setText(R.string.competition_error_title)
        errorMessage.text = message
    }

    private fun describeError(code: Int, desc: String): String = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP ->
            getString(R.string.competition_error_host)

        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT ->
            getString(R.string.competition_error_connect)

        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
            getString(R.string.competition_error_ssl)

        else ->
            getString(R.string.competition_error_other, code, desc)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // endregion

    // region 上传 / 外链 / 提示

    override fun openFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>
    ) {
        // 上一次没消费的回调必须先置空，否则 input 会一直处于"选择中"
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback

        try {
            filePicker.launch(params.createIntent())
        } catch (e: Exception) {
            // 部分定制 ROM 没有文档选择器，或 intent 不被支持
            pendingFileCallback = null
            callback.onReceiveValue(null)
            toast(getString(R.string.competition_no_file_picker))
        }
    }

    private fun openExternal(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        toast(getString(R.string.competition_no_app))
        false
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region 生命周期

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        // 登录态全在 Cookie，落盘一次防杀进程丢失
        CompetitionCookies.flush()
        super.onPause()
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    // endregion
}

/**
 * 页面内"未登录"探测器。
 *
 * 学校的 WebVPN 反代（`:8118` 子域）在票据失效时经常返回 **HTTP 200**，
 * 页面里就一行"未检测到登录"或一个密码框，URL 一个字符都不变。
 * 只靠 `url.contains("login")` 判断，这种页面永远救不回来。
 *
 * 返回 `PWD|KW#3|LEN:1234` 这样的**纯 ASCII** 结果：
 * - `PWD`     页面上有密码输入框（登录页最硬的信号）
 * - `KW#n`    命中了第 n 个关键词（序号映射见 [LOGIN_KEYWORDS]）
 * - `LEN:`    正文长度，用来挡掉"正文里恰好有'登录'两字"的正常页面
 *
 * 为什么关键词用 \uXXXX 写：`evaluateJavascript` 的回传值是 JSON，
 * 非 ASCII 字符会被转义，中文直接塞进去反而对不上。回索引号最稳。
 */
private const val LOGIN_SENSOR_JS = """
(function(){try{
  var b=document.body||{};
  var t=(b.innerText||'').replace(/\s+/g,'');
  var pi=document.querySelector('input[type=password]');
  var pwd=!!(pi&&pi.offsetWidth>0&&pi.offsetHeight>0);
  var kw=['\u672a\u767b\u5f55','\u767b\u5f55\u8d85\u65f6','\u672a\u68c0\u6d4b\u5230\u767b\u5f55','\u8bf7\u5148\u767b\u5f55','\u8bf7\u91cd\u65b0\u767b\u5f55','\u767b\u5f55\u5df2\u8fc7\u671f','\u4f1a\u8bdd\u5df2\u5931\u6548','\u767b\u5f55\u5931\u6548','\u672a\u767b\u5f55\u6216\u767b\u5f55\u5df2\u8fc7\u671f'];
  var idx=-1;
  for(var i=0;i<kw.length;i++){if(t.indexOf(kw[i])>=0){idx=i;break;}}
  return (pwd?'PWD|':'')+(idx>=0?('KW#'+idx+'|'):'')+'LEN:'+t.length;
}catch(e){return 'ERR'}})()
"""

/**
 * 关键词表（顺序必须与 [LOGIN_SENSOR_JS] 里的 kw 数组严格一致）。
 *
 * 前 [WEAK_KEYWORD_COUNT] 个是弱提示词：正常页面里也可能出现（比如门户角落写着
 * "未登录"），所以只在正文很短时才采信；后面的都是错误页专有的强提示词。
 */
private val LOGIN_KEYWORDS = listOf("未登录", "登录超时", "未检测到登录", "请先登录", "请重新登录", "登录已过期", "会话已失效", "登录失效", "未登录或登录已过期")

private const val WEAK_KEYWORD_COUNT = 2

/**
 * 解析探测器回传值，判断页面是不是"没登录"。
 *
 * 三档判据，逐级放宽，尽量避免误判导致反复 reload：
 * 1. 强提示词（"未检测到登录""会话已失效"）—— 错误页专有，不看长度直接采信；
 * 2. 密码框 + 正文短 —— 正常门户里也可能藏一个隐藏登录框；
 * 3. 弱提示词 + 正文很短。
 */
private fun looksUnauthenticated(raw: String?): Boolean {
    val text = raw?.removeSurrounding("\"").orEmpty()
    if (text.isBlank() || text.startsWith("ERR")) return false
    val len = Regex("LEN:(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val idx = Regex("KW#(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    val hasPassword = text.contains("PWD")

    // 表与 JS 里的数组必须同长，序号越界说明两边对不上，宁可放过不要误 reload
    if (idx >= LOGIN_KEYWORDS.size) return false
    if (idx >= WEAK_KEYWORD_COUNT) return true
    if (hasPassword && len in 1..120) return true
    return idx >= 0 && len in 1..300
}
