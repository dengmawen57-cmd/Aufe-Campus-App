package cn.aufe.campus.ui.electric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.ElectricityUsageRecord
import cn.aufe.campus.data.model.PaymentRecord
import cn.aufe.campus.ui.CampusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val s by vm.electric.collectAsState()

    LaunchedEffect(Unit) { vm.loadElectricBuildings() }

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
                // 余额卡
                item {
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
                            Text(
                                "购电 ${"%.2f".format(info.balance.remainingPurchased)} 度 · " +
                                    "补助 ${"%.2f".format(info.balance.remainingSubsidy)} 度",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                if (info.usageRecords.isNotEmpty()) {
                    item { Text("用电记录（${info.usageRecords.size} 条）", fontWeight = FontWeight.SemiBold) }
                    items(info.usageRecords) { u -> UsageRow(u) }
                }
                if (info.payments.isNotEmpty()) {
                    item { Text("缴费记录（${info.payments.size} 条）", fontWeight = FontWeight.SemiBold) }
                    items(info.payments) { p -> PaymentRow(p) }
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
        }
    }
}

@Composable
private fun UsageRow(u: ElectricityUsageRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(u.recordTime, fontSize = 13.sp)
                if (u.meterName.isNotBlank()) {
                    Text(
                        u.meterName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "${"%.2f".format(u.usageAmount)} 度",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun PaymentRow(p: PaymentRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.paymentTime, fontSize = 13.sp)
                if (p.paymentType.isNotBlank()) {
                    Text(
                        p.paymentType,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "${"%.2f".format(p.amount)} 元",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

/** 级联下拉选择框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerBox(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
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
            options.forEachIndexed { i, (_, name) ->
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
