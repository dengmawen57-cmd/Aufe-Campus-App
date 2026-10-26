package cn.aufe.campus.ui.ykt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.TransactionRecord
import cn.aufe.campus.ui.CampusViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val IncomeRed = Color(0xFFD64545)     // 收入
private val ExpenseGreen = Color(0xFF2E8B57)  // 支出

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YktScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val s by vm.ykt.collectAsState()
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadYktBalance() }
    // 余额加载完成后回填默认日期范围
    LaunchedEffect(s.rangeText) {
        if (start.isBlank() && s.startDate.isNotBlank()) {
            start = s.startDate
            end = s.endDate
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("一卡通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadYktBalance(force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新余额")
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
                            "卡内余额",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (s.balanceLoading && s.balance == null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp), Alignment.CenterStart
                            ) { CircularProgressIndicator(Modifier.height(24.dp)) }
                        } else {
                            Text(
                                s.balance?.balanceText ?: "—",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (s.balanceError.isNotBlank()) {
                            Text(
                                s.balanceError,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // 日期范围
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("统计范围", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = start,
                                onValueChange = { start = it },
                                label = { Text("开始", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = end,
                                onValueChange = { end = it },
                                label = { Text("结束", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = { val (a, b) = rangeDays(7); start = a; end = b },
                                label = { Text("近 7 天", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { val (a, b) = rangeThisMonth(); start = a; end = b },
                                label = { Text("本月", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { val (a, b) = rangeDays(90); start = a; end = b },
                                label = { Text("近 3 个月", fontSize = 12.sp) }
                            )
                        }
                        Button(
                            onClick = { vm.loadYktRecords(start.trim(), end.trim()) },
                            enabled = !s.recordsLoading && start.isNotBlank() && end.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            Text(if (s.recordsLoading) "查询中…" else "查询消费明细")
                        }
                        Text(
                            "学校该接口响应较慢（约需数十秒），查询时请耐心等待",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // 明细
            if (s.recordsLoading) {
                item {
                    Box(Modifier.fillMaxWidth(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "正在查询消费明细…",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (s.recordsError.isNotBlank()) {
                item {
                    Text(
                        s.recordsError,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            } else if (s.records.isNotEmpty()) {
                item {
                    Text(
                        "消费记录（${s.records.size} 条）· ${s.rangeText}",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(s.records) { r -> TransactionRow(r) }
            } else if (s.startDate.isNotBlank()) {
                item {
                    TextButton(onClick = { vm.loadYktRecords(start.trim(), end.trim()) }) {
                        Text("点击查询 ${s.rangeText} 的消费明细")
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(r: TransactionRecord) {
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
                Text(
                    r.operationType.ifBlank { "交易" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    r.transactionTime.ifBlank { r.accountingTime },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (r.area.isNotBlank()) {
                    Text(
                        r.area,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    r.amountText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (r.isIncome) IncomeRed else ExpenseGreen,
                    textAlign = TextAlign.End
                )
                Text(
                    "余额 ${"%.2f".format(r.balance)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- 日期工具 ----------

private val FMT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

private fun rangeDays(days: Int): Pair<String, String> {
    val cal = Calendar.getInstance()
    val end = FMT.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, -days)
    return FMT.format(cal.time) to end
}

private fun rangeThisMonth(): Pair<String, String> {
    val cal = Calendar.getInstance()
    val end = FMT.format(cal.time)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    return FMT.format(cal.time) to end
}
