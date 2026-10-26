package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.ScoreGroup
import cn.aufe.campus.data.model.ScoreItem
import cn.aufe.campus.data.model.ScoreReport
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints

/** 爱安财（安徽财经大学第二课堂平台）成绩单 API */
class DektApi(private val client: AufeClient) {

    /** 获取成绩单明细 */
    fun fetchScoreDetail(token: String): ScoreReport {
        val html = client.getText("${Endpoints.DEKT_SCORE_DETAIL}?web=1&t=${token.encode()}")
        if (html.contains("参数不正确") || html.contains("登录")) {
            throw SessionExpiredException("爱安财登录已失效，请重新登录")
        }
        return parseScoreDetail(html)
    }

    /** 获取成绩单证书信息 */
    fun fetchCertificate(token: String): ScoreReport {
        val html = client.getText("${Endpoints.DEKT_CERTIFICATE}?web=1&t=${token.encode()}")
        if (html.contains("参数不正确")) {
            throw SessionExpiredException("爱安财登录已失效，请重新登录")
        }
        return parseCertificate(html)
    }

    private fun parseScoreDetail(html: String): ScoreReport {
        fun field(label: String): String =
            Regex("$label[：:]\\s*([^<\\s]+)").find(html)?.groupValues?.get(1)?.trim() ?: ""

        val totalScore = Regex("class=\"tabt\">([\\d.]+)分").find(html)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        // 评定：<span style="color:red">不合格\n社会实践不满1.00分</span>
        val evalRaw = Regex("评定[：:]\\s*<span[^>]*>([\\s\\S]*?)</span>").find(html)
            ?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val evaluation = evalRaw.substringBefore(" ").trim()
        val note = evalRaw.substringAfter(" ", "").trim().ifBlank {
            Regex("社会实践不满[\\d.]+分").find(html)?.value?.trim().orEmpty()
        }

        // 学分明细：按 scoreList 块解析课组
        val groups = mutableListOf<ScoreGroup>()
        val blocks = html.split(Regex("<div class=\"scoreList\""))
        for (i in 1 until blocks.size) {
            val block = blocks[i]
            // title1"> 与 （ 之间有换行/空白，组名本身含全角括号，需惰性匹配
            val titleM = Regex("title1\">\\s*（(.+?)[：:]([\\d.]+)分\\s*）").find(block)
            val groupName = titleM?.groupValues?.get(1)?.trim().orEmpty()
            val groupScore = titleM?.groupValues?.get(2)?.trim().orEmpty()
            // 行结构：<div class="row"><div>类别</div><div>项目</div><div>分值</div></div>（扁平无嵌套）
            val items = parseRowItems(block, swapFirstTwo = true)
            if (groupName.isNotEmpty()) {
                groups.add(ScoreGroup(groupName, groupScore, items))
            }
        }

        // 工作履历 / 获奖信息：tabtitle 之后的 infolist 块（列：职务全称/学期/分值、活动名称/奖项/分值）
        val jobs = parseInfoSection(html, "工作履历")
        val awards = parseInfoSection(html, "获奖信息")

        return ScoreReport(
            userName = field("姓名"),
            userNo = field("学号"),
            grade = field("年级"),
            className = field("班级"),
            college = field("学院").removePrefix("学院").trim().ifBlank { field("学院") },
            totalScore = totalScore,
            evaluation = evaluation,
            evaluationNote = note,
            jobs = jobs,
            awards = awards,
            groups = groups
        )
    }

    /**
     * 解析扁平行表格：按 `<div class="row">` 切块，每块取前 3 个单元格。
     * swapFirstTwo=true 时交换前两列（学分明细：类别/项目/分值 → 项目名/类别/分值）。
     */
    private fun parseRowItems(block: String, swapFirstTwo: Boolean): List<ScoreItem> {
        return block.split(Regex("<div class=\"row\">")).drop(1).mapNotNull { chunk ->
            val cells = Regex("<div[^>]*>([^<]*)</div>").findAll(chunk)
                .map { it.groupValues[1].replace("&nbsp;", " ").trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .toList()
            if (cells.size >= 3) {
                if (swapFirstTwo) ScoreItem(cells[1], cells[0], cells[2])
                else ScoreItem(cells[0], cells[1], cells[2])
            } else null
        }
    }

    /** 解析"工作履历 / 获奖信息"区块：找到 tabtitle 标签后的第一个 infolist */
    private fun parseInfoSection(html: String, label: String): List<ScoreItem> {
        val idx = html.indexOf(label)
        if (idx < 0) return emptyList()
        val seg = html.substring(idx)
        val listStart = seg.indexOf("<div class=\"infolist\">")
        if (listStart < 0) return emptyList()
        var block = seg.substring(listStart + "<div class=\"infolist\">".length)
        // 截断到下一个 tabtitle（下一区块）为止
        val end = block.indexOf("<div class=\"tabtitle\"")
        if (end > 0) block = block.substring(0, end)
        return parseRowItems(block, swapFirstTwo = false)
    }

    private fun parseCertificate(html: String): ScoreReport {
        fun info(cls: String): String =
            Regex("class=\"info $cls\">([^<]*)<").find(html)?.groupValues?.get(1)?.trim() ?: ""
        return ScoreReport(
            userName = info("info_name"),
            userNo = info("info_userno"),
            className = info("info_class"),
            totalScore = info("info_score").toDoubleOrNull() ?: 0.0,
            evaluation = Regex("【([^】]+)】").find(html)?.groupValues?.get(1)?.trim().orEmpty(),
            evaluationNote = Regex("【[^】]+】</span>\\s*<span[^>]*>([^<]*)</span>").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        )
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

/** 会话失效异常 */
class SessionExpiredException(message: String) : Exception(message)
