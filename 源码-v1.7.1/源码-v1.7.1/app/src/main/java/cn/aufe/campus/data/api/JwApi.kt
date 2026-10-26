package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.CourseSlot
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.data.model.SelectableCourse
import cn.aufe.campus.data.model.SelectedCourse
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
}
