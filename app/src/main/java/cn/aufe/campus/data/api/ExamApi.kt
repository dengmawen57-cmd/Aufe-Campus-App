package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.ExamItem
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import org.json.JSONArray
import org.json.JSONObject

/**
 * 考试安排（移植自 LoveACE 的 JWCService.getExamInfo）。
 *
 * ## 为什么要拼两个来源
 *
 * 学校把考试分成两套：
 * - **校统考**：`examPlan/detail` 返回一个 JSON 数组，每项 `title` 里用换行
 *   塞了「课程名 / 时间 / 地点」三段，座位号不在这个接口里，
 *   得先在 `examPlan/index` 那个页面的卡片上抠出来再对上课程名。
 * - **其他考试**（重修、补考等）：`othersExamPlan/queryScores` 返回分页记录，
 *   字段名是拼音缩写（KCM/KSRQ/KSSJ/KSDD/BZ）。
 *
 * 两个来源合并后按「日期 + 时间」排序，用户在同一个列表里看就行。
 */
class ExamApi(private val client: AufeClient) {

    /**
     * 拉当前学期的全部考试。
     *
     * @param termCode 当前学期计划号（形如 2026-2027-1），用于"其他考试"的查询条件；
     *   结尾是 1 表示秋季学期，考试最晚考到次年 3 月，否则到当年 9 月。
     */
    fun loadExams(termCode: String): List<ExamItem> {
        // 本 app minSdk 24，没有 java.time（除非开脱糖），统一用 Calendar
        val cal = java.util.Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        val year = cal.get(java.util.Calendar.YEAR)
        val startDate = fmt.format(cal.time)
        val endDate = if (termCode.endsWith("1")) "${year + 1}-03-30" else "$year-09-30"

        val school = runCatching { loadSchoolExams(startDate, endDate) }.getOrDefault(emptyList())
        val other = runCatching { loadOtherExams(termCode) }.getOrDefault(emptyList())

        if (school.isEmpty() && other.isEmpty()) {
            throw java.io.IOException("没有查到考试安排（可能本学期还没排考）")
        }
        return (school + other).sortedWith(compareBy({ it.date }, { it.time }))
    }

    // ---------- 校统考 ----------

    private fun loadSchoolExams(startDate: String, endDate: String): List<ExamItem> {
        // 座位号在 index 页的卡片里，先抠出来
        val seatInfo = runCatching {
            parseSeatInfo(client.getText(Endpoints.JW_EXAM_PLAN_INDEX))
        }.getOrDefault(emptyList())

        val ts = System.currentTimeMillis()
        val url = "${Endpoints.JW_EXAM_PLAN_DETAIL}?start=$startDate&end=$endDate&_=$ts"
        val body = client.getText(
            url,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).trim()

        // 没排考时服务端会返回一个空数组（甚至只有一个 "]"），按"没有考试"处理
        if (body.isBlank() || body == "]" || body == "[]") return emptyList()

        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ExamItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "")
            if (title.isBlank()) continue
            val lines = title.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val courseName = lines.firstOrNull().orEmpty()
            val seat = seatInfo.firstOrNull { it.first == courseName }?.second.orEmpty()
            out.add(
                ExamItem(
                    courseName = courseName,
                    date = o.optString("start", "").trim(),
                    time = lines.getOrNull(1).orEmpty(),
                    location = lines.drop(2).joinToString(" "),
                    type = "校统考",
                    note = if (seat.isBlank()) "" else "座位号 $seat"
                )
            )
        }
        return out
    }

    /**
     * 从考试计划首页抠「课程名 → 座位号」。
     *
     * 页面结构（LoveACE 里用的 Jsoup 选择器）：
     * `div.widget-box` → `h5.widget-title`（标题里形如「（课程名）」）+ `div.widget-main`（含"座位号："）
     * 这里按 widget-box 切块，再在块内正则匹配，不依赖 DOM 树。
     */
    private fun parseSeatInfo(html: String): List<Pair<String, String>> {
        if (html.isBlank()) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val chunks = html.split("widget-box", ignoreCase = true)
        for (chunk in chunks.drop(1)) {
            // 标题：h5 里的文本，取最后一个括号里的内容作为课程名
            val titleRaw = Regex(
                """<h5[^>]*class\s*=\s*["'][^"']*widget-title[^"']*["'][^>]*>([\s\S]*?)</h5>""",
                RegexOption.IGNORE_CASE
            ).find(chunk)?.groupValues?.get(1)?.let { stripTags(it) }?.trim().orEmpty()
            if (titleRaw.isBlank()) continue
            val courseName = Regex("""[)）]\s*(.+)$""").find(titleRaw)?.groupValues?.get(1)?.trim()
                ?: titleRaw
            val seat = Regex("""座位号\s*[：:]\s*([^\s<]+)""").find(stripTags(chunk))
                ?.groupValues?.get(1)?.trim().orEmpty()
            if (seat.isNotBlank()) result.add(courseName to seat)
        }
        return result
    }

    // ---------- 其他考试 ----------

    private fun loadOtherExams(termCode: String): List<ExamItem> {
        val body = client.postFormText(
            Endpoints.JW_OTHER_EXAM_QUERY,
            linkedMapOf(
                "zxjxjhh" to termCode,
                "tab" to "0",
                "pageNum" to "1",
                "pageSize" to "30"
            )
        )
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val records: JSONArray = root.optJSONArray("records")
            ?: root.optJSONObject("list")?.optJSONArray("records")
            ?: return emptyList()

        val out = ArrayList<ExamItem>(records.length())
        for (i in 0 until records.length()) {
            val arr = records.optJSONArray(i)
            if (arr != null) {
                // 数组式返回：课程名 / 日期 / 时间 / 地点 / 备注 的固定位置
                if (arr.length() < 8) continue
                out.add(
                    ExamItem(
                        courseName = arr.optString(2, "").trim(),
                        date = arr.optString(4, "").trim(),
                        time = arr.optString(5, "").trim(),
                        location = arr.optString(6, "").trim(),
                        type = "其他考试",
                        note = arr.optString(7, "").trim()
                    )
                )
                continue
            }
            val o = records.optJSONObject(i) ?: continue
            val name = o.optString("KCM", "").trim()
            val date = o.optString("KSRQ", "").trim()
            val time = o.optString("KSSJ", "").trim()
            if (name.isEmpty() && date.isEmpty() && time.isEmpty()) continue
            out.add(
                ExamItem(
                    courseName = name,
                    date = date,
                    time = time,
                    location = o.optString("KSDD", "").trim(),
                    type = "其他考试",
                    note = o.optString("BZ", "").trim()
                )
            )
        }
        return out
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()
}
