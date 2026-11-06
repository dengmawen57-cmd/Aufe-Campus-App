package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.CourseQuery
import cn.aufe.campus.data.model.CourseQueryPage
import cn.aufe.campus.data.model.OfferedCourse
import cn.aufe.campus.data.model.QueryOption
import cn.aufe.campus.data.model.QueryOptions
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import org.json.JSONArray
import org.json.JSONObject

/**
 * 开课查询（移植自 LoveACE 的 CourseScheduleService）。
 *
 * 学校教务的这门课表查询本身就是个 AJAX 分页接口，返回标准 JSON，
 * 所以这里不需要任何 HTML 解析——比 LoveACE 那边还简单一点。
 *
 * ## 两个接口
 * 1. `courseSchdule/index`：一个 HTML 页面，藏着学期和四类下拉选项（select 标签）
 * 2. `courseSchdule/courseInfo`：POST 表单，返回 `{list:{records:[...],pageContext:{totalCount}}}`
 *
 * ## 为什么下拉选项要从 HTML 里抠
 *
 * 这几类代码（院系 / 校区 / 教学楼 / 课程类别）没有单独的 JSON 接口，
 * 只能从首页的 `<select id="kkxsh">` 里一个个 `<option>` 抠出来。
 * 抠不到就返回空列表，UI 自动隐藏那个筛选项 —— 宁可少一个筛选项，
 * 也不能因为某个下拉框改版就让整页报错。
 */
class CourseQueryApi(private val client: AufeClient) {

    /** 拉首页：解析学期列表 + 四类下拉选项 */
    fun loadIndex(): QueryOptions {
        val html = client.getText(Endpoints.JW_COURSE_QUERY_INDEX)
        return QueryOptions(
            terms = parseOptions(html, "zxjxjhh"),
            departments = parseOptions(html, "kkxsh"),
            campuses = parseOptions(html, "kkxqh"),
            buildings = parseOptions(html, "jxlh"),
            categories = parseOptions(html, "kclb")
        )
    }

    /** 分页查询开课记录 */
    fun queryPage(query: CourseQuery, pageNum: Int, pageSize: Int = PAGE_SIZE): CourseQueryPage {
        val params = linkedMapOf(
            "zxjxjhh" to query.termCode,
            "kch" to query.courseNo,
            "kcm" to query.courseName,
            "kkxsh" to query.department,
            "kkxqh" to query.campus,
            "jxlh" to query.building,
            "jash" to "",
            "skxq" to (query.weekday?.toString() ?: ""),
            "skjc" to (query.startSession?.toString() ?: ""),
            "kclb" to query.category,
            "skjs" to query.teacher,
            // 下面这几个是页面上的"显示名"字段，接口要求必须带上，留空即可
            "xqname" to "",
            "jcname" to "",
            "jxlname" to "",
            "jasname" to "",
            "pageNum" to pageNum.toString(),
            "pageSize" to pageSize.toString()
        )
        val body = client.postFormText(Endpoints.JW_COURSE_QUERY_PAGE, params)

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw java.io.IOException("开课查询返回的不是 JSON，可能接口已改版")
        val list = root.optJSONObject("list")
            ?: throw java.io.IOException("开课查询结果格式异常（缺少 list）")

        val total = list.optJSONObject("pageContext")?.optInt("totalCount", 0)
            ?: list.optInt("totalCount", 0)
        val records = list.optJSONArray("records") ?: JSONArray()
        val out = ArrayList<OfferedCourse>(records.length())
        for (i in 0 until records.length()) {
            val o = records.optJSONObject(i) ?: continue
            out.add(parseRecord(o))
        }
        return CourseQueryPage(out, total)
    }

    private fun parseRecord(o: JSONObject): OfferedCourse {
        val weekday = str(o, "skxq")
        val startSession = str(o, "skjc")
        val endSession = str(o, "cxjc")
        val campus = str(o, "kkxqm").ifBlank { str(o, "xqm") }
        val building = str(o, "jxlm")
        val room = str(o, "jasm")

        // 上课时间：星期 + 节次；地点：校区 + 教学楼 + 教室
        val timeText = buildString {
            if (weekday.isNotBlank()) append(weekdayName(weekday))
            if (startSession.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append("第").append(startSession)
                // cxjc 是结束节次：只有它比起始节次大时才写成「第1-2节」，
                // 否则（等于或为 0/空）就只写起始节，免得出现「第1-1节」
                val e = endSession.toIntOrNull()
                val s = startSession.toIntOrNull()
                if (e != null && s != null && e > s) append('-').append(e)
                append("节")
            }
            if (isEmpty()) append("—")
        }
        val placeText = listOf(campus, building, room)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "—" }

        return OfferedCourse(
            courseNo = str(o, "kch"),
            classNo = str(o, "kxh"),
            courseName = str(o, "kcm"),
            credits = str(o, "xf"),
            hours = str(o, "xs"),
            teacher = str(o, "skjs"),
            timeText = timeText,
            placeText = placeText,
            campusName = campus,
            categoryName = str(o, "kclbmc"),
            // skzc 是 24 位 0/1 串（第 1 周在第 0 位），直接显示就是一串二进制；
            // zcsm 是给人类看的周次说明，优先用它，都没有再从 0/1 串反推
            weeks = weekText(str(o, "zcsm"), str(o, "skzc")),
            examType = str(o, "kslxmc"),
            capacity = str(o, "bkskrl"),
            selectedCount = str(o, "xss"),
            remark = str(o, "xkmssm")
        )
    }

    /**
     * 读字符串字段，顺手把三种「假空值」统一成空串。
     *
     * Android 的 `org.json` 里 `optString(key, fallback)` 对 `JSON null`
     * **不会**返回 fallback，而是返回字面量 `"null"`（`JSON.toString(NULL)` 的结果）。
     * 于是卡片上就出现了「周次 … · 容量 40 · 已选 40 · null」这种尾巴。
     * 这里一次性挡掉 `null` / `"null"` / `"undefined"`。
     */
    private fun str(o: JSONObject, key: String): String {
        val raw = o.opt(key) ?: return ""
        if (raw == JSONObject.NULL) return ""
        val s = raw.toString().trim()
        return if (s.equals("null", true) || s.equals("undefined", true)) "" else s
    }

    /**
     * 把周次整理成人能读的样子。
     *
     * 教务的 `skzc` 是**位图串**：`"000000000111111111000000"` 表示第 10~18 周上课
     * （第 i 位对应第 i+1 周，共 24 位）。直接展示就是一屏 0 和 1，所以：
     *
     * 1. 有 `zcsm`（周次说明，形如「10-18周」）就直接用；
     * 2. 否则把位图串压成区间：`10-18周`、`1-8,10-16周`；
     * 3. 都不是（空 / 全 0）就返回空串，UI 不显示这一项。
     */
    private fun weekText(zcsm: String, skzc: String): String {
        if (zcsm.isNotBlank() && !zcsm.all { it == '0' || it == '1' }) return normalizeWeekText(zcsm)
        val bits = skzc.trim()
        if (bits.isEmpty() || bits.any { it != '0' && it != '1' }) {
            // 不是 0/1 串，说明本来就是文字（比如「1-16周」），原样用
            return normalizeWeekText(zcsm.ifBlank { skzc })
        }
        if (bits.none { it == '1' }) return ""

        val ranges = ArrayList<IntRange>()
        var start = -1
        bits.forEachIndexed { idx, ch ->
            if (ch == '1') {
                if (start < 0) start = idx
            } else if (start >= 0) {
                ranges.add(start + 1..idx)
                start = -1
            }
        }
        if (start >= 0) ranges.add(start + 1..bits.length)

        val text = ranges.joinToString(",") { r ->
            if (r.first == r.last) "${r.first}" else "${r.first}-${r.last}"
        }
        return if (text.isBlank()) "" else "${text}周"
    }

    /** 统一成「…周」结尾，去掉原串里重复的「第」「周」写法差异 */
    private fun normalizeWeekText(s: String): String {
        val t = s.trim().replace(" ", "")
        if (t.isEmpty()) return ""
        return when {
            t.endsWith("周") -> t
            t.all { it.isDigit() || it == '-' || it == ',' || it == '，' } -> "${t.replace('，', ',')}周"
            else -> t
        }
    }

    private fun weekdayName(v: String): String = when (v.trim()) {
        "1" -> "周一"
        "2" -> "周二"
        "3" -> "周三"
        "4" -> "周四"
        "5" -> "周五"
        "6" -> "周六"
        "7" -> "周日"
        else -> "周$v"
    }

    /**
     * 从首页 HTML 里抠某个 select 的全部 option。
     *
     * 用正则而不是引入 Jsoup：只需要 `<option value="x">名称</option>` 一种形状，
     * 为此加一个 HTML 解析库（约 400KB）不值当。
     */
    private fun parseOptions(html: String, id: String): List<QueryOption> {
        val block = selectBlock(html, id) ?: return emptyList()
        val regex = Regex(
            """<option[^>]*value\s*=\s*["']([^"']*)["'][^>]*>([\s\S]*?)</option>""",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(block).mapNotNull { m ->
            val code = m.groupValues[1].trim()
            val name = stripTags(m.groupValues[2]).trim()
            // 空的 value 一般是"请选择"占位，跳过
            if (code.isEmpty() || name.isEmpty()) null else QueryOption(code, name)
        }.toList()
    }

    /** 截出 `<select id="id">...</select>` 这一段（同时兼容 name= 写法） */
    private fun selectBlock(html: String, id: String): String? {
        val head = Regex(
            """<select[^>]*(?:id\s*=\s*["']$id["']|name\s*=\s*["']$id["'])[^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return null
        val end = html.indexOf("</select>", head.range.last)
        if (end < 0) return null
        return html.substring(head.range.last + 1, end)
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    companion object {
        /** 每页条数：列表滚到底部再拉下一页，不做整学期全量 */
        const val PAGE_SIZE = 20
    }
}
