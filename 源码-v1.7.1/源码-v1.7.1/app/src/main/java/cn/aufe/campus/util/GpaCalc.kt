package cn.aufe.campus.util

import cn.aufe.campus.data.model.ScoreRecord

/**
 * 绩点 / 均分计算。
 *
 * 口径：
 * - 优先用教务直接下发的「绩点」列（成绩单里就有），只有缺失时才按分数换算：
 *   百分制 → (分数 - 50) / 10，即 60 分 = 1.0、90 分 = 4.0、100 = 5.0（五分制）
 * - 等级制成绩：优秀 4.5 / 良好 3.5 / 中等 2.5 / 及格 1.5 / 不及格 0
 * - GPA = Σ(绩点 × 学分) / Σ学分（学分加权，这是教务处的标准算法）
 * - 加权平均分 = Σ(分数 × 学分) / Σ学分
 * - 算术平均分 = 有百分制分数的课程简单平均
 *
 * 注意：这里只做"给定一批课程记录"的统计，是否剔除挂科 / 只看必修由调用方先过滤。
 */
object GpaCalc {

    /** 五分制满绩 */
    const val MAX_POINT = 5.0

    data class Stats(
        val gpa: Double = 0.0,
        val weightedScore: Double = 0.0,
        val averageScore: Double = 0.0,
        val credits: Double = 0.0,
        val courseCount: Int = 0,
        val passedCount: Int = 0,
        val failCount: Int = 0
    ) {
        val hasGpa: Boolean get() = courseCount > 0
    }

    /**
     * 单门课绩点；无绩点也无可用分数时返回 null（该课不参与计算）。
     *
     * 教务偶尔会把绩点写成 0（其实是"未计算"），这种如果分数及格，
     * 用分数换算补上，避免把一门 85 分的课当成 0 绩点拉低整体。
     */
    fun pointOf(r: ScoreRecord): Double? {
        val fromGpa = r.gpa.trim().toDoubleOrNull()?.coerceIn(0.0, MAX_POINT)
        val fromScore = gradePoint(r.score)?.coerceIn(0.0, MAX_POINT)
        return when {
            fromGpa != null && fromGpa > 0.0 -> fromGpa
            fromGpa == 0.0 && fromScore != null && fromScore > 0.0 -> fromScore
            else -> fromGpa ?: fromScore
        }
    }

    /** 单门课百分制分数；等级制成绩返回 null */
    fun scoreOf(r: ScoreRecord): Double? = r.score.trim().toDoubleOrNull()

    /** 等级制 / 百分制统一转绩点 */
    private fun gradePoint(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return ((it - 50) / 10).coerceIn(0.0, MAX_POINT) }
        return when {
            s.contains("优秀") || s == "优" -> 4.5
            s.contains("良好") || s == "良" -> 3.5
            s.contains("中等") || s == "中" -> 2.5
            s.contains("不及格") || s.contains("不合格") || s.contains("未通过") -> 0.0
            s.contains("及格") || s.contains("合格") || s.contains("通过") -> 1.5
            else -> null
        }
    }

    /** 统计一批课程 */
    fun stats(list: List<ScoreRecord>): Stats {
        var pointCredit = 0.0   // Σ(绩点×学分)
        var scoreCredit = 0.0   // Σ(分数×学分)
        var creditSum = 0.0     // Σ学分（参与绩点计算，含等级制课程）
        var scoreCreditSum = 0.0 // Σ学分（只算有百分制分数的课程，加权均分的分母）
        var scoreSum = 0.0      // 分数和（算术平均用）
        var scoreCount = 0
        var courseCount = 0
        var passedCount = 0
        var failCount = 0

        for (r in list) {
            if (r.passed) passedCount++ else if (r.score.isNotBlank()) failCount++
            val credit = r.credit
            if (credit <= 0.0) continue
            val point = pointOf(r) ?: continue
            pointCredit += point * credit
            creditSum += credit
            courseCount++
            // 等级制（优秀/合格）没有百分制分数，不能进加权均分的分母
            scoreOf(r)?.let {
                scoreCredit += it * credit
                scoreCreditSum += credit
                scoreSum += it
                scoreCount++
            }
        }

        return Stats(
            gpa = if (creditSum > 0) pointCredit / creditSum else 0.0,
            weightedScore = if (scoreCreditSum > 0) scoreCredit / scoreCreditSum else 0.0,
            averageScore = if (scoreCount > 0) scoreSum / scoreCount else 0.0,
            credits = creditSum,
            courseCount = courseCount,
            passedCount = passedCount,
            failCount = failCount
        )
    }

    /** 按学期统计，学期倒序（新的在前） */
    fun byTerm(list: List<ScoreRecord>): List<Pair<String, Stats>> =
        list.groupBy { it.term.ifBlank { "其他学期" } }
            .map { (term, group) -> term to stats(group) }
            .filter { it.second.hasGpa }
            .sortedByDescending { it.first }

    /** 绩点分布：档位名 → 课程门数 */
    fun distribution(list: List<ScoreRecord>): List<Pair<String, Int>> {
        val buckets = linkedMapOf(
            "4.5 以上" to 0,
            "3.5 - 4.5" to 0,
            "2.5 - 3.5" to 0,
            "1.5 - 2.5" to 0,
            "1.5 以下" to 0
        )
        val keys = buckets.keys.toList()
        for (r in list) {
            if (r.credit <= 0.0) continue
            val p = pointOf(r) ?: continue
            val idx = when {
                p >= 4.5 -> 0
                p >= 3.5 -> 1
                p >= 2.5 -> 2
                p >= 1.5 -> 3
                else -> 4
            }
            buckets[keys[idx]] = (buckets[keys[idx]] ?: 0) + 1
        }
        return buckets.toList()
    }

    /** 课程属性归类：必修 / 选修 / 其他 */
    fun categoryOf(r: ScoreRecord): String {
        val p = r.property
        return when {
            p.contains("必修") -> "必修"
            p.contains("选修") -> "选修"
            else -> "其他"
        }
    }
}
