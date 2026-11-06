package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.PortalDoc
import cn.aufe.campus.data.model.PortalField
import cn.aufe.campus.data.model.PortalFieldType
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import cn.aufe.campus.data.net.UploadFile
import java.io.IOException

/**
 * 学科竞赛平台的原生接口层。
 *
 * ## 核心：把 WebForms 的"页面状态"当成一个对象传
 *
 * WebForms 每次回发都要带上一页的隐藏字段（`__VIEWSTATE` 可达 8KB、还有 `__EVENTVALIDATION`），
 * 而且**每次响应都会换新的**。所以这里不允许"记住一个 viewstate 反复用"，
 * 只能 [Page] 进 [Page] 出：每个方法都接收上一页、返回下一页。
 *
 * 少了任何一层，服务端的表现都是"页面原样返回、点了没有任何反应"。
 *
 * ## 三个动作形态
 * | 形态 | 参数 |
 * |---|---|
 * | 切标签 / 行内动作 / 翻页 | `__EVENTTARGET` + `__EVENTARGUMENT` |
 * | 提交按钮（暂存、提交、上传） | 按钮自身的 name（`__EVENTTARGET` 留空） |
 * | 附件上传 | 同上，但整包换成 `multipart/form-data` |
 */
class CompetitionPortalApi(private val client: AufeClient) {

    /**
     * 一次页面状态。
     *
     * [url] 是**本次响应里 `<form action>` 解析出来的绝对地址** —— 申报页会按类别变成
     * `xsXmsb.aspx?sbid=0&ssdm=1&sf=hjsb`，类别号不同地址就不同，绝不能写死。
     */
    data class Page(
        val url: String,
        val html: String,
        val doc: PortalDoc,
        val hidden: LinkedHashMap<String, String>,
        val fields: List<PortalField>
    ) {
        /** 取控件的当前值（界面改了值就用界面给的） */
        fun valueOf(name: String, edited: Map<String, String>): String =
            edited[name] ?: fields.firstOrNull { it.name == name }?.value.orEmpty()

        val isMultipart: Boolean get() = PortalParser.isMultipart(html)
    }

    // ---------------------------------------------------------------- 打开

    /**
     * 打首页。首页本身就是"获奖申报"的列表（服务端默认选中第一个标签）。
     */
    fun open(): Page {
        val html = client.getText(Endpoints.XSXM_MAIN)
        return page(html, Endpoints.XSXM_MAIN)
    }

    /**
     * 回发一次。
     *
     * @param target `__EVENTTARGET`；为空表示"点的是 submit 按钮"
     * @param argument `__EVENTARGUMENT`（`xmsb$0` 这类行命令）
     * @param edited 界面上用户改过的值（字段名 → 值），会覆盖服务端原值
     * @param button 点的是哪个按钮；非空时把按钮 name=value 一起送上去
     */
    fun post(
        from: Page,
        target: String = "",
        argument: String = "",
        edited: Map<String, String> = emptyMap(),
        button: cn.aufe.campus.data.model.PortalAction? = null
    ): Page {
        val params = buildParams(from, edited)
        params["__EVENTTARGET"] = target
        params["__EVENTARGUMENT"] = argument
        if (button != null && button.submitName.isNotBlank()) {
            params[button.submitName] = button.submitValue
        }
        val html = client.postFormText(from.url, params)
        return page(html, from.url)
    }

    /** 带附件的回发（附件上传页的 enctype 是 multipart） */
    fun postWithFile(
        from: Page,
        edited: Map<String, String> = emptyMap(),
        button: cn.aufe.campus.data.model.PortalAction? = null,
        file: UploadFile
    ): Page {
        val params = buildParams(from, edited)
        params["__EVENTTARGET"] = ""
        params["__EVENTARGUMENT"] = ""
        if (button != null && button.submitName.isNotBlank()) {
            params[button.submitName] = button.submitValue
        }
        val html = client.postMultipartText(from.url, params, file)
        return page(html, from.url)
    }

    /**
     * 组装表单体：隐藏字段 + 所有控件当前值。
     *
     * 注意只送"服务端这次给出来的控件"。多送字段 ASP.NET 会以
     * `Invalid postback or callback argument` 直接 500，比少送更难查。
     */
    private fun buildParams(from: Page, edited: Map<String, String>): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        params.putAll(from.hidden)
        from.fields.forEach { f ->
            val v = edited[f.name] ?: f.value
            when (f.type) {
                PortalFieldType.FILE -> Unit               // 文件走 multipart，不在这里
                PortalFieldType.CHECKBOX -> if (v == "true") params[f.name] = f.value.ifBlank { "on" }
                PortalFieldType.RADIO -> params[f.name] = v
                else -> params[f.name] = v
            }
        }
        // 用户改过、但服务端这页没给出来的字段（比如刚在上一页填的），也带上
        edited.forEach { (k, v) -> if (!params.containsKey(k)) params[k] = v }
        return params
    }

    private fun page(html: String, referer: String): Page {
        if (PortalParser.looksLikeLogin(html)) {
            throw IOException("竞赛平台登录态已失效，请回到首页重新登录一次")
        }
        val action = PortalParser.formAction(html)
        val url = if (action.isBlank()) referer else resolve(referer, action)
        val doc = PortalParser.parse(html)
        return Page(
            url = url,
            html = html,
            doc = doc,
            hidden = PortalParser.hiddenFields(html),
            fields = doc.fields
        )
    }

    /**
     * `./xsXmsb.aspx?sbid=0` 这种相对地址补成绝对地址。
     *
     * 用字符串手拼而不是 `java.net.URI.resolve`：`URI` 对含 `&` 的 query
     * 会做二次编码，服务端收到的 sbid 就变了。
     */
    private fun resolve(base: String, action: String): String {
        if (action.startsWith("http", true)) return action
        val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: return action
        val dir = base.substringAfter(origin).substringBeforeLast('/', "")
        val path = when {
            action.startsWith("/") -> action
            else -> "$dir/${action.removePrefix("./")}"
        }
        return origin + path
    }
}
