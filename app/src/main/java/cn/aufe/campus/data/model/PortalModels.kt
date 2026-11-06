package cn.aufe.campus.data.model

/**
 * 学科竞赛平台（xsXmMain.aspx）的原生数据模型。
 *
 * 这个平台是 ASP.NET WebForms：所有页面都是同一张表单的不同"状态"，
 * 交互靠 `__EVENTTARGET`（点哪个控件）+ `__EVENTARGUMENT`（行命令，如 `xmsb$0`）。
 *
 * 所以模型里几乎每个能点的地方都带一个 [PortalAction] —— 它记着"点它会往服务端发什么"，
 * 而不是记"点了会打开哪个 Activity"。界面只管把 action 交回接口层重发一次。
 */

/** 一个"可以点"的东西：服务端回发目标 + 行参数 */
data class PortalAction(
    /** 界面上显示的文字：申报 / 项目详情 / 下一页 / 删除 */
    val label: String = "",
    /** `__EVENTTARGET`，例如 `ctl00$ContentPlaceHolder1$ContentPlaceHolder2$gvSb` */
    val target: String = "",
    /** `__EVENTARGUMENT`，例如 `xmsb$0`；翻页/按钮类为空 */
    val argument: String = "",
    /** 提交按钮的 name（`<input type=submit>` 走这条，不用 EVENTTARGET） */
    val submitName: String = "",
    /** 提交按钮的原始 value（"提  交" 里的空格不能丢，个别页面会比对 value） */
    val submitValue: String = "",
    /** 危险动作（删除类），界面需要二次确认 */
    val danger: Boolean = false
)

/** 表格一列 */
data class PortalColumn(val title: String, val width: Float? = null)

/** 表格一行：单元格文字 + 这一行上的动作 */
data class PortalRow(
    val cells: List<String> = emptyList(),
    val actions: List<PortalAction> = emptyList()
)

/** 分页信息 */
data class PortalPager(
    val pageIndex: Int = 1,
    val pageCount: Int = 1,
    val first: PortalAction? = null,
    val prev: PortalAction? = null,
    val next: PortalAction? = null,
    val last: PortalAction? = null
) {
    val shown: Boolean get() = pageCount > 1 || pageIndex > 1
}

// ---------------- 表单 ----------------

enum class PortalFieldType { TEXT, PASSWORD, TEXTAREA, SELECT, CHECKBOX, RADIO, FILE, DATE }

data class PortalOption(
    val value: String,
    val label: String,
    val selected: Boolean = false
)

/**
 * 一个表单控件。
 *
 * [label] 是从页面里"猜"出来的：WebForms 的表单是 `<td>标签：</td><td>控件</td>` 这种排版，
 * 取控件所在单元格左边那个单元格的文字、去掉末尾冒号。
 */
data class PortalField(
    val name: String,
    val label: String,
    val type: PortalFieldType = PortalFieldType.TEXT,
    val value: String = "",
    val options: List<PortalOption> = emptyList(),
    /** 控件右侧的红色提示（"与获奖证书一致"这类） */
    val hint: String = "",
    val readOnly: Boolean = false,
    val maxLength: Int = 0
)

/** 表单里的一组字段（按小节分组显示） */
data class PortalSection(
    val title: String = "",
    val fields: List<PortalField> = emptyList()
)

// ---------------- 页面 ----------------

enum class PortalKind { GRID, FORM, UNKNOWN }

/** 一个页签（列表页顶部的四个标签，或申报页的四个子页签） */
data class PortalTabLink(
    val label: String,
    val action: PortalAction,
    val selected: Boolean = false
)

/**
 * 解析好、可直接渲染的一页。
 *
 * [raw] 是服务端原文，提交时要靠它里面的隐藏字段（`__VIEWSTATE` 等）；
 * 界面不碰它，只用来在回发时拼表单。
 */
data class PortalDoc(
    val kind: PortalKind = PortalKind.UNKNOWN,
    /** 标题（取自页面 h1/标题栏，取不到就空） */
    val title: String = "",
    val tabs: List<PortalTabLink> = emptyList(),
    val columns: List<PortalColumn> = emptyList(),
    val rows: List<PortalRow> = emptyList(),
    val pager: PortalPager = PortalPager(),
    val sections: List<PortalSection> = emptyList(),
    /** 表单底部的提交类按钮：暂存 / 提交 / 上传 / 添加成员… */
    val buttons: List<PortalAction> = emptyList(),
    /** 表格为空时的提示语（服务端给的，如"当前没有申报任何项目"） */
    val emptyHint: String = ""
) {
    val fields: List<PortalField> get() = sections.flatMap { it.fields }
}
