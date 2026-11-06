package cn.aufe.campus.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.aufe.campus.MainActivity
import cn.aufe.campus.R
import cn.aufe.campus.widget.ReminderPrefs

/**
 * 后台常驻开关与电池优化引导。
 *
 * 背景：上课提醒靠 AlarmManager 每 2 分钟 tick 一次，而系统进入 Doze 后
 * 不精确闹钟可能被推迟十几分钟——正好错过"提前 10 分钟提醒"这个时间窗。
 * 想让它稳定工作，只能把进程以前台服务的方式"顶"在后台：
 * 用户能看到一条常驻通知，系统也不会随手把进程回收。
 *
 * ## v1.8.1 加固点
 *
 * 1. **只在应用可见时启动**。`start()` 不再被 Application / BroadcastReceiver
 *    调用，只由 MainActivity.onStart 与用户点开关触发。Android 12+ 从后台
 *    启动前台服务会被系统拒绝，并被判超时杀进程（详见 KeepAliveService 注释）。
 * 2. **熔断**。连续 [MAX_FAIL] 次没能进入前台态，自动把开关关掉，
 *    宁可少一个功能也不要反复崩溃。
 * 3. **通知构建全程兜底**，并提供一条不依赖任何自定义资源的极简通知，
 *    防止图标/渠道层面的问题把服务启动打穿。
 */
object KeepAliveManager {

    const val PREFS = "aufe_keep_alive"
    const val KEY_ENABLED = "enabled"

    /** 连续进入前台失败次数（熔断用） */
    private const val KEY_FAIL = "foreground_fail_count"

    /** 连续失败达到这个次数就自动关闭常驻 */
    private const val MAX_FAIL = 3

    /** 用户是否开启过后台常驻 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** 开关并立即生效 */
    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_FAIL, 0)
                .apply()
        }
        if (enabled) start(context) else stop(context)
    }

    /**
     * 拉起前台服务（调用前必须已经 setEnabled(true)）。
     *
     * **只能在应用处于前台可见时调用**，否则 Android 12+ 会拒绝并可能
     * 判超时杀进程。内部仍做 try-catch 兜底，任何失败都只是"没起来"。
     */
    fun start(context: Context) {
        if (!isEnabled(context)) return
        // 已熔断关闭的场景不再尝试
        if (failCount(context) >= MAX_FAIL) return
        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, KeepAliveService::class.java)
            )
        }.onFailure {
            // 连 startForegroundService 都抛了，说明当前上下文不允许启动
            // （典型：应用在后台）。记一次失败，避免后续继续空转。
            onForegroundFail(context)
        }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }

    /**
     * 应用回到可见状态时恢复常驻。
     *
     * **只有 Activity 可见时才会走到这里**（MainActivity.onStart），
     * 这是 Android 12+ 允许启动前台服务的场景。
     */
    fun restoreIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        // 已被熔断：不再自动拉起，用户手动重新开启会重置计数
        if (failCount(context) >= MAX_FAIL) return
        start(context)
    }

    /** 是否已被熔断（供设置页提示用户） */
    fun isCircuitBroken(context: Context): Boolean = failCount(context) >= MAX_FAIL

    /** 服务成功进入前台态：清零失败计数 */
    internal fun onForegroundOk(context: Context) {
        runCatching {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getInt(KEY_FAIL, 0) != 0) {
                p.edit().putInt(KEY_FAIL, 0).apply()
            }
        }
    }

    /**
     * 进入前台失败：累加计数，达到阈值自动关闭开关。
     *
     * 这是 v1.8.0「一直弹停止运行」的最终兜底——即便某个 ROM 上
     * 前台服务始终起不来，也只会失败 3 次，然后安静地放弃。
     */
    internal fun onForegroundFail(context: Context) {
        runCatching {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val n = p.getInt(KEY_FAIL, 0) + 1
            if (n >= MAX_FAIL) {
                // 熔断：关掉开关，下次进应用时用户会看到它已关闭
                p.edit().putInt(KEY_FAIL, n).putBoolean(KEY_ENABLED, false).apply()
            } else {
                p.edit().putInt(KEY_FAIL, n).apply()
            }
        }
    }

    private fun failCount(context: Context): Int =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_FAIL, 0)
        }.getOrDefault(0)

    /** 是否已加入电池优化白名单（未加入时系统仍可能冻结后台） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /**
     * 申请电池优化白名单。
     *
     * 优先走系统对话框（一次点击即可），部分 ROM 拦截了这个 ACTION，
     * 就退回到"电池优化设置列表"，让用户手动找本应用。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(direct) }.isSuccess
        if (!ok) runCatching { context.startActivity(fallback) }
    }

    /** 常驻通知的文案：把"提醒开没开"也写进去，用户一眼知道现在在做什么 */
    internal fun notificationText(context: Context): String {
        val prefs = context.getSharedPreferences(ReminderPrefs.PREFS, Context.MODE_PRIVATE)
        val remind = prefs.getBoolean(ReminderPrefs.KEY_ENABLED, false)
        val advance = prefs.getInt(ReminderPrefs.KEY_ADVANCE, ReminderPrefs.DEFAULT_ADVANCE)
        return if (remind) "课表提醒运行中 · 提前 $advance 分钟" else "后台保活中 · 未开启上课提醒"
    }

    internal const val CHANNEL_ID = "keep_alive"
    internal const val NOTIFICATION_ID = 2001

    /**
     * 创建通知渠道。
     *
     * **必须判 SDK ≥ 26**：`NotificationChannel` / `getNotificationChannel` /
     * `createNotificationChannel` 都是 API 26 引入的，minSdk 24 的设备上
     * 直接调用会 `NoSuchMethodError` / `NoClassDefFoundError` ——
     * 那会在服务 onCreate 里崩掉整个进程。
     */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "后台运行",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "保持上课提醒与课表刷新在后台正常运行"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    /** 常驻通知（低优先级、常驻、可一键关闭常驻） */
    internal fun buildNotification(context: Context): Notification {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, KeepAliveActionReceiver::class.java)
                .setAction(KeepAliveActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("安财校园通")
            .setContentText(notificationText(context))
            .setContentIntent(open)
            .addAction(0, "关闭后台常驻", stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 极简通知：只保留进入前台态必须的最小元素。
     *
     * 用于完整通知构建/提交失败时的兜底，保证前台服务仍能合法存在。
     * 小图标用系统内置的 `android.R.drawable.stat_notify_sync` 兜底，
     * 万一项目里的 `ic_notification` 被误删也不会崩。
     */
    internal fun buildMinimalNotification(context: Context): Notification {
        ensureChannel(context)
        val icon = runCatching { R.drawable.ic_notification }
            .getOrElse { android.R.drawable.stat_notify_sync }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("安财校园通")
            .setContentText("后台运行中")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
