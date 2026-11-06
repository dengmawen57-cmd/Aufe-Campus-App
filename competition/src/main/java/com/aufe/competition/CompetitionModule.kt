package com.aufe.competition

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * 学科竞赛模块对外唯一入口（宿主 App 只需要认这一个类）。
 *
 * 用法：
 *   CompetitionModule.start(context)                       // 默认进"获奖申报"
 *   CompetitionModule.start(context, CompetitionModule.TAB_SIGN_UP)  // 直接进"报名参赛"
 *   CompetitionModule.clearSession()                       // 注销 / 切换账号时清 Cookie
 */
object CompetitionModule {

    /** 获奖申报 */
    const val TAB_AWARD_APPLY: Int = 0

    /** 已申报奖项 */
    const val TAB_APPLIED: Int = 1

    /** 报名参赛 */
    const val TAB_SIGN_UP: Int = 2

    /** 已报名项目 */
    const val TAB_SIGNED: Int = 3

    private val TAB_LABELS = mapOf(
        TAB_AWARD_APPLY to "获奖申报",
        TAB_APPLIED to "已申报奖项",
        TAB_SIGN_UP to "报名参赛",
        TAB_SIGNED to "已报名项目"
    )

    /** tab 序号 -> 页面上的标签文案（用于注入 JS 点击切换） */
    fun tabLabel(tab: Int): String = TAB_LABELS[tab] ?: TAB_LABELS.getValue(TAB_AWARD_APPLY)

    /** 打开学科竞赛模块 */
    fun start(context: Context, tab: Int = TAB_AWARD_APPLY) {
        val intent = CompetitionWebActivity.intent(context, tab)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 清理 WebView Cookie（学校统一身份认证 + VPN 会话） */
    fun clearSession() = CompetitionCookies.clear()

    /** 当前是否已建立过该平台的会话（用于"我的"页面展示状态，非必须） */
    fun hasSession(): Boolean = !CompetitionCookies.cookie(CompetitionConfig.entryUrl()).isNullOrEmpty()
}
