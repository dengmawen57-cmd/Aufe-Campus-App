package cn.aufe.campus.data.net

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 持久化 CookieJar：把 VPN/CAS 会话 Cookie 保存到 SharedPreferences，
 * WebView 登录完成后由 AuthManager 导入，OkHttp 请求时自动携带。
 *
 * 关键点：VPN 各子系统（jwcxk2-* / ykt-* / hqkd-* / uaap-* 等）都是
 * *.vpn2.aufe.edu.cn 的子域。若把 TWFID 存成 hostOnly（只属于 vpn2.aufe.edu.cn），
 * 访问一卡通等子域时 Cookie 不会携带，接口会返回登录页 —— 一卡通余额为空即由此引起。
 * 因此所有 vpn2 域的 Cookie 统一提升为 domain cookie 并归入同一桶（子域共享）。
 */
class PersistentCookieStore(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aufe_cookies", Context.MODE_PRIVATE)

    // 来源 host -> cookie 列表（保留来源，避免各子系统的同名 JSESSIONID 互相覆盖）
    private val cache: MutableMap<String, MutableList<Cookie>> = load()

    init {
        migrateIfNeeded()
    }

    companion object {
        private const val VPN_HOST = "vpn2.aufe.edu.cn"
        private const val KEY_VERSION = "layout_version"
        private const val LAYOUT_VERSION = 2

        /**
         * 本地会话 Cookie 的保留时长。
         *
         * 原实现写死 12 小时，等于每天强制让会话"到期"一次——即使学校网关那边
         * 会话还有效，本地也会主动丢弃 Cookie，逼着走一次完整重登。
         * 这里放宽到 7 天：Cookie 是否真的有效由服务端判定（失效会返回登录页，
         * 由 AuthManager 静默重登兜底），本地只是"别提前扔"。
         */
        const val SESSION_COOKIE_TTL_MS = 7L * 24 * 3600 * 1000

        /** 需要跨所有子域共享的全局凭据（由 VPN 主域统一下发） */
        private val GLOBAL_COOKIE_NAMES = setOf("TWFID", "CASTGC", "SINFORSID", "SINFORSID2")

        /** 域名归一化（仅用于判断是否 VPN 域，不再用于分桶） */
        fun extractDomain(host: String): String =
            if (host.contains(VPN_HOST)) VPN_HOST else host
    }

    /**
     * v2 迁移：旧版把所有 vpn2 子域 Cookie 混在同一桶，导致教务/一卡通/电费的
     * JSESSIONID 同名互相覆盖（表现为选课等接口 HTTP 500）。
     * 这里丢弃来源不明的 JSESSIONID（各系统下次访问会自动重建），保留 TWFID/CASTGC 等凭据，
     * 用户无需重新登录。
     */
    private fun migrateIfNeeded() {
        if (prefs.getInt(KEY_VERSION, 1) >= LAYOUT_VERSION) return
        cache.forEach { (_, list) ->
            list.removeAll { it.name.equals("JSESSIONID", true) }
        }
        save()
        prefs.edit().putInt(KEY_VERSION, LAYOUT_VERSION).apply()
    }

    private fun load(): MutableMap<String, MutableList<Cookie>> {
        val map = mutableMapOf<String, MutableList<Cookie>>()
        val raw = prefs.getString("cookies", null) ?: return map
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { domain ->
                val arr = obj.optJSONArray(domain) ?: JSONArray()
                val list = mutableListOf<Cookie>()
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val cookie = cookieFromJson(c) ?: continue
                    list.add(cookie)
                }
                if (list.isNotEmpty()) map[domain] = list
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun save() {
        val obj = JSONObject()
        cache.forEach { (domain, list) ->
            if (list.isEmpty()) return@forEach
            val arr = JSONArray()
            list.forEach { arr.put(cookieToJson(it)) }
            obj.put(domain, arr)
        }
        prefs.edit().putString("cookies", obj.toString()).apply()
    }

    private fun cookieToJson(c: Cookie): JSONObject = JSONObject().apply {
        put("name", c.name)
        put("value", c.value)
        put("domain", c.domain)
        put("path", c.path)
        put("expiresAt", c.expiresAt)
        put("secure", c.secure)
        put("hostOnly", c.hostOnly)
        put("persistent", c.persistent)
    }

    private fun cookieFromJson(o: JSONObject): Cookie? = try {
        Cookie.Builder().apply {
            name(o.getString("name"))
            value(o.getString("value"))
            if (o.optBoolean("hostOnly")) hostOnlyDomain(o.getString("domain"))
            else domain(o.getString("domain"))
            path(o.optString("path", "/"))
            if (o.optLong("expiresAt") > 0) expiresAt(o.optLong("expiresAt"))
            if (o.optBoolean("secure")) secure()
        }.build()
    } catch (_: Exception) {
        null
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        val key = url.host                     // 按来源 host 分桶
        val list = cache.getOrPut(key) { mutableListOf() }
        cookies.forEach { newCookie ->
            // 过期 Cookie：删除同名；否则覆盖同名
            if (newCookie.persistent && newCookie.expiresAt < now) {
                list.removeAll { it.name == newCookie.name }
            } else {
                // 只有 VPN 主域下发的 Cookie（如 TWFID）才提升到主域，供所有子域共享；
                // 子域自己的 JSESSIONID 必须保持 hostOnly，否则会串到其它子系统导致 500
                val stored = if (key == VPN_HOST && newCookie.hostOnly) {
                    promoteToVpnDomain(newCookie)
                } else newCookie
                list.removeAll { it.name == stored.name }
                list.add(stored)
            }
        }
        save()
    }

    private fun promoteToVpnDomain(c: Cookie): Cookie = try {
        Cookie.Builder()
            .name(c.name)
            .value(c.value)
            .domain(VPN_HOST)
            .path(if (c.path.isBlank()) "/" else c.path)
            .apply {
                if (c.secure) secure()
                if (c.persistent) expiresAt(c.expiresAt)
            }
            .build()
    } catch (_: Exception) {
        c
    }

    /**
     * 合并顺序（同名 Cookie 以先出现者为准）：
     * 1) 本 host 自己的（教务/一卡通/电费各自的 JSESSIONID 互不干扰）
     * 2) VPN 主域桶（TWFID 等全局凭据）
     * 3) 其他 vpn 子域桶（只补充本 host 没有的 Cookie，如 TWFID）
     */
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        val host = url.host
        val isVpn = host.contains(VPN_HOST)

        val order = mutableListOf(host)
        if (isVpn) {
            if (host != VPN_HOST) order.add(VPN_HOST)
            cache.keys
                .filter { it != host && it != VPN_HOST && it.contains(VPN_HOST) }
                .sorted()
                .forEach { order.add(it) }
        } else {
            cache.keys.filter { it != host }.forEach { order.add(it) }
        }

        order.distinct().forEach { key ->
            cache[key]?.forEach cookie@{ c ->
                if (c.expiresAt in 1 until now) return@cookie
                if (!c.matches(url)) return@cookie
                if (result.any { it.name == c.name }) return@cookie
                result.add(c)
            }
        }
        return result
    }

    fun clear() {
        cache.clear()
        prefs.edit().remove("cookies").apply()
    }

    fun hasSession(): Boolean =
        cache.values.flatten().any { c ->
            c.name.equals("TWFID", true) || c.name.contains("CASTGC", true) ||
                c.name.equals("JSESSIONID", true) || c.name.contains("sinforsid", true)
        }

    /** 手动写入一个 Cookie（OkHttp 登录后保存 TWFID） */
    fun setCookie(name: String, value: String, domain: String) {
        val key = extractDomain(domain)
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path("/")
        if (key == VPN_HOST) builder.domain(VPN_HOST) else builder.domain(domain)
        if (domain.startsWith("https")) builder.secure()
        builder.expiresAt(System.currentTimeMillis() + SESSION_COOKIE_TTL_MS)
        val list = cache.getOrPut(key) { mutableListOf() }
        list.removeAll { it.name == name }
        list.add(builder.build())
        save()
    }

    /** 立即持久化（登录成功后调用） */
    fun persist() = save()

    /**
     * 登录成功后统一续期。
     *
     * WebView 里的会话 Cookie 往往没有 Expires，导入时只能由我们补一个本地过期时间；
     * 这个时间一旦到点，OkHttp 就不再携带 Cookie，表现就是「明明刚登录却掉线」。
     * 每次登录/静默重登成功都续一次，避免本地成为会话的短板。
     */
    @Synchronized
    fun refreshExpiry(ttlMs: Long = SESSION_COOKIE_TTL_MS) {
        if (cache.isEmpty()) return
        val now = System.currentTimeMillis()
        // 只在"剩余寿命不足一半"时续期：既避免本地提前过期，也避免每次恢复都白写一次磁盘
        val threshold = now + ttlMs / 2
        var changed = false
        cache.forEach { (key, list) ->
            val rebuilt = list.map { c ->
                if (c.expiresAt in 1L until threshold) {
                    changed = true
                    withExpiry(c, now + ttlMs)
                } else c
            }
            cache[key] = rebuilt.toMutableList()
        }
        if (changed) save()
    }

    /** 以新的过期时间重建 Cookie（保持原 domain / hostOnly / secure 语义） */
    private fun withExpiry(c: Cookie, newExpiry: Long): Cookie = try {
        Cookie.Builder()
            .name(c.name)
            .value(c.value)
            .path(if (c.path.isBlank()) "/" else c.path)
            .apply {
                if (c.hostOnly) hostOnlyDomain(c.domain) else domain(c.domain)
                if (c.secure) secure()
                expiresAt(newExpiry)
            }
            .build()
    } catch (_: Exception) {
        c
    }

    /** 取出指定 Cookie 值（调试/登录态判断用） */
    fun cookieValue(name: String): String? =
        cache.values.flatten().firstOrNull { it.name.equals(name, true) }?.value

    /** 从 WebView 的 CookieManager 导入全部 Cookie */
    fun importFromWebView(
        cookieManager: android.webkit.CookieManager,
        urls: List<String>
    ): Int {
        var count = 0
        urls.forEach url@{ url ->
            val cookieHeader = cookieManager.getCookie(url) ?: return@url
            val httpUrl = url.toHttpUrlOrNull() ?: return@url
            cookieHeader.split(";").forEach pair@{ pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@pair
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (name.isBlank() || value.isBlank() || value == "deleted") return@pair
                val builder = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path("/")
                // 主域 Cookie（TWFID 等）跨子域共享；子域 Cookie（各自 JSESSIONID）保持 hostOnly，
                // 否则教务 / 一卡通 / 电费的同名 Cookie 会互相串用
                if (httpUrl.host == VPN_HOST) {
                    builder.domain(VPN_HOST)
                } else {
                    // 全局凭据已由主域 URL 导入，此处跳过，避免被降级成子域 hostOnly
                    if (name.uppercase(Locale.ROOT) in GLOBAL_COOKIE_NAMES) return@pair
                    builder.hostOnlyDomain(httpUrl.host)
                }
                if (url.startsWith("https")) builder.secure()
                // 会话级 Cookie（WebView 常见），补一个足够长的本地过期时间以便持久化
                builder.expiresAt(System.currentTimeMillis() + SESSION_COOKIE_TTL_MS)
                // 按来源 host 分桶：domain 已提升为主域（可跨子域），但存储保留来源，
                // 避免 jwcxk2 / ykt / hqkd 各自的 JSESSIONID 同名互覆盖
                val list = cache.getOrPut(httpUrl.host) { mutableListOf() }
                list.removeAll { it.name == name }
                list.add(builder.build())
                count++
            }
        }
        save()
        return count
    }
}
