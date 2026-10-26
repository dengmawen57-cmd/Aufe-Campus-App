package cn.aufe.campus.widget

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.aufe.campus.MainActivity
import cn.aufe.campus.R
import cn.aufe.campus.util.ScheduleUtils
import java.util.Calendar

/** 上课提醒设置存储 */
object ReminderPrefs {
    const val PREFS = "aufe_notify"
    const val KEY_ENABLED = "enabled"
    const val KEY_ADVANCE = "advance_minutes"
    const val KEY_FIRED = "fired_keys"
    const val DEFAULT_ADVANCE = 10

    /** 可选的提前提醒分钟数 */
    val ADVANCE_OPTIONS = listOf(5, 10, 15, 20, 30, 60)
}

/** 提醒闹钟调度：每 2 分钟检测一次（低功耗，分钟级精度足够） */
object ClassReminderScheduler {

    const val ACTION_TICK = "cn.aufe.campus.action.CLASS_REMINDER_TICK"
    const val INTERVAL_MS = 120_000L

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + INTERVAL_MS,
            INTERVAL_MS,
            tickPendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(tickPendingIntent(context))
    }

    private fun tickPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            100,
            Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** 上课提醒接收器：定时检测 / 开机后恢复调度 */
class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ClassReminderScheduler.ACTION_TICK -> ReminderEngine.check(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                val prefs = context.getSharedPreferences(ReminderPrefs.PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(ReminderPrefs.KEY_ENABLED, false)) {
                    ClassReminderScheduler.schedule(context)
                }
            }
        }
    }
}

/** 提醒引擎：根据课表数据计算"即将上课"并发通知 */
object ReminderEngine {

    private const val CHANNEL_ID = "class_reminder"

    /**
     * 每次 tick 调用：
     * 1. 读取课表数据（WidgetDataStore 中持久化的课程）
     * 2. 找出今天（且在当前周次）即将开始的课程
     * 3. 上课前 advance 分钟落入本检测周期的，发通知（同一天同一课程只发一次）
     */
    fun check(context: Context) {
        val prefs = context.getSharedPreferences(ReminderPrefs.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ReminderPrefs.KEY_ENABLED, false)) return
        val advance = prefs.getInt(ReminderPrefs.KEY_ADVANCE, ReminderPrefs.DEFAULT_ADVANCE)
        val courses = WidgetDataStore.load(context)
        if (courses.isEmpty()) return

        val now = Calendar.getInstance()
        val today = ScheduleUtils.todayDay()
        val week = ScheduleUtils.currentWeek()
        val dateKey = ScheduleUtils.dateKey(now)
        val fired = prefs.getStringSet(ReminderPrefs.KEY_FIRED, emptySet())!!.toMutableSet()
        // 只保留今天的去重键，避免无限增长
        val cleaned = fired.filter { it.startsWith(dateKey) }.toMutableSet()
        var changed = cleaned.size != fired.size

        courses.forEach course@{ c ->
            c.slots
                .filter { it.day == today && ScheduleUtils.slotInWeek(it.weekDesc, week) }
                .forEach slot@{ s ->
                    val startCal = ScheduleUtils.todaySessionStart(s.startSession, now) ?: return@slot
                    val startMillis = startCal.timeInMillis
                    val fireAt = startMillis - advance * 60_000L
                    val delta = fireAt - now.timeInMillis
                    // 落在本检测周期内（放宽到 2 倍周期容忍 tick 漂移）
                    if (delta in 0..(ClassReminderScheduler.INTERVAL_MS * 2)) {
                        val remaining = (startMillis - now.timeInMillis + 59_999) / 60_000
                        if (remaining < 1) return@slot // 已上课，不再提醒
                        val key = "$dateKey|${c.courseNo}|${s.startSession}"
                        if (key !in cleaned) {
                            cleaned.add(key)
                            changed = true
                            notify(context, c.courseName, s.startSession, s.place, remaining)
                        }
                    }
                }
        }
        if (changed) prefs.edit().putStringSet(ReminderPrefs.KEY_FIRED, cleaned).apply()
    }

    private fun notify(
        context: Context,
        courseName: String,
        session: Int,
        place: String,
        remainingMinutes: Long
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "下节课即将开始的提醒"
                }
            )
        }
        val time = ScheduleUtils.sessionTime(session)
        val timeText = time?.let { "${it.first}-${it.second}" } ?: ""
        val placeText = place.ifBlank { "地点待定" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("即将上课：${courseName.ifBlank { "课程" }}")
            .setContentText("第${session}节 $timeText · $placeText · 约${remainingMinutes}分钟后上课")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(WidgetDataStore.openAppIntent(context))
            .build()
        nm.notify((courseName + session).hashCode(), notification)
    }
}
