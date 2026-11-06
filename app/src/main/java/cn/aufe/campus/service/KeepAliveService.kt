package cn.aufe.campus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import cn.aufe.campus.widget.ReminderEngine
import cn.aufe.campus.widget.ScheduleWidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台常驻前台服务。
 *
 * 只做两件**不需要联网**的事，因此长时间开着也不费流量、不耗电到离谱：
 * 1. 每分钟跑一次上课提醒判断（比 AlarmManager 的不精确闹钟在 Doze 下准得多）；
 * 2. 每 5 分钟请求重建一次桌面小组件（跨天、下课、周次变化能及时反映）。
 *
 * 刻意不做的事：不在后台偷偷拉成绩/课表。教务系统在 VPN 后面，
 * 后台周期性请求既慢又容易把会话顶掉，收益远小于风险——回到前台自然会同步。
 *
 * ## v1.8.1 稳定性重写（针对 v1.8.0 的连续崩溃）
 *
 * v1.8.0 有两条会**把整个 App 拖崩**的路径，已全部拆掉：
 *
 * 1. `CampusApp.onCreate` 里无条件 `restoreIfEnabled()`。
 *    Application.onCreate 会被**任何**进程启动触发——包括每 2 分钟一次的
 *    上课提醒广播、开机广播、小组件更新广播。这些场景应用都在后台，
 *    Android 12+ 明确禁止从后台启动前台服务；即便抛出的异常被 catch，
 *    只要服务实例被创建却没能及时 `startForeground()`，系统就会以
 *    `RemoteServiceException: Context.startForegroundService() did not then
 *    call Service.startForeground()` 直接杀掉进程并弹「应用已停止运行」。
 *    → 启动入口改为**只在 MainActivity 可见时**调用（见 MainActivity.onStart）。
 *
 * 2. `onTaskRemoved` / `onDestroy` 里的"2 秒后闹钟自救"。
 *    服务一旦因为上面的原因起不来，自救闹钟就会 2 秒后再拉一次 →
 *    再崩 → 再拉，形成**无限崩溃循环**，用户看到的是「一直弹停止运行」。
 *    → 自救逻辑整段删除。只保留系统自身的 `START_STICKY` 重启，
 *      系统重启时应用通常已回到前台，不会触发后台启动限制。
 *
 * 另外本文件所有可能抛异常的地方（建通知、贴通知、tick 逻辑）都做了兜底，
 * 保证服务最坏情况只是"没起作用"，绝不会让调用方进程崩溃。
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 第一件事必须是把服务顶成前台态：晚一步系统就会判定超时并杀进程。
        // goForeground 内部已保证不向外抛异常。
        goForeground()
        runCatching { startLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统重启（START_STICKY）时重新贴一次通知，避免通知被清导致 ANR 判定
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        // v1.8.1：不再安排"闹钟重启"。原因见类注释第 2 条——
        // 那是崩溃循环的放大器。系统需要时会用 START_STICKY 自己拉起来。
        super.onDestroy()
    }

    /**
     * 进入前台态。
     *
     * 三级降级，保证**一定**有一次 `startForeground()` 成功：
     * 1. 正常通知（带"关闭后台常驻"按钮）
     * 2. 极简通知（去掉按钮/PendingIntent，排除 PendingIntent 层面的问题）
     * 3. 都不成 → 记录熔断计数并主动 stopSelf，避免"服务活着但不在前台"
     *    这种最糟状态被系统反复判定超时。
     */
    private fun goForeground() {
        if (foregroundStarted) {
            // 已在前台态，只需刷新文案（例如用户刚改了提醒开关）
            runCatching {
                val n = KeepAliveManager.buildNotification(this)
                NotificationManagerCompat.from(this)
                    .notify(KeepAliveManager.NOTIFICATION_ID, n)
            }
            return
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        val candidates = listOf(
            runCatching { KeepAliveManager.buildNotification(this) }.getOrNull(),
            runCatching { KeepAliveManager.buildMinimalNotification(this) }.getOrNull()
        )
        for (n in candidates) {
            if (n == null) continue
            val ok = runCatching {
                ServiceCompat.startForeground(this, KeepAliveManager.NOTIFICATION_ID, n, type)
            }.isSuccess
            if (ok) {
                foregroundStarted = true
                KeepAliveManager.onForegroundOk(this)
                return
            }
        }
        KeepAliveManager.onForegroundFail(this)
        runCatching { stopSelf() }
    }

    private fun startLoop() {
        scope.launch {
            var ticks = 0
            while (isActive) {
                // 每一轮都单独包住：提醒逻辑出任何岔子都不该让服务退出
                runCatching { ReminderEngine.check(this@KeepAliveService) }
                if (ticks > 0 && ticks % WIDGET_REFRESH_EVERY_TICKS == 0) {
                    runCatching { ScheduleWidgetSync.requestUpdate(this@KeepAliveService) }
                }
                ticks++
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /** 预留：需要精确定时时用（当前主流程不依赖，避免再次形成重启环） */
    @Suppress("unused")
    private fun scheduleOnce(context: Context, delayMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            3001,
            Intent(context, KeepAliveActionReceiver::class.java)
                .setAction(KeepAliveActionReceiver.ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 60_000L
        private const val WIDGET_REFRESH_EVERY_TICKS = 5
    }
}

/**
 * 常驻通知里的按钮落点。
 *
 * 单独开一个 Receiver 而不是塞进 ClassReminderReceiver：
 * 常驻功能是独立模块，开关状态自己管，避免和提醒逻辑互相牵连。
 *
 * v1.8.1：不再由本应用自己的闹钟触发起重试，只保留用户点通知按钮 /
 * 系统的场景；收到后先确认开关仍打开。
 */
class KeepAliveActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 用 try/catch 而不是 runCatching：runCatching 的 lambda 处于表达式位置，
        // 里面的 when/if 会被强制要求穷尽分支。这里只是要"吞掉异常"。
        try {
            when (intent.action) {
                ACTION_STOP -> {
                    KeepAliveManager.setEnabled(context, false)
                    KeepAliveManager.stop(context)
                    runCatching {
                        NotificationManagerCompat.from(context)
                            .cancel(KeepAliveManager.NOTIFICATION_ID)
                    }
                }

                ACTION_RESTART, ACTION_START -> {
                    if (KeepAliveManager.isEnabled(context)) {
                        KeepAliveManager.start(context)
                    }
                }
            }
        } catch (_: Throwable) {
            // 常驻模块的任何问题都不该影响系统稳定性
        }
    }

    companion object {
        const val ACTION_STOP = "cn.aufe.campus.action.KEEP_ALIVE_STOP"
        const val ACTION_START = "cn.aufe.campus.action.KEEP_ALIVE_START"
        const val ACTION_RESTART = "cn.aufe.campus.action.KEEP_ALIVE_RESTART"
    }
}
