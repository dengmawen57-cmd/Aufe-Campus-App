package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.PortalAction
import cn.aufe.campus.data.model.PortalColumn
import cn.aufe.campus.data.model.PortalDoc
import cn.aufe.campus.data.model.PortalField
import cn.aufe.campus.data.model.PortalFieldType
import cn.aufe.campus.data.model.PortalKind
import cn.aufe.campus.data.model.PortalOption
import cn.aufe.campus.data.model.PortalPager
import cn.aufe.campus.data.model.PortalRow
import cn.aufe.campus.data.model.PortalSection
import cn.aufe.campus.data.model.PortalTabLink

/**
 * 竞赛平台 HTML → 原生模型。
 *
 * ## 为什么用正则而不是 Jsoup
 * 只需要 `<table>/<tr>/<td>/<a>/<input>/<select>/<textarea>` 这几类扁平结构，
 * 页面里没有嵌套表格（分页行用 colspan 而不是套表），正则够用，省一个 400KB 依赖。
 *
 * ## 两条铁律（踩过的坑）
 * 1. **`__doPostBack` 参数在 HTML 里是 `&#39;` 实体**。不解实体就一条都匹配不出来，
 *    表现是"摘要里 postback 目标全空"。
 * 2. **表格行有两种**：数据行（td 数 = 表头数）和分页行（单个 td 带 colspan）。
 *    把分页行当数据行会让列表里多出一行乱码。
 *
 * ## 表单字段的标签是"猜"出来的
 * WebForms 的排版是 `<td>项目名称：</td><td><input></td>`，也可能一行放两对
 * （`<td>级别：</td><td><select></td><td>等级：</td><td><select></td>`）。
 * 规则：控件所在单元格左边那个单元格若是纯文字，就当标签。
 */
object PortalParser {

    /** WebForms 层级分隔符 */
    private const val SEP = "\$"

    private val ROW = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val CELL = Regex("<t([dh])[^>]*>([\\s\\S]*?)</t\\1>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val POSTBACK = Regex("__doPostBack\\('([^']*)'\\s*,\\s*'([^']*)'\\)")
    private val INPUT_TAG = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SELECT_TAG = Regex("<select\\b([^>]*)>([\\s\\S]*?)</select>", RegexOption.IGNORE_CASE)
    private val TEXTAREA_TAG = Regex("<textarea\\b([^>]*)>([\\s\\S]*?)</textarea>", RegexOption.IGNORE_CASE)
    private val OPTION_TAG = Regex("<option\\b([^>]*)>([\\s\\S]*?)</option>", RegexOption.IGNORE_CASE)
    private val ANCHOR_TAG = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** 列表页上真正属于"筛选"的控件；其余控件（学分转移等）不暴露给界面 */
    private const val FILTER_TEXT = "txtSsmc|ddlSslb|txtXsxmQsrq|txtXsxmZzrq|txtXsxmXmmc"
    private const val FILTER_BUTTON = "Button1|btnXsxmSx"

    /** 绝对不能渲染成原生按钮的控件：注销会把登录态清掉 */
    private const val NEVER_EXPOSE = "btnExit|btnGrzl|txtNewPageIndex|__LASTFOCUS"

    // ---------------------------------------------------------------- 入口

    /**
     * 解析一页。
     *
     * @param html 服务端原文（未解实体）
     */
    fun parse(html: String): PortalDoc {
        val simple = unquote(html)
        val tabs = parseTabs(simple)
        val grid = parseGrid(simple)
        val sections = formSections(html)
        val rawButtons = formButtons(simple)
        val buttons = dropTabDuplicates(rawButtons, tabs)

        // 判定"这是一张表单"而不是列表：看有没有表单专属的痕迹。
        // 只看"有没有表格"是不够的 —— 申报页的「项目成员」子页签本身也可能是一个
        // GridView（已加成员列表），下面才是"添加成员"的输入框，那种页面必须当表单渲染。
        val looksForm = sections.any { it.fields.isNotEmpty() } &&
            (rawButtons.any { it.submitName.endsWith("btnTj") || it.submitName.endsWith("btnZc") } ||
                sections.any { s -> s.fields.any { it.type == PortalFieldType.FILE } })

        if (looksForm) {
            return PortalDoc(
                kind = PortalKind.FORM,
                title = pageTitle(html),
                tabs = tabs,
                sections = sections,
                buttons = buttons
            )
        }

        if (grid != null) {
            val (columns, rows, pager, emptyHint) = grid
            return PortalDoc(
                kind = PortalKind.GRID,
                title = pageTitle(html),
                tabs = tabs,
                columns = columns,
                rows = rows,
                pager = pager,
                emptyHint = emptyHint,
                sections = listOf(PortalSection(title = "筛选", fields = listFields(html))),
                buttons = listButtons(simple)
            )
        }

        return PortalDoc(
            kind = PortalKind.UNKNOWN,
            title = pageTitle(html),
            tabs = tabs,
            sections = sections,
            buttons = buttons
        )
    }

    // ---------------------------------------------------------------- 页签

    /**
     * 剔掉与页签同名的按钮。
     *
     * 申报表单的「基本信息 / 项目成员 / 指导教师 / 附件上传」在页面上出现两次：
     * 一次是 `DataList1` 里的页签（对应 [parseTabs]），一次是底部的
     * `<a __doPostBack>` 导航链接（对应 [formButtons] 的 links）。
     * 两边都渲染出来，用户就会看到上下两排一模一样的按钮（v1.11.2 的界面问题）。
     * 页签已经在顶部常驻，底部只保留真正的操作按钮（暂存 / 提交 / 添加成员）。
     */
    private fun dropTabDuplicates(
        buttons: List<PortalAction>,
        tabs: List<PortalTabLink>
    ): List<PortalAction> {
        if (tabs.isEmpty()) return buttons
        val navLabels = tabs.mapTo(HashSet()) { it.label.trim() }
        return buttons.filterNot { it.label.trim() in navLabels }
    }

    /**
     * 页签来自 `DataList1`：每个 `<td class="SelectedTab|tab"><a href="__doPostBack(...)">文字</a></td>`。
     * 列表页四个主标签和申报页四个子页签用的是同一个控件，所以一套解析通吃。
     */
    private fun parseTabs(simple: String): List<PortalTabLink> {
        val block = tableById(simple, "DataList1") ?: return emptyList()
        val out = mutableListOf<PortalTabLink>()
        val cellRe = Regex(
            "<td[^>]*class\\s*=\\s*[\"'](SelectedTab|tab)[\"'][^>]*>([\\s\\S]*?)</td>",
            RegexOption.IGNORE_CASE
        )
        cellRe.findAll(block).forEach { m ->
            val selected = m.groupValues[1].equals("SelectedTab", true)
            val cell = m.groupValues[2]
            val href = ANCHOR_TAG.find(cell)?.value ?: return@forEach
            val pb = POSTBACK.find(href) ?: return@forEach
            val label = text(cell)
            if (label.isBlank()) return@forEach
            out.add(
                PortalTabLink(
                    label = label,
                    action = PortalAction(label = label, target = pb.groupValues[1], argument = pb.groupValues[2]),
                    selected = selected
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 表格

    private data class Grid(
        val columns: List<PortalColumn>,
        val rows: List<PortalRow>,
        val pager: PortalPager,
        val emptyHint: String
    )

    /**
     * 找 `ContentPlaceHolder2_gvXXX` 这张 GridView。
     *
     * GridView 的表格 id 一定是 `..._ContentPlaceHolder2_gv` 开头：
     * `gvSb`（获奖申报）/ `gvBmxm`（报名参赛）/ `gvYbmxm`（我已报名）/ `gvHj`（已申报奖项）。
     */
    private fun parseGrid(simple: String): Grid? {
        val head = Regex(
            "<table[^>]*id\\s*=\\s*[\"'][^\"']*ContentPlaceHolder2_(gv[A-Za-z0-9_]*)",
            RegexOption.IGNORE_CASE
        ).find(simple) ?: return null
        val block = sliceTable(simple, head.range.first) ?: return null

        var columns = emptyList<PortalColumn>()
        val rows = mutableListOf<PortalRow>()
        var emptyHint = ""

        ROW.findAll(block).forEach { rowMatch ->
            val cells = CELL.findAll(rowMatch.groupValues[1]).toList()
            if (cells.isEmpty()) return@forEach
            val isHeader = cells.all { it.groupValues[1].equals("h", true) }

            if (isHeader && columns.isEmpty()) {
                columns = cells.map {
                    PortalColumn(title = text(it.groupValues[2]), width = widthOf(it.groupValues[0]))
                }
                return@forEach
            }

            // 分页行：只有一个跨列的 td
            val hasColspan = cells.any { colspanOf(it.groupValues[0]) > 1 }
            if (hasColspan) {
                // 空表格提示（"当前没有申报任何项目"）也长这样，取里面有字的那一格
                val t = text(rowMatch.groupValues[1])
                if (emptyHint.isBlank() && rows.isEmpty() && t.isNotBlank() && !t.contains("页")) emptyHint = t
                return@forEach
            }

            val texts = cells.map { text(it.groupValues[2]) }
            if (texts.all { it.isBlank() }) return@forEach
            // 表头行（<th>）已单独处理；这里排除另一种"全是 th"的分段行
            rows.add(PortalRow(cells = texts, actions = actionsOf(rowMatch.groupValues[1])))
        }

        val pager = parsePager(simple)
        return Grid(columns, rows, pager, emptyHint)
    }

    private fun parsePager(simple: String): PortalPager {
        fun intOf(idPart: String): Int = Regex(
            "<span[^>]*id\\s*=\\s*[\"'][^\"']*$idPart[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>",
            RegexOption.IGNORE_CASE
        ).find(simple)?.let { text(it.groupValues[1]).toIntOrNull() } ?: 1

        fun link(idPart: String): PortalAction? {
            val tag = Regex("<a[^>]*id\\s*=\\s*[\"'][^\"']*$idPart[^\"']*[\"'][^>]*>", RegexOption.IGNORE_CASE)
                .find(simple)?.value ?: return null
            val pb = POSTBACK.find(tag) ?: return null
            return PortalAction(label = idPart, target = pb.groupValues[1], argument = pb.groupValues[2])
        }

        return PortalPager(
            pageIndex = intOf("LabelCurrentPage"),
            pageCount = intOf("LabelPageCount"),
            first = link("LinkButtonFirstPage"),
            prev = link("LinkButtonPreviousPage"),
            next = link("LinkButtonNextPage"),
            last = link("LinkButtonLastPage")
        )
    }

    // ---------------------------------------------------------------- 行内动作

    /**
     * 一行里的可点项。
     *
     * `__EVENTARGUMENT` 决定对哪一行生效（`xmsb$0` = 第 1 行点申报），
     * 所以动作必须挂在行上，不能提到表格级。
     */
    private fun actionsOf(rowHtml: String): List<PortalAction> {
        val out = mutableListOf<PortalAction>()
        ANCHOR_TAG.findAll(rowHtml).forEach { m ->
            val tag = m.value
            val pb = POSTBACK.find(tag) ?: return@forEach
            // 标签文字在 <a> 之后、下一个 < 之前
            val after = rowHtml.substring(m.range.last + 1)
            val label = text(after.takeWhile { it != '<' })
            if (label.isBlank()) return@forEach
            out.add(
                PortalAction(
                    label = label,
                    target = pb.groupValues[1],
                    argument = pb.groupValues[2],
                    danger = isDanger(label)
                )
            )
        }
        return out
    }

    private fun isDanger(label: String) =
        label.contains("删除") || label.contains("撤销") || label.contains("取消") || label.contains("注销")

    // ---------------------------------------------------------------- 列表页筛选

    /** 列表页只暴露筛选控件，其余（如学分转移表单）一律不渲染，避免误触 */
    private fun listFields(html: String): List<PortalField> =
        controls(html).filter { f -> FILTER_TEXT.split("|").any { f.name.endsWith(it) } }

    private fun listButtons(simple: String): List<PortalAction> =
        submitButtons(simple).filter { a -> FILTER_BUTTON.split("|").any { a.submitName.endsWith(it) } }

    // ---------------------------------------------------------------- 表单

    private fun formSections(html: String): List<PortalSection> {
        val fieldList = controls(html)
        if (fieldList.isEmpty()) return emptyList()

        // 按页面里出现的小节标题切段（"第一步：项目基本信息"这种）
        val markers = SECTION_MARKER.findAll(unquote(html))
            .map { it.range.first to it.groupValues[1].trim() }
            .toList()

        val byTitle = linkedMapOf<String, MutableList<PortalField>>()
        fieldList.forEach { f ->
            val pos = html.indexOf("name=\"${f.name}\"")
            val title = markers.lastOrNull { pos >= 0 && it.first < pos }?.second.orEmpty()
            byTitle.getOrPut(title) { mutableListOf() }.add(f)
        }
        return byTitle.map { (title, list) -> PortalSection(title = title, fields = list) }
    }

    private val SECTION_MARKER = Regex(
        "(第[一二三四五六七八九十]+步[:：][^<]{0,40})",
        RegexOption.IGNORE_CASE
    )

    private fun formButtons(simple: String): List<PortalAction> {
        val submits = submitButtons(simple).filter { a ->
            NEVER_EXPOSE.split("|").none { a.submitName.endsWith(it) }
        }
        // 表单里用链接形态出现的动作（添加成员 / 上传 / 移除…）
        val links = ANCHOR_TAG.findAll(simple).mapNotNull { m ->
            val tag = m.value
            val pb = POSTBACK.find(tag) ?: return@mapNotNull null
            val after = simple.substring(m.range.last + 1)
            val label = text(after.takeWhile { it != '<' })
            if (label.isBlank() || label.length > 8) return@mapNotNull null
            PortalAction(
                label = label,
                target = pb.groupValues[1],
                argument = pb.groupValues[2],
                danger = isDanger(label)
            )
        }.toList()
        return submits + links
    }

    private fun submitButtons(simple: String): List<PortalAction> =
        INPUT_TAG.findAll(simple).mapNotNull { m ->
            val tag = m.value
            if (!attr(tag, "type").equals("submit", true)) return@mapNotNull null
            val name = attr(tag, "name")
            if (name.isBlank()) return@mapNotNull null
            val value = decode(attr(tag, "value")).ifBlank { name.substringAfterLast(SEP) }
            PortalAction(
                label = value.replace(" ", ""),
                submitName = name,
                submitValue = value,
                danger = isDanger(value)
            )
        }.toList()

    /**
     * 抽出所有可编辑控件。
     *
     * 顺序按页面出现顺序，界面渲染时保持原样 —— 服务端字段是有逻辑顺序的
     * （先项目名称、再级别/等级、再日期），打乱会让人填错。
     */
    private fun controls(html: String): List<PortalField> {
        data class Hit(val pos: Int, val field: PortalField)

        val hits = mutableListOf<Hit>()

        INPUT_TAG.findAll(html).forEach { m ->
            val tag = m.value
            val type = attr(tag, "type").lowercase()
            val name = attr(tag, "name")
            if (name.isBlank()) return@forEach
            if (type in setOf("hidden", "submit", "button", "image", "reset")) return@forEach
            if (NEVER_EXPOSE.split("|").any { name.endsWith(it) }) return@forEach

            val value = decode(attr(tag, "value"))
            val field = when (type) {
                "radio", "checkbox" -> PortalField(
                    name = name,
                    label = "",
                    type = if (type == "radio") PortalFieldType.RADIO else PortalFieldType.CHECKBOX,
                    value = value,
                    options = listOf(
                        PortalOption(
                            value = value,
                            label = decode(attr(tag, "title")).ifBlank { value },
                            selected = tag.contains("checked", true)
                        )
                    )
                )
                "file" -> PortalField(name = name, label = "", type = PortalFieldType.FILE)
                "password" -> PortalField(name = name, label = "", type = PortalFieldType.PASSWORD, value = value)
                else -> PortalField(
                    name = name,
                    label = "",
                    type = PortalFieldType.TEXT,
                    value = value,
                    maxLength = attr(tag, "maxlength").toIntOrNull() ?: 0
                )
            }
            hits.add(Hit(m.range.first, field))
        }

        SELECT_TAG.findAll(html).forEach { m ->
            val head = "<select${m.groupValues[1]}>"
            val name = attr(head, "name")
            if (name.isBlank()) return@forEach
            if (NEVER_EXPOSE.split("|").any { name.endsWith(it) }) return@forEach
            val options = OPTION_TAG.findAll(m.groupValues[2]).map { o ->
                PortalOption(
                    value = decode(attr("<x${o.groupValues[1]}>", "value")),
                    label = text(o.groupValues[2]),
                    selected = o.groupValues[1].contains("selected", true)
                )
            }.toList()
            if (options.isEmpty()) return@forEach
            val chosen = options.firstOrNull { it.selected } ?: options.first()
            hits.add(
                Hit(
                    m.range.first,
                    PortalField(
                        name = name,
                        label = "",
                        type = PortalFieldType.SELECT,
                        value = chosen.value,
                        options = options
                    )
                )
            )
        }

        TEXTAREA_TAG.findAll(html).forEach { m ->
            val head = "<textarea${m.groupValues[1]}>"
            val name = attr(head, "name")
            if (name.isBlank()) return@forEach
            hits.add(
                Hit(
                    m.range.first,
                    PortalField(
                        name = name,
                        label = "",
                        type = PortalFieldType.TEXTAREA,
                        value = decode(m.groupValues[2]).trim()
                    )
                )
            )
        }

        // 同名 radio/checkbox 合并成一组
        val merged = linkedMapOf<String, PortalField>()
        hits.sortedBy { it.pos }.forEach { h ->
            val f = h.field
            val old = merged[f.name]
            if (old != null && (f.type == PortalFieldType.RADIO || f.type == PortalFieldType.CHECKBOX)) {
                merged[f.name] = old.copy(options = old.options + f.options)
            } else if (old == null) {
                merged[f.name] = f
            }
        }

        return merged.values.map { f -> f.copy(label = labelFor(html, f.name), hint = hintFor(html, f.name)) }
    }

    /**
     * 猜控件标签。
     *
     * 这个平台有**三种**排版，只认第一种会让界面退化成一排"填写内容"：
     * 1. 表格版式（申报表单）：`<td>项目具体名称：</td><td><input></td>`
     * 2. 内联版式（各列表页的筛选区）：`按发布日期范围筛选：<input>至 <input>`
     * 3. 只有提示语没有标签：`<span>输入赛事部分名称筛选…</span><input>`
     *
     * 最后再用控件名字典兜一层 —— 控件名（`txtSsmc` / `ddlSslb`）是平台固定的，
     * 比任何启发式都可靠。
     */
    private fun labelFor(html: String, name: String): String {
        // 字典里的字段名是平台固定的，比从 HTML 里猜更可靠，先查
        val known = knownLabel(name)
        if (known.isNotBlank()) return known
        val pos = html.indexOf("name=\"$name\"")
        if (pos < 0) return ""
        return cellLabel(html, pos).ifBlank { inlineLabel(html, pos) }
    }

    /** 版式 1：控件在 `<td>` 里，标签是同一格的开头文字，或者左边那一格 */
    private fun cellLabel(html: String, pos: Int): String {
        val win = html.substring(maxOf(0, pos - 1400), pos)
        val tdOpen = win.lastIndexOf("<td", ignoreCase = true)
        val tdClose = win.lastIndexOf("</td>", ignoreCase = true)
        // 控件根本不在单元格里（筛选区在 <div> 内）→ 交给内联逻辑
        if (tdOpen <= tdClose || tdOpen < 0) return ""

        // (a) 标签和控件同格：<td>项目名称：<input>
        val gt = win.indexOf('>', tdOpen)
        if (gt >= 0) {
            val inner = win.substring(gt + 1)
            if (!containsControl(inner)) {
                cleanLabel(text(inner))?.let { return it }
            }
        }

        // (b) 控件独占一格：往左逐格找纯文字格（最多看 4 格，跨表头/跨行就停）
        var left = win.substring(0, tdOpen)
        repeat(4) {
            val o = left.lastIndexOf("<td", ignoreCase = true)
            val c = left.lastIndexOf("</td>", ignoreCase = true)
            if (o < 0 || c < o) return ""
            val cellHtml = left.substring(o, c + 5)
            left = left.substring(0, o)
            if (!containsControl(cellHtml)) {
                cleanLabel(text(cellHtml))?.let { return it }
            }
        }
        return ""
    }

    /** 版式 2：控件前面的纯文本就是标签（`按发布日期范围筛选：<input>`） */
    private fun inlineLabel(html: String, pos: Int): String {
        val win = html.substring(maxOf(0, pos - 500), pos)
        // 掐掉还没闭合的 `<input `，剩下的最后一段标签外文字就是紧邻标签
        val tail = win.substringBeforeLast('<').substringAfterLast('>')
        return cleanLabel(text(tail)).orEmpty()
    }

    /** 标签清洗：解实体、去尾部冒号/星号，太长（整句提示）和纯连接词都不算标签 */
    private fun cleanLabel(raw: String): String? {
        val t = decode(raw).replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('：', ':', '＊', '*')
            .trim()
        if (t.isBlank() || t.length > 24) return null
        if (t in setOf("至", "~", "～", "-", "—", "到")) return null
        return t
    }

    /**
     * 已知控件名 → 中文标签。
     *
     * 只收那些"页面上真没有可读标签"的控件（筛选区那一排），
     * 其余仍然靠 [cellLabel] 从 HTML 里读，页面改版也不会全部失效。
     */
    private val KNOWN_LABELS = listOf(
        "txtXsxmQsrq" to "发布日期起",
        "txtXsxmZzrq" to "发布日期止",
        "txtXsxmXmmc" to "项目名称包含",
        "ddlSslb" to "赛事类别",
        "txtSsmc" to "赛事名称"
    )

    private fun knownLabel(name: String): String =
        KNOWN_LABELS.firstOrNull { name == it.first || name.endsWith("$" + it.first) }?.second.orEmpty()

    /** 控件右侧那行红色小字（"与获奖证书一致"），没有就找控件前面那句提示 */
    private fun hintFor(html: String, name: String): String {
        val idx = html.indexOf("name=\"$name\"")
        if (idx < 0) return ""
        val rowBounds = rowRangeAround(html, idx)
        if (rowBounds != null) {
            val rowHtml = html.substring(rowBounds.first, rowBounds.second)
            val cell = CELL.findAll(rowHtml).firstOrNull { it.groupValues[2].contains("name=\"$name\"") }
                ?.groupValues?.get(2)
            if (cell != null) {
                val after = cell.substringAfter("name=\"$name\"", "")
                val span = Regex("<span[^>]*>([^<]{2,40})</span>").find(after)?.groupValues?.get(1)
                if (span != null) return decode(span).trim()
            }
        }
        // 前导提示语：<span>输入赛事部分名称筛选（空代表所有赛事）</span><input>
        val pre = html.substring(maxOf(0, idx - 500), idx).substringBeforeLast('<')
        val before = Regex("<span[^>]*>([^<]{2,40})</span>\\s*$", RegexOption.IGNORE_CASE).find(pre)
            ?.groupValues?.get(1) ?: return ""
        return decode(before).trim()
    }

    private fun containsControl(cellHtml: String) =
        cellHtml.contains("<input", true) || cellHtml.contains("<select", true) ||
            cellHtml.contains("<textarea", true)

    // ---------------------------------------------------------------- 通用工具

    /** 取 `name="xxx"` 的 input 的 value（用于 `__VIEWSTATE` 等隐藏字段） */
    fun hiddenFields(html: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        INPUT_TAG.findAll(html).forEach { m ->
            val tag = m.value
            if (!attr(tag, "type").equals("hidden", true)) return@forEach
            val name = attr(tag, "name")
            if (name.isBlank()) return@forEach
            out[name] = decode(attr(tag, "value"))
        }
        // WebForms 的 submit 按钮只有在被点击时才出现在表单里，但有些页面把
        // "按钮的 name" 当成状态位；这里不主动带，交给调用方按需覆盖。
        return out
    }

    fun formAction(html: String): String =
        Regex("<form[^>]*>", RegexOption.IGNORE_CASE).find(html)
            ?.let { attr(it.value, "action") }
            ?.let { decode(it) }
            .orEmpty()

    fun isMultipart(html: String): Boolean {
        val form = Regex("<form[^>]*>", RegexOption.IGNORE_CASE).find(html)?.value ?: return false
        return attr(form, "enctype").contains("multipart", true)
    }

    /** 页面里有没有目标平台的登录框（会话掉了） */
    fun looksLikeLogin(html: String): Boolean =
        html.contains("txtUsername", true) || html.contains("name=\"txtPwd\"", true)

    /**
     * 取出页面里的 `alert('...')` 文案。
     *
     * 这个平台**不用 HTTP 状态表示失败**：提交成功/失败都是 200，
     * 结果只写在一句 `alert('保存成功')` 里。不抓这句，界面就只能显示"已提交"，
     * 而实际上可能因为"请上传证明材料"被拒。
     */
    fun alerts(html: String): List<String> =
        Regex("alert\\(\\s*['\"]([^'\"]*)['\"]\\s*\\)", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { decode(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun pageTitle(html: String): String {
        val t = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.let { text(it.groupValues[1]) }.orEmpty()
        return t
    }

    private fun rowRangeAround(html: String, pos: Int): Pair<Int, Int>? {
        if (pos < 0) return null
        val start = html.lastIndexOf("<tr", pos, ignoreCase = true)
        if (start < 0) return null
        val end = html.indexOf("</tr>", pos, ignoreCase = true)
        if (end < 0) return null
        return start to end
    }

    private fun tableById(html: String, idPart: String): String? {
        val head = Regex(
            "<table[^>]*id\\s*=\\s*[\"'][^\"']*$idPart[^\"']*[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return null
        return sliceTable(html, head.range.first)
    }

    /** 从 `<table ...>` 的起点切到配对的 `</table>`（处理嵌套） */
    private fun sliceTable(html: String, start: Int): String? {
        var depth = 0
        var i = start
        val open = Regex("<table\\b", RegexOption.IGNORE_CASE)
        val close = Regex("</table\\s*>", RegexOption.IGNORE_CASE)
        while (i < html.length) {
            val nextOpen = open.find(html, i)?.range?.first ?: Int.MAX_VALUE
            val nextClose = close.find(html, i)?.range
            if (nextClose == null) return null
            if (nextOpen < nextClose.first) {
                depth++
                i = nextOpen + 6
            } else {
                depth--
                i = nextClose.last + 1
                if (depth == 0) return html.substring(start, i)
            }
        }
        return null
    }

    private fun widthOf(cellAttrs: String): Float? {
        val style = Regex("width\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(cellAttrs)
        return style?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun colspanOf(cellAttrs: String): Int {
        val m = Regex("colspan\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE).find(cellAttrs)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    /** 属性取值（同名属性取第一个） */
    fun attr(tag: String, name: String): String =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1).orEmpty()

    /** 去标签 + 解实体 + 压空白 */
    fun text(html: String): String =
        decode(TAG.replace(html, " ")).replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    /** 把 `&#39;` 之类还原，只在"找链接"的副本上做，不动原文 */
    private fun unquote(html: String): String =
        html.replace("&#39;", "'").replace("&#x27;", "'").replace("&#039;", "'")

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 1)..(i + 10)) {
                    val name = s.substring(i + 1, semi)
                    val rep = when {
                        name == "nbsp" -> "\u00a0"
                        name == "amp" -> "&"
                        name == "lt" -> "<"
                        name == "gt" -> ">"
                        name == "quot" -> "\""
                        name == "apos" -> "'"
                        name.startsWith("#x") || name.startsWith("#X") ->
                            name.substring(2).toIntOrNull(16)?.toChar()?.toString()
                        name.startsWith("#") -> name.substring(1).toIntOrNull()?.toChar()?.toString()
                        else -> null
                    }
                    if (rep != null) {
                        sb.append(rep); i = semi + 1; continue
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}
