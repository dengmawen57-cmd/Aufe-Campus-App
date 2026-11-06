package cn.aufe.campus.ui.competition

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.aufe.campus.CampusApp
import cn.aufe.campus.data.api.CompetitionPortalApi
import cn.aufe.campus.data.api.PortalParser
import cn.aufe.campus.data.model.PortalAction
import cn.aufe.campus.data.model.PortalFieldType
import cn.aufe.campus.data.model.PortalKind
import cn.aufe.campus.data.net.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学科竞赛（原生）的视图模型。
 *
 * ## 为什么用"页面栈"而不是固定几个状态
 *
 * 这个平台有 4 个主标签、每个标签能点进表单、表单里还有 4 个子页签，
 * 层级不固定（从申报表单点"返回"要回到"获奖申报"列表，而不是 App 首页）。
 * 用 `List<Page>` 当栈，返回就是 pop，不用为每个层级写一个状态字段。
 *
 * ## 每次回发都重建页面
 *
 * WebForms 的隐藏字段每次响应都会变，不能缓存复用；这里每次 POST 都用
 * 栈顶那一页的隐藏字段，成功后把新页压栈/替换栈顶。
 */
class PortalViewModel(app: Application) : AndroidViewModel(app) {

    private val api = CompetitionPortalApi((app as CampusApp).authManager.client)

    data class State(
        val stack: List<CompetitionPortalApi.Page> = emptyList(),
        val loading: Boolean = false,
        /** 正在执行的动作文案（"提交中…"），非空时禁用按钮 */
        val busy: String = "",
        val error: String = "",
        /** 用户改过、还没提交的表单值 */
        val edited: Map<String, String> = emptyMap(),
        /** 一次性提示（服务端 alert 文案） */
        val notice: String = "",
        /** 需要回退到内嵌页时给出的地址（解析失败兜底） */
        val fallbackUrl: String = ""
    ) {
        val page: CompetitionPortalApi.Page? get() = stack.lastOrNull()
        val canGoBack: Boolean get() = stack.size > 1
        val isForm: Boolean get() = page?.doc?.kind == PortalKind.FORM
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var loadedOnce = false

    // ---------------------------------------------------------------- 加载

    fun load(force: Boolean = false) {
        if (_state.value.loading) return
        if (loadedOnce && !force) return
        _state.value = _state.value.copy(loading = true, error = "", fallbackUrl = "")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.open() } }
                .onSuccess { page ->
                    loadedOnce = true
                    _state.value = State(stack = listOf(page))
                }
                .onFailure { fail("打开竞赛平台失败", it) }
        }
    }

    /** 下拉刷新：重开首页（回到列表第一个标签） */
    fun refresh() {
        loadedOnce = false
        load(force = true)
    }

    // ---------------------------------------------------------------- 交互

    /** 切主标签 / 子页签 */
    fun selectTab(action: PortalAction) = post(action, "正在切换…")

    /** 点行内动作（申报 / 项目详情 / 我要报名 / 查看编辑 / 翻页） */
    fun act(action: PortalAction) = post(action, "正在打开…")

    /** 点表单底部按钮（暂存 / 提交 / 上传） */
    fun submitButton(action: PortalAction, file: UploadFile? = null) {
        val page = _state.value.page ?: return
        if (action.submitName.isNotBlank() && file != null &&
            page.doc.fields.any { it.type == PortalFieldType.FILE }
        ) {
            val label = action.label.ifBlank { "上传" }
            busyThen(label) {
                api.postWithFile(page, _state.value.edited, action, file)
            }
        } else {
            post(action, action.label.ifBlank { "提交中" } + "…")
        }
    }

    fun edit(name: String, value: String) {
        _state.value = _state.value.copy(edited = _state.value.edited + (name to value))
    }

    /** 返回上一页；栈只剩一页时交给界面关闭页面 */
    fun back(): Boolean {
        val s = _state.value
        if (s.stack.size <= 1) return false
        _state.value = s.copy(stack = s.stack.dropLast(1), edited = emptyMap(), error = "")
        return true
    }

    fun consumeNotice() {
        if (_state.value.notice.isNotBlank()) _state.value = _state.value.copy(notice = "")
    }

    fun dismissError() {
        if (_state.value.error.isNotBlank()) _state.value = _state.value.copy(error = "")
    }

    // ---------------------------------------------------------------- 内部

    private fun post(action: PortalAction, busyText: String) {
        val page = _state.value.page ?: return
        busyThen(busyText) {
            api.post(page, action.target, action.argument, _state.value.edited, action)
        }
    }

    private fun busyThen(busyText: String, block: suspend () -> CompetitionPortalApi.Page) {
        if (_state.value.busy.isNotBlank()) return
        _state.value = _state.value.copy(busy = busyText, error = "", fallbackUrl = "")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { page -> apply(page) }
                .onFailure { fail(busyText.trimEnd('…'), it) }
        }
    }

    /**
     * 吃掉一次成功响应。
     *
     * 三种情况分开处理，否则会出现"点了提交界面没变化"：
     * 1. 服务端给了 alert 且提到成功 → 提示 + 回到上一页（列表）
     * 2. 返回的是列表（GRID）→ 视为已完成，替换掉当前表单页
     * 3. 还是表单 → 原地替换（子页签切换、暂存后重新编辑都走这条）
     */
    private fun apply(page: CompetitionPortalApi.Page) {
        val s = _state.value
        val message = PortalParser.alerts(page.html).firstOrNull().orEmpty()

        val stack = when {
            // 解析不出来：保持原样，让界面显示"没认出来"并提供内嵌页兜底
            page.doc.kind == PortalKind.UNKNOWN -> s.stack
            // 从表单提交后回到列表（服务端 location 到首页）→ 直接以列表为根，
            // 否则返回键会退回那张已经提交过的表单
            page.doc.kind == PortalKind.GRID && s.isForm -> listOf(page)
            s.stack.isEmpty() -> listOf(page)
            else -> s.stack.dropLast(1) + page
        }

        _state.value = s.copy(
            stack = stack,
            busy = "",
            edited = emptyMap(),
            error = if (page.doc.kind == PortalKind.UNKNOWN) "这一页没能解析成原生界面" else "",
            fallbackUrl = if (page.doc.kind == PortalKind.UNKNOWN) page.url else "",
            notice = message
        )
    }

    private fun fail(prefix: String, e: Throwable) {
        val msg = e.message ?: e::class.java.simpleName
        _state.value = _state.value.copy(
            busy = "",
            loading = false,
            error = if (msg.startsWith(prefix)) msg else "$prefix：$msg",
            fallbackUrl = _state.value.page?.url.orEmpty()
        )
    }
}
