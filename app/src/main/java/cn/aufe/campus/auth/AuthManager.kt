package cn.aufe.campus.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import cn.aufe.campus.data.api.DektApi
import cn.aufe.campus.data.api.JwApi
import cn.aufe.campus.data.api.YktApi
import cn.aufe.campus.data.local.CredentialStore
import cn.aufe.campus.data.local.Credentials
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import cn.aufe.campus.data.net.PasswordChangeRequiredException
import cn.aufe.campus.data.net.PersistentCookieStore
import cn.aufe.campus.util.AppLog
import cn.aufe.campus.data.net.SessionExpiredException
import cn.aufe.campus.data.net.TooManyLoginsException
import cn.aufe.campus.data.net.VpnConnection
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** 登录态：驱动启动页与导航 */
enum class AuthState {
    /** 尚未开始检查（首帧，避免闪登录页） */
    Initial,

    /** 正在恢复会话（Cookie 校验 / 静默重登） */
    Restoring,

    /** 已登录 */
    Authenticated,

    /** 需要用户手动登录 */
    Unauthenticated
}

/** 会话恢复的结果：登录页据此给出准确提示，而不是一律"请重新登录" */
enum class RestoreResult {
    Success,

    /** 本机没有任何凭据 */
    NoCredentials,

    /** 服务端明确拒绝密码（密码被改过 / 输错了） */
    BadCredentials,

    /** 学校要求先改密码（不是密码错误，不能反复重试） */
    PasswordChangeRequired,

    /** 网络 / 网关临时异常，值得重试 */
    NetworkError
}

/**
 * 认证与会话管理（参照彩带小工具 AuthViewModel，并针对"登录恒定"做了增强）
 *
 * ## 通道
 * - 主通道：WebView 登录（VPN 门户 + CAS），对门户改版最稳
 * - 静默通道：OkHttp 双级登录（VpnConnection），用于启动恢复、心跳保活、掉线自愈
 *
 * ## 登录为什么能"恒定"
 * 1) **凭据只在登录成功后落盘**，且"记住密码"只影响输入框回填，不影响静默恢复；
 * 2) 启动先用 Cookie 快路径，且严格区分"真掉线"和"接口偶发异常"——
 *    非选课阶段教务接口 500 不再被误判为会话失效（原实现会因此白跑一次重登甚至回登录页）；
 * 3) 本地 Cookie 有效期由 12 小时硬过期改为 7 天并随每次登录续期，本地不再成为会话短板；
 * 4) 任何业务请求一旦嗅探到登录页，网络层会**当场静默重登并自动重试一次**，用户无感；
 * 5) 心跳每 3 分钟保活、每 2 轮（约 6 分钟）做一次真实探活，掉线立即重登；
 * 6) App 回到前台时补一次探活，隔夜再打开也能自动续上；
 * 7) 重登失败按指数退避重试，只有"密码被服务端拒绝"才回登录页，且凭据不会被误清；
 * 8) 静默重登成功会递增 [loginEpoch]，数据层可据此清缓存并重新拉取。
 */
class AuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val credentialStore = CredentialStore(appContext)

    val cookieStore = PersistentCookieStore(appContext)
    val client = AufeClient(cookieStore)
    val jwApi = JwApi(client)
    val dektApi = DektApi(client)

    private var yktInstance: YktApi? = null

    /** 一卡通 API（懒加载，WebView 登录与 OkHttp 登录通用） */
    @Synchronized
    fun yktApi(): YktApi = yktInstance ?: YktApi(client).also { yktInstance = it }

    /** 进程级协程域：心跳与静默重连使用（不随界面销毁而中断） */
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- 登录态 ----------

    private val _authState = MutableStateFlow(AuthState.Initial)
    val authState = _authState.asStateFlow()

    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()

    /** 会话彻底失效（静默重连也失败）时为 true，UI 应跳登录页 */
    private val _sessionDead = MutableStateFlow(false)
    val sessionDead = _sessionDead.asStateFlow()

    /** 最近一次恢复结果，登录页可直接展示原因 */
    private val _lastRestore = MutableStateFlow(RestoreResult.NoCredentials)
    val lastRestore = _lastRestore.asStateFlow()

    /** 静默重登成功次数：数据层据此刷新已失效的缓存 */
    private val _loginEpoch = MutableStateFlow(0)
    val loginEpoch = _loginEpoch.asStateFlow()

    /** 并发闸门：保证同一时刻只有一次"启动恢复"在跑 */
    private val restoreGate = AtomicBoolean(false)

    /** 重登互斥：多路请求同时掉线时只真正登录一次 */
    private val loginMutex = Mutex()

    private var heartbeatJob: Job? = null

    /** 探活期间屏蔽异步回调，避免自己触发自己 */
    @Volatile
    private var suppressExpiredCallback = false

    /** 服务端已明确拒绝密码：不再自动重试，避免把账号撞进限流 */
    @Volatile
    private var credsInvalid = false

    @Volatile
    private var lastRefreshAt = 0L

    @Volatile
    private var lastValidateAt = 0L

    /** 失败日志去重用：上一次失败的原因签名、时间、被折叠掉的重复次数（v1.11.2） */
    @Volatile
    private var lastFailSignature = ""

    @Volatile
    private var lastFailAt = 0L

    @Volatile
    private var lastFailRepeat = 0

    /**
     * 上一次重登失败的真实原因（v1.11.5）。
     *
     * 以前不管哪一步炸的，对外一律是 `RestoreResult.NetworkError` —— 日志里
     * 只写得出"重登失败 last=NetworkError"，看不出到底是网关没响应、
     * CAS 没解析出 lt，还是密码被拒。这里把异常原文留住，写进诊断日志。
     */
    @Volatile
    private var lastLoginError: String = ""

    init {
        wireSessionExpired()
        // 老版本把 Cookie 写成 12 小时过期，这里启动即续期，避免升级后仍然掉线
        runCatching { cookieStore.refreshExpiry() }
    }

    // ---------- 凭据 ----------

    /**
     * 保存凭据 —— **只在登录成功后调用**。
     * 原实现在点击"登录"时先写盘，一旦密码输错就把好凭据覆盖成错密码，
     * 之后每次启动的静默恢复都失败，表现就是"每次都要重新登录"。
     */
    fun saveCredentials(vpnUser: String, vpnPass: String, jwUser: String, jwPass: String, remember: Boolean) {
        val user = vpnUser.trim()
        val creds = Credentials(
            vpnUser = user,
            vpnPass = vpnPass,
            jwUser = jwUser.ifBlank { user }.trim(),
            jwPass = jwPass.ifBlank { vpnPass }
        )
        if (creds.vpnUser.isBlank() || creds.vpnPass.isBlank()) return
        credentialStore.save(creds)
        if (remember) credentialStore.saveRemembered(creds) else credentialStore.clearRemembered()
        credsInvalid = false
        // v1.8.1：登录态相关的写盘一律用 commit() 同步落盘。
        // 用 apply() 时若进程紧接着被杀（v1.8.0 的崩溃循环就会），
        // 标记可能还没刷到磁盘，下次启动会被当成"用户主动退出过"。
        prefs.edit()
            .putBoolean(KEY_LOGGED_OUT, false)
            .putLong(KEY_LAST_LOGIN_AT, System.currentTimeMillis())
            .commit()
    }

    /** 登录彻底成功（WebView 通道）：凭据 + token + 会话 Cookie 一起提交 */
    fun commitLogin(
        vpnUser: String,
        vpnPass: String,
        jwUser: String,
        jwPass: String,
        remember: Boolean,
        dektToken: String
    ) {
        saveCredentials(vpnUser, vpnPass, jwUser, jwPass, remember)
        onLoginSuccess(dektToken)
        startHeartbeat()
    }

    /** 会话恢复专用：读取凭据（与是否勾选记住密码无关） */
    private fun sessionCredentials(): Credentials? =
        credentialStore.load() ?: credentialStore.loadRemembered()

    /** 仅"记住密码"时才回填密码，避免用户预期混乱 */
    fun savedVpnUser(): String = credentialStore.loadRemembered()?.vpnUser ?: ""
    fun savedVpnPass(): String = credentialStore.loadRemembered()?.vpnPass ?: ""
    fun savedJwUser(): String = credentialStore.loadRemembered()?.jwUser ?: ""
    fun savedJwPass(): String = credentialStore.loadRemembered()?.jwPass ?: ""
    fun rememberPass(): Boolean = credentialStore.loadRemembered() != null

    /** 记住的账号（用于登录页预填学号，即便没勾记住密码也显示） */
    fun lastUsedUser(): String =
        credentialStore.loadRemembered()?.vpnUser ?: credentialStore.load()?.vpnUser.orEmpty()

    /**
     * 当前登录使用的学号（教务账号优先，回退 VPN 账号）。
     * 会话凭据始终保存，故不依赖"记住密码"勾选——电费查询等需要 openid 的功能依赖此值。
     */
    fun loginUser(): String {
        val c = credentialStore.load() ?: credentialStore.loadRemembered()
        return c?.jwUser?.takeIf { it.isNotBlank() } ?: c?.vpnUser.orEmpty()
    }

    /** 是否有可用于静默恢复的凭据（含"记住密码"回填的那份） */
    fun hasCredentials(): Boolean = sessionCredentials() != null

    /** 用户是否勾选过"记住密码"（登录页默认值） */
    fun hasRemembered(): Boolean = credentialStore.loadRemembered() != null

    /** 用户是否主动退出过（主动退出后不自动登录，但支持一键快速登录） */
    fun hasLoggedOut(): Boolean = prefs.getBoolean(KEY_LOGGED_OUT, false)

    // ---------- 会话 ----------

    /** 登录完成后调用：导出 WebView Cookie 并保存 token */
    fun onLoginSuccess(dektToken: String) {
        // v1.8.1：同步落盘，理由同上——token 与"未主动退出"标记丢了就等于掉登录
        prefs.edit()
            .apply {
                if (dektToken.isNotBlank()) putString(KEY_DEKT_TOKEN, dektToken)
                putBoolean(KEY_LOGGED_OUT, false)
            }
            .commit()
        credsInvalid = false
        cookieStore.refreshExpiry()
        cookieStore.persist()
        // 换了新的会话 Cookie，一卡通那边的 SSO 必须重新换取一遍，
        // 否则会一直沿用上一个（可能已失效）的 SSO 会话 → 余额永远刷不出来
        runCatching { yktInstance?.invalidateSession() }
        _lastRestore.value = RestoreResult.Success
        _sessionDead.value = false
        _authState.value = AuthState.Authenticated
        lastValidateAt = SystemClock.elapsedRealtime()
        wireSessionExpired()
    }

    fun dektToken(): String = prefs.getString(KEY_DEKT_TOKEN, "") ?: ""

    /** 主动退出：清会话与凭据，但保留"记住密码"那份用于登录页回填 */
    fun clearSession() {
        stopHeartbeat()
        cookieStore.clear()
        credentialStore.clear()
        prefs.edit()
            .remove(KEY_DEKT_TOKEN)
            .putBoolean(KEY_LOGGED_OUT, true)
            .commit()
        runCatching {
            // 注意：这里不能 removeAllCookies，只过期学校域名下的 Cookie，
            // 避免波及其他站点/其他账号体系残留的登录态。
            clearSchoolCookies()
        }
        credsInvalid = false
        _sessionDead.value = false
        _authState.value = AuthState.Unauthenticated
    }

    /** 彻底登出：连同"记住密码"一并抹除（换人使用 / 清除数据场景） */
    fun clearAll() {
        clearSession()
        credentialStore.clearRemembered()
    }

    /**
     * 将会话 Cookie 导入 WebView 的 CookieManager。
     *
     * 除了原有的"无感刷新 token"，v1.10.0 起还承担一件事：**给内嵌网页补登录态**。
     *
     * ## 为什么必须补
     *
     * App 的会话有两份 Cookie，彼此不通：
     * - 权威副本在 [PersistentCookieStore]（OkHttp 用，7 天有效，静默续期只更新这一份）
     * - WebView 用系统的 `CookieManager`，里面多是**会话 Cookie**，杀进程即失效
     *
     * 冷启动后 `CookieManager` 是空的，此时打开任何内嵌网页（学科竞赛）都会被
     * 踢到 VPN 登录页 —— 用户明明已经登录了 App，却要再登一次，这就是
     * "同一套登录"没做到的根因。
     *
     * 所以凡是打开内嵌网页，之前都该先调一次本方法。
     * 注：WebView 实例参数原来就没用到（内部只操作 `CookieManager` 单例），
     * v1.10.0 去掉，顺带让模块侧也能直接调。
     */
    fun importCookiesToWebView(replace: Boolean = true, onDone: () -> Unit = {}) {
        // CookieManager 的接口要靠主线程的 Handler 兑现回调，统一挪到主线程执行。
        // 调用方多半在 IO 作用域里（登录成功后的后台续期、网页版打开前的准备），
        // 混着线程调容易碰到"回调永远不回来"。
        val task = { doImportCookies(replace, onDone) }
        if (Looper.myLooper() == Looper.getMainLooper()) task()
        else Handler(Looper.getMainLooper()).post(task)
    }

    private fun doImportCookies(replace: Boolean, onDone: () -> Unit) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        // 先把"站点 × 单条 Cookie"全部展开，再统一写入。
        //
        // **必须一条一次 setCookie**：Cookie 头里的 `Domain=` / `Path=` 只对最后一条生效。
        // 过去这里是把整串 `TWFID=a; Domain=…; Path=/; CASTGC=b; …` 一次性交给 setCookie，
        // 结果 CASTGC 被当成 TWFID 的一个未知属性丢掉 —— 页面只认得出 VPN 票据、
        // 认不出统一身份认证，于是报"未检测到登录"（v1.11.4 修）。
        val jobs = mutableListOf<Pair<String, String>>()
        Endpoints.COOKIE_URLS.forEach { url ->
            cookiePayloads(url).forEach { payload -> jobs.add(url to payload) }
        }
        if (jobs.isEmpty()) {
            onDone()
            return
        }

        // 两把锁：
        // - counting：原子递减，只有"最后一个"能得到 0；
        // - fired：万一某个 ROM 的 setCookie 回调丢失/重复，保证 onDone 只兑现一次。
        val counting = AtomicInteger(jobs.size)
        val fired = AtomicBoolean(false)
        val finish = {
            if (fired.compareAndSet(false, true)) {
                // setCookie 是异步写，落盘要放在最后一条的回调之后
                runCatching { cm.flush() }
                // v1.11.5：写回读一次，把"到底有没有 TWFID"钉在日志里。
                // 以后再看"未检测到登录"，一眼就能分清是票据没进去，还是服务端不认。
                val twf = runCatching {
                    cm.getCookie(Endpoints.SMART_CAMPUS_BASE).orEmpty().contains("TWFID=")
                }.getOrDefault(false)
                AppLog.i("Web", "Cookie 已写入 WebView ${jobs.size} 条，门户 TWFID=${if (twf) "有" else "无"}")
                onDone()
            }
        }

        val write = {
            jobs.forEach { (url, payload) ->
                runCatching { cm.setCookie(url, payload) { if (counting.decrementAndGet() == 0) finish() } }
                    .onFailure { if (counting.decrementAndGet() == 0) finish() }
            }
        }

        if (!replace) {
            write()
            return
        }

        // 先清后灌（v1.11.3）。
        //
        // 「网页版单独失效、只能退出 App 重登才好」的根因就在这里：
        // `setCookie` 遇到 domain/path 作用域不完全一致的**同名** Cookie 时是追加，
        // 不是覆盖。于是旧的死票据（TWFID）一直躺在 CookieManager 里，
        // 每次打开网页都被网关先读到它 → 又跳登录页。
        // 而"退出重登"那条路径会走 clearSchoolCookies()，顺手清掉了死票据，
        // 所以只有它能修好。
        //
        // v1.11.4 再把"清完再写"做成真串行：等 removeAllCookies 的回调到了才写。
        // 之前是发完清空请求立刻写，两者挤在同一条队列上时，先写进去的会被清掉。
        val started = AtomicBoolean(false)
        val start = { if (started.compareAndSet(false, true)) write() }
        runCatching { cm.removeAllCookies { start() } }.onFailure { start() }
        // 兜底：个别 ROM 的 removeAllCookies 回调不回来，别把用户卡在空白页
        Handler(Looper.getMainLooper()).postDelayed({ start() }, 400)
    }

    /**
     * 给 WebView 用的"重新登录 + 重灌 Cookie"（v1.11.3）。
     *
     * 内嵌网页落在登录页时由承载页回调进来。这里**强制**重登一次，不看：
     * - `authState == Authenticated`：App 侧还活着，不代表 WebView 那张票据活着；
     * - `MIN_REFRESH_GAP_MS`：刚刚才登过也不影响，网页版要的是新票据；
     * - `credsInvalid`：网页版的失效不能反证密码错，重置标记后再试一次。
     */
    fun reLoginForWeb(url: String = "", onDone: (Boolean) -> Unit = {}) {
        authScope.launch {
            val ok = runCatching { reLoginForWebBlocking() }.getOrDefault(false)
            if (ok) {
                // 新会话落盘后再灌，确保 WebView 拿到的是这一轮的票据
                importCookiesToWebView(replace = true, onDone = {
                    if (url.isNotBlank()) injectFor(url)
                    onDone(true)
                })
            } else {
                onDone(false)
            }
        }
    }

    /**
     * 打开内嵌网页前的统一准备：探活 → 必要时强制重登 → 整仓灌 Cookie → 回调。
     *
     * 三个坑一次填掉：
     * 1. `CookieManager` 和 OkHttp 是两套仓库，冷启动后 WebView 那边是空的；
     * 2. 票据过期时页面会被网关踢回登录页，而 **SPA 的门户 URL 根本不变**，
     *    只能靠打开前用同一份 Cookie 先打一次才知道；
     * 3. 旧的死票据会和新票据并存（灌之前整仓清，见 importCookiesToWebView）。
     */
    fun ensureWebSession(url: String, onReady: (WebProbe) -> Unit = {}) {
        authScope.launch {
            var probe = probeWebSession(url)
            AppLog.i("Web", "网页版探活 ${probe.reason} len=${probe.bodyLength} final=${probe.finalUrl}")
            if (!probe.ok && hasCredentials()) {
                val ok = runCatching { reLoginForWebBlocking() }.getOrDefault(false)
                AppLog.i("Web", "网页版会话不可用，强制重登：$ok")
                if (ok) probe = probeWebSession(url)
            }
            // v1.11.5：只要本地有会话，就**整仓清空再灌**。
            //
            // 上一次改成"探活通过就不清"，结果页面从"显示未检测到登录"变成**整页空白**：
            // CookieManager 里上一轮的脏 session（旧 SINFORSID2、旧 CSRF token）
            // 和新灌进去的票据并存，门户 SPA 拿到的 XHR 响应前后不一致，渲染直接中断。
            // 现在口径统一：每次打开都只保留一份"探活已验证有效"的 Cookie 集合，
            // 行为可复现，出问题也好定位（日志里有写入条数和 TWFID 有无）。
            importCookiesToWebView(replace = cookieStore.hasSession(), onDone = {
                injectFor(url)
                AppLog.i("Web", "Cookie 已灌入 WebView（${probe.reason}）")
                onReady(probe)
            })
        }
    }

    /** 网页版会话探活结果 */
    data class WebProbe(
        /** 目标页是否已经是登录后的状态 */
        val ok: Boolean,
        /** 判定理由，直接写进诊断日志 */
        val reason: String,
        val finalUrl: String = "",
        val bodyLength: Int = 0
    )

    /**
     * 用 OkHttp 探一下某个网页版地址当前是"已登录"还是"被踢回登录页"。
     *
     * 之所以要提前探：WebView 侧只能看 URL 或页面文本，而智慧校园那种门户是 SPA，
     * 未登录时 **URL 一个字符都不会变**，只在页面里写一句"未检测到登录"。
     * 等它渲染完再救，用户已经看到错误提示了 —— 所以在打开之前先用同一份
     * Cookie 打一次，提前把会话修好。
     */
    private fun probeWebSession(url: String): WebProbe = runCatching {
        // **先剥掉 `#` 之后的部分**：fragment 根本不会发到服务端，
        // 但门户的路由信息全在 fragment 里（`#/main/portal/portal-1022-21`），
        // 拿它参与判定必然误判（v1.11.5）。
        val bare = url.substringBefore('#')
        client.get(bare).use { resp ->
            val finalUrl = resp.request.url.toString()
            val body = runCatching { resp.peekBody(8_000).string() }.getOrDefault("")
            val (ok, reason) = judgeWebSession(finalUrl, body, resp.code)
            WebProbe(ok, reason, finalUrl, body.length)
        }
    }.getOrElse { WebProbe(false, "探活异常：${it.message ?: it::class.simpleName}", "", 0) }

    /**
     * 判定一次探活响应到底是"已登录"还是"被踢"。
     *
     * 只认三条硬证据，**不再看正文长度，也不再匹配中文提示语**：
     *
     * 1. 最终地址落回了网关登录入口 —— 深信服对"没有有效票据"的请求只会做一件事：
     *    `302 → https://vpn2.aufe.edu.cn/?redirect_uri=…`。所以 host 变回主域、
     *    path 是根，是唯一百分之百确定的"未登录"信号（v1.11.5 实测确认）。
     * 2. 最终地址落在真正的登录端点上（login_psw / cas/login 之类）。
     * 3. 正文里出现网关登录页的 JS 指纹 `g_midatk`（业务页面不会有这行）。
     *
     * 之所以要这么严：门户是 SPA，**登录成功与否都返回同一个 200 shell**，
     * v1.11.4 用"正文 < 6000 字 + 含登录字样"判断，把一个 5566 字节的
     * 正常门户壳判成了登录页。
     */
    private fun judgeWebSession(finalUrl: String, body: String, code: Int): Pair<Boolean, String> {
        val path = finalUrl.substringBefore('#')
        val u = path.toHttpUrlOrNull()
        if (u != null &&
            u.host.equals(WEB_VPN_DOMAIN, ignoreCase = true) &&
            (u.encodedPath.isEmpty() || u.encodedPath == "/")
        ) return false to "被网关重定向到登录入口"
        if (LOGIN_URL_MARKS.any { path.contains(it, ignoreCase = true) }) {
            return false to "落在登录端点"
        }
        if (body.contains("g_midatk")) return false to "网关登录页"
        if (code !in 200..299) return false to "HTTP $code"
        return true to "已登录（HTTP $code）"
    }

    private suspend fun reLoginForWebBlocking(): Boolean = loginMutex.withLock {
        val creds = sessionCredentials() ?: return@withLock false
        credsInvalid = false
        AppLog.i("Auth", "网页版会话失效，强制重新登录一次")
        val result = loginAllWithRetry(creds)
        _lastRestore.value = result
        if (result == RestoreResult.Success) {
            _sessionDead.value = false
            _authState.value = AuthState.Authenticated
            startHeartbeat()
            true
        } else {
            false
        }
    }

    /**
     * 只让学校域名下的 WebView Cookie 过期。
     *
     * Android 的 CookieManager 没有"按域名删除"的 API，通用做法是给该 URL
     * 写一个 Max-Age=0 的同名 Cookie。这里对已知入口 URL 的多个常见路径都做一遍，
     * 保证退出登录后教务/VPN 的残留会话失效，同时不碰其它站点（如校园网门户）。
     */
    private fun clearSchoolCookies() {
        val cm = CookieManager.getInstance()
        val paths = listOf("/", "/portal", "/portal/", "/portal3", "/portal3/")
        Endpoints.COOKIE_URLS.forEach { base ->
            val root = base.trimEnd('/')
            // 显式命名标签：外层也有一个 forEach，`return@forEach` 会指向哪个
            // 有歧义（编译器会给警告），这里是想"跳过这个路径"，必须指内层
            paths.forEach path@{ path ->
                val url = root + path
                val header = runCatching { cm.getCookie(url) }.getOrNull() ?: return@path
                header.split(";").forEach { kv ->
                    val name = kv.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        runCatching { cm.setCookie(url, "$name=; Max-Age=0; Path=$path") }
                    }
                }
            }
        }
        cm.flush()
    }

    /**
     * 拼给 `CookieManager.setCookie` 的 Cookie 头。
     *
     * v1.11.4 起对 vpn2 域的**全局凭据**显式带上 `Domain`。
     *
     * 不带 Domain 时 CookieManager 会把票据绑成当前 host 的 hostOnly Cookie，
     * 于是"从 A 子域灌进去的 TWFID，B 子域的页面读不到"。教务、一卡通这些
     * OkHttp 直连同名 host 的接口感觉不到问题，但网页版里页面会**跨子域**发请求
     * （统一认证、门户主站、SSO 回跳），任何一个子域缺票据，页面就显示
     * "未检测到登录"。
     *
     * 各子系统自己的 JSESSIONID **不能**带 Domain —— 同名会互相覆盖，
     * 那正是 v1.8 选课 HTTP 500 的老毛病。所以只提升下面这几个全局凭据。
     */
    private fun cookiePayloads(url: String): List<String> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val shareDomain = isVpn2Host(httpUrl.host)
        return cookieStore.loadForRequest(httpUrl).map { c ->
            if (shareDomain && c.name.uppercase() in WEB_GLOBAL_CREDENTIALS) {
                "${c.name}=${c.value}; Domain=.$WEB_VPN_DOMAIN; Path=/"
            } else {
                "${c.name}=${c.value}; Path=/"
            }
        }
    }

    /** 该 host 是否属于 vpn2 反代域（这些子域之间可以共享 Cookie） */
    private fun isVpn2Host(host: String): Boolean =
        host.equals(WEB_VPN_DOMAIN, ignoreCase = true) ||
            host.endsWith(".$WEB_VPN_DOMAIN", ignoreCase = true)

    /**
     * 把当前会话直接补灌到 `url` 这一个站点（不改动其它站点）。
     *
     * 整仓灌用的是 `Endpoints.COOKIE_URLS` 那批固定地址；目标页万一换了端口或
     * 子域（例如智慧校园的 all-aufe-edu-cn-s），那一次就会漏掉。打开网页前
     * 对**目标 URL 本身**再补一遍，成本只有一次内存写入。
     */
    fun injectFor(url: String) {
        val payloads = cookiePayloads(url)
        if (payloads.isEmpty()) return
        runCatching {
            val cm = CookieManager.getInstance()
            // 同 importCookiesToWebView：一条一次，属性只作用于本条
            payloads.forEach { p -> cm.setCookie(url, p) { } }
            cm.flush()
        }
    }

    // ---------- 会话恢复 ----------

    /**
     * 静默恢复会话（启动时调用，不阻塞调用方）。
     */
    fun restoreSession(onDone: (Boolean) -> Unit = {}) {
        authScope.launch {
            val ok = restoreSessionBlocking()
            onDone(ok)
        }
    }

    /**
     * 恢复会话（阻塞）。
     *
     * @param force true 表示用户主动点了"快速登录"——即使之前主动退出过也要恢复。
     * 幂等：并发调用只真正执行一次（避免启动时多个入口同时打网络请求）。
     */
    suspend fun restoreSessionBlocking(force: Boolean = false): Boolean {
        if (_authState.value == AuthState.Authenticated) return true
        if (!force && hasLoggedOut() && _authState.value != AuthState.Restoring) {
            // 用户主动退出过：不自动登录，但仍可在登录页一键恢复
            _lastRestore.value = RestoreResult.NoCredentials
            _authState.value = AuthState.Unauthenticated
            return false
        }
        if (!restoreGate.compareAndSet(false, true)) {
            // 已有恢复在进行，等待其结果
            val deadline = System.currentTimeMillis() + WAIT_ONGOING_MS
            while (restoreGate.get() && System.currentTimeMillis() < deadline) delay(120)
            return _authState.value == AuthState.Authenticated
        }
        return try {
            _restoring.value = true
            _authState.value = AuthState.Restoring
            val result = restoreWithRetry()
            _lastRestore.value = result
            AppLog.i("Auth", "会话恢复结果：$result")
            val ok = result == RestoreResult.Success
            if (ok) {
                _sessionDead.value = false
                _authState.value = AuthState.Authenticated
                startHeartbeat()
            } else {
                _authState.value = AuthState.Unauthenticated
            }
            ok
        } finally {
            _restoring.value = false
            restoreGate.set(false)
        }
    }

    /**
     * 带退避的恢复。
     *
     * 冷启动时网络栈常常还没就绪（刚开机 / 息屏唤醒后点图标），首个请求会以
     * "网络不可达"快速失败——这正是"明明存了密码却还要手动登录"的典型原因。
     */
    private suspend fun restoreWithRetry(): RestoreResult {
        var last = RestoreResult.NetworkError
        for (attempt in 1..MAX_RESTORE_ATTEMPTS) {
            last = restoreInternal()
            if (last != RestoreResult.NetworkError) return last
            if (attempt < MAX_RESTORE_ATTEMPTS) delay(backoff(attempt))
        }
        return last
    }

    private fun backoff(attempt: Int): Long {
        val base = LOGIN_RETRY_BASE_MS * attempt
        return base + Random.nextLong(0, base / 2 + 1)
    }

    private suspend fun restoreInternal(): RestoreResult {
        // ---- 第 0 级：乐观快路径（v1.8.2，登录提速）----
        //
        // 本地 Cookie 还新鲜 → 立刻判定已登录，**不等**探活结果。
        // 冷启动原来要先发一次完整 HTTP 请求（走 VPN，1~3 秒）才进主页，
        // 这段时间用户盯着启动页，体感就是"登录很慢"。
        //
        // 现在把探活丢到后台：成功什么都不用做；失败会走 onSessionExpired
        // → 静默重登，用户最多看到某一次请求稍慢，不会卡在启动页。
        if (cookieStore.hasSession() && cookieStore.isFresh()) {
            wireSessionExpired()
            runCatching { cookieStore.refreshExpiry() }
            // 会话还能用 → 之前那次"密码被拒"一定是误判，把标记清掉，
            // 否则它会把之后每一次静默重登都挡在门外（v1.11.2）
            credsInvalid = false
            authScope.launch {
                runCatching {
                    if (validateSession() == SessionCheck.Expired) silentReconnect()
                }
            }
            AppLog.i("Auth", "Cookie 快路径命中，直接放行（探活转后台）")
            return RestoreResult.Success
        }

        // ---- 第 1 级：Cookie 已过期/临近过期，走真实探活 ----
        // 只有走到这里才真的需要网络，所以「等网络就绪」放在快路径之后，
        // 避免为了一个纯本地判断白等
        awaitNetworkReady()
        if (cookieStore.hasSession()) {
            when (validateSession()) {
                SessionCheck.Valid, SessionCheck.Unknown -> {
                    // Unknown 表示接口异常（如非选课阶段 HTTP 500），不是掉线：
                    // 这种情况继续用现有会话，绝不能因为一个 500 就把用户踢回登录页
                    wireSessionExpired()
                    runCatching { cookieStore.refreshExpiry() }
                    credsInvalid = false
                    return RestoreResult.Success
                }
                SessionCheck.Expired -> Unit
            }
        }
        // ---- 第 2 级：静默重登 ----
        val creds = sessionCredentials() ?: return RestoreResult.NoCredentials
        return loginAllWithRetry(creds)
    }

    /** 会话有效性判定 */
    private enum class SessionCheck { Valid, Expired, Unknown }

    /**
     * 真实探活：访问一页教务页面，被网关重定向到登录页才算掉线。
     * 其他异常（超时 / 500 / 解析失败）一律 Unknown，避免误判。
     */
    private suspend fun validateSession(): SessionCheck = withContext(Dispatchers.IO) {
        suppressExpiredCallback = true
        try {
            val resp = client.get(Endpoints.JW_SCHEDULE_PAGE)
            val check = resp.use {
                val body = it.body?.string().orEmpty()
                val url = it.request.url.toString()
                when {
                    url.contains("cas/login", true) -> SessionCheck.Expired
                    isLoginPage(body) -> SessionCheck.Expired
                    else -> SessionCheck.Valid
                }
            }
            lastValidateAt = SystemClock.elapsedRealtime()
            check
        } catch (e: SessionExpiredException) {
            SessionCheck.Expired
        } catch (e: Exception) {
            SessionCheck.Unknown
        } finally {
            suppressExpiredCallback = false
        }
    }

    private fun isLoginPage(body: String): Boolean =
        body.contains("does not support JavaScript") ||
            body.contains("g_midatk") ||
            body.contains("login_auth.csp") ||
            body.contains("svpn_name")

    // ---------- OkHttp 登录 / 静默重连 ----------

    /** 装配"嗅探到登录页 → 静默重连 / 当场修复"的两个回调 */
    fun wireSessionExpired() {
        client.onSessionExpired = { if (!suppressExpiredCallback) silentReconnect() }
        client.sessionRefresher = { refreshSessionBlocking() }
    }

    /** 用户主动触发的 OkHttp 登录（凭据缺省取已保存值） */
    suspend fun loginViaOkHttp(
        vpnUser: String = savedVpnUser(),
        vpnPass: String = savedVpnPass(),
        jwUser: String = savedJwUser(),
        jwPass: String = savedJwPass()
    ): Result<Unit> {
        val user = vpnUser.ifBlank { lastUsedUser() }
        val saved = sessionCredentials()
        val pass = vpnPass.ifBlank { saved?.vpnPass.orEmpty() }
        val creds = Credentials(
            vpnUser = user,
            vpnPass = pass,
            jwUser = jwUser.ifBlank { user },
            jwPass = jwPass.ifBlank { pass }
        )
        if (creds.vpnUser.isBlank() || creds.vpnPass.isBlank()) {
            return Result.failure(IllegalArgumentException("缺少 VPN 账号或密码"))
        }
        val result = loginAllWithRetry(creds)
        return if (result == RestoreResult.Success) {
            _sessionDead.value = false
            _authState.value = AuthState.Authenticated
            startHeartbeat()
            Result.success(Unit)
        } else {
            Result.failure(
                when (result) {
                    RestoreResult.BadCredentials -> Exception("账号或密码错误")
                    RestoreResult.PasswordChangeRequired -> PasswordChangeRequiredException()
                    else -> Exception("登录失败，请检查网络后重试")
                }
            )
        }
    }

    /**
     * 完整登录并落盘（静默重连的唯一入口，网络层也复用它）。
     * 只有"密码被拒绝"才标记 credsInvalid 并停止自动重试。
     */
    private suspend fun loginAllWithRetry(creds: Credentials): RestoreResult =
        withContext(Dispatchers.IO) {
            var netAttempt = 0
            var rateAttempt = 0
            var credRejects = 0
            while (netAttempt < MAX_LOGIN_ATTEMPTS && rateAttempt <= MAX_RATE_RETRY) {
                val outcome = runCatching {
                    VpnConnection(creds.vpnUser, creds.vpnPass, creds.jwPass, cookieStore)
                        .loginAll()
                        .getOrThrow()
                }
                if (outcome.isSuccess) {
                    onLoginCommitted(creds)
                    return@withContext RestoreResult.Success
                }
                val error = outcome.exceptionOrNull()
                // 留一份原文给诊断日志（v1.11.5）：不然所有失败都显示成 NetworkError
                lastLoginError = error?.let {
                    "${it::class.simpleName}: ${it.message.orEmpty().take(120)}"
                }.orEmpty()
                when {
                    error is PasswordChangeRequiredException -> {
                        prefs.edit().putBoolean(KEY_PWD_CHANGE, true).apply()
                        return@withContext RestoreResult.PasswordChangeRequired
                    }
                    isCredentialError(error) -> {
                        // v1.11.2：一次拒绝不算数，隔一会儿再用干净会话试一次。
                        // 网关在并发登录/限流时会给一张"像密码错"的错误页，
                        // 一次就采信会让整个会话被判死，之后满日志的"重登失败"。
                        credRejects++
                        if (credRejects >= CRED_REJECT_CONFIRM) {
                            credsInvalid = true
                            return@withContext RestoreResult.BadCredentials
                        }
                        delay(CRED_RECHECK_DELAY_MS)
                    }
                    error is TooManyLoginsException -> {
                        rateAttempt++
                        // 被网关限流：给足冷却时间，别把账号打进黑名单
                        delay(RATE_LIMIT_BACKOFF_MS * rateAttempt)
                    }
                    else -> {
                        netAttempt++
                        if (netAttempt < MAX_LOGIN_ATTEMPTS) delay(backoff(netAttempt))
                    }
                }
            }
            RestoreResult.NetworkError
        }

    /** 一次成功的登录（静默或显式）统一在这里收尾 */
    private fun onLoginCommitted(creds: Credentials) {
        AppLog.i("Auth", "登录成功（凭据已落盘）")
        credentialStore.save(creds)
        credsInvalid = false
        cookieStore.refreshExpiry()
        cookieStore.persist()
        prefs.edit()
            .putBoolean(KEY_LOGGED_OUT, false)
            .putBoolean(KEY_PWD_CHANGE, false)
            .putLong(KEY_LAST_LOGIN_AT, System.currentTimeMillis())
            .commit()
        val now = SystemClock.elapsedRealtime()
        lastRefreshAt = now
        lastValidateAt = now
        _sessionDead.value = false
        wireSessionExpired()
        // 一卡通 SSO 会话跟着这次登录一起作废重换（见 YktApi 的注释）
        runCatching { yktInstance?.invalidateSession() }
        // 顺手在后台把一卡通 SSO 换好：用户下次点开一卡通就只剩一次余额查询，
        // 少一个往返（v1.11.3）。只在"这次进程里确实用过一卡通"时才预热。
        if (yktInstance != null) {
            authScope.launch { runCatching { yktApi().initSession(force = true) } }
        }
        // 通知数据层：之前的失败缓存可以丢了
        _loginEpoch.value += 1
    }

    /** 判断失败原因是否为"凭据错误"（密码错/账号错），这类不该重试 */
    private fun isCredentialError(e: Throwable?): Boolean {
        val msg = generateSequence(e) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return msg.contains("密码错误") || msg.contains("Invalid username or password") ||
            msg.contains("用户名或密码") || msg.contains("账号或密码")
    }

    /**
     * 阻塞式会话修复 —— 供网络层在"嗅探到登录页"时当场调用。
     * 单飞 + 冷却：同一波请求只会触发一次真正的重登。
     */
    fun refreshSessionBlocking(): Boolean = runCatching {
        runBlocking { refreshSessionLocked() }
    }.getOrDefault(false)

    private suspend fun refreshSessionLocked(): Boolean = loginMutex.withLock {
        if (credsInvalid) return@withLock false
        val now = SystemClock.elapsedRealtime()
        if (lastRefreshAt > 0 && now - lastRefreshAt < MIN_REFRESH_GAP_MS) {
            // 刚刚才登过：让调用方直接重试，避免同一波请求把登录接口打爆
            return@withLock true
        }
        val creds = sessionCredentials() ?: return@withLock false
        val result = loginAllWithRetry(creds)
        _lastRestore.value = result
        result == RestoreResult.Success
    }

    /**
     * 静默重连：会话过期时后台自动重登，失败才置 sessionDead。
     *
     * ## v1.8.1 修复「登录后没多久就自己退出」
     *
     * 原实现只要 `refreshSessionLocked()` 返回 false 就把 `authState` 打成
     * `Unauthenticated`，而 MainActivity 看到这个状态会立刻跳转登录页。
     * 问题是重登失败的原因**绝大多数是网络**（VPN 掉线、校园网切换、
     * 教务系统在维护），凭据本身完全没问题——用户却被迫重新登录一次，
     * 观感就是「刚登上几分钟就被踹出来」。
     *
     * 新规则：只有**凭据被教务明确拒绝**（密码错 / 账号错）或**本地已无凭据**
     * 才判定登录失效。其余一律保持原状态，等下一次探活自动续上。
     */
    fun silentReconnect() {
        if (_restoring.value) return
        if (!hasCredentials()) {
            _sessionDead.value = true
            _authState.value = AuthState.Unauthenticated
            return
        }
        authScope.launch {
            val ok = runCatching { refreshSessionLocked() }.getOrDefault(false)
            if (ok) {
                _sessionDead.value = false
                _authState.value = AuthState.Authenticated
                startHeartbeat()
            } else {
                onRefreshFailed()
            }
        }
    }

    /**
     * 重登失败的统一处置（v1.8.1）。
     *
     * 只在「凭据被拒」或「凭据没了」时退出登录；网络类失败原地不动，
     * 避免把用户从已登录界面踢回登录页。
     */
    private fun onRefreshFailed() {
        val rejected = credsInvalid || _lastRestore.value == RestoreResult.BadCredentials
        val lost = !hasCredentials()
        val now = SystemClock.elapsedRealtime()
        val signature = "rejected=$rejected lost=$lost last=${_lastRestore.value}"
        val detail = if (lastLoginError.isNotBlank()) " err=$lastLoginError" else ""

        // v1.11.2：同一种失败在 30 秒内只记一条。
        // 以前心跳、探活、回前台各自都会打一行，日志里能刷出几十条一模一样的
        // "重登失败 rejected=true"——看起来像登录彻底坏了，其实只是同一件事在重复。
        if (signature == lastFailSignature && now - lastFailAt < FAIL_LOG_WINDOW_MS) {
            lastFailRepeat++
        } else {
            val folded = if (lastFailRepeat > 0) "（同类失败又重复 ${lastFailRepeat} 次，已折叠）" else ""
            AppLog.w("Auth", "重登失败 $signature$detail$folded")
            lastFailSignature = signature
            lastFailAt = now
            lastFailRepeat = 0
        }

        if (rejected || lost) {
            // 幂等：已经判定登出就不再重复置状态，否则界面会跟着反复跳转
            if (_authState.value != AuthState.Unauthenticated) {
                _sessionDead.value = true
                _authState.value = AuthState.Unauthenticated
            }
        }
        // 网络/VPN/教务维护：保持 Authenticated，等下一次心跳或回前台再续
    }

    /**
     * 给诊断日志用的登录状态摘要（v1.11.2）。
     *
     * 用户反馈"日志里全是重登失败"时，光看那几行 W 级日志根本判断不出到底
     * 是密码错了、Cookie 过期了，还是网络抖动。这里把判定依据一次性摊开：
     * 本地有没有凭据、Cookie 新不新鲜、密码拒绝标记是否被置上、上次恢复的结果是什么。
     *
     * **只写状态，不写账号密码**；学号做遮挡，够定位问题又不至于泄露完整信息。
     */
    fun statusSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("状态：${when (_authState.value) {
            AuthState.Authenticated -> "已登录"
            AuthState.Unauthenticated -> "未登录"
            AuthState.Restoring -> "恢复中"
            else -> _authState.value.toString()
        }}")
        sb.appendLine("本地凭据：${if (hasCredentials()) "有" else "无"}")
        sb.appendLine("主动退出过：${if (hasLoggedOut()) "是" else "否"}")
        sb.appendLine("密码被拒标记：${if (credsInvalid) "是（自动重登已停）" else "否"}")
        sb.appendLine("上次恢复结果：${_lastRestore.value}")
        sb.appendLine(
            "会话 Cookie：${if (cookieStore.hasSession()) "有" else "无"}" +
                "（${if (cookieStore.isFresh()) "新鲜" else "临近过期/已过期"}）"
        )
        val lastLogin = prefs.getLong(KEY_LAST_LOGIN_AT, 0L)
        sb.appendLine(
            "上次登录成功：" + if (lastLogin <= 0) "无记录" else
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(lastLogin))
        )
        sb.append("重登节流：").append(
            if (lastRefreshAt <= 0) "未重登过"
            else "距上次 ${(SystemClock.elapsedRealtime() - lastRefreshAt) / 1000}s"
        )
        // v1.11.5：把上次重登失败的真实原因摊出来。
        // 没有它，"last=NetworkError" 到底是网关不通、CAS 解析失败还是密码被拒，
        // 从日志上完全分不出来。
        if (lastLoginError.isNotBlank()) {
            sb.appendLine().append("上次失败原因：").append(lastLoginError)
        }
        return sb.toString()
    }

    // ---------- 心跳保活 ----------

    /**
     * 心跳：每 3 分钟保活一次（刷新网关空闲计时），每 2 轮做一次真实探活。
     * 掉线立即静默重登，用户全程无感。
     */
    fun startHeartbeat() {
        if (!hasCredentials()) return
        heartbeatJob?.cancel()
        heartbeatJob = authScope.launch {
            var tick = 0
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS + Random.nextLong(0, HEARTBEAT_JITTER_MS))
                val creds = sessionCredentials() ?: break
                runCatching {
                    VpnConnection(creds.vpnUser, creds.vpnPass, creds.jwPass, cookieStore).warmPing()
                }
                tick++
                if (tick % VALIDATE_EVERY_TICKS == 0) {
                    if (validateSession() == SessionCheck.Expired) {
                        refreshSessionLocked()
                    }
                }
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * App 回到前台时调用：距上次探活超过 [FOREGROUND_CHECK_GAP_MS] 就补一次。
     * 覆盖"后台放了几小时 / 一整夜"的场景，回到前台就能把会话续上。
     *
     * v1.8.1：重登失败同样走 [onRefreshFailed] 统一判定，
     * 网络类失败不再把用户踢回登录页。
     */
    fun onAppForeground() {
        if (_authState.value != AuthState.Authenticated) return
        if (!hasCredentials()) return
        if (SystemClock.elapsedRealtime() - lastValidateAt < FOREGROUND_CHECK_GAP_MS) return
        authScope.launch {
            if (validateSession() == SessionCheck.Expired) {
                val ok = runCatching { refreshSessionLocked() }.getOrDefault(false)
                if (ok) {
                    _sessionDead.value = false
                    _authState.value = AuthState.Authenticated
                    startHeartbeat()
                } else {
                    onRefreshFailed()
                }
            }
        }
    }

    /** 等待网络可用（最多 [NETWORK_WAIT_TIMEOUT_MS]），无可用网络时直接返回交由上层处理 */
    private suspend fun awaitNetworkReady() {
        val deadline = System.currentTimeMillis() + NETWORK_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val ok = runCatching {
                val nw = cm?.activeNetwork ?: return@runCatching false
                val caps = cm.getNetworkCapabilities(nw) ?: return@runCatching false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.getOrDefault(false)
            if (ok) return
            delay(NETWORK_POLL_MS)
        }
    }

    companion object {
        /** 学校 WebVPN 反代的顶级域：其子域之间共享 TWFID 等全局凭据 */
        private const val WEB_VPN_DOMAIN = "vpn2.aufe.edu.cn"

        /**
         * 地址里出现这些片段，说明已经被网关送去登录页了。
         *
         * v1.11.5 收窄：原来有一条 `"/portal"`，本意是匹配网关老登录页
         * `vpn2.aufe.edu.cn/portal/#!/login`，但智慧校园的门户是 **hash 路由**，
         * 首页地址就是 `#/main/portal/portal-1022-21`，天生带 "/portal"。
         * 于是"已经进到门户"被当成"被踢回登录页"，每次打开都白重登一次，
         * 而重登用的又是同一个单点账号（网关 Is_enable_mult_client=0），
         * 成功率低、还会把正在用的会话搅乱 —— 这正是"智慧校园登录不上"的病根。
         *
         * 现在只留真正的登录端点，且匹配前会把 `#` 之后的 fragment 剥掉。
         */
        private val LOGIN_URL_MARKS = listOf(
            "login_auth", "login_psw", "/cas/login", "por/login", "/portal/#!/login"
        )

        /** 在 WebView 里必须按 Domain 写入的凭据（见 cookieHeaderFor 的注释） */
        private val WEB_GLOBAL_CREDENTIALS = setOf("TWFID", "CASTGC", "SINFORSID", "SINFORSID2")

        private const val PREFS = "aufe_auth"
        private const val KEY_DEKT_TOKEN = "dektToken"
        private const val KEY_LOGGED_OUT = "logged_out"
        private const val KEY_LAST_LOGIN_AT = "last_login_at"
        private const val KEY_PWD_CHANGE = "password_change_required"

        /** 心跳间隔（保活） */
        private const val HEARTBEAT_INTERVAL_MS = 3 * 60 * 1000L
        private const val HEARTBEAT_JITTER_MS = 20_000L

        /** 每 N 轮心跳做一次真实探活 */
        private const val VALIDATE_EVERY_TICKS = 2

        /**
         * 冷启动时等待网络就绪的上限。
         * v1.8.2：从 12 秒收紧到 4 秒——原来一旦系统报告的 activeNetwork 为空，
         * 用户就要盯着启动页干等十几秒；实际上 4 秒还没网，再等也等不出来。
         */
        private const val NETWORK_WAIT_TIMEOUT_MS = 4_000L
        private const val NETWORK_POLL_MS = 200L

        /** 恢复失败后的重试次数 */
        private const val MAX_RESTORE_ATTEMPTS = 3
        private const val MAX_LOGIN_ATTEMPTS = 3
        /** 重试退避基数。v1.8.2：1500→700，三次重试的总等待从约 4.5s 降到约 2.1s */
        private const val LOGIN_RETRY_BASE_MS = 700L

        /** 被网关限流时的冷却基数 */
        private const val MAX_RATE_RETRY = 2
        private const val RATE_LIMIT_BACKOFF_MS = 20_000L

        /** 两次真实重登之间的最小间隔（防止请求风暴） */
        private const val MIN_REFRESH_GAP_MS = 10_000L

        /**
         * 「密码被拒」要连续拒绝几次才采信（v1.11.2）。
         *
         * 网关在并发登录、限流、教务维护时会返回一张内容像
         * "用户名或密码错误"的错误页。原来一次就信，结果是：
         * 密码明明是对的，`credsInvalid` 却被粘死，之后每次心跳都记一遍
         * "重登失败 rejected=true"，日志刷屏还把人从主页踢出去。
         */
        private const val CRED_REJECT_CONFIRM = 2

        /** 两次"密码被拒"之间的间隔：给网关一点时间，别当成连击 */
        private const val CRED_RECHECK_DELAY_MS = 1_200L

        /** 同一原因的失败日志在这个窗口内只记一条（v1.11.2） */
        private const val FAIL_LOG_WINDOW_MS = 30_000L

        /** 前台补探活的最小间隔 */
        private const val FOREGROUND_CHECK_GAP_MS = 60_000L

        /** 等待其它恢复流程的上限 */
        private const val WAIT_ONGOING_MS = 25_000L
    }
}
