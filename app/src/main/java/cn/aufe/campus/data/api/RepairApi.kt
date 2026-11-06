package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.RepairDetail
import cn.aufe.campus.data.model.RepairFormData
import cn.aufe.campus.data.model.RepairOption
import cn.aufe.campus.data.model.RepairOptionGroup
import cn.aufe.campus.data.model.RepairOrder
import cn.aufe.campus.data.model.RepairProgress
import cn.aufe.campus.data.model.RepairSettlement
import cn.aufe.campus.data.model.RepairSubmit
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints

/**
 * 零星维修（移植自 LoveACE 的 RepairService）。
 *
 * 这是学校后勤的一个 **PHP 小系统**（weui 风格页面），和教务完全两套：
 * - 会话靠 `PHPSESSID`，第一次访问 `index.php/index/user` 时由 CAS 网关带过去
 * - 列表页是 `/myorder/staue/{0..3}`：0 待接单 / 1 待完工 / 2 待评价 / 3 已完成
 * - 提交报修是 AJAX（`sf_request_type=ajax`），返回 `{"isok":true}` 之类的 JSON
 *
 * ## 为什么要自己 ensureSession
 *
 * 教务那套 Cookie 是这个 PHP 站认不出来的。第一次进站必须由网关重定向一次，
 * 站点才会下发自己的 PHPSESSID；少了这一步，列表页永远是空的。
 * 只做一次，之后靠 CookieJar 自动带上（登录态换了会被 [invalidateSession] 重置）。
 */
class RepairApi(private val client: AufeClient) {

    @Volatile
    private var sessionReady = false

    /** 登录态变化时调用（换号 / 重登） */
    fun invalidateSession() {
        sessionReady = false
    }

    private fun ensureSession() {
        if (sessionReady) return
        runCatching {
            client.get(Endpoints.WXQD_USER).use { it.body?.string().orEmpty() }
        }
        sessionReady = true
    }

    // ---------- 工单列表 ----------

    /** 一次拉齐四种状态，前端按状态分组展示 */
    fun loadOrders(): List<RepairOrder> {
        ensureSession()
        val all = mutableListOf<RepairOrder>()
        for (status in 0..3) {
            runCatching { fetchOrders(status) }.getOrDefault(emptyList()).let { all.addAll(it) }
        }
        if (all.isEmpty()) {
            throw java.io.IOException("没有查到报修工单（可能还没提交过报修）")
        }
        return all
    }

    private fun fetchOrders(status: Int): List<RepairOrder> {
        val html = client.getText("${Endpoints.WXQD_ORDER_LIST}/$status")
        val statusText = when (status) {
            0 -> "待接单"
            1 -> "待完工"
            2 -> "待评价"
            3 -> "已完成"
            else -> "未知"
        }
        val out = mutableListOf<RepairOrder>()
        for ((openTag, inner) in blocks(html, "a")) {
            if (!openTag.contains("weui_media_appmsg")) continue
            val href = attr(openTag, "href")
            val taskId = Regex("""taskid/(\d+)""").find(href)?.groupValues?.get(1)
                ?: Regex("""taskid%2F(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)
                ?: continue
            if (inner.isBlank()) continue

            val title = Regex("""<h4[^>]*>([\s\S]*?)</h4>""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)?.let { text(it) }.orEmpty()

            var workHours = ""
            var orderNumber = ""
            var reporter = ""
            var location = ""
            var createTime = ""

            val descs = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
                .findAll(inner)
                .map { text(it.groupValues[1]) }
                .toList()
            for (desc in descs) {
                if (desc.contains("工时")) {
                    workHours = Regex("""工时\s*[：:]\s*(\S+)""").find(desc)?.groupValues?.get(1).orEmpty()
                    orderNumber = Regex("""\d{12,}""").find(desc)?.value.orEmpty()
                }
                if (desc.contains("校区") || desc.contains("公寓") || desc.contains("楼")) {
                    // 形如「张三 东校区 1号公寓 302」，第一段是报修人，其余是地点
                    val parts = desc.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.isNotEmpty() && reporter.isBlank()) reporter = parts.first()
                    if (parts.size > 1) location = parts.drop(1).joinToString(" ")
                }
                if (createTime.isBlank()) {
                    createTime = Regex("""\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2}""").find(desc)?.value
                        ?: Regex("""\d{4}-\d{1,2}-\d{1,2}""").find(desc)?.value.orEmpty()
                }
            }

            out.add(
                RepairOrder(
                    taskId = taskId,
                    title = title,
                    orderNumber = orderNumber,
                    workHours = workHours,
                    reporter = reporter,
                    location = location,
                    createTime = createTime,
                    status = status,
                    statusText = statusText
                )
            )
        }
        return out
    }

    // ---------- 工单详情 ----------

    fun loadDetail(taskId: String): RepairDetail {
        ensureSession()
        val html = client.getText("${Endpoints.WXQD_ORDER_DETAIL}/$taskId")

        fun inputById(id: String): String {
            val re = Regex("""<input[^>]*id\s*=\s*["']$id["'][^>]*>""", RegexOption.IGNORE_CASE)
            val tag = re.find(html)?.value ?: return ""
            return attr(tag, "value")
        }

        val description = Regex(
            """<[^>]*id\s*=\s*["']WordDesc["'][^>]*>([\s\S]*?)</""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.let { text(it) }.orEmpty()

        // 维修进度：左边是阶段名，右边是时间 + 说明，按序号一一对应
        val progress = mutableListOf<RepairProgress>()
        val con = Regex("""<div[^>]*class\s*=\s*["'][^"']*progressCon[^"']*["'][^>]*>([\s\S]*?)</div>\s*</div>""",
            RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty()
        if (con.isNotBlank()) {
            val left = listItems(con, "conLeft")
            val right = listItems(con, "conRight")
            left.forEachIndexed { i, stage ->
                val r = right.getOrNull(i).orEmpty()
                val time = Regex("""<span[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
                    .find(r)?.groupValues?.get(1)?.let { text(it) }.orEmpty()
                val desc = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
                    .find(r)?.groupValues?.get(1)?.let { text(it) }.orEmpty()
                progress.add(RepairProgress(stage, time, desc))
            }
        }

        // 决算单
        val settlements = mutableListOf<RepairSettlement>()
        val table = Regex("""<table[^>]*class\s*=\s*["'][^"']*wxcl[^"']*["'][^>]*>([\s\S]*?)</table>""",
            RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty()
        if (table.isNotBlank()) {
            Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE).findAll(table).forEach { row ->
                val cells = Regex("""<td[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
                    .findAll(row.groupValues[1]).map { text(it.groupValues[1]) }.toList()
                if (cells.size >= 3 && cells.any { it.isNotBlank() }) {
                    settlements.add(RepairSettlement(cells[0], cells[1], cells[2]))
                }
            }
        }

        return RepairDetail(
            taskId = taskId,
            faultArea = inputById("Area_Name"),
            repairProject = inputById("Project_Name"),
            phone = inputById("telphone"),
            faultAddress = inputById("Address"),
            description = description,
            progress = progress,
            settlements = settlements
        )
    }

    private fun listItems(html: String, className: String): List<String> {
        val block = Regex(
            """<ul[^>]*class\s*=\s*["'][^"']*$className[^"']*["'][^>]*>([\s\S]*?)</ul>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1).orEmpty()
        if (block.isBlank()) return emptyList()
        return Regex("<li[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)
            .findAll(block).map { text(it.groupValues[1]) }.toList()
    }

    // ---------- 报修表单选项 ----------

    fun loadFormData(): RepairFormData {
        ensureSession()
        val areas = parseGroupedOptions(client.getText(Endpoints.WXQD_AREA_LIST))
        val projects = parseGroupedOptions(client.getText(Endpoints.WXQD_PROJECT_LIST))
        if (areas.isEmpty() && projects.isEmpty()) {
            throw java.io.IOException("没有获取到报修的区域/项目列表")
        }
        return RepairFormData(areas, projects)
    }

    /**
     * 解析 mui 的分段控件：顶部 tab 给分组名，对应的内容区给条目。
     *
     * 两种写法都兼容：
     * 1. tab 顺序与内容区顺序一致 → 按序号配对
     * 2. tab 的 href="#id" 指向内容区 → 按 id 配对
     */
    private fun parseGroupedOptions(html: String): List<RepairOptionGroup> {
        val tabNames = Regex(
            """<a[^>]*class\s*=\s*["'][^"']*mui-control-item[^"']*["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { text(it.groupValues[1]) }.toList()

        val contents = Regex(
            """<div[^>]*class\s*=\s*["'][^"']*mui-control-content[^"']*["'][^>]*>([\s\S]*?)</ul>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { it.groupValues[1] }.toList()

        val groups = mutableListOf<RepairOptionGroup>()
        contents.forEachIndexed { i, chunk ->
            val items = Regex("""<li[^>]*data-val\s*=\s*["']([^"']*)["'][^>]*>([\s\S]*?)</li>""",
                RegexOption.IGNORE_CASE)
                .findAll(chunk)
                .mapNotNull { m ->
                    val id = m.groupValues[1].trim()
                    val name = text(m.groupValues[2])
                    if (id.isEmpty() || name.isEmpty()) null else RepairOption(id, name)
                }
                .toList()
            if (items.isNotEmpty()) {
                groups.add(RepairOptionGroup(tabNames.getOrElse(i) { "第${i + 1}组" }, items))
            }
        }
        return groups
    }

    // ---------- 提交 ----------

    fun submit(request: RepairSubmit): String {
        ensureSession()
        val body = client.postFormText(
            Endpoints.WXQD_REPAIR_SUBMIT,
            linkedMapOf(
                "areaid" to request.areaId,
                "areaname" to request.areaName,
                "projectid" to request.projectId,
                "projectname" to request.projectName,
                "telphone" to request.phone,
                "address" to request.address,
                "worddesc" to request.description,
                "pic" to "",
                "voiceid" to "",
                "taskType" to "1"
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "${Endpoints.WXQD_BASE}/index.php/index/repair"
            )
        )
        return if (body.contains("\"isok\"") && body.contains("true")) {
            "报修提交成功"
        } else {
            val msg = Regex(""""msg"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            throw java.io.IOException(msg ?: "提交失败，请稍后再试")
        }
    }

    // ---------- 小工具 ----------

    /** 找出所有指定标签的 (开标签, 内部内容) */
    private fun blocks(html: String, tag: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val open = Regex("<$tag\\b[^>]*>", RegexOption.IGNORE_CASE)
        var index = 0
        while (true) {
            val m = open.find(html, index) ?: break
            val close = html.indexOf("</$tag>", m.range.last)
            if (close < 0) break
            out.add(m.value to html.substring(m.range.last + 1, close))
            index = close + 1
        }
        return out
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
            .replace(Regex("\\s+"), " ")
            .trim()
}
