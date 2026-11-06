package com.aufe.competition

/**
 * 宿主 App → 承载页的"会话救援"通道。
 *
 * ## 为什么需要它
 *
 * 网页版和原生界面走的是两套 Cookie 仓库：
 * - 原生：OkHttp + `PersistentCookieStore`（App 自己维护，7 天静默续期）
 * - 网页版：系统 `CookieManager`
 *
 * 两边的 VPN 票据（TWFID）会各自过期。网页版过期时页面会被网关弹回登录页，
 * 而 App 侧的会话往往还是好的 —— 于是出现"网页版单独失效"。
 *
 * 更糟的是，光重新灌一次 Cookie 修不好：`setCookie` 只会在作用域不匹配时**追加**
 * 一条同名 Cookie，旧的死票据仍然在，网关先读到它，继续跳登录页。
 * 所以用户只能"退出 App 再登录"——那条路径里有 `clearSchoolCookies()`，
 * 顺手把死票据清掉了。这正是"只有整体退出重登才能恢复"的原因。
 *
 * 现在的做法：承载页一旦发现落在登录页，就通过这个通道请宿主
 * "重新登录一次 + 整仓重灌 Cookie"，然后自己 reload。最多自动救两次，
 * 再失败才提示用户手动重登，避免死循环。
 */
object CompetitionSession {

    /**
     * 宿主注入：重新登录并把新的会话 Cookie 写进系统 CookieManager。
     * 参数 `onDone(true)` 表示可以 reload 了。
     */
    @Volatile
    var refresher: ((onDone: (Boolean) -> Unit) -> Unit)? = null
}
