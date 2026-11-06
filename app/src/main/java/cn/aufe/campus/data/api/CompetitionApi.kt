package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.AwardProject
import cn.aufe.campus.data.model.CompetitionData
import cn.aufe.campus.data.model.CreditsSummary
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints

/**
 * 竞赛获奖（移植自 LoveACE 的 CompetitionService）。
 *
 * ## 这是个 ASP.NET WebForms 页面，必须"带着状态翻页"
 *
 * WebForms 的所有交互都是往同一个 .aspx POST 表单，靠隐藏字段区分动作：
 * - `__VIEWSTATE`：页面状态，服务端加密的 base64，**每次响应都会变**，
 *   翻下一页必须用上一页返回的那个，不能复用第一次的
 * - `__VIEWSTATEGENERATOR` / `__EVENTVALIDATION`：校验用
 * - `__EVENTTARGET`：点的是哪个控件（这里是"已申报奖项"这个 LinkButton）
 *
 * 少带或带错任何一个，服务端只会原样把第一页吐回来 —— 表现就是
 * "翻页没反应"或者"一直显示第一页"。所以下面每翻一页都重新从当前 HTML 里抠。
 *
 * ## 没有引入 Jsoup
 *
 * 只需要 `<table id=*gvHj*>` 里的 `<tr>/<td>`，和几个固定 id 的 span，
 * 正则足够，也省一个 400KB 的依赖。
 */
class CompetitionApi(private val client: AufeClient) {

    /** 拉全部获奖记录 + 学分汇总（内部自动翻完所有页） */
    fun load(): CompetitionData {
        val indexPath = Endpoints.XSXM_MAIN
        val indexHtml = client.getText(indexPath)

        val firstPost = linkedMapOf(
            "__VIEWSTATE" to inputValue(indexHtml, "__VIEWSTATE"),
            "__VIEWSTATEGENERATOR" to inputValue(indexHtml, "__VIEWSTATEGENERATOR"),
            "__EVENTVALIDATION" to inputValue(indexHtml, "__EVENTVALIDATION"),
            // "已申报奖项"标签；$ 是 WebForms 的层级分隔符，Kotlin 里要转义
            "__EVENTTARGET" to
                "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$DataList1\$ctl01\$LinkButton1",
            "__EVENTARGUMENT" to "",
            "__LASTFOCUS" to "",
            "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$ddlSslb" to "%",
            "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$txtSsmc" to ""
        )
        var html = client.postFormText(indexPath, firstPost)

        val awards = mutableListOf<AwardProject>()
        awards.addAll(parseAwards(html))
        val summary = parseSummary(html)

        // 翻页：总页数来自 GridView 的 pager
        val totalPages = parseTotalPages(html).coerceIn(1, MAX_PAGES)
        var page = 1
        while (page < totalPages) {
            val form = nextPageForm(html, page + 1)
            if (form == null) break
            html = client.postFormText(indexPath, form)
            val got = parseAwards(html)
            if (got.isEmpty()) break
            awards.addAll(got)
            page++
        }

        if (awards.isEmpty() && summary == null) {
            throw java.io.IOException("没有解析到获奖记录（可能本学期还没有申报，或页面已改版）")
        }
        return CompetitionData(awards, summary)
    }

    // ---------- 列表 ----------

    private fun parseAwards(html: String): List<AwardProject> {
        val table = tableBlock(html) ?: return emptyList()
        val rows = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
            .findAll(table)
        val out = mutableListOf<AwardProject>()
        for (row in rows) {
            val cells = Regex("<td[^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
                .findAll(row.groupValues[1])
                .map { text(it.groupValues[1]) }
                .toList()
            if (cells.size < 12) continue
            // 表头 / 分页行：第一列不是纯数字（项目号）就跳过
            val projectId = cells[0]
            if (projectId.toIntOrNull() == null) continue

            out.add(
                AwardProject(
                    projectId = projectId,
                    projectName = cells[1],
                    level = cells[2],
                    grade = cells[3],
                    awardDate = cells[4],
                    applicantId = cells[5],
                    applicantName = cells[6],
                    order = cells[7].toIntOrNull() ?: 0,
                    credits = cells[8].toDoubleOrNull() ?: 0.0,
                    bonus = cells[9].toDoubleOrNull() ?: 0.0,
                    status = cells[10],
                    verificationStatus = cells[11]
                )
            )
        }
        return out
    }

    /** 切出 `id` 里含 gvHj 的那张表格 */
    private fun tableBlock(html: String): String? {
        val head = Regex("""<table[^>]*id\s*=\s*["'][^"']*gvHj[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        val end = html.indexOf("</table>", head.range.last)
        if (end < 0) return null
        return html.substring(head.range.first, end)
    }

    private fun parseTotalPages(html: String): Int {
        val m = Regex(
            """<span[^>]*id\s*=\s*["'][^"']*gvHj_LabelPageCount[^"']*["'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return 1
        return text(m.groupValues[1]).toIntOrNull() ?: 1
    }

    /**
     * 组装"下一页"的表单。
     *
     * 优先点 `LinkButtonNextPage`（下一页箭头），退而求其次用 `btnGo`（跳转按钮）
     * 并带上页码输入框的值。两个都找不到就返回 null，停止翻页
     * —— 总比死循环强。
     */
    private fun nextPageForm(html: String, targetPage: Int): Map<String, String>? {
        val viewState = inputValue(html, "__VIEWSTATE")
        if (viewState.isBlank()) return null

        val target = doPostBackTarget(html, "LinkButtonNextPage")
            ?: doPostBackTarget(html, "btnGo")
            ?: return null
        // 点"下一页"就不要再填页码，避免服务端收到矛盾指令
        val isNextPageButton = target.contains("LinkButtonNextPage", true)

        val form = linkedMapOf(
            "__VIEWSTATE" to viewState,
            "__VIEWSTATEGENERATOR" to inputValue(html, "__VIEWSTATEGENERATOR"),
            "__EVENTVALIDATION" to inputValue(html, "__EVENTVALIDATION"),
            "__EVENTTARGET" to target,
            "__EVENTARGUMENT" to "",
            "__LASTFOCUS" to "",
            "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$ddlSslb" to "%",
            "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$txtSsmc" to ""
        )
        if (!isNextPageButton) {
            val pageInput = Regex("""<input[^>]*id\s*=\s*["'][^"']*txtNewPageIndex[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(html)?.value
            val name = pageInput?.let { attr(it, "name") }.orEmpty()
            if (name.isNotBlank()) form[name] = targetPage.toString()
        }
        return form
    }

    private fun doPostBackTarget(html: String, idPart: String): String? {
        val tag = Regex(
            """<a[^>]*id\s*=\s*["'][^"']*$idPart[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return null
        val href = attr(tag, "href")
        return Regex("""__doPostBack\('([^']+)'""").find(href)?.groupValues?.get(1)
    }

    // ---------- 学分汇总 ----------

    private fun parseSummary(html: String): CreditsSummary? {
        val values = listOf(
            "lblXkjsxf",  // 学科竞赛
            "lblKyxf",    // 科研
            "lblKzjslxf", // 可折算竞赛
            "lblCxcyxf",  // 创新创业
            "lblNlzgxf",  // 能力资格
            "lblQtxf"     // 其他
        ).map { credit(html, it) }
        if (values.all { it == null }) return null
        return CreditsSummary(
            disciplineCompetition = values[0],
            scientificResearch = values[1],
            transferableCompetition = values[2],
            innovationPractice = values[3],
            abilityCertification = values[4],
            otherProject = values[5]
        )
    }

    private fun credit(html: String, idPart: String): Double? {
        val m = Regex(
            """<span[^>]*id\s*=\s*["'][^"']*$idPart[^"']*["'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return null
        val t = text(m.groupValues[1])
        if (t.isEmpty() || t == "无") return null
        return t.toDoubleOrNull()
    }

    // ---------- 小工具 ----------

    /** 取 `name="xxx"` 的 input 的 value */
    private fun inputValue(html: String, name: String): String {
        val tag = Regex(
            """<input[^>]*name\s*=\s*["']$name["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return ""
        return attr(tag, "value")
    }

    private fun attr(tag: String, name: String): String {
        val m = Regex("""$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun text(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("\u00a0", " ")
            .trim()

    companion object {
        /** 防止 pager 解析出错时无限翻页 */
        private const val MAX_PAGES = 30
    }
}
