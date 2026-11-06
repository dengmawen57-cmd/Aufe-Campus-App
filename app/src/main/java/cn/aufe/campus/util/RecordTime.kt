package cn.aufe.campus.util

import java.util.Calendar

/**
 * 电费记录的时间解析与格式化。
 *
 * ## 为什么要单独写一层
 *
 * 电费系统 `/use/record`、`/pay/record` 两个页面的时间字段结构很不统一：
 *
 * - 同一条记录里，时间可能被拆进多个嵌套标签
 *   （`<span class="item-title">10-27<span>14:32</span></span>`），
 *   旧实现用 `item-title[^>]*>([^<]*)<` 只能抓到第一个 `<` 之前的内容，
 *   于是界面上只剩下半截时间——这正是「时间显示不详细」的根因。
 * - 不同楼栋/不同版本的页面格式还不一样：`2026-10-27`、`10-27 14:32`、
 *   `2026/10/27`、`2026年10月27日 14:32` 都可能出现。
 *
 * 所以这里做「宽容解析」：能抓到的部分尽量抓，抓不到的按惯例补全
 * （缺年份补当年、缺时间就只显示日期），再由 UI 决定怎么呈现。
 *
 * ## 为什么不用 java.time
 *
 * 本 app minSdk 24，`java.time` 要 API 26 起才有（除非开 desugaring，
 * 会多一个依赖）。项目里其它地方一律用 [Calendar]，这里保持一致。
 */
object RecordTime {

    /** 解析结果。[score] 用于在候选串里挑「最详细」的那一条 */
    data class Parsed(
        val raw: String,
        val hasDate: Boolean,
        val hasTime: Boolean,
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        /** 原始串里是否自带年份（自带的可信度更高，用于候选排序） */
        val yearExplicit: Boolean = false
    ) {
        /** 完整度：有日期 +2，有时刻 +4，原生带年份 +1 */
        val score: Int
            get() = (if (hasDate) 2 else 0) + (if (hasTime) 4 else 0) + (if (yearExplicit) 1 else 0)

        /** `2026-10-27` */
        val dateText: String
            get() = if (!hasDate) "" else "%04d-%02d-%02d".format(year, month, day)

        /** `10-27`（紧凑场景用） */
        val shortDateText: String
            get() = if (!hasDate) "" else "%02d-%02d".format(month, day)

        /** `14:32` / `14:32:10` */
        val timeText: String
            get() = when {
                !hasTime -> ""
                second > 0 -> "%02d:%02d:%02d".format(hour, minute, second)
                else -> "%02d:%02d".format(hour, minute)
            }

        /** `周二` */
        val weekdayText: String
            get() = if (!hasDate) "" else weekdayOf(year, month, day)

        /** `今天` / `昨天` / `3 天前` / `10 月 3 日`；无日期返回空串 */
        val relativeText: String
            get() = if (!hasDate) "" else relativeOf(year, month, day)

        /**
         * 主展示串：`2026-10-27 周二 14:32`
         *
         * 没有时刻时只给日期（不硬编 00:00，避免误导）；
         * 只有时刻没有日期时给 `14:32`。
         */
        val fullText: String
            get() = when {
                hasDate && hasTime -> "$dateText $weekdayText $timeText"
                hasDate -> "$dateText $weekdayText"
                hasTime -> timeText
                else -> raw
            }

        /** 副展示串：相对时间 + 时刻，例如 `今天 14:32` */
        val shortText: String
            get() = when {
                hasDate && hasTime -> "$relativeText $timeText"
                hasDate -> relativeText
                hasTime -> timeText
                else -> raw
            }
    }

    // 带年份：2026-10-27 / 2026/10/27 / 2026.10.27 / 2026年10月27日
    private val DATE_WITH_YEAR =
        Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")

    // 不带年份：10-27 / 10/27 / 10月27日。
    // 这里**故意不接受 `.` 作分隔符**：记录里常混着「3.21度」这类用电量，
    // 允许 `.` 就会把 3.21 度误判成 3 月 21 日，凭空造出一条错误时间。
    private val DATE_NO_YEAR =
        Regex("""(\d{1,2})\s*[-/月]\s*(\d{1,2})\s*日?""")

    // 时刻：14:32 / 14:32:10。两侧加边界，避免从 "1234:56" 里切出 "34:56"
    private val TIME = Regex("""(?<![\d:])(\d{1,2})\s*:\s*(\d{2})(?:\s*:\s*(\d{2}))?(?![\d:])""")

    private val WEEK_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /**
     * 解析一段可能混着日期/时刻的文本。完全解析不出来时返回 null。
     *
     * 注意顺序：先啃带年份的日期，成功就从剩余串里再找时刻；
     * 直接先找无年份日期会把 `2026-10-27` 里的 `10-27` 也匹配上。
     */
    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrBlank()) return null
        var text = raw.replace('\u00A0', ' ').replace('　', ' ').trim()
        if (text.isEmpty()) return null

        var year = -1
        var month = -1
        var day = -1
        var hasDate = false
        var yearExplicit = false

        // 相对日期先处理：文本里有「今天/昨天」时，日期就是它
        val today = Calendar.getInstance()
        when {
            text.contains("今天") || text.contains("今日") -> {
                year = today.get(Calendar.YEAR)
                month = today.get(Calendar.MONTH) + 1
                day = today.get(Calendar.DAY_OF_MONTH)
                hasDate = true
                text = text.replace("今天", " ").replace("今日", " ")
            }
            text.contains("昨天") || text.contains("昨日") -> {
                val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
                year = y.get(Calendar.YEAR)
                month = y.get(Calendar.MONTH) + 1
                day = y.get(Calendar.DAY_OF_MONTH)
                hasDate = true
                text = text.replace("昨天", " ").replace("昨日", " ")
            }
        }

        if (!hasDate) {
            val m = DATE_WITH_YEAR.find(text)
            if (m != null) {
                val (y, mo, d) = m.destructured
                if (validDate(y.toInt(), mo.toInt(), d.toInt())) {
                    year = y.toInt(); month = mo.toInt(); day = d.toInt()
                    hasDate = true; yearExplicit = true
                    text = text.removeRange(m.range)
                }
            }
        }

        if (!hasDate) {
            val m = DATE_NO_YEAR.find(text)
            if (m != null) {
                val (mo, d) = m.destructured
                if (validDate(today.get(Calendar.YEAR), mo.toInt(), d.toInt())) {
                    year = today.get(Calendar.YEAR)
                    month = mo.toInt(); day = d.toInt()
                    hasDate = true
                    text = text.removeRange(m.range)
                }
            }
        }

        var hour = 0
        var minute = 0
        var second = 0
        var hasTime = false
        TIME.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: -1
            val mi = m.groupValues[2].toIntOrNull() ?: -1
            val se = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            if (h in 0..23 && mi in 0..59 && se in 0..59) {
                hour = h; minute = mi; second = se; hasTime = true
            }
        }

        if (!hasDate && !hasTime) return null

        val parsed = Parsed(
            raw = raw.trim(), hasDate = hasDate, hasTime = hasTime,
            year = if (hasDate) year else 1970,
            month = if (hasDate) month else 1,
            day = if (hasDate) day else 1,
            hour = hour, minute = minute, second = second,
            yearExplicit = yearExplicit
        )
        return parsed
    }

    /**
     * 从一段（可能含多个候选的）文本里挑最详细的时间。
     *
     * 用法：把一整条记录的所有文本碎片丢进来，内部按 [Parsed.score] 排序。
     * 这样即使页面把日期放在标题、时刻放在另一个 span 里，也能拼出完整时间。
     */
    fun best(candidates: List<String>): Parsed? =
        candidates.asSequence()
            .mapNotNull { parse(it) }
            .maxByOrNull { it.score * 1000 + it.raw.length }

    /** 把一个时间戳格式化成 `M月d日 HH:mm`（小组件等场景用） */
    fun stamp(at: Long): String {
        if (at <= 0L) return ""
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        return "%d月%d日 %02d:%02d".format(
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        )
    }

    // ---------- 内部 ----------

    private fun validDate(y: Int, m: Int, d: Int): Boolean =
        y in 1970..2100 && m in 1..12 && d in 1..31

    private fun weekdayOf(y: Int, m: Int, d: Int): String = runCatching {
        val cal = Calendar.getInstance()
        cal.isLenient = false
        cal.set(y, m - 1, d, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        WEEK_NAMES[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }.getOrDefault("")

    private fun relativeOf(y: Int, m: Int, d: Int): String = runCatching {
        val that = Calendar.getInstance().apply {
            isLenient = false
            set(y, m - 1, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val days = ((today.timeInMillis - that.timeInMillis) / 86_400_000L).toInt()
        when (days) {
            0 -> "今天"
            1 -> "昨天"
            2 -> "前天"
            in 3..6 -> "$days 天前"
            in -6..-1 -> "${-days} 天后"
            else -> "%d 月 %d 日".format(m, d)
        }
    }.getOrDefault("")
}
