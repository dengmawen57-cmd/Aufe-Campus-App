package cn.aufe.campus.ui.competition

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.aufe.campus.data.api.CompetitionPortalApi
import cn.aufe.campus.data.model.PortalAction
import cn.aufe.campus.data.model.PortalColumn
import cn.aufe.campus.data.model.PortalDoc
import cn.aufe.campus.data.model.PortalField
import cn.aufe.campus.data.model.PortalFieldType
import cn.aufe.campus.data.model.PortalKind
import cn.aufe.campus.data.model.PortalRow
import cn.aufe.campus.data.model.PortalSection
import cn.aufe.campus.data.model.PortalTabLink
import cn.aufe.campus.data.net.UploadFile
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.FeatureScaffold
import cn.aufe.campus.ui.components.LoadingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学科竞赛（校内创新创业管理平台）—— 全原生界面。
 *
 * 不再用 WebView：列表、申报表单都由 [CompetitionPortalApi] 拉 HTML、
 * [cn.aufe.campus.data.api.PortalParser] 解析成模型，再由 Compose 渲染。
 * 登录态与主 App 共用同一个 OkHttp 会话，所以**不需要二次登录**。
 *
 * 只有两种情况会退回内嵌页：
 * 1. 解析失败（页面改版）
 * 2. 网络/服务端报错，用户想自己在网页里操作
 */
@Composable
fun CompetitionPortalScreen(
    onBack: () -> Unit,
    onOpenWeb: (Int) -> Unit = {}
) {
    val vm: PortalViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    // 返回：表单 → 列表；列表 → 关闭页面
    BackHandler {
        if (!vm.back()) onBack()
    }

    val page: CompetitionPortalApi.Page? = state.page
    val doc: PortalDoc = page?.doc ?: PortalDoc()

    FeatureScaffold(
        title = if (doc.kind == PortalKind.FORM) "获奖申报" else "学科竞赛",
        subtitle = subtitleOf(doc),
        onBack = { if (!vm.back()) onBack() },
        actions = {
            // 网页版入口：原生界面解析不出来、或者用户就是想用原站某些功能时，
            // 从右上角一步跳过去（比藏在页面底部好找）。
            // 带上当前标签的序号，进去就停在同一个标签，不用再点一次。
            IconButton(
                onClick = { onOpenWeb(currentTabIndex(doc)) },
                enabled = state.busy.isBlank()
            ) {
                Icon(Icons.Filled.Language, contentDescription = "网页版")
            }
            IconButton(onClick = { vm.refresh() }, enabled = state.busy.isBlank()) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (state.notice.isNotBlank()) {
                NoticeBar(state.notice)
                LaunchedEffect(state.notice) {
                    delay(3200)
                    vm.consumeNotice()
                }
            }

            when {
                state.loading && page == null -> LoadingBox("正在打开竞赛平台…", 260.dp)

                state.error.isNotBlank() && page == null -> Column {
                    ErrorBox(state.error, onRetry = { vm.refresh() }, height = 220.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        OutlinedButton(onClick = { onOpenWeb(0) }) { Text("在网页中打开") }
                    }
                }

                state.busy.isNotBlank() && page == null -> LoadingBox(state.busy, 240.dp)

                page == null -> LoadingBox("正在打开竞赛平台…", 240.dp)

                else -> {
                    if (doc.tabs.isNotEmpty()) {
                        TabStrip(doc.tabs, enabled = state.busy.isBlank()) { vm.selectTab(it) }
                    }
                    if (state.error.isNotBlank()) {
                        InlineError(state.error, onDismiss = { vm.dismissError() })
                    }

                    when (doc.kind) {
                        PortalKind.GRID -> GridBody(
                            doc = doc,
                            busy = state.busy,
                            edited = state.edited,
                            onEdit = vm::edit,
                            onAct = vm::act,
                            onSubmit = { vm.submitButton(it) }
                        )

                        PortalKind.FORM -> FormBody(
                            doc = doc,
                            busy = state.busy,
                            edited = state.edited,
                            onEdit = vm::edit,
                            onSubmit = { action, file -> vm.submitButton(action, file) }
                        )

                        PortalKind.UNKNOWN -> Column(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "这一页的结构没认出来（平台可能改版了）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(onClick = { onOpenWeb(0) }) { Text("在网页中打开") }
                        }
                    }

                    if (state.busy.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(state.busy, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** 当前选中的主标签序号（网页版入口据此停在同一标签） */
private fun currentTabIndex(doc: PortalDoc): Int =
    doc.tabs.indexOfFirst { it.selected }.coerceAtLeast(0)

private fun subtitleOf(doc: PortalDoc): String = when {
    doc.kind == PortalKind.FORM -> "原生表单 · 与 App 共用登录"
    doc.kind == PortalKind.GRID && doc.rows.isNotEmpty() ->
        "共 ${doc.rows.size} 条" + if (doc.pager.shown) " · 第 ${doc.pager.pageIndex}/${doc.pager.pageCount} 页" else ""
    doc.kind == PortalKind.GRID && doc.emptyHint.isNotBlank() -> doc.emptyHint
    doc.kind == PortalKind.GRID -> "校内平台 · 与 App 共用登录"
    else -> "校内平台"
}

// ---------------------------------------------------------------- 顶部页签

@Composable
private fun TabStrip(tabs: List<PortalTabLink>, enabled: Boolean, onClick: (PortalAction) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            val selected = tab.selected
            val bg = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
            val fg = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .background(bg, RoundedCornerShape(16.dp))
                    .clickable(enabled = enabled && !selected) { onClick(tab.action) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(tab.label, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---------------------------------------------------------------- 列表

@Composable
private fun GridBody(
    doc: PortalDoc,
    busy: String,
    edited: Map<String, String>,
    onEdit: (String, String) -> Unit,
    onAct: (PortalAction) -> Unit,
    onSubmit: (PortalAction) -> Unit
) {
    val filters = doc.sections.flatMap { it.fields }
    var showFilter by remember { mutableStateOf(filters.isNotEmpty() && filters.any { it.value.isNotBlank() }) }
    var pendingDanger by remember { mutableStateOf<PortalAction?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (filters.isNotEmpty() && doc.buttons.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable { showFilter = !showFilter }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("筛选", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (showFilter) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            if (showFilter) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    filters.forEach { f -> FieldEditor(f, edited, onEdit, compact = true) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        doc.buttons.forEach { b ->
                            OutlinedButton(
                                onClick = { onSubmit(b) },
                                enabled = busy.isBlank(),
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                            ) { Text(b.label) }
                        }
                    }
                }
            }
        }

        // 注意：这里**不能**写 `return@Column`。
        // 「已申报奖项」这一类页面给的是一张只有表头（甚至没有表头）的空 GridView，
        // 从 Column 的 content lambda 里提前返回会跳过 Compose 编译器插入的 endGroup，
        // 让 group 栈错位，下一次重组就在 ComposerImpl.exitGroup 里抛
        // IndexOutOfBoundsException: Index -1 out of bounds for length 0（表现为闪退）。
        val titleIndex = remember(doc.rows, doc.columns) { pickTitleIndex(doc.columns, doc.rows) }

        if (doc.rows.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    doc.emptyHint.ifBlank { "这里还没有记录" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(doc.rows) { _, row ->
                    RowCard(
                        columns = doc.columns,
                        row = row,
                        titleIndex = titleIndex,
                        enabled = busy.isBlank(),
                        onAct = { a ->
                            // 删除类会直接落库；「我要报名」也可能直接产生一条报名记录，
                            // 这两种都先问一句，别让一次误触变成一条真实记录
                            if (a.danger || a.label.contains("报名")) pendingDanger = a else onAct(a)
                        }
                    )
                }
            }

            if (doc.pager.shown) {
                PagerBar(doc, busy.isBlank(), onAct)
            }
        }
    }

    val danger = pendingDanger
    if (danger != null) {
        ConfirmDanger(
            label = danger.label,
            onConfirm = {
                pendingDanger = null
                onAct(danger)
            },
            onDismiss = { pendingDanger = null }
        )
    }
}

/** 挑一列当卡片标题：优先"名称"，其次平均字数最多的那列 */
private fun pickTitleIndex(columns: List<PortalColumn>, rows: List<PortalRow>): Int {
    columns.forEachIndexed { i, c -> if (c.title.contains("名称") || c.title.contains("项目")) return i }
    if (rows.isEmpty()) return 0
    val count = rows[0].cells.size
    var best = 0
    var bestLen = -1f
    for (i in 0 until count) {
        val avg = rows.map { it.cells.getOrNull(i)?.length ?: 0 }.average().toFloat()
        if (avg > bestLen) { bestLen = avg; best = i }
    }
    return best
}

@Composable
private fun RowCard(
    columns: List<PortalColumn>,
    row: PortalRow,
    titleIndex: Int,
    enabled: Boolean,
    onAct: (PortalAction) -> Unit
) {
    val title = row.cells.getOrNull(titleIndex).orEmpty().ifBlank { "记录" }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

            val detail = row.cells.mapIndexedNotNull { i, v ->
                when {
                    i == titleIndex || v.isBlank() -> null
                    else -> Pair(columns.getOrNull(i)?.title.orEmpty(), v)
                }
            }
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                detail.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(74.dp)
                        )
                        Text(value, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (row.actions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    row.actions.forEach { a ->
                        TextButton(onClick = { onAct(a) }, enabled = enabled) {
                            Text(
                                a.label,
                                color = if (a.danger) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagerBar(doc: PortalDoc, enabled: Boolean, onAct: (PortalAction) -> Unit) {
    val p = doc.pager
    Column {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 用固定中文标签，不用页面里的控件名（LinkButtonFirstPage 之类）
            listOf(
                "首页" to p.first,
                "上一页" to p.prev,
                "下一页" to p.next,
                "尾页" to p.last
            ).forEach { (label, action) ->
                if (action != null) {
                    TextButton(onClick = { onAct(action) }, enabled = enabled) {
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "第 ${p.pageIndex} / ${p.pageCount} 页",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------- 表单

@Composable
private fun FormBody(
    doc: PortalDoc,
    busy: String,
    edited: Map<String, String>,
    onEdit: (String, String) -> Unit,
    onSubmit: (PortalAction, UploadFile?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileField = doc.fields.firstOrNull { it.type == PortalFieldType.FILE }
    var picked by remember { mutableStateOf<UploadFile?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && fileField != null) {
            scope.launch {
                picked = withContext(Dispatchers.IO) { readUpload(context, uri, fileField.name) }
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        doc.sections.filter { it.fields.isNotEmpty() }.forEach { section ->
            item {
                if (section.title.isNotBlank()) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            itemsIndexed(section.fields) { _, f ->
                if (f.type == PortalFieldType.FILE) {
                    FilePickerRow(f, picked) { picker.launch("*/*") }
                } else {
                    FieldEditor(f, edited, onEdit)
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            // 横向可滚：按钮多的时候（返回/暂存/提交…）挤在一行会被压成竖排文字
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                doc.buttons.forEach { b ->
                    // 这里不能用 weight：父 Row 可横向滚动，宽度约束是无限的，
                    // weight 会把按钮压成 0 宽。统一按内容宽度 + 滚动条兜住
                    if (b.submitName.isNotBlank()) {
                        OutlinedButton(
                            onClick = { onSubmit(b, picked) },
                            enabled = busy.isBlank()
                        ) { Text(b.label.ifBlank { "确定" }) }
                    } else {
                        OutlinedButton(
                            onClick = { onSubmit(b, null) },
                            enabled = busy.isBlank()
                        ) { Text(b.label) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilePickerRow(field: PortalField, picked: UploadFile?, onPick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(field.label.ifBlank { "附件（证明材料）" }, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPick) { Text(if (picked == null) "选择文件" else "重新选择") }
            Spacer(Modifier.width(10.dp))
            Text(
                picked?.fileName ?: "未选择",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 单个字段编辑器。
 *
 * 值从 `edited` 里取，改一次就回写 —— 不做本地 `remember`，
 * 否则切页签/提交后服务端回填的值会被旧本地态盖掉。
 */
@Composable
private fun FieldEditor(
    field: PortalField,
    edited: Map<String, String>,
    onEdit: (String, String) -> Unit,
    compact: Boolean = false
) {
    val value = edited[field.name] ?: field.value

    when (field.type) {
        PortalFieldType.SELECT -> SelectField(field, value) { onEdit(field.name, it) }

        PortalFieldType.RADIO, PortalFieldType.CHECKBOX -> RadioField(field, value) { onEdit(field.name, it) }

        PortalFieldType.TEXTAREA -> {
            OutlinedTextField(
                value = value,
                onValueChange = { onEdit(field.name, it) },
                label = { Text(field.label.ifBlank { "填写内容" }) },
                modifier = Modifier.fillMaxWidth().height(if (compact) 88.dp else 120.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            HintText(field.hint)
        }

        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = { onEdit(field.name, it) },
                label = { Text(field.label.ifBlank { "填写内容" }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            HintText(field.hint)
        }
    }
}

/** 控件下方的那行红色小字（"与获奖证书一致"），单独一行比 supportingText 稳 */
@Composable
private fun HintText(hint: String) {
    // 不写 `if (hint.isBlank()) return`：composable 里尽量只用 if/else 分支，
    // 少给编译器制造 group 收尾上的歧义
    if (hint.isNotBlank()) {
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

@Composable
private fun SelectField(field: PortalField, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = field.options.firstOrNull { it.value == value } ?: field.options.firstOrNull()

    Column(Modifier.fillMaxWidth()) {
        Text(field.label.ifBlank { "请选择" }, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(current?.label ?: "未选择", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                field.options.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(o.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = { onChange(o.value); expanded = false },
                        trailingIcon = {
                            if (o.value == current?.value) Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioField(field: PortalField, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (field.label.isNotBlank()) {
            Text(field.label, style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field.options.forEach { o ->
                val selected = o.value == value
                Box(
                    Modifier
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onChange(o.value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        o.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 小件

@Composable
private fun NoticeBar(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InlineError(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) { Text("知道了", fontSize = 12.sp) }
    }
}

@Composable
private fun ConfirmDanger(label: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认$label？") },
        text = { Text("这个操作在服务端直接生效，可能需要重新提交一次。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(label, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 读用户选的文件（SAF 给的 content:// 只能通过 ContentResolver 打开） */
private fun readUpload(context: Context, uri: Uri, fieldName: String): UploadFile? = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = queryDisplayName(context, uri) ?: "attachment"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    UploadFile(field = fieldName, fileName = name, mime = mime, bytes = bytes)
}.getOrNull()

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}.getOrNull()
