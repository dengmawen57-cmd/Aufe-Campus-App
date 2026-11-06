package cn.aufe.campus

import android.app.Application
import cn.aufe.campus.auth.AuthManager
import cn.aufe.campus.util.CrashLogger
import cn.aufe.campus.util.IconSwitcher
import cn.aufe.campus.util.WeekProvider

/**
 * 应用入口。
 *
 * ## v1.8.1 重要修正：这里**不能**启动后台常驻服务
 *
 * v1.8.0 曾在 `onCreate` 里调用 `KeepAliveManager.restoreIfEnabled(this)`，
 * 后果非常严重：`Application.onCreate` 会被**任何**进程启动触发，包括
 *
 * - 上课提醒的 AlarmManager 广播（每 2 分钟一次）
 * - 开机广播 `BOOT_COMPLETED`
 * - 桌面小组件的更新广播
 *
 * 这些场景里应用处于**后台**，而 Android 12+ 禁止从后台启动前台服务。
 * 即便异常被 catch，只要服务实例被创建却没能及时 `startForeground()`，
 * 系统就会以 `RemoteServiceException` 直接杀掉进程并弹「应用已停止运行」。
 * 叠加 v1.8.0 的"失败后 2 秒闹钟自救"，就形成了**无限崩溃循环**，
 * 同时把进程里尚未落盘的登录凭据一起冲掉（表现为"登录自动退出"）。
 *
 * → 常驻服务的启动入口已移到 `MainActivity.onStart`（应用可见，合法）。
 */
class CampusApp : Application() {

    lateinit var authManager: AuthManager
        private set

    override fun onCreate() {
        super.onCreate()
        // v1.8.1：安装崩溃记录器。只写本地文件、不拦截异常，
        // 目的是万一还有问题，能直接从 last_crash.txt 拿到真实堆栈。
        CrashLogger.install(this)
        // v1.8.2：图标切换靠 activity-alias 的启用状态实现。
        // 万一上次切换中途被系统杀掉，可能出现"所有 alias 都被禁用"
        // （应用从桌面消失）。这里启动时自检一次并修复。
        runCatching { IconSwitcher.repairIfNeeded(this) }
        // 周次状态需要在 Application 里初始化：课表页、桌面小组件、上课提醒都会读它
        WeekProvider.init(this)
        authManager = AuthManager(this)

        // v1.11.3：内嵌网页（学科竞赛网页版）会话失效时，承载页会回调这里。
        // 重新登录 + 整仓重灌 Cookie，用户不用退出 App 再登录。
        // v1.11.4：重登完成后顺带把目标页的 Cookie 补灌一遍（injectFor），
        // 承载页 reload 时不会再落到登录页。
        com.aufe.competition.CompetitionSession.refresher = { onDone ->
            authManager.reLoginForWeb(
                url = com.aufe.competition.CompetitionConfig.entryUrl(),
                onDone = onDone
            )
        }
    }
}
