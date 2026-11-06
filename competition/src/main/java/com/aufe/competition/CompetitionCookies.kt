package com.aufe.competition

import android.webkit.CookieManager

/**
 * WebView Cookie 管理。
 *
 * 该平台没有 token，身份完全依赖 Cookie（VPN 的 TWFID + 学校 CAS 的票据），
 * 所以：进程退出前 flush，注销时清空，两者都不能省。
 */
internal object CompetitionCookies {

    fun cookie(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)
    }.getOrNull()

    /** 把内存 Cookie 落盘，避免杀进程后丢登录态 */
    fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun clear() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
