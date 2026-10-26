package cn.aufe.campus.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** 全校网资源端点（经深信服 SSL VPN 子域代理访问） */
object Endpoints {
    // VPN 门户（新版）
    const val VPN_PORTAL = "https://vpn2.aufe.edu.cn/portal/"
    const val VPN_LOGIN_PAGE = "https://vpn2.aufe.edu.cn/portal/#!/login"

    // ---- VPN / CAS（OkHttp 双级登录用，移植自彩带小工具）----
    const val VPN_BASE = "https://vpn2.aufe.edu.cn"
    const val VPN_AUTH = "$VPN_BASE/por/login_auth.csp?apiversion=1"
    const val VPN_LOGIN = "$VPN_BASE/por/login_psw.csp?anti_replay=1&encrypt=1&type=cs"
    const val UAAP_LOGIN_PAGE =
        "http://uaap-aufe-edu-cn.vpn2.aufe.edu.cn:8118/cas/login?service=http%3A%2F%2Fjwcxk2.aufe.edu.cn%2Fj_spring_cas_security_check"

    // 智慧校园（统一身份认证 CAS 入口）
    const val SMART_CAMPUS = "http://all-aufe-edu-cn-s.vpn2.aufe.edu.cn:8118/"

    // 本科综合教务系统（URP）
    const val JW_BASE = "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
    const val JW_COURSE_LIST = "$JW_BASE/student/courseSelect/planCourse/courseList"
    const val JW_FREE_COURSE_LIST = "$JW_BASE/student/courseSelect/freeCourse/courseList"
    const val JW_SELECT_SUBMIT = "$JW_BASE/student/courseSelect/selectCourses/waitingfor"
    const val JW_PLAN = "$JW_BASE/student/integratedQuery/planCompletion/index"
    const val JW_SCHEDULE_PAGE = "$JW_BASE/student/courseSelect/thisSemesterCurriculum/index"
    const val JW_SCORE_PAGE = "$JW_BASE/student/integratedQuery/scoreQuery/allTermScores/index"

    // 爱安财（安徽财经大学第二课堂平台）
    const val DEKT_BASE = "http://api-dekt-ac-acxk-net.vpn2.aufe.edu.cn:8118"
    const val DEKT_CORE_LOGIN = "$DEKT_BASE/User/Index/CoreLogin"
    const val DEKT_SCORE_DETAIL = "$DEKT_BASE/User/Center/ScoreDetailImageView"
    const val DEKT_CERTIFICATE = "$DEKT_BASE/User/Center/CertificateImageView"
    const val DEKT_SPA = "http://dekt-ac-acxk-net.vpn2.aufe.edu.cn:8118/#/"

    // ---- 一卡通 ----
    const val YKT_BASE = "http://ykt-aufe-edu-cn-s.vpn2.aufe.edu.cn:8118"
    const val YKT_SSO_LOGIN = "$YKT_BASE/casLogin.jsp"
    const val YKT_BALANCE = "$YKT_BASE/queryUserBalances.action"
    const val YKT_COST_LIST = "$YKT_BASE/queryUserCostList.action"

    // ---- 宿舍电费（ISIM，注意：无 :8118 端口）----
    const val ISIM_BASE = "http://hqkd-aufe-edu-cn.vpn2.aufe.edu.cn"
    const val ISIM_GO = "$ISIM_BASE/go"                    // ?openid=学号&sn=sn
    const val ISIM_ABOUT = "$ISIM_BASE/about"              // 楼栋列表
    const val ISIM_FLOORS = "$ISIM_BASE/about/floors"      // /{楼栋码}
    const val ISIM_ROOMS = "$ISIM_BASE/about/rooms"        // /{楼层码}
    const val ISIM_REBIND = "$ISIM_BASE/about/rebinding"   // 绑定房间
    const val ISIM_USE_RECORD = "$ISIM_BASE/use/record"    // 用电记录 + 余额
    const val ISIM_PAY_RECORD = "$ISIM_BASE/pay/record"    // 缴费记录

    /** WebView 登录过程中需要收集 Cookie 的地址 */
    val COOKIE_URLS = listOf(
        "https://vpn2.aufe.edu.cn/",
        "https://vpn.aufe.edu.cn/",
        SMART_CAMPUS,
        "http://uaap-aufe-edu-cn.vpn2.aufe.edu.cn:8118/",
        "http://all-aufe-edu-cn-s.vpn2.aufe.edu.cn:8118/",
        "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118/",
        DEKT_BASE,
        "http://dekt-ac-acxk-net.vpn2.aufe.edu.cn:8118/",
        YKT_BASE,
        ISIM_BASE
    )
}

/**
 * 网络客户端封装。
 * 默认读超时 30s；slow 客户端读超时 150s，预留给服务端极慢（>30s）的接口（如一卡通消费明细）。
 *
 * 会话过期处理两条路径：
 * 1) getText/postFormText 读到登录页 → 抛 SessionExpiredException（UI 明确提示）
 * 2) 任意请求嗅探到登录页 → 回调 onSessionExpired（由 AuthManager 装配为静默重连）
 */
class AufeClient(val cookieStore: PersistentCookieStore) {

    /** 检测到 VPN 会话过期时回调（由 AuthManager 装配为静默重连，异步、不阻塞请求） */
    var onSessionExpired: (() -> Unit)? = null

    /**
     * 阻塞式会话修复钩子（由 AuthManager 装配）。
     * 返回 true 表示已重新登录成功，当前请求可以立刻重试一次。
     *
     * 这是"登录恒定"最关键的一环：业务请求一旦嗅探到登录页，
     * 不再直接把 SessionExpiredException 抛给界面（用户看到"会话已过期请重新登录"），
     * 而是当场静默重登 + 重试，用户完全无感。
     */
    @Volatile
    var sessionRefresher: (() -> Boolean)? = null

    /** 是否已完成一次静默重连（避免同一波请求重复触发） */
    @Volatile
    var reconnecting: Boolean = false

    private val defaultClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieStore)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val slowClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieStore)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 对外暴露的默认客户端（WebView Cookie 同步等场景使用） */
    val okHttp: OkHttpClient get() = defaultClient

    private fun httpClient(slow: Boolean): OkHttpClient = if (slow) slowClient else defaultClient

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 统一执行入口：用 peekBody 嗅探登录页（不消费 body，不影响后续读取） */
    private fun execute(request: Request, slow: Boolean): Response {
        val response = httpClient(slow).newCall(request).execute()
        runCatching {
            val peek = response.peekBody(4096).string()
            if (isVpnLoginPage(peek, response.request.url.toString())) {
                onSessionExpired?.invoke()
            }
        }
        return response
    }

    private fun isVpnLoginPage(body: String, url: String): Boolean {
        if (url.contains("/por/login_auth.csp") || url.contains("/por/login_psw.csp")) return false
        val t = body.trimStart()
        val looksHtml = t.startsWith("<!DOCTYPE") || t.startsWith("<html")
        return looksHtml && (
            body.contains("does not support JavaScript") ||
                body.contains("g_midatk") ||
                body.contains("login_auth.csp") ||
                body.contains("svpn_name")
            )
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), slow: Boolean = false): Response {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", ua)
        return execute(builder.build(), slow)
    }

    fun postForm(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        slow: Boolean = false
    ): Response {
        val form = FormBody.Builder()
        params.forEach { (k, v) -> form.add(k, v) }
        val builder = Request.Builder().url(url).post(form.build())
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", ua)
        return execute(builder.build(), slow)
    }

    /** GET 并返回文本（失败抛异常；会话失效会自动重登后重试一次） */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        slow: Boolean = false
    ): String = withSessionRetry {
        get(url, headers, slow).use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException(httpError(resp.code))
            val body = resp.body?.string() ?: ""
            checkNotLoginPage(body, url)
            body
        }
    }

    /**
     * 会话失效自愈：第一次嗅探到登录页 → 阻塞重登 → 重试一次。
     * 重登失败才把异常抛给上层（此时确实需要用户重新输入密码）。
     */
    private fun <T> withSessionRetry(block: () -> T): T = try {
        block()
    } catch (e: SessionExpiredException) {
        val refreshed = runCatching { sessionRefresher?.invoke() == true }.getOrDefault(false)
        if (!refreshed) throw e
        block()
    }

    /** 把 HTTP 状态码翻译成用户可理解的原因（避免直接把 500 抛到界面上） */
    private fun httpError(code: Int): String = when (code) {
        500, 502, 503 -> "服务端异常（HTTP $code）：可能当前不在选课阶段，或系统正在维护，请稍后重试"
        401, 403 -> "登录状态无效（HTTP $code），请重新登录"
        404 -> "接口地址已变化（HTTP 404），系统可能已改版"
        408, 504 -> "服务端响应超时（HTTP $code），请稍后重试"
        else -> "请求失败（HTTP $code）"
    }

    /** POST 表单并返回文本（失败抛异常；会话失效会自动重登后重试一次） */
    fun postFormText(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        slow: Boolean = false
    ): String = withSessionRetry {
        postForm(url, params, headers, slow).use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException(httpError(resp.code))
            val body = resp.body?.string() ?: ""
            checkNotLoginPage(body, url)
            body
        }
    }

    /**
     * 检测 VPN 会话失效：TWFID/CASTGC 过期时，VPN 网关会把所有请求重定向到门户登录页
     * （返回一段约 7KB 的 JS 跳转页）。此时必须明确报错，而不是让解析层静默返回空数据。
     */
    private fun checkNotLoginPage(body: String, url: String) {
        if (body.contains("does not support JavaScript") || body.contains("g_midatk")) {
            throw SessionExpiredException("登录会话已过期（VPN 网关已踢出），请重新登录")
        }
    }
}

/** VPN 会话失效异常（区别于普通网络错误，UI 可引导用户重新登录） */
class SessionExpiredException(message: String) : java.io.IOException(message)
