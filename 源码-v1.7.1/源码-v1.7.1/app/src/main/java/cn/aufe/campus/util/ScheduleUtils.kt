package cn.aufe.campus.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 课程表公共工具：节次时间表 / 周次解析 / 当前周计算。
 * 供课表页、桌面小卡片、上课提醒共用。
 */
object ScheduleUtils {

    const val MAX_SESSION = 11
    const val MAX_WEEK = 20

    /**
     * 本学期第 1 周的周一。
     * 2026-2027 学年第一学期：8/31 周一开学，10/19 所在周即第 8 周。
     * 实际周次以 [WeekProvider] 为准（教务同步 > 手动校准 > 这个默认值）。
     */
    const val SEMESTER_START = WeekProvider.DEFAULT_ANCHOR

    /** 第 N 节的上下课时间（与学校标准作息一致，第 11 节为晚间延长节） */
    val SESSION_TIMES: List<Pair<String, String>> = listOf(
        "08:00" to "08:45",
        "08:55" to "09:40",
        "10:10" to "10:55",
        "11:05" to "11:50",
        "14:00" to "14:45",
        "14:55" to "15:40",
        "16:10" to "16:55",
        "17:05" to "17:50",
        "19:00" to "19:45",
        "19:55" to "20:40",
        "20:50" to "21:35"
    )

    /** 第 session 节的 (开始, 结束) 时间，越界返回 null */
    fun sessionTime(session: Int): Pair<String, String>? =
        if (session in 1..MAX_SESSION) SESSION_TIMES[session - 1] else null

    /** 解析 "5-18周"、"第9周"、"1-8,10-16周" 等周次描述为区间列表 */
    fun parseWeeks(desc: String): List<IntRange> {
        if (desc.isBlank()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        Regex("(\\d+)\\s*-\\s*(\\d+)\\s*周").findAll(desc).forEach { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@forEach
            val b = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (b >= a) ranges.add(a..b)
        }
        if (ranges.isEmpty()) {
            Regex("第?\\s*(\\d+)\\s*周").findAll(desc).forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { ranges.add(it..it) }
            }
        }
        return ranges
    }

    /** 该时间段是否在指定周次上课（未注明周次默认每周） */
    fun slotInWeek(weekDesc: String, week: Int): Boolean {
        val ranges = parseWeeks(weekDesc)
        if (ranges.isEmpty()) return true
        return ranges.any { week in it }
    }

    /**
     * 当前是第几周。
     *
     * 统一走 [WeekProvider]：教务同步到的周次 > 用户手动校准 > 学期起始日推算，
     * 并且全部以周一为边界滚动，不会因为"学期起始日不是周一"而少算一周。
     */
    fun currentWeek(): Int = WeekProvider.currentWeek()

    /** 今天是星期几（1=周一 … 7=周日） */
    fun todayDay(): Int {
        val cal = Calendar.getInstance()
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 0
        }
    }

    /** 今天第 session 节上课时刻的 Calendar（基于 now 的日期，秒/毫秒归零） */
    fun todaySessionStart(
        session: Int,
        now: Calendar = Calendar.getInstance()
    ): Calendar? {
        val (h, m) = sessionTime(session)?.first?.split(":") ?: return null
        val hi = h.toIntOrNull() ?: return null
        val mi = m.toIntOrNull() ?: return null
        return (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hi)
            set(Calendar.MINUTE, mi)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    /** 日期键 yyyy-MM-dd（用于提醒去重） */
    fun dateKey(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.time)

    /** 星期中文（一~日） */
    fun dayLabel(day: Int): String = when (day) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; 7 -> "日"
        else -> ""
    }
}
