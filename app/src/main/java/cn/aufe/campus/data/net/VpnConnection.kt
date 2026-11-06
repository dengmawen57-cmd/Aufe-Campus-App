package cn.aufe.campus.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OkHttp 双级登录（移植自彩带小工具 AUFEConnection）：
 * 1) EC(VPN) 登录：RSA 加密密码
 * 2) UAAP(CAS) 登录：DES 加密密码
 *
 * 与 AufeClient 共享同一个 PersistentCookieStore，登录后所有接口直接可用。
 * 定位：WebView 登录为主通道（对门户改版最稳），本类作为**静默重连通道**——
 * 会话过期时后台自动重登，用户无感；失败才回登录页。
 */
class VpnConnection(
    private val userId: String,
    private val ecPassword: String,
    private val password: String,
    private val cookieStore: PersistentCookieStore
) {
    private val client: OkHttpClient = sharedClient(cookieStore)

    var twfId: String? = null
        private set

    /** 步骤 1：VPN 门户登录 */
    suspend fun ecLogin(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = getText(Endpoints.VPN_AUTH)
            val twf = Regex("<TwfID>(.*?)</TwfID>").find(body)?.groupValues?.get(1)
                ?: throw Exception("未找到 TwfID（VPN 门户结构可能已改版）")
            val rsaKey = Regex("<RSA_ENCRYPT_KEY>(.*?)</RSA_ENCRYPT_KEY>").find(body)?.groupValues?.get(1)
                ?: throw Exception("未找到 RSA 公钥")
            val rsaExp = Regex("<RSA_ENCRYPT_EXP>(.*?)</RSA_ENCRYPT_EXP>").find(body)?.groupValues?.get(1)
                ?: throw Exception("未找到 RSA 指数")
            val csrf = Regex("<CSRF_RAND_CODE>(.*?)</CSRF_RAND_CODE>").find(body)?.groupValues?.get(1)
                ?: throw Exception("未找到 CSRF 码")

            val encrypted = VpnCrypto.rsaEncrypt("${ecPassword}_$csrf", rsaKey, rsaExp)
            val form = FormBody.Builder()
                .add("svpn_rand_code", "")
                .add("mitm", "")
                .add("svpn_req_randcode", csrf)
                .add("svpn_name", userId)
                .add("svpn_password", encrypted)
                .build()
            val resp = client.newCall(
                Request.Builder()
                    .url(Endpoints.VPN_LOGIN)
                    .header("Cookie", "TWFID=$twf")
                    .post(form)
                    .build()
            ).execute()
            val text = resp.body?.string() ?: ""
            resp.close()

            when {
                text.contains("<Result>1</Result>") -> {
                    twfId = twf
                    cookieStore.setCookie("TWFID", twf, "vpn2.aufe.edu.cn")
                    cookieStore.persist()
                }
                text.contains("Invalid username or password") ->
                    throw Exception("VPN 账号或密码错误")
                text.contains("maybe attacked") || text.contains("CAPTCHA") ->
                    throw TooManyLoginsException("登录过于频繁，学校网关已临时限制")
                else -> throw Exception("VPN 登录失败：${text.take(80)}")
            }
        }
    }

    /** 步骤 2：CAS 统一身份认证登录 */
    suspend fun uaapLogin(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = getText(Endpoints.UAAP_LOGIN_PAGE)
            if (!body.contains("name=\"lt\"") && !body.contains("name='lt'")) {
                // 可能已经登录（重定向到 service），视为成功
                if (body.contains("ticket=") || body.contains("j_spring_cas_security_check")) return@runCatching
            }
            val lt = inputValue(body, "lt")
                ?: throw Exception("未找到 lt（CAS 页面结构可能已改版）")
            val execution = inputValue(body, "execution").orEmpty()

            // 带上页面里所有 hidden 字段，兼容 CAS 改版新增的参数
            val formBuilder = FormBody.Builder()
            Regex("""<input[^>]*type=["']hidden["'][^>]*>""", RegexOption.IGNORE_CASE)
                .findAll(body).forEach { m ->
                    val tag = m.value
                    val n = Regex("""name=["'](.*?)["']""").find(tag)?.groupValues?.get(1)
                    val v = Regex("""value=["'](.*?)["']""").find(tag)?.groupValues?.get(1).orEmpty()
                    if (!n.isNullOrBlank() && n != "lt" && n != "execution") formBuilder.add(n, v)
                }
            formBuilder.add("username", userId)
            formBuilder.add("password", VpnCrypto.desEncrypt(password, lt))
            formBuilder.add("lt", lt)
            if (execution.isNotBlank()) formBuilder.add("execution", execution)
            formBuilder.add("_eventId", "submit")
            formBuilder.add("submit", "LOGIN")

            val resp = client.newCall(
                Request.Builder().url(Endpoints.UAAP_LOGIN_PAGE).post(formBuilder.build()).build()
            ).execute()
            val text = resp.body?.string() ?: ""
            val finalUrl = resp.request.url.toString()
            resp.close()

            // 学校强制改密：TGT 已铸出（说明密码正确）但 CAS 不签发票据，302 到改密页。
            // 必须先于"密码错误"判定——这种情况清凭据、反复重试都没有意义。
            if (finalUrl.contains("completeInfo/password") || finalUrl.contains("/acp/")) {
                throw PasswordChangeRequiredException()
            }
            if (text.contains("Invalid username or password") ||
                text.contains("用户名或密码错误") ||
                text.contains("密码错误")
            ) throw Exception("教务/统一认证密码错误")
            if (text.contains("maybe attacked") || text.contains("CAPTCHA")) {
                throw TooManyLoginsException("登录过于频繁，学校网关已临时限制")
            }

            val stillLoginPage = text.contains("name=\"lt\"") || text.contains("name='lt'")
            val redirected = finalUrl.contains("ticket=") || finalUrl.contains("jwcxk2")
            if (stillLoginPage && !redirected) throw Exception("CAS 登录失败：仍在登录页")
        }
    }

    /**
     * 保活（对齐参考 app 的心跳）：访问门户授权接口，刷新网关的空闲计时，
     * 让 VPN 会话不会因为我们"什么都没干"而被回收。
     * 注意：这个接口即使用户未登录也会返回 TwfID，所以它**只能保活，不能判断是否掉线**。
     */
    suspend fun warmPing(): Boolean = withContext(Dispatchers.IO) {
        runCatching { getText(Endpoints.VPN_AUTH).isNotBlank() }.getOrDefault(false)
    }

    /**
     * 真实探活：请求一次教务页面，若被网关重定向到登录页即判定会话已失效。
     * 这是"心跳"真正该看的东西——门户接口 ping 通不代表教务会话还在。
     */
    suspend fun isSessionAlive(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.newCall(Request.Builder().url(Endpoints.JW_SCHEDULE_PAGE).get().build()).execute()
            val text = resp.body?.string() ?: ""
            val url = resp.request.url.toString()
            resp.close()
            if (url.contains("cas/login", true) || url.contains("login_auth.csp")) return@runCatching false
            !(text.contains("does not support JavaScript") ||
                text.contains("g_midatk") ||
                text.contains("svpn_name") ||
                text.contains("login_auth.csp"))
        }.getOrDefault(false)
    }

    /** 兼容旧调用：等价于 [isSessionAlive] */
    suspend fun heartbeat(): Boolean = isSessionAlive()

    /** 完整登录：EC + CAS */
    suspend fun loginAll(): Result<Unit> {
        ecLogin().getOrElse { return Result.failure(it) }
        return uaapLogin()
    }

    private fun getText(url: String): String {
        val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
        val text = resp.body?.string() ?: ""
        resp.close()
        return text
    }

    /**
     * 取表单里指定 name 的 input 的 value，不依赖属性顺序
     * （原实现要求 `name="lt" value="..."` 连续出现，CAS 改版调整属性顺序就会失败）
     */
    private fun inputValue(html: String, name: String): String? {
        Regex("""<input[^>]*\bname=["']$name["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { tag ->
                Regex("""\bvalue=["'](.*?)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)?.let { return it }
            }
        // 兜底：value 在 name 之前的写法
        return Regex("""<input[^>]*\bvalue=["']([^"']*)["'][^>]*\bname=["']$name["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
    }
}

/**
 * 学校强制修改统一身份认证密码（密码本身是对的）：
 * 不计入"密码错误"、不清凭据、不自动重试。
 */
class PasswordChangeRequiredException :
    Exception("学校要求先修改统一身份认证密码")

/** 登录过于频繁被网关限流：需要长退避，不能继续猛打 */
class TooManyLoginsException(message: String) : Exception(message)

/**
 * 复用一个 OkHttpClient：心跳/静默重登会反复创建 VpnConnection，
 * 若每次都新建 client 会不断堆积连接池与线程（长时间运行必然出问题）。
 */
private val vpnClientLock = Any()
private var vpnClientRef: OkHttpClient? = null
private var vpnClientJar: PersistentCookieStore? = null

private fun sharedClient(store: PersistentCookieStore): OkHttpClient = synchronized(vpnClientLock) {
    val cached = vpnClientRef
    if (cached != null && vpnClientJar === store) {
        cached
    } else {
        val built = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(store)
            .build()
        vpnClientRef = built
        vpnClientJar = store
        built
    }
}
