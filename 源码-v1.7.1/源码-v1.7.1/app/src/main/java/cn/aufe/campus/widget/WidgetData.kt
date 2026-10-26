package cn.aufe.campus.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import cn.aufe.campus.util.ScheduleUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 桌面小组件的数据层（对齐 LoveACE 的 SemesterWidgetData）。
 *
 * 设计要点：
 * - 小组件运行在 launcher 进程，**只读本地缓存**（WidgetDataStore），绝不联网；
 * - 所有时间计算用 Calendar（本 app minSdk 24，没有 java.time）；
 * - 配色与 App 内课表页同一套（按课程名哈希取色），两处视觉一致。
 */

// ==================== 课程条目 ====================

/** 小组件里的一节课（已按当前周过滤） */
data class WidgetCourseEntry(
    val courseName: String,
    val location: String,
    val teacherName: String = "",
    val sessionStart: Int,
    val sessionEnd: Int,
    val timeText: String,      // "08:00-09:40"
    val sessionText: String,   // "1-2节"
    val colorIndex: Int = 0,
    /** 今天已上完（仅今日课程计算，用于弱化显示） */
    val finished: Boolean = false,
    /** 正在上课 */
    val ongoing: Boolean = false
) {
    val rowSpan: Int get() = (sessionEnd - sessionStart + 1).coerceAtLeast(1)
}

// ==================== 配色（与课表页一致） ====================

data class WidgetCourseColor(val bg: Color, val bar: Color, val text: Color)

/** 与 ui/schedule/ScheduleScreen.kt 的 BLOCK_COLORS 保持一致 */
private val BLOCK_COLORS = listOf(
    Color(0xFF1A5FB4), Color(0xFF00897B), Color(0xFF8E24AA), Color(0xFFE64A19),
    Color(0xFF3949AB), Color(0xFFC2185B), Color(0xFF00796B), Color(0xFF5D4037),
    Color(0xFF0288D1), Color(0xFF689F38)
)

private fun buildPalette(dark: Boolean): List<WidgetCourseColor> = BLOCK_COLORS.map { base ->
    if (dark) {
        WidgetCourseColor(
            bg = lerp(base, Color(0xFF12151A), 0.78f),
            bar = lerp(base, Color.White, 0.30f),
            text = lerp(base, Color.White, 0.60f)
        )
    } else {
        WidgetCourseColor(
            bg = lerp(base, Color.White, 0.87f),
            bar = base,
            text = lerp(base, Color.Black, 0.12f)
        )
    }
}

val WIDGET_COLORS_LIGHT: List<WidgetCourseColor> by lazy { buildPalette(false) }
val WIDGET_COLORS_DARK: List<WidgetCourseColor> by lazy { buildPalette(true) }

fun widgetColors(dark: Boolean): List<WidgetCourseColor> =
    if (dark) WIDGET_COLORS_DARK else WIDGET_COLORS_LIGHT

// ==================== 组件状态 ====================

sealed class WidgetState {

    /** 学期中且有课 */
    data class InSession(
        val currentWeek: Int,
        val totalWeeks: Int,
        val dayOfWeek: Int,
        val dayOfWeekText: String,
        val dateText: String,
        val weekDays: List<WeekDayInfo>,
        val isEnding: Boolean,
        val todayCourses: List<WidgetCourseEntry>,
        val weekCourses: Map<Int, List<WidgetCourseEntry>>,
        val hasCourseData: Boolean
    ) : WidgetState()

    /** 学期中（或有课表缓存），但今天没课 */
    data class NoCourseToday(
        val currentWeek: Int,
        val totalWeeks: Int,
        val dayOfWeek: Int,
        val dayOfWeekText: String,
        val dateText: String,
        val weekDays: List<WeekDayInfo>,
        val isEnding: Boolean,
        val weekCourses: Map<Int, List<WidgetCourseEntry>>,
        val nextCourse: WidgetCourseEntry? = null,
        val nextCourseDay: String? = null,
        val termOver: Boolean = false
    ) : WidgetState()

    /** 还没开学 */
    data class BeforeTerm(
        val daysUntilStart: Long,
        val startDateText: String
    ) : WidgetState()

    /** 本地还没有课表缓存，需要先打开 App 拉一次 */
    data object NoCache : WidgetState()

    data object Error : WidgetState()
}

/** 星期指示行的一项 */
data class WeekDayInfo(
    val dayOfWeek: String,
    val dayOfMonth: Int,
    val isToday: Boolean,
    val isPast: Boolean,
    val courseCount: Int = 0
)

// ==================== 一周网格 ====================

data class WidgetGridCell(val entry: WidgetCourseEntry, val rowSpan: Int)

/** day-session -> 单元格；同一节课覆盖的所有行都指向同一个单元格 */
fun buildWidgetGrid(weekCourses: Map<Int, List<WidgetCourseEntry>>): Map<String, WidgetGridCell> {
    val grid = mutableMapOf<String, WidgetGridCell>()
    weekCourses.forEach { (day, entries) ->
        entries.forEach { entry ->
            val cell = WidgetGridCell(entry, entry.rowSpan)
            for (s in entry.sessionStart..entry.sessionEnd) {
                grid["$day-$s"] = cell
            }
        }
    }
    return grid
}

/** 网格需要渲染到第几节（无课时至少 8 节，让版面不空） */
fun widgetGridMaxSession(grid: Map<String, WidgetGridCell>): Int {
    var max = 0
    grid.values.forEach { if (it.entry.sessionEnd > max) max = it.entry.sessionEnd }
    return max.coerceIn(8, ScheduleUtils.MAX_SESSION)
}

// ==================== 节日/时间工具 ====================

/** 小组件宿主是否深色模式（不依赖 API 30 的 isNightModeActive） */
internal fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val WEEKDAY_SHORT = arrayOf("一", "二", "三", "四", "五", "六", "日")

/** "08:00-09:40"；缺数据时退化为节次文本 */
fun sessionTimeRange(start: Int, end: Int): String {
    val s = ScheduleUtils.sessionTime(start)?.first.orEmpty()
    val e = ScheduleUtils.sessionTime(end)?.second.orEmpty()
    return if (s.isNotEmpty() && e.isNotEmpty()) "$s-$e" else "第${start}节"
}

private fun sessionTextOf(start: Int, end: Int): String =
    if (end > start) "$start-${end}节" else "${start}节"

private fun minutesOf(hhmm: String): Int {
    val h = hhmm.substringBefore(":").toIntOrNull() ?: return -1
    val m = hhmm.substringAfter(":", "0").toIntOrNull() ?: 0
    return h * 60 + m
}

private fun nowMinutes(now: Calendar): Int =
    now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

private fun dateTextOf(c: Calendar): String =
    "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"

// ==================== 状态计算 ====================

private fun totalWeeksOf(courses: List<WidgetDataStore.WidgetCourse>): Int {
    var max = 0
    courses.forEach { c ->
        c.slots.forEach { s ->
            ScheduleUtils.parseWeeks(s.weekDesc).forEach { r -> if (r.last > max) max = r.last }
        }
    }
    return if (max <= 0) 18 else max.coerceIn(8, ScheduleUtils.MAX_WEEK)
}

/** 课程颜色索引：按课表顺序稳定分配（与课表页哈希取色不冲突，这里保持"同课同色"） */
private fun buildColorMap(courses: List<WidgetDataStore.WidgetCourse>): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    var idx = 0
    courses.forEach { c ->
        val key = colorKey(c)
        if (key !in map) {
            map[key] = idx % BLOCK_COLORS.size
            idx++
        }
    }
    return map
}

private fun colorKey(c: WidgetDataStore.WidgetCourse): String =
    if (c.courseNo.isNotBlank()) c.courseNo else c.courseName

/**
 * 取某一天在当前周的所有课。
 * @param markLive 是否标记"已结束/正在上"（只对今天有意义）
 */
private fun entriesFor(
    courses: List<WidgetDataStore.WidgetCourse>,
    week: Int,
    day: Int,
    colorMap: Map<String, Int>,
    now: Calendar?,
    markLive: Boolean
): List<WidgetCourseEntry> {
    val nowMin = now?.let { nowMinutes(it) } ?: -1
    val out = mutableListOf<WidgetCourseEntry>()
    courses.forEach { c ->
        c.slots.forEach slot@{ s ->
            if (s.day != day) return@slot
            if (!ScheduleUtils.slotInWeek(s.weekDesc, week)) return@slot
            val start = s.startSession.coerceIn(1, ScheduleUtils.MAX_SESSION)
            val end = (s.startSession + s.sessionCount - 1)
                .coerceIn(start, ScheduleUtils.MAX_SESSION)

            var finished = false
            var ongoing = false
            if (markLive && nowMin >= 0) {
                val startMin = minutesOf(ScheduleUtils.sessionTime(start)?.first.orEmpty())
                val endMin = minutesOf(ScheduleUtils.sessionTime(end)?.second.orEmpty())
                if (startMin >= 0 && endMin >= 0) {
                    finished = nowMin >= endMin
                    ongoing = nowMin in startMin until endMin
                }
            }

            out.add(
                WidgetCourseEntry(
                    courseName = c.courseName.ifBlank { "课程" },
                    location = s.place,
                    teacherName = c.teacher,
                    sessionStart = start,
                    sessionEnd = end,
                    timeText = sessionTimeRange(start, end),
                    sessionText = sessionTextOf(start, end),
                    colorIndex = colorMap[colorKey(c)] ?: 0,
                    finished = finished,
                    ongoing = ongoing
                )
            )
        }
    }
    return out.sortedBy { it.sessionStart }
}

private fun buildWeekDays(
    now: Calendar,
    today: Int,
    weekCourses: Map<Int, List<WidgetCourseEntry>>
): List<WeekDayInfo> {
    val monday = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_MONTH, -(today - 1))
    }
    return (0..6).map { offset ->
        val d = (monday.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
        val dow = offset + 1
        WeekDayInfo(
            dayOfWeek = WEEKDAY_SHORT[offset],
            dayOfMonth = d.get(Calendar.DAY_OF_MONTH),
            isToday = dow == today,
            isPast = dow < today,
            courseCount = weekCourses[dow]?.size ?: 0
        )
    }
}

/** 今天没课时，找下一节：先找本周后面的日子，再找下一周 */
private fun findNextCourse(
    courses: List<WidgetDataStore.WidgetCourse>,
    week: Int,
    today: Int,
    totalWeeks: Int,
    colorMap: Map<String, Int>
): Pair<WidgetCourseEntry, String>? {
    for (d in (today + 1)..7) {
        val list = entriesFor(courses, week, d, colorMap, null, false)
        if (list.isNotEmpty()) return list.first() to WEEKDAY_NAMES[d - 1]
    }
    if (week < totalWeeks) {
        for (d in 1..7) {
            val list = entriesFor(courses, week + 1, d, colorMap, null, false)
            if (list.isNotEmpty()) return list.first() to "下${WEEKDAY_NAMES[d - 1]}"
        }
    }
    return null
}

private fun semesterStartCal(): Calendar? = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        .parse(ScheduleUtils.SEMESTER_START)
        ?.let { d ->
            (Calendar.getInstance().clone() as Calendar).apply {
                time = d
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }
} catch (_: Exception) {
    null
}

private fun startOfDay(c: Calendar): Calendar = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

private fun computeWidgetState(courses: List<WidgetDataStore.WidgetCourse>): WidgetState {
    val now = Calendar.getInstance()
    val totalWeeks = totalWeeksOf(courses)
    val today = ScheduleUtils.todayDay().let { if (it in 1..7) it else 1 }

    // 还没开学
    semesterStartCal()?.let { start ->
        val todayZero = startOfDay(now)
        if (todayZero.before(start)) {
            val days = ((start.timeInMillis - todayZero.timeInMillis) / 86_400_000L).coerceAtLeast(0)
            return WidgetState.BeforeTerm(
                daysUntilStart = days,
                startDateText = ScheduleUtils.SEMESTER_START
            )
        }
    }

    val week = ScheduleUtils.currentWeek()
    val termOver = week > totalWeeks
    val colorMap = buildColorMap(courses)

    val todayCourses = if (termOver) {
        emptyList()
    } else {
        entriesFor(courses, week, today, colorMap, now, markLive = true)
    }
    val weekCourses = (1..7).associateWith { d ->
        if (termOver) emptyList()
        else entriesFor(courses, week, d, colorMap, now, markLive = d == today)
    }
    val weekDays = buildWeekDays(now, today, weekCourses)
    val isEnding = totalWeeks - week in 0..2

    val commonWeek = week.coerceAtLeast(1)

    if (todayCourses.isEmpty()) {
        val next = if (termOver) null
        else findNextCourse(courses, commonWeek, today, totalWeeks, colorMap)
        return WidgetState.NoCourseToday(
            currentWeek = commonWeek,
            totalWeeks = totalWeeks,
            dayOfWeek = today,
            dayOfWeekText = WEEKDAY_NAMES[today - 1],
            dateText = dateTextOf(now),
            weekDays = weekDays,
            isEnding = isEnding || termOver,
            weekCourses = weekCourses,
            nextCourse = next?.first,
            nextCourseDay = next?.second,
            termOver = termOver
        )
    }

    return WidgetState.InSession(
        currentWeek = commonWeek,
        totalWeeks = totalWeeks,
        dayOfWeek = today,
        dayOfWeekText = WEEKDAY_NAMES[today - 1],
        dateText = dateTextOf(now),
        weekDays = weekDays,
        isEnding = isEnding,
        todayCourses = todayCourses,
        weekCourses = weekCourses,
        hasCourseData = courses.isNotEmpty()
    )
}

/** 小组件入口：从本地缓存拼出当前应该展示的状态 */
fun fetchWidgetState(context: Context): WidgetState = try {
    val courses = WidgetDataStore.load(context)
    if (courses.isEmpty()) WidgetState.NoCache else computeWidgetState(courses)
} catch (_: Throwable) {
    WidgetState.Error
}
