package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.EvalCourse
import cn.aufe.campus.data.model.EvalCourseList
import cn.aufe.campus.data.model.EvalPrepared
import cn.aufe.campus.data.model.EvalResult
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 学生评教（移植自 LoveACE 的 TeacherEvaluationService）。
 *
 * ## 三步 token 链
 *
 * 评教系统怕被脚本刷，做了三级 token，一步都不能省：
 * 1. `teachingEvaluation/evaluation/index` → 页面里的 `tokenValue`（第一级）
 * 2. `teachingEvaluation/evaluationPage`（带上第一级 token）→ 问卷页里的 `tokenValue`（第二级）
 * 3. `assessment?sf_request_type=ajax`（带上第二级 token + 全部答案）
 *
 * ## 首次访问的倒计时保护
 *
 * 第一次进评教页时，学校要求先停留 N 秒（页面里有 `isFirstVisit` 和 `infoTime`）。
 * 这个不能"帮用户跳过"：服务端记了时间，提前交就是无效评价。
 * 所以 [loadCourses] 会把剩余秒数带回去，UI 老老实实等完再提交。
 *
 * ## 评价页停留 2 分钟（v1.9.8 修的死结）
 *
 * 每门课的评价页打开后，学校要求**停留 2 分钟以上**才能提交，否则服务端
 * 直接判无效，而且**不告诉你原因**（`result` 非 success、`msg` 为空）。
 * v1.9.7 及以前是"打开页面 → 立刻填 → 立刻交"，18 门课全部撞在这条规则上，
 * 界面上只剩一句"提交失败（服务端未说明原因）"。
 *
 * 现在拆成 [prepare] / [submit] 两步，中间由 ViewModel 插倒计时（取 140 秒，
 * 2 分钟再加 20 秒余量，避免服务端与本地时钟差一点点又判无效）。
 *
 * ## 答案怎么选
 *
 * - 单选题：80% 概率选「权重为 1.0」的选项（也就是满分项），
 *   余下 20% 在次一档里随机 —— 全填一模一样的选项反而容易被判异常。
 * - 文字题：从几段通顺的评语里随机，去掉换行，太短的丢弃重取。
 */
class EvaluationApi(private val client: AufeClient) {

    private val pageHeaders = mapOf("User-Agent" to MOBILE_UA)
    private val ajaxHeaders = mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to MOBILE_UA
    )

    /** 拉课程列表（含关闭 / 倒计时保护判断） */
    fun loadCourses(): EvalCourseList {
        val html = client.getText(Endpoints.JW_EVAL_INDEX, pageHeaders)

        if (html.contains("评估开关已关闭")) {
            return EvalCourseList(closed = true, closedMessage = "学校当前已关闭评教开关")
        }

        val token = tokenOf(html)
        if (token.isBlank()) throw java.io.IOException("没找到评教 token，可能评教页面已改版")

        val wait = waitSecondsOf(html)
        if (wait > 0) {
            // 首次访问保护：先让界面倒计时，时间到了再拉列表
            return EvalCourseList(waitSeconds = wait)
        }

        val body = client.postFormText(
            Endpoints.JW_EVAL_COURSE_LIST,
            linkedMapOf("optType" to "1", "pagesize" to "50"),
            ajaxHeaders
        )
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw java.io.IOException("评教课程列表返回格式异常")
        val data: JSONArray = root.optJSONArray("data") ?: JSONArray()

        val courses = ArrayList<EvalCourse>(data.length())
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optJSONObject("id") ?: JSONObject()
            val q = o.optJSONObject("questionnaire") ?: JSONObject()
            val course = EvalCourse(
                name = o.optString("evaluationContent", "").trim(),
                teacher = o.optString("evaluatedPeople", "").trim(),
                evaluatedPeople = o.optString("evaluatedPeople", "").trim(),
                evaluatedPeopleNumber = id.optString("evaluatedPeople", "").trim(),
                courseSeq = id.optString("coureSequenceNumber", "").trim(),
                contentNumber = id.optString("evaluationContentNumber", "").trim(),
                questionnaireCode = q.optString("questionnaireNumber", "").trim(),
                questionnaireName = q.optString("questionnaireName", "").trim(),
                evaluated = o.optString("isEvaluated", "").trim() == "是"
            )
            if (course.name.isNotBlank() || course.teacher.isNotBlank() ||
                course.contentNumber.isNotBlank()
            ) {
                courses.add(course)
            }
        }
        return EvalCourseList(waitSeconds = 0, courses = courses)
    }

    /** 首页的最新 token（提交失败后重试用） */
    fun fetchIndexToken(): String = tokenOf(client.getText(Endpoints.JW_EVAL_INDEX, pageHeaders))

    /**
     * 打开这门课的评价页，解析题目并生成答案，返回一份**待提交**的表单。
     *
     * ## 为什么必须和 [submit] 拆成两步（v1.9.8 修复）
     *
     * 教务处的评教页有"阅读时长"校验：`evaluationPage` 每打开一次就记一个时间戳，
     * 服务端要求这张页面停留足够久才承认提交（LoveACE 实测 **140 秒**）。
     * 提前提交的表现是 `result != "success"` 且 **msg 为空** ——
     * 界面上就是那句没有信息量的"提交失败（服务端未说明原因）"。
     *
     * v1.9.7 及以前是"取页面 → 立刻填 → 立刻提交"，两者只隔几十毫秒，
     * 于是 18 门课全军覆没。现在拆开，由 ViewModel 在中间插入倒计时，
     * 这个类只负责"把页面变成一个可提交的表单"。
     *
     * @param pendingCount 待评门数，服务端拿它显示进度；同一批保持同一个值
     */
    fun prepare(course: EvalCourse, pendingCount: Int, indexToken: String): EvalPrepared {
        if (indexToken.isBlank()) throw java.io.IOException("首页 token 为空")

        val pageHtml = client.postFormText(
            Endpoints.JW_EVAL_COURSE_PAGE,
            linkedMapOf(
                "count" to pendingCount.toString(),
                "evaluatedPeople" to course.evaluatedPeople,
                "evaluatedPeopleNumber" to course.evaluatedPeopleNumber,
                "questionnaireCode" to course.questionnaireCode,
                "questionnaireName" to course.questionnaireName,
                "coureSequenceNumber" to course.courseSeq,
                "evaluationContentNumber" to course.contentNumber,
                "evaluationContentContent" to "",
                "tokenValue" to indexToken
            ),
            ajaxHeaders
        )

        val qToken = tokenOf(pageHtml)
        if (qToken.isBlank()) throw java.io.IOException("没找到问卷 token")

        val radios = parseRadios(pageHtml)
        val textareas = parseTextareas(pageHtml)
        if (radios.isEmpty() && textareas.isEmpty()) {
            throw java.io.IOException("没解析到评价题目")
        }

        val form = linkedMapOf(
            "optType" to "submit",
            "tokenValue" to qToken,
            "questionnaireCode" to course.questionnaireCode.ifBlank { inputValue(pageHtml, "wjdm") },
            "evaluationContent" to course.contentNumber.ifBlank { inputValue(pageHtml, "pgnr") },
            "evaluatedPeopleNumber" to
                course.evaluatedPeopleNumber.ifBlank { inputValue(pageHtml, "bprdm") },
            "count" to pendingCount.toString()
        )
        radios.forEach { (name, options) -> form[name] = pickOption(options) }
        textareas.forEachIndexed { index, name -> form[name] = pickText(index) }

        return EvalPrepared(course = course, form = form)
    }

    /**
     * 提交 [prepare] 生成的表单。
     *
     * 注意：返回 `success=false` **不等于**服务器没记下这次评价 ——
     * token 过期、重复提交都会返回失败。所以上层拿到失败后应当
     * 重新拉一次课程列表核对 `isEvaluated`，而不是直接判定失败。
     */
    fun submit(prepared: EvalPrepared): EvalResult {
        val name = prepared.course.name
        val body = client.postFormText(Endpoints.JW_EVAL_SUBMIT, prepared.form, ajaxHeaders)
        val json = runCatching { JSONObject(body) }.getOrNull()
        val result = json?.optString("result", "").orEmpty()
        val ok = result.equals("success", ignoreCase = true)
        val msg = json?.optString("msg", "").orEmpty().ifBlank {
            when {
                ok -> "提交成功"
                json != null -> "提交失败（服务端未说明原因）"
                else -> body.take(80)
            }
        }
        return EvalResult(name, ok, msg)
    }

    // ---------- 解析 ----------

    /** 从页面里取 tokenValue：优先 input，其次脚本里的赋值 */
    private fun tokenOf(html: String): String {
        val tag = Regex(
            """<input[^>]*(?:id|name)\s*=\s*["']tokenValue["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value
        if (tag != null) {
            val v = attr(tag, "value")
            if (v.isNotBlank()) return v
        }
        return Regex("""tokenValue['"]?\s*[:,=]\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1).orEmpty()
    }

    /**
     * 首次访问倒计时：只有"第一次来"且"还没读完须知"才需要等。
     *
     * 两个标志都在页面脚本里（`var isFirstVisit = "1";`），
     * 读不到就当没保护，直接放行。
     */
    private fun waitSecondsOf(html: String): Int {
        val firstVisit = scriptVar(html, "isFirstVisit")
        val finished = scriptVar(html, "isFinished")
        if (firstVisit != "1" || finished == "1") return 0
        val infoTime = inputValue(html, "infoTime").toIntOrNull()
            ?: Regex("""var\s+infoTime\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        return infoTime.coerceIn(0, MAX_WAIT_SECONDS)
    }

    private fun scriptVar(html: String, name: String): String =
        Regex("""var\s+$name\s*=\s*["']([^"']*)["']""").find(html)?.groupValues?.get(1).orEmpty()

    /**
     * 解析单选题：name → 候选值列表（值形如 `10_1.0`，即「分值_权重」）。
     *
     * 页面里同一题的多个 radio 共用一个 name，逐个收集即可。
     */
    private fun parseRadios(html: String): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            if (!attr(tag, "type").equals("radio", ignoreCase = true)) return@forEach
            val name = attr(tag, "name")
            val value = attr(tag, "value")
            if (name.isBlank() || value.isBlank()) return@forEach
            map.getOrPut(name) { mutableListOf() }.add(value)
        }
        return map
    }

    private fun parseTextareas(html: String): List<String> =
        Regex("<textarea\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
            .map { attr(it.value, "name") }
            .filter { it.isNotBlank() }
            .toList()

    private fun inputValue(html: String, name: String): String {
        val tag = Regex(
            """<input[^>]*name\s*=\s*["']$name["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return ""
        return attr(tag, "value")
    }

    private fun attr(tag: String, name: String): String {
        val m = Regex("""$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    // ---------- 选答案 ----------

    /** 80% 选满分项，其余在次一档随机；拿不到权重就随便挑一个 */
    private fun pickOption(options: List<String>): String {
        if (options.isEmpty()) return ""
        val withWeight = options.map { it to weightOf(it) }
        val full = withWeight.filter { it.second >= 0.999 }.map { it.first }
        if (full.isNotEmpty() && Random.nextDouble() < 0.8) return full.random()

        val groups = withWeight.groupBy { it.second }.entries.sortedByDescending { it.key }
        val second = if (full.isNotEmpty()) {
            groups.firstOrNull { it.key < 0.999 }?.value
        } else null
        return (second ?: groups.firstOrNull()?.value ?: withWeight).map { it.first }.random()
    }

    /** 值形如 `10_1.0` 或 `1.0` */
    private fun weightOf(value: String): Double {
        val parts = value.split("_")
        return parts.getOrNull(1)?.toDoubleOrNull()
            ?: parts.getOrNull(0)?.toDoubleOrNull()
            ?: 0.0
    }

    /** 按题目顺序挑评语：第一题总评，第二题启发/建议，后面通用 */
    private fun pickText(index: Int): String {
        val pool = when (index) {
            0 -> overallTexts
            1 -> suggestionTexts
            else -> generalTexts
        }
        repeat(3) {
            val t = pool.random()
            if (t.length >= 4) return t
        }
        return pool.first()
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

        /** 倒计时上限，防止页面写了个荒谬的值让用户干等 */
        private const val MAX_WAIT_SECONDS = 180

        private val overallTexts = listOf(
            "老师授课条理清晰、重点突出，能结合实际帮助我理解课程内容，收获很大",
            "老师备课充分、讲解细致，课堂节奏适中，整体学习体验很好",
            "老师认真负责，课程内容充实，理论与实践结合得比较好，对专业理解帮助明显"
        )
        private val suggestionTexts = listOf(
            "教学效果很好，暂时没有建议，希望老师保持现在的授课方式",
            "整体安排合理，建议后续适当增加课堂互动和案例拓展，帮助同学巩固理解",
            "课程内容讲解清楚，希望可以多留一些答疑时间，方便同学及时解决疑问"
        )
        private val generalTexts = listOf(
            "课程内容对我很有启发，提升了分析和解决问题的能力",
            "老师的讲解让我对这门课有了更系统的认识，课堂案例也很贴近实际",
            "这门课让我收获很多，既理解了知识点，也学会了更有条理地分析问题"
        )
    }
}
