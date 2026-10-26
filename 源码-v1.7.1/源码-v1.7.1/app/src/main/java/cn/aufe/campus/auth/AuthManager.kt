package cn.aufe.campus.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import cn.aufe.campus.data.api.DektApi
import cn.aufe.campus.data.api.JwApi
import cn.aufe.campus.data.api.YktApi
import cn.aufe.campus.data.local.CredentialStore
import cn.aufe.campus.data.local.Credentials
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import cn.aufe.campus.data.net.PasswordChangeRequiredException
import cn.aufe.campus.data.net.PersistentCookieStore
import cn.aufe.campus.data.net.SessionExpiredException
import cn.aufe.campus.data.net.TooManyLoginsException
import cn.aufe.campus.data.net.VpnConnection
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
        prefs.edit()
            .putBoolean(KEY_LOGGED_OUT, false)
            .putLong(KEY_LAST_LOGIN_AT, System.currentTimeMillis())
            .apply()
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
        if (dektToken.isNotBlank()) prefs.edit().putString(KEY_DEKT_TOKEN, dektToken).apply()
        prefs.edit().putBoolean(KEY_LOGGED_OUT, false).apply()
        credsInvalid = false
        cookieStore.refreshExpiry()
        cookieStore.persist()
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
            .apply()
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

    /** 将会话 Cookie 导入 WebView（用于无感刷新 token） */
    fun importCookiesToWebView(webView: WebView, onDone: () -> Unit) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        var pending = Endpoints.COOKIE_URLS.size
        if (pending == 0) {
            onDone()
            return
        }
        Endpoints.COOKIE_URLS.forEach { url ->
            val header = cookieHeaderFor(url)
            if (header.isNullOrBlank()) {
                pending--
                if (pending <= 0) onDone()
            } else {
                cm.setCookie(url, header) {
                    pending--
                    if (pending <= 0) onDone()
                }
            }
        }
        cm.flush()
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
            paths.forEach { path ->
                val url = root + path
                val header = runCatching { cm.getCookie(url) }.getOrNull() ?: return@forEach
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

    private fun cookieHeaderFor(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val cookies = cookieStore.loadForRequest(httpUrl)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
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
        awaitNetworkReady()
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
        // ---- 第 1 级：Cookie 快路径 ----
        if (cookieStore.hasSession()) {
            when (validateSession()) {
                SessionCheck.Valid, SessionCheck.Unknown -> {
                    // Unknown 表示接口异常（如非选课阶段 HTTP 500），不是掉线：
                    // 这种情况继续用现有会话，绝不能因为一个 500 就把用户踢回登录页
                    wireSessionExpired()
                    runCatching { cookieStore.refreshExpiry() }
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
                when {
                    error is PasswordChangeRequiredException -> {
                        prefs.edit().putBoolean(KEY_PWD_CHANGE, true).apply()
                        return@withContext RestoreResult.PasswordChangeRequired
                    }
                    isCredentialError(error) -> {
                        credsInvalid = true
                        return@withContext RestoreResult.BadCredentials
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
        credentialStore.save(creds)
        credsInvalid = false
        cookieStore.refreshExpiry()
        cookieStore.persist()
        prefs.edit()
            .putBoolean(KEY_LOGGED_OUT, false)
            .putBoolean(KEY_PWD_CHANGE, false)
            .putLong(KEY_LAST_LOGIN_AT, System.currentTimeMillis())
            .apply()
        val now = SystemClock.elapsedRealtime()
        lastRefreshAt = now
        lastValidateAt = now
        _sessionDead.value = false
        wireSessionExpired()
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

    /** 静默重连：会话过期时后台自动重登，失败才置 sessionDead */
    fun silentReconnect() {
        if (_restoring.value) return
        if (!hasCredentials()) {
            _sessionDead.value = true
            _authState.value = AuthState.Unauthenticated
            return
        }
        authScope.launch {
            val ok = runCatching { refreshSessionLocked() }.getOrDefault(false)
            _sessionDead.value = !ok
            if (ok) {
                _authState.value = AuthState.Authenticated
                startHeartbeat()
            } else {
                // 凭据仍在：登录页会自动再尝试一次，用户也可以点"快速登录"
                _authState.value = AuthState.Unauthenticated
            }
        }
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
     */
    fun onAppForeground() {
        if (_authState.value != AuthState.Authenticated) return
        if (!hasCredentials()) return
        if (SystemClock.elapsedRealtime() - lastValidateAt < FOREGROUND_CHECK_GAP_MS) return
        authScope.launch {
            if (validateSession() == SessionCheck.Expired) {
                val ok = runCatching { refreshSessionLocked() }.getOrDefault(false)
                _sessionDead.value = !ok
                if (ok) {
                    _authState.value = AuthState.Authenticated
                    startHeartbeat()
                } else {
                    // 凭据仍在：登录页会自动再试一次，也可手动点"快速登录"
                    _authState.value = AuthState.Unauthenticated
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

        /** 冷启动时等待网络就绪的上限 */
        private const val NETWORK_WAIT_TIMEOUT_MS = 12_000L
        private const val NETWORK_POLL_MS = 500L

        /** 恢复失败后的重试次数 */
        private const val MAX_RESTORE_ATTEMPTS = 3
        private const val MAX_LOGIN_ATTEMPTS = 3
        private const val LOGIN_RETRY_BASE_MS = 1_500L

        /** 被网关限流时的冷却基数 */
        private const val MAX_RATE_RETRY = 2
        private const val RATE_LIMIT_BACKOFF_MS = 20_000L

        /** 两次真实重登之间的最小间隔（防止请求风暴） */
        private const val MIN_REFRESH_GAP_MS = 10_000L

        /** 前台补探活的最小间隔 */
        private const val FOREGROUND_CHECK_GAP_MS = 60_000L

        /** 等待其它恢复流程的上限 */
        private const val WAIT_ONGOING_MS = 25_000L
    }
}
