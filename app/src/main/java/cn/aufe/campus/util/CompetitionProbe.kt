package cn.aufe.campus.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import cn.aufe.campus.CampusApp
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 学科竞赛平台「页面结构」采集器（v1.10.1）。
 *
 * ## 为什么需要它
 *
 * 这个平台是 ASP.NET WebForms：所有交互都是往同一个 `xsXmMain.aspx` POST 表单，
 * 靠 `__EVENTTARGET`（点的是哪个控件）+ `__EVENTARGUMENT`（行命令，如 `xmsb$0`）
 * + `__VIEWSTATE`（页面状态）区分动作。**控件名一个字符都不能错** —— 错了服务端
 * 只会把原页面原样吐回来，不报错，表现成"点了没反应"。所以必须拿到真实页面。
 *
 * ## v1.10.1 修正
 *
 * v1.10.0 的 postback 正则没处理 HTML 实体：页面里写的是
 * `href="javascript:__doPostBack(&#39;ctl00$...&#39;,&#39;xmsb$0&#39;)"`，
 * 单引号被转义成 `&#39;`，导致子页面一个都没点进去（摘要里"可用的 postback 目标"全空）。
 * 现在先解实体再解析，并加了一层「点进去」——把申报表单、项目详情、已报名详情一并抓下来。
 *
 * ## 安全性
 *
 * - 只发 GET 与"切换标签 / 打开详情"的 POST；
 * - 白名单：详情 / 查看 / 编辑 / 申报 / 新增 / 添加 / 上传 / 成员
 *   黑名单：删除 / 提交 / 确定 / 保存 / 注销 / 退出 / 返回 / 筛选 / 我要报名
 * - 「我要报名」只记录参数、**不自动点**：行命令有直接落库的可能，
 *   该由用户决定，不该由采集器代劳。
 */
object CompetitionProbe {

    data class Result(
        val ok: Boolean,
        val zipPath: String = "",
        val zipFile: File? = null,
        val message: String = ""
    )

    private const val STAMP_PATTERN = "yyyyMMdd_HHmmss"
    private val IG = RegexOption.IGNORE_CASE

    /**
     * 四个标签在 DataList1 下的控件名。
     *
     * **ctl 序号与标签文字的显示顺序不一致**（服务端数据源排序所致）。
     * 下面是 2026-11-03 实测的对应关系，摘要里也会再标一次。
     */
    private val TABS = listOf(
        "01" to ("ctl01" to "已申报奖项"),
        "02" to ("ctl02" to "报名参赛"),
        "03" to ("ctl03" to "已报名项目"),
        "04" to ("ctl04" to "获奖申报")
    )

    private const val FILTER_ALL = "%"

    private val CLICK_ALLOW = listOf("详情", "查看", "编辑", "申报", "新增", "添加", "上传", "成员")
    private val CLICK_DENY = listOf("删除", "提交", "确定", "保存", "注销", "退出", "返回", "筛选")

    suspend fun collect(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val client = (context.applicationContext as CampusApp).authManager.client
        val entry = Endpoints.XSXM_MAIN
        val files = linkedMapOf<String, String>()
        val summary = StringBuilder()

        summary.appendLine("安财校园通 · 学科竞赛平台结构摘要（v1.10.1）")
        summary.appendLine("采集时间：${now()}")
        summary.appendLine("入口：$entry")
        summary.appendLine()
        summary.appendLine("标签对应关系（实测，ctl 序号 ≠ 显示顺序）：")
        TABS.forEach { pair -> summary.appendLine("  ${pair.second.first} → ${pair.second.second}") }
        summary.appendLine()
        summary.appendLine(
            "postback 解题：href 里是 &#39; 实体；__EVENTTARGET=控件名，"
                + "__EVENTARGUMENT=行命令（如 xmsb\$0 = 第 1 行点申报，xxxx\$0 = 详情）。"
        )

        try {
            onProgress("正在打开平台首页…")
            val indexHtml = client.getText(entry)
            files["00_首页.html"] = indexHtml
            summary.appendLine()
            summary.appendLine("========== 首页 ==========")
            summary.appendLine("长度：${indexHtml.length}  是否登录页：${looksLikeLogin(indexHtml)}")
            appendPostbacks(summary, indexHtml)
            appendFormControls(summary, indexHtml)

            for ((i, pair) in TABS.withIndex()) {
                val (ctl, name) = pair.second
                onProgress("正在抓取 ${name}（${i + 1}/${TABS.size}）…")

                // 每个标签都重新 GET 首页：VIEWSTATE 每次响应都变，
                // 连续用同一个去点四个标签会触发状态校验失败
                val fresh = runCatching { client.getText(entry) }.getOrDefault(indexHtml)
                val target =
                    "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$DataList1\$$ctl\$LinkButton1"
                val key = "${pair.first}_${ctl}_$name"
                val html = try {
                    val form = linkedMapOf(
                        "__EVENTTARGET" to target,
                        "__EVENTARGUMENT" to "",
                        "__LASTFOCUS" to "",
                        "__VIEWSTATE" to hidden(fresh, "__VIEWSTATE"),
                        "__VIEWSTATEGENERATOR" to hidden(fresh, "__VIEWSTATEGENERATOR"),
                        "__EVENTVALIDATION" to hidden(fresh, "__EVENTVALIDATION"),
                        "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$ddlSslb" to FILTER_ALL,
                        "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$txtSsmc" to ""
                    )
                    client.postFormText(entry, form)
                } catch (e: Exception) {
                    summary.appendLine()
                    summary.appendLine("========== $name（$ctl）==========")
                    summary.appendLine("标签回发失败：${e.message}")
                    delay(400)
                    continue
                }

                files["$key.html"] = html
                summary.appendLine()
                summary.appendLine("========== $name（$ctl）==========")
                summary.appendLine("长度：${html.length}  是否登录页：${looksLikeLogin(html)}")
                summary.appendLine("正文开头：${plainText(html).take(200)}")
                summary.appendLine("表格：")
                appendTables(summary, html)
                summary.appendLine("表单控件：")
                appendFormControls(summary, html)
                summary.appendLine("全部 postback 目标：")
                appendPostbacks(summary, html)

                val clicks = clickable(html, 3)
                if (clicks.isEmpty()) {
                    summary.appendLine("（无白名单内的可点入口）")
                } else {
                    for ((ci, pb) in clicks.withIndex()) {
                        onProgress("正在打开 $name → ${pb.label}…")
                        try {
                            val sub = client.postFormText(entry, pbForm(html, pb))
                            files["${key}_子${ci + 1}_${safeName(pb.label)}.html"] = sub
                            summary.appendLine()
                            summary.appendLine("---------- $name → ${pb.label} ----------")
                            summary.appendLine("控件：${pb.target}  行命令：${pb.arg.ifBlank { "空" }}")
                            summary.appendLine("长度：${sub.length}  是否登录页：${looksLikeLogin(sub)}")
                            summary.appendLine("正文开头：${plainText(sub).take(240)}")
                            summary.appendLine("表格：")
                            appendTables(summary, sub)
                            summary.appendLine("表单控件：")
                            appendFormControls(summary, sub)
                            summary.appendLine("全部 postback 目标：")
                            appendPostbacks(summary, sub)

                            // 申报表单还有四个子页签（基本信息 / 项目成员 / 指导教师 / 附件上传），
                            // 它们回发到 xsXmsb.aspx 而不是首页，地址和参数都不同，单独抓一遍。
                            if (sub.contains("btnTj", true)) {
                                captureFormTabs(sub, key, "子${ci + 1}", files, summary, client, onProgress)
                            }
                        } catch (e: Exception) {
                            summary.appendLine()
                            summary.appendLine("---------- $name → ${pb.label} ----------")
                            summary.appendLine("打开失败：${e.message}")
                        }
                        delay(500)
                    }
                }
                delay(400)
            }

            onProgress("正在打包…")
            val dir = File(context.cacheDir, "logs").apply { if (!exists()) mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            files["99_结构摘要.txt"] = summary.toString()

            val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.CHINA).format(Date())
            val zip = File(dir, "竞赛页面结构_$stamp.zip")
            ZipOutputStream(FileOutputStream(zip)).use { zos ->
                files.forEach { (name, content) ->
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            Result(true, zip.absolutePath, zip, "已采集 ${files.size} 个文件")
        } catch (e: Exception) {
            AppLog.e("CompetitionProbe", "采集失败", e)
            Result(false, message = e.message ?: e::class.java.simpleName)
        }
    }

    fun share(context: Context, file: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "学科竞赛平台页面结构")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享页面结构").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    }.getOrDefault(false)

    // ---------- 申报表单的四个子页签 ----------

    /** 申报页 `DataList1` 下的子页签（顺序固定：ctl00…ctl03） */
    private val FORM_TABS = listOf("基本信息", "项目成员", "指导教师", "附件上传")

    /**
     * 抓申报表单的「项目成员 / 指导教师 / 附件上传」。
     *
     * 这三个页签和主页面**不是一个地址**：表单的 `action` 是
     * `./xsXmsb.aspx?sbid=0&ssdm=N&sf=hjsb`（N 是赛事类别号），
     * 回发要往这个地址 POST，而且隐藏字段必须用这一页的。
     *
     * 只发 `__EVENTTARGET`，不带任何业务字段 —— 目的只是拿到结构，
     * 这样绝不会往申报单里写数据。
     */
    private fun captureFormTabs(
        html: String,
        key: String,
        prefix: String,
        files: MutableMap<String, String>,
        summary: StringBuilder,
        client: AufeClient,
        onProgress: (String) -> Unit
    ) {
        val formTag = Regex("<form[^>]*>", IG).find(html)?.value ?: return
        val action = decodeEntities(attr(formTag, "action").orEmpty())
        if (action.isBlank()) return
        val url = resolveUrl(Endpoints.XSXM_MAIN, action)

        FORM_TABS.drop(1).forEachIndexed { idx, name ->
            val ctl = "ctl0${idx + 1}"
            val target = "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$DataList1\$$ctl\$LinkButton1"
            onProgress("正在打开 $prefix → $name…")
            runCatching {
                val params = linkedMapOf(
                    "__LASTFOCUS" to "",
                    "__EVENTVALIDATION" to hidden(html, "__EVENTVALIDATION"),
                    "__VIEWSTATEGENERATOR" to hidden(html, "__VIEWSTATEGENERATOR"),
                    "__VIEWSTATE" to hidden(html, "__VIEWSTATE"),
                    "__EVENTTARGET" to target,
                    "__EVENTARGUMENT" to ""
                )
                client.postFormText(url, params)
            }.onSuccess { tabHtml ->
                files["${key}_${prefix}_${safeName(name)}.html"] = tabHtml
                summary.appendLine()
                summary.appendLine("---------- $prefix → $name ----------")
                summary.appendLine("地址：$url")
                summary.appendLine("长度：${tabHtml.length}")
                summary.appendLine("正文开头：${plainText(tabHtml).take(240)}")
                val tabForm = Regex("<form[^>]*>", IG).find(tabHtml)?.value.orEmpty()
                summary.appendLine("enctype：${attr(tabForm, "enctype") ?: "(无)"}")
                summary.appendLine("表单控件：")
                appendFormControls(summary, tabHtml)
                summary.appendLine("全部 postback 目标：")
                appendPostbacks(summary, tabHtml)
            }.onFailure {
                summary.appendLine()
                summary.appendLine("---------- $prefix → $name ----------")
                summary.appendLine("打开失败：${it.message}")
            }
        }
    }

    /** `./xsXmsb.aspx?a=1` 这种相对地址补成绝对地址 */
    private fun resolveUrl(base: String, action: String): String {
        if (action.startsWith("http", true)) return action
        val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: return action
        val dir = base.substringAfter(origin).substringBeforeLast('/', "")
        val path = if (action.startsWith("/")) action else "$dir/${action.removePrefix("./")}"
        return origin + path
    }

    // ---------- postback ----------

    private data class Postback(val target: String, val arg: String, val label: String)

    /**
     * 解析页面里所有 `__doPostBack`。
     *
     * 关键：ASP.NET 把参数里的单引号写成 `&#39;`，不解实体一条都匹配不上
     * （v1.10.0 就是栽在这，摘要里"可用的 postback 目标"全空）。
     */
    private fun postbacks(html: String): List<Postback> {
        val out = mutableListOf<Postback>()
        Regex(
            """<a[^>]*href\s*=\s*["']javascript:__doPostBack\(([^)]*)\)["'][^>]*>([\s\S]*?)</a>""",
            IG
        ).findAll(html).forEach { m ->
            val args = decodeEntities(m.groupValues[1])
            val pair = Regex("""'([^']*)'\s*,\s*'([^']*)'""").find(args) ?: return@forEach
            out.add(
                Postback(
                    target = pair.groupValues[1],
                    arg = pair.groupValues[2],
                    label = plainText(m.groupValues[2]).take(40)
                )
            )
        }
        return out
    }

    /** 白名单内的子入口，最多 [limit] 个 */
    private fun clickable(html: String, limit: Int): List<Postback> =
        postbacks(html)
            .filter { it.arg.isNotBlank() }                       // 整页回发的不要
            .filter { it.arg.contains("$") }                      // 只认 GridView 行命令
            .filter { pb -> CLICK_ALLOW.any { pb.label.contains(it) } }
            .filter { pb -> CLICK_DENY.none { pb.label.contains(it) } }
            .distinctBy { it.target + "|" + it.arg }
            .take(limit)

    /** 用「链接所在页面」的 VIEWSTATE 组装点击请求 —— 跨页复用必然失败 */
    private fun pbForm(baseHtml: String, pb: Postback): Map<String, String> = linkedMapOf(
        "__EVENTTARGET" to pb.target,
        "__EVENTARGUMENT" to pb.arg,
        "__LASTFOCUS" to "",
        "__VIEWSTATE" to hidden(baseHtml, "__VIEWSTATE"),
        "__VIEWSTATEGENERATOR" to hidden(baseHtml, "__VIEWSTATEGENERATOR"),
        "__EVENTVALIDATION" to hidden(baseHtml, "__EVENTVALIDATION"),
        "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$ddlSslb" to FILTER_ALL,
        "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$txtSsmc" to ""
    )

    // ---------- 摘要 ----------

    private fun appendTables(sb: StringBuilder, html: String) {
        Regex("<table[^>]*>", IG).findAll(html).forEach { m ->
            val id = attr(m.value, "id") ?: "(无 id)"
            val end = html.indexOf("</table>", m.range.last).let { if (it < 0) html.length else it }
            val body = html.substring(m.range.last + 1, end)
            val rows = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", IG).findAll(body).toList()
            val head = rows.firstOrNull()?.groupValues?.get(1)?.let { plainText(it) }.orEmpty()
            val sampleRow = rows.drop(1).firstOrNull()?.groupValues?.get(1)
            val sample = sampleRow?.let {
                Regex("<td[^>]*>([\\s\\S]*?)</td>", IG).findAll(it)
                    .map { c -> plainText(c.groupValues[1]) }.toList()
            } ?: emptyList()
            sb.appendLine("  - id=$id  行数=${rows.size}")
            sb.appendLine("      表头：${head.take(160)}")
            if (sample.isNotEmpty()) {
                sb.appendLine("      首行数据：${sample.joinToString(" | ").take(220)}")
            }
            sampleRow?.let { r ->
                val cmds = Regex("""__doPostBack\(([^)]*)\)""", IG).findAll(r)
                    .map { decodeEntities(it.groupValues[1]) }.toList()
                if (cmds.isNotEmpty()) {
                    sb.appendLine("      首行命令：${cmds.joinToString(" ／ ").take(200)}")
                }
            }
        }
    }

    private fun appendFormControls(sb: StringBuilder, html: String) {
        Regex("<input[^>]*>", IG).findAll(html).forEach { m ->
            val tag = m.value
            val name = attr(tag, "name") ?: return@forEach
            if (name.startsWith("__")) return@forEach
            val type = attr(tag, "type") ?: "text"
            if (type.equals("hidden", true)) return@forEach
            val value = attr(tag, "value").orEmpty().take(40)
            sb.appendLine("  - input  name=$name  type=$type  value=$value")
        }
        Regex("<select[^>]*>", IG).findAll(html).forEach { m ->
            val name = attr(m.value, "name") ?: return@forEach
            sb.appendLine("  - select name=$name")
        }
        Regex("<textarea[^>]*>", IG).findAll(html).forEach { m ->
            val name = attr(m.value, "name") ?: return@forEach
            sb.appendLine("  - textarea name=$name")
        }
    }

    private fun appendPostbacks(sb: StringBuilder, html: String) {
        val list = postbacks(html)
        if (list.isEmpty()) {
            sb.appendLine("  （无）")
            return
        }
        list.distinctBy { it.target + "|" + it.arg }.take(60).forEach { pb ->
            sb.appendLine("  - 「${pb.label}」 → ${pb.target}  arg=${pb.arg.ifBlank { "空" }}")
        }
    }

    // ---------- 小工具 ----------

    private fun decodeEntities(s: String): String = s
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun hidden(html: String, name: String): String = Regex(
        """<input[^>]*name\s*=\s*["']${Regex.escape(name)}["'][^>]*>""",
        IG
    ).find(html)?.let { attr(it.value, "value") }.orEmpty()

    private fun attr(tag: String, name: String): String? = Regex(
        """\b$name\s*=\s*["']([^"']*)["']""",
        IG
    ).find(tag)?.groupValues?.get(1)

    private fun safeName(label: String): String =
        label.replace(Regex("[\\\\/:*?\"<>|\\s]"), "").take(12).ifBlank { "页" }

    private fun plainText(html: String): String = html
        .replace(Regex("<script[\\s\\S]*?</script>", IG), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", IG), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun looksLikeLogin(html: String): Boolean =
        html.contains("login", true) && html.length < 8000 &&
            (html.contains("密码") || html.contains("password", true))

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
