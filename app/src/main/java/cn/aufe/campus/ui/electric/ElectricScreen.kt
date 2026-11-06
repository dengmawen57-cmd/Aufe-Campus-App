package cn.aufe.campus.ui.electric

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.BillOption
import cn.aufe.campus.data.model.ElectricityInfo
import cn.aufe.campus.data.model.ElectricityUsageRecord
import cn.aufe.campus.data.model.PaymentRecord
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.util.RecordTime

/** 展开后一次最多渲染多少条（记录可能上百条，全渲染会卡） */
private const val PAGE_STEP = 30

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val s by vm.electric.collectAsState()
    val recharge by vm.recharge.collectAsState()

    var showRecharge by rememberSaveable { mutableStateOf(false) }
    // 两组记录的展开状态。放在这里是因为它们要同时驱动「表头」和「明细项」，
    // 而 LazyListScope 的 content lambda 不是 @Composable，没法在里面 remember。
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    var usageLimit by rememberSaveable { mutableIntStateOf(PAGE_STEP) }
    var payOpen by rememberSaveable { mutableStateOf(false) }
    var payLimit by rememberSaveable { mutableIntStateOf(PAGE_STEP) }

    // 提前取出配色：recordGroup 不是 @Composable，它的参数表里不能读 MaterialTheme
    val usageAccent = MaterialTheme.colorScheme.primary
    val payAccent = MaterialTheme.colorScheme.tertiary

    LaunchedEffect(Unit) { vm.loadElectricBuildings() }
    // 提前把充值账户与校区列表拉好，用户点「充值」时不用干等
    LaunchedEffect(Unit) { vm.openRecharge() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("宿舍电费") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 充值入口（v1.11.9：从余额卡里挪到页面右上角）。
                    // 原来它是余额卡右侧一个按钮，余额一长（"剩余电量"那行
                    // 数字变宽）就把按钮挤得很局促；放顶栏也更好找。
                    FilledTonalButton(
                        onClick = { showRecharge = true },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("充值", fontSize = 13.sp)
                    }
                    IconButton(onClick = { vm.loadElectricBuildings(force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新楼栋")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 级联选择：楼栋 → 楼层 → 房间
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PickerBox(
                        label = "楼栋",
                        value = s.buildings.getOrNull(s.buildingIdx)?.second ?: "请选择",
                        options = s.buildings,
                        enabled = s.buildings.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onSelect = { vm.selectBuilding(it) }
                    )
                    PickerBox(
                        label = "楼层",
                        value = s.floors.getOrNull(s.floorIdx)?.second ?: "请选择",
                        options = s.floors,
                        enabled = s.floors.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onSelect = { vm.selectFloor(it) }
                    )
                    PickerBox(
                        label = "房间",
                        value = s.rooms.getOrNull(s.roomIdx)?.second ?: "请选择",
                        options = s.rooms,
                        enabled = s.rooms.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onSelect = { vm.selectRoom(it) }
                    )
                }
                if (s.loading) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp), Alignment.Center
                    ) { CircularProgressIndicator() }
                }
            }

            if (s.infoLoading) {
                item {
                    Box(Modifier.fillMaxWidth(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "正在查询电费…",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            s.info?.let { info ->
                item {
                    BalanceCard(
                        info = info,
                        roomName = s.rooms.getOrNull(s.roomIdx)?.second ?: ""
                    )
                }

                if (info.usageRecords.isNotEmpty()) {
                    recordGroup(
                        records = info.usageRecords,
                        title = "用电记录",
                        icon = Icons.Filled.Bolt,
                        accent = usageAccent,
                        expanded = usageOpen,
                        limit = usageLimit,
                        onToggle = { usageOpen = !usageOpen },
                        onLoadMore = { usageLimit += PAGE_STEP },
                        summary = { list -> "共 %.2f 度".format(list.sumOf { it.usageAmount }) },
                        latest = { u -> RecordTime.parse(u.recordTime)?.fullText ?: u.recordTime },
                        row = { u -> UsageRow(u) }
                    )
                }

                if (info.payments.isNotEmpty()) {
                    recordGroup(
                        records = info.payments,
                        title = "缴费记录",
                        icon = Icons.Filled.Wallet,
                        accent = payAccent,
                        expanded = payOpen,
                        limit = payLimit,
                        onToggle = { payOpen = !payOpen },
                        onLoadMore = { payLimit += PAGE_STEP },
                        summary = { list ->
                            val total = list.sumOf { it.amount }
                            if (total >= 0) "共 +%.2f 元".format(total)
                            else "共 %.2f 元".format(total)
                        },
                        latest = { p -> RecordTime.parse(p.paymentTime)?.fullText ?: p.paymentTime },
                        row = { p -> PaymentRow(p) }
                    )
                }

                if (info.usageRecords.isEmpty() && info.payments.isEmpty()) {
                    item {
                        Text(
                            "该房间暂无用电/缴费记录",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (s.error.isNotBlank()) {
                item {
                    Text(
                        s.error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
        }
    }

    if (showRecharge) {
        RechargeSheet(
            vm = vm,
            state = recharge,
            onDismiss = {
                showRecharge = false
                vm.closeRecharge()
            }
        )
    }
}

/**
 * 可折叠记录分组。
 *
 * 收起时只留「标题 + 条数 + 合计 + 最近一条完整时间」，展开后才逐条渲染。
 * 记录动辄几十上百条，默认收起能让首屏只剩余额和两行摘要，
 * 想看明细再点开——这正是需求里说的「可以收起来」。
 *
 * 注意：本函数**不是** @Composable。`LazyColumn { }` 的 content 是
 * `LazyListScope.() -> Unit`，在里面调 @Composable 扩展会编译不过，
 * 所以展开状态必须由调用方提升上来。
 */
private fun <T> LazyListScope.recordGroup(
    records: List<T>,
    title: String,
    icon: ImageVector,
    accent: Color,
    expanded: Boolean,
    limit: Int,
    onToggle: () -> Unit,
    onLoadMore: () -> Unit,
    summary: (List<T>) -> String,
    latest: (T) -> String,
    row: @Composable (T) -> Unit
) {
    item {
        RecordGroupHeader(
            title = title,
            count = records.size,
            summaryText = summary(records),
            latestText = latest(records.first()),
            icon = icon,
            accent = accent,
            expanded = expanded,
            onToggle = onToggle
        )
    }

    if (expanded) {
        val visible = records.take(limit)
        itemsIndexed(
            items = visible,
            key = { i, _ -> "$title-$i" }
        ) { _, item -> row(item) }

        if (records.size > visible.size) {
            item {
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    TextButton(onClick = onLoadMore) {
                        Text("显示更多（还有 ${records.size - visible.size} 条）", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordGroupHeader(
    title: String,
    count: Int,
    summaryText: String,
    latestText: String,
    icon: ImageVector,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$count 条",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        summaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent
                    )
                }
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "收起" else "展开", fontSize = 12.sp)
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // 收起时补一行「最近一条的完整时间」，一眼就知道数据新鲜度
            if (!expanded) {
                Text(
                    "最近 · $latestText",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 39.dp)
                )
            }
        }
    }
}

/** 余额卡（v1.11.9：充值按钮已移到顶栏右上角） */
@Composable
private fun BalanceCard(info: ElectricityInfo, roomName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "剩余电量（度）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "%.2f".format(info.balance.total),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "购电 ${"%.2f".format(info.balance.remainingPurchased)} 度 · " +
                    "补助 ${"%.2f".format(info.balance.remainingSubsidy)} 度" +
                    if (roomName.isNotBlank()) " · $roomName" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

/** 一条用电记录：主行日期 + 星期 + 时刻，副行相对时间与电表 */
@Composable
private fun UsageRow(u: ElectricityUsageRecord) {
    val t = remember(u.recordTime) { RecordTime.parse(u.recordTime) }
    RecordRow(
        accent = MaterialTheme.colorScheme.primary,
        main = t?.fullText ?: u.recordTime,
        sub = listOfNotNull(
            t?.timeText?.takeIf { it.isNotBlank() }?.let { "时刻 $it" },
            t?.relativeText?.takeIf { it.isNotBlank() },
            u.meterName.takeIf { it.isNotBlank() }?.let { "电表 $it" }
        ).joinToString(" · "),
        amountText = "%.2f 度".format(u.usageAmount),
        amountColor = MaterialTheme.colorScheme.onSurface
    )
}

/** 一条缴费记录 */
@Composable
private fun PaymentRow(p: PaymentRecord) {
    val t = remember(p.paymentTime) { RecordTime.parse(p.paymentTime) }
    val positive = p.amount >= 0
    RecordRow(
        accent = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        main = t?.fullText ?: p.paymentTime,
        sub = listOfNotNull(
            t?.timeText?.takeIf { it.isNotBlank() }?.let { "时刻 $it" },
            t?.relativeText?.takeIf { it.isNotBlank() },
            p.paymentType.takeIf { it.isNotBlank() }
        ).joinToString(" · "),
        amountText = (if (positive) "+%.2f 元" else "%.2f 元").format(p.amount),
        amountColor = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

/** 记录行外壳：左侧色条 + 主/副信息 + 右侧数值 */
@Composable
private fun RecordRow(
    accent: Color,
    main: String,
    sub: String,
    amountText: String,
    amountColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .background(accent.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(main, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                amountText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = amountColor,
                textAlign = TextAlign.End
            )
        }
    }
}

/** 级联下拉选择框（查询页用：(code, name) 对） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerBox(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) = DropdownField(
    label = label,
    value = value,
    options = options.map { it.second },
    enabled = enabled,
    modifier = modifier,
    onSelect = onSelect
)

/** 充值页用的下拉框（选项是 BillOption） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BillPicker(
    label: String,
    value: String,
    options: List<BillOption>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) = DropdownField(
    label = label,
    value = value,
    options = options.map { it.name },
    enabled = enabled,
    modifier = modifier,
    onSelect = onSelect
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label, fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 14.sp) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    }
                )
            }
        }
    }
}
