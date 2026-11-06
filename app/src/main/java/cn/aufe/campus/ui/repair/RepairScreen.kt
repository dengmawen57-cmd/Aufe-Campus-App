package cn.aufe.campus.ui.repair

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.RepairDetail
import cn.aufe.campus.data.model.RepairOption
import cn.aufe.campus.data.model.RepairOptionGroup
import cn.aufe.campus.data.model.RepairOrder
import cn.aufe.campus.data.model.RepairSubmit
import cn.aufe.campus.ui.FeatureViewModel
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.FeatureScaffold
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.TagChip

/**
 * 零星维修（移植自 LoveACE）。
 *
 * 三块内容：
 * 1. **工单列表**：待完成（待接单 + 待完工）/ 已完成（待评价 + 已完成）
 * 2. **工单详情**：故障信息 + 维修进度时间轴 + 决算单
 * 3. **提交报修**：区域 / 项目 / 电话 / 地点 / 描述（真实提交，会进后勤系统）
 *
 * ⚠️ 报修是**真实工单**，点提交后勤就会派人。所以按钮上是「提交报修」而不是
 * 花哨的名字，提交前也不做二次弹窗 —— 表单本身已经是"确认"的动作，
 * 再弹一层只会让人误点。提交成功后给明确结果提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairScreen(vm: FeatureViewModel, onBack: () -> Unit) {
    val state by vm.repair.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadRepairOrders() }
    // 打开详情后从状态里取数据（状态里没数据时说明还在加载）
    LaunchedEffect(detailId) {
        detailId?.let { vm.openRepairDetail(it) }
    }

    val list = if (tab == 0) state.pending else state.finished

    FeatureScaffold(
        title = "零星维修",
        subtitle = "待完成 ${state.pending.size} 单 · 已完成 ${state.finished.size} 单",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showForm = true }) {
                Icon(Icons.Filled.Add, contentDescription = "报修")
            }
            IconButton(onClick = { vm.loadRepairOrders(force = true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("待完成", "已完成").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when {
                state.loading && state.orders.isEmpty() -> LoadingBox("正在获取工单…", 240.dp)
                state.error.isNotBlank() && state.orders.isEmpty() ->
                    ErrorBox(state.error, onRetry = { vm.loadRepairOrders(force = true) }, 240.dp)
                list.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (tab == 0) "没有待处理的报修单" else "还没有已完成的报修单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { showForm = true }) { Text("去报修") }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(list) { order ->
                        OrderCard(order) { detailId = order.taskId }
                    }
                }
            }
        }
    }

    // 悬浮按钮：报修
    // （FeatureScaffold 没开 FAB 插槽，这里用 Box 叠一层最省事）
    if (state.message.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { vm.clearRepairMessage() },
            title = { Text("提交成功") },
            text = { Text(state.message + "\n\n可以在「待完成」里跟进进度。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearRepairMessage()
                    tab = 0
                }) { Text("知道了") }
            }
        )
    }
    if (state.error.isNotBlank() && state.orders.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.clearRepairMessage() },
            title = { Text("操作失败") },
            text = { Text(state.error) },
            confirmButton = {
                TextButton(onClick = { vm.clearRepairMessage() }) { Text("知道了") }
            }
        )
    }

    if (showForm) {
        RepairFormSheet(
            groups = state.form,
            loading = state.formLoading,
            error = state.error,
            submitting = state.submitting,
            onDismiss = { showForm = false },
            onSubmit = { req ->
                vm.submitRepair(req)
                showForm = false
            },
            onNeedForm = { vm.loadRepairForm() }
        )
    }

    if (detailId != null) {
        val detail = state.detail
        ModalBottomSheet(onDismissRequest = {
            detailId = null
            vm.closeRepairDetail()
        }) {
            if (state.detailLoading || detail == null) {
                LoadingBox("正在加载工单详情…", 220.dp)
            } else {
                OrderDetailSheet(detail)
            }
        }
    }
}

@Composable
private fun OrderCard(order: RepairOrder, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.title.ifBlank { "报修单" },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TagChip(
                    order.statusText,
                    color = when (order.status) {
                        0, 1 -> MaterialTheme.colorScheme.primary
                        2 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    order.location.takeIf { it.isNotBlank() },
                    order.reporter.takeIf { it.isNotBlank() }?.let { "报修人 $it" },
                    order.createTime.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (order.orderNumber.isNotBlank() || order.workHours.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(
                        order.orderNumber.takeIf { it.isNotBlank() }?.let { "单号 $it" },
                        order.workHours.takeIf { it.isNotBlank() }?.let { "工时 $it" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun OrderDetailSheet(detail: RepairDetail) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp)
    ) {
        Text("工单详情", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        InfoLine("故障区域", detail.faultArea)
        InfoLine("维修项目", detail.repairProject)
        InfoLine("联系电话", detail.phone)
        InfoLine("故障地点", detail.faultAddress)
        if (detail.description.isNotBlank()) {
            InfoLine("问题描述", detail.description)
        }

        if (detail.progress.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("维修进度", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            detail.progress.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("·", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(p.stage, style = MaterialTheme.typography.bodyMedium)
                        val sub = listOf(p.time, p.desc).filter { it.isNotBlank() }.joinToString(" ")
                        if (sub.isNotBlank()) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (detail.settlements.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("决算单", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            detail.settlements.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(s.serviceName, Modifier.weight(1f), fontSize = 12.sp)
                    Text(s.material, Modifier.weight(1f), fontSize = 12.sp)
                    Text(s.workPoints, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(
            "工单一旦提交就不能在本机撤销，需要取消请联系后勤。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            Modifier.width(76.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 报修表单。
 *
 * 区域和项目都是「分组 → 条目」的两级结构（学校按楼栋/类别分组），
 * 这里用两个下拉，点开就是「分组名」标题 + 该组下的条目。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepairFormSheet(
    groups: cn.aufe.campus.data.model.RepairFormData?,
    loading: Boolean,
    error: String,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RepairSubmit) -> Unit,
    onNeedForm: () -> Unit
) {
    var area by remember { mutableStateOf<RepairOption?>(null) }
    var project by remember { mutableStateOf<RepairOption?>(null) }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    // 刚打开弹层的第一帧还没触发加载，用这个标记避免闪一下「没拿到列表」
    var attempted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        attempted = true
        onNeedForm()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp)
        ) {
            Text("提交报修", style = MaterialTheme.typography.titleMedium)
            Text(
                "提交后后勤会按区域派单，请填清楚具体位置（例如：3 号楼 412）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // ⚠️ 这里绝对不能用 `return@Column` 提前退出：
            // Column 是 inline，content lambda 里的 return 会跳过 Compose 编译器
            // 为这段内容插入的 endGroup，导致 ModalBottomSheet 内部
            // BoxWithConstraints 子组合结束时槽位栈对不上，
            // 直接抛 ArrayIndexOutOfBoundsException: length=0; index=-5 闪退。
            // 改成 when 分支，三种情况各成一段，结构上不可能失衡。
            when {
                groups != null -> RepairFormBody(
                    groups = groups,
                    area = area,
                    project = project,
                    phone = phone,
                    address = address,
                    desc = desc,
                    submitting = submitting,
                    onArea = { area = it },
                    onProject = { project = it },
                    onPhone = { phone = it },
                    onAddress = { address = it },
                    onDesc = { desc = it },
                    onSubmit = onSubmit
                )

                loading || !attempted -> LoadingBox("正在获取可选项…", 140.dp)

                else -> Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (error.isNotBlank()) error else "没拿到区域/项目列表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onNeedForm) { Text("重新获取区域/项目列表") }
                }
            }
        }
    }
}

/**
 * 表单主体。单独抽一个函数，一是让 [RepairFormSheet] 的 when 分支保持"一段进一段出"，
 * 二是这里的控件多、输入状态多，混在弹层骨架里不好读。
 */
@Composable
private fun RepairFormBody(
    groups: cn.aufe.campus.data.model.RepairFormData,
    area: RepairOption?,
    project: RepairOption?,
    phone: String,
    address: String,
    desc: String,
    submitting: Boolean,
    onArea: (RepairOption) -> Unit,
    onProject: (RepairOption) -> Unit,
    onPhone: (String) -> Unit,
    onAddress: (String) -> Unit,
    onDesc: (String) -> Unit,
    onSubmit: (RepairSubmit) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        GroupedPicker(
            label = "故障区域",
            groups = groups.areas,
            selected = area,
            onPick = onArea
        )
        Spacer(Modifier.height(10.dp))
        GroupedPicker(
            label = "维修项目",
            groups = groups.projects,
            selected = project,
            onPick = onProject
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = onPhone,
            label = { Text("联系电话") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onAddress,
            label = { Text("具体地点（楼栋 + 房间）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = desc,
            onValueChange = onDesc,
            label = { Text("问题描述") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        val ready = area != null && project != null &&
            phone.isNotBlank() && address.isNotBlank() && desc.isNotBlank()
        Button(
            onClick = {
                onSubmit(
                    RepairSubmit(
                        areaId = area?.id.orEmpty(),
                        areaName = area?.name.orEmpty(),
                        projectId = project?.id.orEmpty(),
                        projectName = project?.name.orEmpty(),
                        phone = phone.trim(),
                        address = address.trim(),
                        description = desc.trim()
                    )
                )
            },
            enabled = ready && !submitting,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("提交中…")
            } else {
                Text(if (ready) "提交报修" else "请填完必填项")
            }
        }
    }
}

/** 分组下拉：菜单里带分组标题 */
@Composable
private fun GroupedPicker(
    label: String,
    groups: List<RepairOptionGroup>,
    selected: RepairOption?,
    onPick: (RepairOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                selected?.name ?: "请选择",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }
        Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 380.dp)
        ) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            group.name.ifBlank { "未分组" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
                group.items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name, maxLines = 1) },
                        onClick = {
                            onPick(item)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
