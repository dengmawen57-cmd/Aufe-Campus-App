package cn.aufe.campus.util

import android.content.Context
import cn.aufe.campus.data.model.ScoreRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成绩导出为 CSV。
 *
 * 用途很实际：评奖学金、保研材料、给家里看，都需要把成绩单拿出来。
 *
 * 三个细节：
 * - 写入 UTF-8 **带 BOM**：不加 BOM 的 CSV 在中文 Windows 的 Excel 里会整片乱码；
 * - Android 10+ 走 MediaStore（不需要存储权限）；
 * - Android 9 及以下分两种：有存储权限就写公共下载目录，
 *   没授权就退到应用专属目录（`Android/data/包名/files/Download`），
 *   不为了导一张表去弹权限框——把落点如实告诉用户就行。
 */
object ScoreCsvExporter {

    private const val BOM = "\uFEFF"

    /** 返回保存位置的可读描述；空串表示失败。 */
    fun export(context: Context, termName: String, records: List<ScoreRecord>): String {
        if (records.isEmpty()) return ""
        val content = buildCsv(termName, records)
        val fileName = buildFileName(termName)
        return runCatching { write(context, fileName, content) }.getOrDefault("")
    }

    private fun buildCsv(termName: String, records: List<ScoreRecord>): String {
        val sb = StringBuilder(BOM)
        sb.append("学期,").append(escape(termName)).append("\r\n")
        sb.append("课程名称,课程号,课序号,学分,课程属性,考核方式,成绩,绩点,是否通过,未通过原因\r\n")
        records.forEach { r ->
            sb.append(
                listOf(
                    escape(r.courseName),
                    escape(r.courseNo),
                    escape(r.classSeq),
                    fmtNum(r.credit),
                    escape(r.property),
                    escape(r.examType),
                    escape(r.score),
                    escape(r.gpa),
                    if (r.passed) "是" else "否",
                    escape(r.failReason)
                ).joinToString(",")
            ).append("\r\n")
        }
        // 末尾给一行小计，方便直接贴进表格
        val credits = records.sumOf { it.credit }
        val passed = records.count { it.passed }
        val avg = records.mapNotNull { it.score.toDoubleOrNull() }
            .let { if (it.isEmpty()) null else it.average() }
        sb.append("\r\n")
        sb.append("合计,课程 ").append(records.size).append(" 门")
            .append(",学分 ").append(fmtNum(credits))
            .append(",通过 ").append(passed).append(" 门")
            .append(",平均分 ").append(avg?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "—")
            .append("\r\n")
        return sb.toString()
    }

    private fun write(context: Context, fileName: String, content: String): String =
        TextFileSaver.save(context, fileName, "text/csv", content)

    private fun buildFileName(termName: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val safeTerm = termName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
            .ifBlank { "成绩" }
        return "安财成绩_${safeTerm}_$stamp.csv"
    }

    private fun fmtNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.CHINA, "%.1f", v)

    /** CSV 转义：含逗号/引号/换行的字段必须用双引号包住，内部引号翻倍 */
    private fun escape(raw: String): String {
        val s = raw.trim()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }
}
