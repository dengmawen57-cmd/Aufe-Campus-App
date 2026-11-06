package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.AcademicInfo
import cn.aufe.campus.data.model.CourseSlot
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.data.model.ScoreDetailItem
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.data.model.SelectableCourse
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.data.model.TermItem
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import org.json.JSONArray
import org.json.JSONObject

/** 本科综合教务系统（URP）API */
class JwApi(private val client: AufeClient) {

    data class CourseListResult(
        val selected: List<SelectedCourse> = emptyList(),
        val planCourses: List<SelectableCourse> = emptyList(),
        val inSelectionPhase: Boolean = false,
        val message: String = ""
    )

    /** 查询课程表 / 方案内选课列表 */
    fun fetchCourseList(
        courseNo: String = "",
        courseName: String = "",
        categoryCode: String = "",
        propertyCode: String = ""
    ): CourseListResult {
        val params = buildMap {
            put("fajhh", "")
            put("jhxn", "")
            put("kcsxdm", propertyCode)
            put("kch", courseNo)
            put("kcm", courseName)
            put("kclbdm", categoryCode)
            put("xqh", "")
            put("xq", "0")
            put("jc", "0")
        }
        val text = postWithSessionRetry(Endpoints.JW_COURSE_LIST, params)
        return parseCourseList(text, fromPlan = true)
    }

    /**
     * 首次请求偶发 HTTP 500（会话刚重建 / JSESSIONID 失效）时，
     * 先访问一次教务入口页刷新会话，再重试一次。
     */
    private fun postWithSessionRetry(url: String, params: Map<String, String>): String = try {
        client.postFormText(url, params)
    } catch (e: Exception) {
        runCatching { client.get(Endpoints.JW_SCHEDULE_PAGE).close() }
        client.postFormText(url, params)
    }

    /** 自由选课（通识选修课等）列表 */
    fun fetchFreeCourseList(
        courseNo: String = "",
        courseName: String = "",
        teacher: String = "",
        categoryCode: String = "",
        deptCode: String = ""
    ): CourseListResult {
        val params = buildMap {
            put("kkxsh", deptCode)
            put("kch", courseNo)
            put("kcm", courseName)
            put("skjs", teacher)
            put("xq", "0")
            put("jc", "0")
            put("kclbdm", categoryCode)
        }
        val text = postWithSessionRetry(Endpoints.JW_FREE_COURSE_LIST, params)
        return parseCourseList(text, fromPlan = false)
    }

    private fun parseCourseList(text: String, fromPlan: Boolean): CourseListResult {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return CourseListResult(message = "数据解析失败：${e.message}")
        }
        // 已选课程（课表）
        val selected = mutableListOf<SelectedCourse>()
        val yxkcRaw = json.optString("yxkclist", "")
        if (yxkcRaw.isNotBlank() && yxkcRaw != "null") {
            try {
                val arr = JSONArray(yxkcRaw)
                for (i in 0 until arr.length()) {
                    parseSelectedCourse(arr.getJSONObject(i))?.let { selected.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        // 可选课程
        val listKey = if (fromPlan) "rwfalist" else "rwRxkZlList"
        val courses = mutableListOf<SelectableCourse>()
        val rawList = json.opt(listKey)
        if (rawList is JSONArray) {
            // 同一课程多个时间条目，按 课程号@课序号@学期 分组
            val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
            for (i in 0 until rawList.length()) {
                val o = rawList.optJSONObject(i) ?: continue
                val key = if (fromPlan) {
                    "${o.optString("courseNum")}@${o.optString("classNum")}@${o.optString("termCode")}"
                } else {
                    "${o.optString("kch")}@${o.optString("kxh")}@${o.optString("zxjxjhh")}"
                }
                grouped.getOrPut(key) { mutableListOf() }.add(o)
            }
            grouped.forEach { (_, objs) ->
                parseSelectableCourse(objs, fromPlan)?.let { courses.add(it) }
            }
        }
        val inPhase = courses.isNotEmpty()
        return CourseListResult(
            selected = selected,
            planCourses = courses,
            inSelectionPhase = inPhase,
            message = if (!inPhase) "当前为非选课阶段，暂无可选课程" else ""
        )
    }

    private fun parseSelectedCourse(o: JSONObject): SelectedCourse? = try {
        val id = o.optJSONObject("id") ?: JSONObject()
        val slots = mutableListOf<CourseSlot>()
        val tap = o.optJSONArray("timeAndPlaceList")
        if (tap != null) {
            for (i in 0 until tap.length()) {
                val t = tap.optJSONObject(i) ?: continue
                slots.add(
                    CourseSlot(
                        weekDesc = t.optString("weekDescription"),
                        day = t.optInt("classDay"),
                        startSession = t.optInt("classSessions"),
                        sessionCount = t.optInt("continuingSession", 1),
                        campus = t.optString("campusName"),
                        building = t.optString("teachingBuildingName"),
                        room = t.optString("classroomName")
                    )
                )
            }
        }
        SelectedCourse(
            courseNo = id.optString("coureNumber"),
            classSeq = id.optString("coureSequenceNumber"),
            termCode = id.optString("executiveEducationPlanNumber"),
            courseName = o.optString("courseName"),
            credit = o.optDouble("unit", 0.0),
            category = o.optString("courseCategoryName"),
            examType = o.optString("examTypeName"),
            teacher = o.optString("attendClassTeacher").trim(),
            selectStatus = o.optString("selectCourseStatusName"),
            studyMode = o.optString("studyModeName"),
            slots = slots
        )
    } catch (_: Exception) {
        null
    }

    private fun parseSelectableCourse(objs: List<JSONObject>, fromPlan: Boolean): SelectableCourse? {
        if (objs.isEmpty()) return null
        val first = objs[0]
        return try {
            val slots = objs.mapNotNull { o ->
                val day = o.optString("weekNum").trim()
                val start = o.optString("courseStartNum").trim()
                if (day.isBlank() || start.isBlank()) null
                else CourseSlot(
                    weekDesc = o.optString("zcsm"),
                    day = day.toIntOrNull() ?: 0,
                    startSession = start.toIntOrNull() ?: 0,
                    sessionCount = o.optString("cxjc").trim().toIntOrNull() ?: 1,
                    campus = o.optString("xqm"),
                    building = o.optString("jxlm"),
                    room = o.optString("jasm")
                )
            }
            SelectableCourse(
                termCode = if (fromPlan) first.optString("termCode") else first.optString("zxjxjhh"),
                courseNo = if (fromPlan) first.optString("courseNum") else first.optString("kch"),
                classSeq = if (fromPlan) first.optString("classNum") else first.optString("kxh"),
                courseName = first.optString("kcm"),
                credit = first.optString("xf").trim().toDoubleOrNull() ?: 0.0,
                category = first.optString("kclbmc"),
                courseProperty = first.optString("kcsxmc"),
                examType = first.optString("kslxmc"),
                teacher = first.optString("skjs").trim(),
                dept = first.optString("kkxsjc").ifBlank { first.optString("kkxqh") },
                capacity = first.optString("bkskyl").trim().toIntOrNull() ?: 0,
                slots = slots,
                note = first.optString("xkbz"),
                restrict = first.optString("xkxzsm"),
                selectRule = first.optString("xkkzsm"),
                fromPlan = fromPlan
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 提交选课。
     * dealType: 2=方案内选课, 5=自由选课
     */
    fun submitSelection(courses: List<SelectableCourse>, dealType: Int): String {
        val kcIds = courses.joinToString(",") { it.selectKey }
        val names = courses.joinToString(",") { it.selectName }
        // 课程名字符串本身，带尾逗号（不能用 names.map { it.code }：会按字符取 Unicode 码点）
        val kcms = "$names,"
        val params = buildMap {
            put("dealType", dealType.toString())
            put("fajhh", "")
            put("kcIds", kcIds)
            put("kcms", kcms)
            put("sj", "0_0")
            put("kyl", "1")
        }
        val text = client.postFormText(Endpoints.JW_SELECT_SUBMIT, params)
        return parseSubmitResult(text)
    }

    /** 解析选课结果页面，提取有效信息 */
    private fun parseSubmitResult(html: String): String {
        val body = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: html
        val text = body.replace(Regex("<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // 过滤导航噪音，保留结果区域
        val keywords = listOf("成功", "失败", "错误", "已选", "课余量不足", "冲突", "抱歉", "对不起", "选课", "退课")
        val sentences = text.split(Regex("[。；;，,]"))
            .map { it.trim() }
            .filter { s -> s.length in 4..120 && keywords.any { s.contains(it) } }
            .distinct()
        return if (sentences.isNotEmpty()) sentences.joinToString("；")
        else text.take(300).ifBlank { "未获取到选课结果，请稍后在我的课表中查看" }
    }

    /**
     * 从教务「本学期课表」页读取当前教学周。
     *
     * URP 页面上通常写着「当前教学周：第 N 周」或有一个默认选中的周次下拉框。
     * 只认这两种明确标记——课表里到处是「1-16周」这类课程周次描述，
     * 无脑匹配「第N周」会把它们误当成当前周，宁可返回 null 交给本地推算。
     */
    fun fetchTeachingWeek(): Int? = runCatching {
        val html = client.getText(Endpoints.JW_SCHEDULE_PAGE)
        parseTeachingWeek(html)
    }.getOrNull()

    private fun parseTeachingWeek(html: String): Int? {
        val plain = html
            .replace(Regex("<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")

        // 1) 显式的"当前周"
        Regex("当前(?:教学)?周[次]?\\s*[:：]?\\s*第?\\s*(\\d{1,2})\\s*周?")
            .find(plain)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { if (it in 1..30) return it }

        // 2) "周次"字样附近紧跟的 第N周
        val idx = plain.indexOf("周次")
        if (idx >= 0) {
            val window = plain.substring(idx, (idx + 200).coerceAtMost(plain.length))
            Regex("第?\\s*(\\d{1,2})\\s*周").find(window)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { if (it in 1..30) return it }
        }
        return null
    }

    /** 培养方案 */
    fun fetchPlan(): Pair<PlanOverview, List<PlanGroup>> {
        val html = client.getText(Endpoints.JW_PLAN)
        return parsePlan(html)
    }

    // ---------- 成绩查询（本科综合教务系统·全部成绩） ----------

    data class ScorePage(
        val records: List<ScoreRecord> = emptyList(),
        val totalCount: Int = 0
    )

    /**
     * 查询全部成绩。
     * 流程：先 GET 页面提取动态 token，再 POST 数据接口（sf_request_type=ajax）。
     */
    fun fetchTermScores(
        termPlanNo: String = "",
        courseNo: String = "",
        courseName: String = "",
        pageNum: Int = 1,
        pageSize: Int = 100
    ): ScorePage {
        // 1. 获取页面并提取动态 token
        val page = client.getText(Endpoints.JW_SCORE_PAGE)
        val token = Regex("scoreQuery/([A-Za-z0-9]+)/allTermScores/data")
            .find(page)?.groupValues?.get(1)
            ?: throw java.io.IOException("未能获取成绩查询令牌，请重新登录后再试")
        // 2. POST 数据接口
        val url = "${Endpoints.JW_BASE}/student/integratedQuery/scoreQuery/$token/allTermScores/data?sf_request_type=ajax"
        val params = buildMap {
            put("zxjxjhh", termPlanNo)
            put("kch", courseNo)
            put("kcm", courseName)
            put("pageNum", pageNum.toString())
            put("pageSize", pageSize.toString())
        }
        val text = client.postFormText(url, params)
        return parseTermScores(text)
    }

    /** 解析成绩数据：records 为二维数组，字段位置由页面渲染脚本确定 */
    private fun parseTermScores(text: String): ScorePage {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ScorePage()
        }
        val list = json.optJSONObject("list") ?: return ScorePage()
        val records = list.optJSONArray("records") ?: return ScorePage()
        val totalCount = list.optJSONObject("pageContext")?.optInt("totalCount", 0) ?: 0
        val result = mutableListOf<ScoreRecord>()
        for (i in 0 until records.length()) {
            val row = records.optJSONArray(i) ?: continue
            fun s(idx: Int): String = row.optString(idx, "").trim()
            result.add(
                ScoreRecord(
                    term = extractTerm(s(0), s(1)),
                    courseNo = s(2),
                    classSeq = s(3),
                    courseName = s(4),
                    enName = s(5),
                    credit = s(6).toDoubleOrNull() ?: 0.0,
                    hours = s(7).toDoubleOrNull() ?: 0.0,
                    property = s(8),
                    examType = s(9),
                    score = s(10),
                    gpa = s(11),
                    failReason = s(12)
                )
            )
        }
        return ScorePage(result, totalCount)
    }

    /** v[0]/v[1] 含学期信息：v[0] 通常为学年（2026-2027），v[1] 为学期号（1/2） */
    private fun extractTerm(v0: String, v1: String): String {
        if (v0.matches(Regex("\\d{4}-\\d{4}")) && v1.matches(Regex("[123]"))) return "$v0-$v1"
        if (v0.isNotBlank()) return v0
        return v1
    }

    // ---------- 学业概览 / 学期列表 / 本学期成绩 / 成绩明细 ----------

    private val ajaxHeaders = mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest"
    )

    /**
     * 学业概览：教务首页那张「平均学分绩点 / 排名 / 已修学分」卡片背后的接口。
     *
     * 这是**学校口径**：学校按培养方案折算，和本地按成绩单逐门加权算的绩点会有差异，
     * 因此界面上两者并列展示，不做合并。
     */
    fun fetchAcademicInfo(): AcademicInfo {
        val text = client.postFormText(
            "${Endpoints.JW_ACADEMIC_INFO}?sf_request_type=ajax",
            mapOf("flag" to ""),
            ajaxHeaders
        )
        val arr = JSONArray(text)
        if (arr.length() == 0) throw java.io.IOException("学业信息为空，可能尚未产生成绩")
        val o = arr.getJSONObject(0)
        return AcademicInfo(
            completedCourses = o.num("courseNum"),
            failedCourses = o.num("coursePas"),
            gpa = o.dbl("gpa"),
            averageScorePoint = o.dbl("sspjf_jd"),
            averageRank = o.num("sspjf_pm"),
            averageRankTotal = o.num("sspjf_rs"),
            pendingCourses = o.num("courseNum_bxqyxd"),
            currentTerm = o.optString("zxjxjhh", "").trim()
        )
    }

    /** 兼容"数字被写成字符串"的情形：optInt 遇到 "6" 也会正常解析，遇到空串返回默认值 */
    private fun JSONObject.num(key: String): Int = optString(key, "").trim().toIntOrNull() ?: 0

    private fun JSONObject.dbl(key: String): Double =
        optString(key, "").trim().toDoubleOrNull() ?: 0.0

    /**
     * 学期列表：教务「学期课表」页顶部有个 select#planCode，
     * 选项就是历届学期（value 为学期计划号）。这里把它读出来做学期切换。
     *
     * 名称里的"春/秋"统一改写成"下/上"——学生口语就是"上学期/下学期"。
     */
    fun fetchTerms(): List<TermItem> {
        // 优先用"学期课表"页（学期下拉框在页面顶部，解析最稳）；
        // 该页若改版拿不到，再退到"本学期课表"页试一次。
        val candidates = listOf(Endpoints.JW_TERM_LIST, Endpoints.JW_SCHEDULE_PAGE)
        for (url in candidates) {
            val list = runCatching { parseTerms(client.getText(url)) }.getOrNull()
            if (!list.isNullOrEmpty()) return list
        }
        // 两条路都拿不到学期下拉框时的兜底：按当前日期推算本学期。
        // 宁可给一个"可能猜错但格式正确"的学期，也不要因为解析失败
        // 让成绩页彻底空着——用户至少还能点进去试。
        return listOf(guessCurrentTerm())
    }

    /**
     * 从 HTML 里解析学期下拉框。
     *
     * ## 为什么返回的名称一律由学期号生成（v1.8.2）
     *
     * v1.8.1 直接把 `<option>` 的文本当学期名，结果在真机上显示成
     * `#438EB9` `#222A2D` 这类**十六进制颜色值**——说明页面上那段 DOM
     * 并不是干净的可视文本（教务前端把 option 的内容用脚本或样式拼过），
     * 抓到的 innerText 根本不是学期名。
     *
     * 学期号（option 的 value）才是接口真正需要的参数，而它有稳定的格式
     * `2026-2027-1`。所以改成：
     * 1. 只认 value 里符合 `四位年-四位年-序号` 的选项，其余一律丢弃；
     * 2. 展示名**完全**由学期号推导（`2026-2027-1` → `2026-2027 上学期`），
     *    不再信任页面文本，只会用文本判断"是否为当前学期"。
     */
    private fun parseTerms(html: String): List<TermItem> {
        val block = selectBlock(html, "planCode") ?: return emptyList()

        val optionRe = Regex("<option([^>]*)>([\\s\\S]*?)</option>", RegexOption.IGNORE_CASE)
        val valueRe = Regex("value\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        val list = optionRe.findAll(block).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val code = valueRe.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            if (code.isEmpty()) return@mapNotNull null
            val text = m.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ")
                .trim()
            // 学期号不合格（颜色值、纯数字、空）→ 直接丢掉
            val name = termNameFromCode(code) ?: return@mapNotNull null
            TermItem(
                termCode = code,
                termName = name,
                isCurrent = attrs.contains("selected", true) || text.contains("当前")
            )
        }.toList()

        if (list.isEmpty()) return emptyList()
        return if (list.any { it.isCurrent }) list
        else list.mapIndexed { i, t -> if (i == 0) t.copy(isCurrent = true) else t }
    }

    /**
     * 取出 `id`/`name` 为指定值的 `<select>` 内部 HTML。
     *
     * 先走严格匹配（带 id/name 属性），失败再退到"从关键字往前找最近的
     * `<select`，往后找最近的 `</select>`"。v1.8.1 直接从关键字切到第一个
     * `</select>`，如果 `planCode` 出现在脚本或样式里就会切错区域，
     * 把别的下拉框的选项当成了学期。
     */
    private fun selectBlock(html: String, id: String): String? {
        val strict = Regex(
            "<select[^>]*(?:id|name)\\s*=\\s*[\"']?" + Regex.escape(id) + "[\"']?[^>]*>" +
                "([\\s\\S]*?)</select>",
            RegexOption.IGNORE_CASE
        )
        strict.find(html)?.let { return it.groupValues[1] }

        val hit = html.indexOf(id)
        if (hit < 0) return null
        val end = html.indexOf("</select>", hit)
        if (end < 0) return null
        val open = html.lastIndexOf("<select", hit)
        val from = if (open in 0 until hit) open else hit
        return html.substring(from, end)
    }

    /** `2026-2027-1` → `2026-2027 上学期`；不合格返回 null */
    private fun termNameFromCode(code: String): String? {
        val m = TERM_CODE_RE.find(code) ?: return null
        val y1 = m.groupValues[1]
        val y2 = m.groupValues[2]
        val part = m.groupValues.getOrNull(3).orEmpty()
        val suffix = when (part) {
            "1" -> " 上学期"
            "2" -> " 下学期"
            "3" -> " 短学期"
            else -> ""
        }
        return "$y1-$y2$suffix"
    }

    /**
     * 拿不到学期下拉框时按日期推算当前学期。
     *
     * 安财的学期划分：8 月～次年 1 月为秋季（`-1`），2 月～7 月为春季（`-2`）。
     * 这里只用于兜底展示与尝试请求，猜错时用户切到别的学期即可。
     */
    private fun guessCurrentTerm(): TermItem {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val (startYear, part) = when {
            month >= 8 -> year to 1
            month == 1 -> (year - 1) to 1
            else -> (year - 1) to 2
        }
        val code = "$startYear-${startYear + 1}-$part"
        return TermItem(
            termCode = code,
            termName = termNameFromCode(code) ?: code,
            isCurrent = true
        )
    }

    /**
     * 本学期成绩。
     *
     * 为什么不用 allTermScores 过滤本学期：thisTermScores 的每条记录多带一个
     * `examtime`，而查「成绩明细（平时/期中/期末）」必须把它作为 kssj 传回去。
     */
    fun fetchThisTermScores(): List<ScoreRecord> {
        val html = client.getText(Endpoints.JW_THIS_TERM_SCORES_PAGE)
        val token = Regex("/([A-Za-z0-9]+)/thisTermScores/data")
            .find(html)?.groupValues?.get(1)
            ?: throw java.io.IOException("未能获取本学期成绩令牌，请重新登录后再试")
        val url = "${Endpoints.JW_BASE}/student/integratedQuery/scoreQuery/$token/thisTermScores/data?sf_request_type=ajax"
        val text = client.getText(url, ajaxHeaders + mapOf("Referer" to Endpoints.JW_THIS_TERM_SCORES_PAGE))
        val root = JSONArray(text)
        val list = root.optJSONObject(0)?.optJSONArray("list") ?: return emptyList()
        val result = mutableListOf<ScoreRecord>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optJSONObject("id") ?: JSONObject()
            result.add(
                ScoreRecord(
                    term = id.optString("executiveEducationPlanNumber", "").trim(),
                    courseNo = id.optString("courseNumber", "").trim(),
                    classSeq = item.optString("coureSequenceNumber", "").trim(),
                    courseName = item.optString("courseName", "").trim(),
                    enName = item.optString("englishCourseName", "").trim(),
                    credit = item.optString("credit", "").trim().toDoubleOrNull() ?: 0.0,
                    hours = item.optString("studyHour", "").trim().toDoubleOrNull() ?: 0.0,
                    property = item.optString("coursePropertyName", "").trim(),
                    examType = item.optString("examTypeName", "").trim(),
                    score = item.optString("courseScore", "").trim(),
                    // 本学期接口不一定下发绩点，取不到就留空，由 GpaCalc 按分数换算
                    gpa = item.optString("gradePoint", "").trim()
                        .ifEmpty { item.optString("gardePoint", "").trim() },
                    examTime = id.optString("examtime", "").trim()
                )
            )
        }
        return result
    }

    /**
     * 成绩明细：一门课的平时 / 期中 / 期末 / 总评构成。
     * 只对本学期、且已有成绩的课程有效（教务只在当学期页面开放这个入口）。
     */
    fun fetchScoreDetail(record: ScoreRecord): List<ScoreDetailItem> {
        val html = client.getText(Endpoints.JW_THIS_TERM_SCORES_PAGE)
        val token = Regex("/([A-Za-z0-9]+)/thisterm/coursePropertyScores/serchScoreDetail")
            .find(html)?.groupValues?.get(1)
            ?: throw java.io.IOException("未能获取成绩明细入口，教务可能未开放")
        val url = "${Endpoints.JW_BASE}/student/integratedQuery/scoreQuery/" +
            "$token/thisterm/coursePropertyScores/serchScoreDetail?sf_request_type=ajax"
        val text = client.postFormText(
            url,
            mapOf(
                "zxjxjhh" to record.term,
                "kch" to record.courseNo,
                "kssj" to record.examTime,
                "kxh" to record.classSeq
            ),
            ajaxHeaders + mapOf("Referer" to Endpoints.JW_THIS_TERM_SCORES_PAGE)
        )
        val mx = JSONObject(text).optJSONArray("mx") ?: return emptyList()
        val result = mutableListOf<ScoreDetailItem>()
        for (i in 0 until mx.length()) {
            val o = mx.optJSONObject(i) ?: continue
            val typeCode = o.optJSONObject("id")?.optString("scoreTypeCode", "").orEmpty()
            result.add(
                ScoreDetailItem(
                    scoreType = scoreTypeName(typeCode),
                    usualScore = o.optString("pscj", "").trim(),
                    midtermScore = o.optString("qzcj", "").trim(),
                    finalScore = o.optString("qmcj", "").trim(),
                    categoryScore = o.optString("zcj", "").trim(),
                    remark = o.optString("remark", "").trim()
                )
            )
        }
        return result
    }

    private fun scoreTypeName(code: String): String = when (code) {
        "001" -> "课堂成绩"
        "002" -> "实验成绩"
        "003" -> "实践成绩"
        else -> "成绩构成"
    }

    private fun parsePlan(html: String): Pair<PlanOverview, List<PlanGroup>> {
        val plain = html.replace(Regex("<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
        val planName = Regex("(\\d{4}级[^\\s(]{2,30}?培养方案)").find(plain)?.groupValues?.get(1) ?: "培养方案"
        val totalCourses = Regex("(\\d+)\\s*总门数").find(plain)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val totalCredits = Regex("([\\d.]+)\\s*总学分").find(plain)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val totalHours = Regex("([\\d.]+)\\s*总课时").find(plain)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val overview = PlanOverview(planName, totalCourses, totalCredits, totalHours)

        // 提取 zTree 扁平节点数组
        val groups = mutableListOf<PlanGroup>()
        try {
            val m = Regex("\\$\\.fn\\.zTree\\.init\\([^,]+,\\s*setting,\\s*(\\[[\\s\\S]*?\\])\\s*\\)")
                .find(html) ?: Regex("zTree\\.init\\([^,]+,\\s*setting,\\s*(\\[[\\s\\S]*?\\])\\s*\\)").find(html)
            val arr = JSONArray(m?.groupValues?.get(1) ?: "[]")
            data class Node(
                val id: String, val pId: String, val flagType: String,
                val name: String, val minCredit: Double, val passedCredit: Double, val missing: Int
            )
            val nodes = mutableListOf<Node>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rawName = o.optString("name")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&nbsp;", " ")
                    .trim()
                val minCredit = Regex("最低修读学分:([\\d.]+)").find(rawName)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val passed = Regex("通过学分:([\\d.]+)").find(rawName)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val missing = Regex("必修课缺修门数:(\\d+)").find(rawName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val cleanName = rawName.substringBefore("(最低修读学分").trim()
                nodes.add(Node(o.optString("id"), o.optString("pId"), o.optString("flagType"), cleanName, minCredit, passed, missing))
            }
            // 构建树：只用课组节点（flagType 001/002 等非 kch）
            val groupNodes = nodes.filter { it.flagType != "kch" }
            fun build(parentId: String): List<PlanGroup> =
                groupNodes.filter { it.pId == parentId }.map { n ->
                    PlanGroup(n.name, n.minCredit, n.passedCredit, n.missing, build(n.id))
                }
            groups.addAll(build("-1"))
        } catch (_: Exception) {
        }
        return Pair(overview, groups)
    }

    private companion object {
        /**
         * 学期号匹配：`2026-2027-1`、`2026-2027`、`2026–2027–2`（全角破折号）都能识别。
         * 第三段是学期序号：1=秋季/上学期，2=春季/下学期，3=短学期。
         */
        val TERM_CODE_RE = Regex("(\\d{4})\\s*[-–—]\\s*(\\d{4})(?:\\s*[-–—]\\s*(\\d))?")
    }
}
