package cn.aufe.campus.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 教学周次来源，课表页 / 桌面小组件 / 上课提醒统一从这里取，保证三处永远一致。
 *
 * 取值优先级：
 * 1. 教务系统（每次打开课表时自动同步，抓到"当前周"就作为权威值，之后按周自然滚动）
 * 2. 本地校准值（用户手动把某一周设为本周后记住的锚点）
 * 3. 默认学期起始周（[DEFAULT_ANCHOR]，没有上面两种时用）
 *
 * 所有计算都以「周一」为边界：跨周就 +1，同周内不变。
 */
object WeekProvider {

    private const val PREF = "week_state"
    private const val KEY_ANCHOR = "anchor_monday"        // 第 1 周周一 yyyy-MM-dd
    private const val KEY_SERVER_WEEK = "server_week"     // 教务同步到的周次
    private const val KEY_SERVER_MONDAY = "server_monday" // 该周次对应的周一
    private const val KEY_SYNC_AT = "sync_at"             // 上次尝试同步时间（毫秒）

    /** 教务同步的最小间隔，避免每次进课表都请求教务 */
    private const val SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 手动校准后的静默期 */
    private const val MANUAL_LOCK_MS = 30L * 24 * 60 * 60 * 1000L

    /** 2026-2027 学年第一学期第 1 周的周一（8/31 起为第 1 周，10/19 那周为第 8 周） */
    const val DEFAULT_ANCHOR = "2026-08-31"

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = runCatching {
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    /** 当前教学周（1..MAX_WEEK） */
    fun currentWeek(): Int {
        val today = mondayOf(Calendar.getInstance())
        val p = sp

        if (p != null) {
            // 1. 教务同步值：按周滚动
            val sw = p.getInt(KEY_SERVER_WEEK, 0)
            val sm = parseDate(p.getString(KEY_SERVER_MONDAY, null))
            if (sw > 0 && sm != null) {
                val w = sw + weeksBetween(mondayOf(sm), today)
                if (w >= 1) return w.coerceAtMost(ScheduleUtils.MAX_WEEK)
            }
            // 2. 手动校准锚点
            val anchor = parseDate(p.getString(KEY_ANCHOR, null))
            if (anchor != null) {
                val w = 1 + weeksBetween(mondayOf(anchor), today)
                if (w >= 1) return w.coerceAtMost(ScheduleUtils.MAX_WEEK)
            }
        }

        // 3. 默认学期起始周
        val def = parseDate(DEFAULT_ANCHOR) ?: return 1
        return (1 + weeksBetween(mondayOf(def), today)).coerceIn(1, ScheduleUtils.MAX_WEEK)
    }

    /** 当前周次的来源，用于在界面上说明 */
    fun sourceLabel(): String {
        val p = sp ?: return "按学期起始日推算"
        if (p.getInt(KEY_SERVER_WEEK, 0) > 0) return "已与教务同步"
        if (p.getString(KEY_ANCHOR, null) != null) return "手动校准"
        return "按学期起始日推算"
    }

    /** 是否到了可以尝试同步的时间（6 小时内不重复请求） */
    fun shouldSync(): Boolean {
        val p = sp ?: return false
        val last = p.getLong(KEY_SYNC_AT, 0L)
        return System.currentTimeMillis() - last > SYNC_INTERVAL_MS
    }

    /** 记录一次同步尝试（成功或失败都记，避免失败后疯狂重试） */
    fun markSyncTried() {
        sp?.edit()?.putLong(KEY_SYNC_AT, System.currentTimeMillis())?.apply()
    }

    /** 教务同步到当前周：以今天所在周为锚点，之后自动 +1 */
    fun saveServerWeek(week: Int) {
        if (week < 1) return
        val p = sp ?: return
        p.edit()
            .putInt(KEY_SERVER_WEEK, week)
            .putString(KEY_SERVER_MONDAY, format(mondayOf(Calendar.getInstance())))
            .putLong(KEY_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 手动校准：把「本周」认定为第 week 周。
     * 校准后 30 天内不再用教务页的值覆盖用户判断（教务页若抓不稳会误判），
     * 期间用户仍可在校准弹窗里「恢复自动」。
     */
    fun setManualWeek(week: Int) {
        if (week < 1) return
        val p = sp ?: return
        val monday = mondayOf(Calendar.getInstance())
        monday.add(Calendar.DAY_OF_YEAR, -(week - 1) * 7)
        p.edit()
            .putString(KEY_ANCHOR, format(monday))
            .remove(KEY_SERVER_WEEK)
            .remove(KEY_SERVER_MONDAY)
            .putLong(KEY_SYNC_AT, System.currentTimeMillis() + MANUAL_LOCK_MS)
            .apply()
    }

    /** 恢复为默认推算（同时清掉手动校准与教务同步值） */
    fun resetToDefault() {
        sp?.edit()
            ?.remove(KEY_ANCHOR)
            ?.remove(KEY_SERVER_WEEK)
            ?.remove(KEY_SERVER_MONDAY)
            ?.remove(KEY_SYNC_AT)
            ?.apply()
    }

    // ---------- 日期工具 ----------

    /** 某个日期所在周的周一（00:00:00.000） */
    fun mondayOf(cal: Calendar): Calendar {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val dow = c.get(Calendar.DAY_OF_WEEK) // 周日=1 … 周六=7
        val offset = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        c.add(Calendar.DAY_OF_YEAR, -offset)
        return c
    }

    /** a→b 相隔几周（四舍五入，b 早于 a 时为负） */
    fun weeksBetween(a: Calendar, b: Calendar): Int {
        val diff = b.timeInMillis - a.timeInMillis
        return Math.round(diff / (7.0 * 24 * 60 * 60 * 1000)).toInt()
    }

    private fun format(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.time)

    private fun parseDate(s: String?): Calendar? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(s) ?: return null
            Calendar.getInstance().apply { time = d }
        }.getOrNull()
    }
}
