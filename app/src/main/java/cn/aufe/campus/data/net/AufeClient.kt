package cn.aufe.campus.data.net

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.FormBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** 上传用文件（附件申报时用） */
data class UploadFile(
    /** 服务端控件的 name（FileUpload 常有随机前缀，必须用页面解析出来的） */
    val field: String,
    val fileName: String,
    val mime: String,
    val bytes: ByteArray
)

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

    // 智慧校园（统一身份认证 CAS 入口 → 校内一站式门户）
    //
    // 这个域名是学校 WebVPN 的反代，**不是"先连 VPN 客户端才能开"**：
    // 它本身就能从公网解析，真正决定能不能进去的是 TWFID 票据。
    // 票据由 App 的 OkHttp 登录拿到（por/login_psw.csp），再注入 WebView 即可，
    // 所以用户不需要在手机上开任何 VPN 工具。
    const val SMART_CAMPUS_BASE = "http://all-aufe-edu-cn-s.vpn2.aufe.edu.cn:8118"

    /**
     * 门户 SPA 的入口（不带 hash 路由）。
     *
     * 首页那段带 `_key` 的地址是门户模板的定位参数，换会话后理论上可能失效。
     * 万一首屏渲染不出内容（白屏），回退到这里让前端自己跳默认门户。
     */
    const val SMART_CAMPUS_FALLBACK = "$SMART_CAMPUS_BASE/wui/index.html"

    /**
     * 门户首页（前端路由是 hash，改 hash 只是切栏目，不会重新走登录）。
     *
     * v1.11.5 去掉 `_key=u5xviv`。它是"门户模板"的定位参数，是从浏览器地址栏
     * 原样复制下来的，和当时的会话、模板版本绑定；换一轮会话后它一失效，
     * wui 这个 Vue 应用的路由就匹配不到模板 —— 表现正是**标题栏正常、内容区全白**，
     * 既不报错也不跳转。让它自己落到默认门户比钉死一个 key 稳。
     */
    const val SMART_CAMPUS =
        "$SMART_CAMPUS_BASE/wui/index.html#/main/portal/portal-1022-21" +
            "?menuIds=0,1022&menuPathIds=0,1022"

    // 本科综合教务系统（URP）
    const val JW_BASE = "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
    const val JW_COURSE_LIST = "$JW_BASE/student/courseSelect/planCourse/courseList"
    const val JW_FREE_COURSE_LIST = "$JW_BASE/student/courseSelect/freeCourse/courseList"
    const val JW_SELECT_SUBMIT = "$JW_BASE/student/courseSelect/selectCourses/waitingfor"
    const val JW_PLAN = "$JW_BASE/student/integratedQuery/planCompletion/index"
    const val JW_SCHEDULE_PAGE = "$JW_BASE/student/courseSelect/thisSemesterCurriculum/index"
    const val JW_SCORE_PAGE = "$JW_BASE/student/integratedQuery/scoreQuery/allTermScores/index"

    /** 学业概览（学校口径 GPA / 排名 / 已修门数）：返回 JSON 数组 */
    const val JW_ACADEMIC_INFO = "$JW_BASE/main/academicInfo"

    /** 学期下拉列表所在页面（calendarSemesterCurriculum 里有 select#planCode） */
    const val JW_TERM_LIST = "$JW_BASE/student/courseSelect/calendarSemesterCurriculum/index"

    /** 本学期成绩页：页面里带动态路径，且成绩含 examTime（查明细的必要参数） */
    const val JW_THIS_TERM_SCORES_PAGE =
        "$JW_BASE/student/integratedQuery/scoreQuery/thisTermScores/index"

    // ---- 开课查询（v1.9.1，移植自 LoveACE）----
    const val JW_COURSE_QUERY_INDEX = "$JW_BASE/student/integratedQuery/course/courseSchdule/index"
    const val JW_COURSE_QUERY_PAGE =
        "$JW_BASE/student/integratedQuery/course/courseSchdule/courseInfo?sf_request_type=ajax"

    // ---- 考试安排（v1.9.1）----
    const val JW_EXAM_PLAN_INDEX = "$JW_BASE/student/examinationManagement/examPlan/index"
    const val JW_EXAM_PLAN_DETAIL = "$JW_BASE/student/examinationManagement/examPlan/detail"
    const val JW_OTHER_EXAM_QUERY =
        "$JW_BASE/student/examinationManagement/othersExamPlan/queryScores?sf_request_type=ajax"

    // ---- 自动评价（v1.9.1）----
    const val JW_EVAL_INDEX = "$JW_BASE/student/teachingEvaluation/evaluation/index"
    const val JW_EVAL_COURSE_PAGE =
        "$JW_BASE/student/teachingEvaluation/teachingEvaluation/evaluationPage"
    const val JW_EVAL_COURSE_LIST =
        "$JW_BASE/student/teachingEvaluation/teachingEvaluation/search?sf_request_type=ajax"
    const val JW_EVAL_SUBMIT =
        "$JW_BASE/student/teachingEvaluation/teachingEvaluation/assessment?sf_request_type=ajax"

    // ---- 零星维修（v1.9.1，PHP 抢单平台）----
    const val WXQD_BASE = "http://wxqd-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
    const val WXQD_USER = "$WXQD_BASE/index.php/index/user"          // 建立 PHPSESSID
    const val WXQD_ORDER_LIST = "$WXQD_BASE/index.php/index/myorder/staue"  // /{0..3}
    const val WXQD_ORDER_DETAIL = "$WXQD_BASE/index.php/index/showrepair/taskid"  // /{id}
    const val WXQD_AREA_LIST = "$WXQD_BASE/index.php/index/arealist"
    const val WXQD_PROJECT_LIST = "$WXQD_BASE/index.php/index/projectlist"
    const val WXQD_REPAIR_SUBMIT = "$WXQD_BASE/index.php/index/repair?sf_request_type=ajax"

    // ---- 竞赛获奖（v1.9.1，ASP.NET WebForms）----
    const val XSXM_BASE = "http://211-86-241-245.vpn2.aufe.edu.cn:8118"
    const val XSXM_MAIN = "$XSXM_BASE/xsXmMain.aspx"

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

    // ---- 电费充值（挂在一卡通系统下，与 ISIM 查询是两套房间编码）----
    const val YKT_POWER_INIT = "$YKT_BASE/utilityUnBindUserPowerPayInit.action"  // 账号信息 + accId
    const val YKT_POWER_BIND_DATA = "$YKT_BASE/utilitBindXiaoQuData.action"      // 四级联动选项
    const val YKT_POWER_PAY = "$YKT_BASE/utilityUnBindUserPowerPay.action"       // 提交充值
    const val YKT_POWER_HISTORY = "$YKT_BASE/utilityQueryRunningAccountInfo.action" // 购电流水

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
        SMART_CAMPUS_BASE,
        "http://uaap-aufe-edu-cn.vpn2.aufe.edu.cn:8118/",
        "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118/",
        DEKT_BASE,
        "http://dekt-ac-acxk-net.vpn2.aufe.edu.cn:8118/",
        YKT_BASE,
        ISIM_BASE,
        // v1.9.1 新增模块的站点：登录时一并收集，退出时一并清理
        WXQD_BASE,
        XSXM_BASE
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
        // 连接超时收到 10 秒：VPN 网关可达时握手都在 1 秒内，
        // 20 秒只会让"其实已经断了"的请求白等更久（v1.11.3）
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
     * POST 表单 + 一个文件（`multipart/form-data`）。
     *
     * WebForms 的 `FileUpload` 控件必须用 multipart；同时**其余字段要按普通表单字段一起送**，
     * 否则 `__VIEWSTATE` 丢了，服务端会当成一次非法回发直接 500。
     */
    fun postMultipartText(
        url: String,
        params: Map<String, String>,
        file: UploadFile,
        headers: Map<String, String> = emptyMap(),
        slow: Boolean = false
    ): String = withSessionRetry {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            params.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(
                file.field,
                file.fileName,
                file.bytes.toRequestBody(file.mime.toMediaTypeOrNull())
            )
        }.build()
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", ua)
        execute(builder.build(), slow).use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException(httpError(resp.code))
            val text = resp.body?.string() ?: ""
            checkNotLoginPage(text, url)
            text
        }
    }
    /**
     * 检测 VPN 会话失效：TWFID/CASTGC 过期时，VPN 网关会把所有请求重定向到门户登录页
     * （返回一段约 7KB 的 JS 跳转页）。此时必须明确报错，而不是让解析层静默返回空数据。
     */
    private fun checkNotLoginPage(body: String, url: String) {
        if (body.contains("does not support JavaScript") || body.contains("g_midatk")) {
            // 记下是哪条接口被踢的：排查"到底哪个域名的会话掉了"时这行最有用
            cn.aufe.campus.util.AppLog.w("Net", "会话失效：$url")
            throw SessionExpiredException("登录会话已过期（VPN 网关已踢出），请重新登录")
        }
    }
}

/** VPN 会话失效异常（区别于普通网络错误，UI 可引导用户重新登录） */
class SessionExpiredException(message: String) : java.io.IOException(message)
