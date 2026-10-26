package cn.aufe.campus.ui.gpa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.EmptyBox
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.util.GpaCalc

/**
 * 绩点查询。
 *
 * 数据来自「成绩查询」同一套教务成绩（含学分、绩点、课程属性），
 * 这里按学分加权算出平均学分绩点，并给出学期趋势、绩点分布与不同口径的对比。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpaScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val state by vm.scoreQuery.collectAsState()

    /** 0=全部 1=必修 2=选修 */
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var onlyPassed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadScoreQuery() }

    // 绩点必须基于全量成绩：首屏拿到第 1 页后继续把后面几页补完
    LaunchedEffect(state.loaded, state.loadingMore, state.noMore) {
        if (state.loaded && !state.noMore && !state.loadingMore && state.records.isNotEmpty()) {
            vm.loadAllScores()
        }
    }

    val records = state.records
    val filtered = records.filter { r ->
        val catOk = when (scope) {
            1 -> GpaCalc.categoryOf(r) == "必修"
            2 -> GpaCalc.categoryOf(r) == "选修"
            else -> true
        }
        catOk && (!onlyPassed || r.passed)
    }
    val stats = GpaCalc.stats(filtered)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("绩点查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.loadScoreQuery(force = true)
                        vm.loadAllScores()
                    }) {
                        if (state.loading || state.loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
        ) {
            when {
                state.loading && records.isEmpty() -> LoadingBox("正在获取成绩数据…", 260.dp)
                state.error.isNotBlank() && records.isEmpty() ->
                    ErrorBox(state.error, { vm.loadScoreQuery(force = true) }, 260.dp)
                records.isEmpty() -> EmptyBox("暂无成绩记录", 220.dp)
                else -> GpaContent(
                    records = records,
                    filtered = filtered,
                    stats = stats,
                    scope = scope,
                    onScopeChange = { scope = it },
                    onlyPassed = onlyPassed,
                    onOnlyPassedChange = { onlyPassed = it },
                    syncing = state.loadingMore,
                    error = state.error
                )
            }
        }
    }
}

@Composable
private fun GpaContent(
    records: List<ScoreRecord>,
    filtered: List<ScoreRecord>,
    stats: GpaCalc.Stats,
    scope: Int,
    onScopeChange: (Int) -> Unit,
    onlyPassed: Boolean,
    onOnlyPassedChange: (Boolean) -> Unit,
    syncing: Boolean,
    error: String
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 总绩点 ----------
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GpaRing(
                            gpa = stats.gpa,
                            modifier = Modifier.size(104.dp)
                        )
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "平均学分绩点",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "五分制 · 学分加权",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row {
                                MiniMetric(
                                    label = "加权均分",
                                    value = if (stats.weightedScore > 0) "%.2f".format(stats.weightedScore) else "--",
                                    modifier = Modifier.weight(1f)
                                )
                                MiniMetric(
                                    label = "算均分",
                                    value = if (stats.averageScore > 0) "%.2f".format(stats.averageScore) else "--",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        MetricCell(
                            value = trimZero(stats.credits),
                            label = "计入学分",
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            value = "${stats.courseCount}",
                            label = "课程门数",
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            value = "${stats.passedCount}",
                            label = "通过门数",
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            value = "${stats.failCount}",
                            label = "未通过",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ---------- 统计口径 ----------
        item {
            Section("统计范围") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("全部课程", "仅必修", "仅选修").forEachIndexed { idx, label ->
                        FilterChip(
                            selected = scope == idx,
                            onClick = { onScopeChange(idx) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("只统计已通过课程", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关闭时未通过课程按 0 绩点计入（教务处的算法）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = onlyPassed, onCheckedChange = onOnlyPassedChange)
                }
            }
        }

        if (error.isNotBlank()) {
            item {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        if (syncing) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在补齐全部成绩…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- 学期趋势 ----------
        val terms = GpaCalc.byTerm(filtered)
        if (terms.isNotEmpty()) {
            item {
                Section("学期绩点") {
                    terms.forEachIndexed { idx, (term, st) ->
                        TermRow(term, st)
                        if (idx != terms.lastIndex) {
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }

        // ---------- 绩点分布 ----------
        val dist = GpaCalc.distribution(filtered)
        val maxCount = dist.maxOfOrNull { it.second } ?: 0
        if (maxCount > 0) {
            item {
                Section("绩点分布") {
                    dist.forEachIndexed { idx, (label, count) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(74.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Bar(
                                fraction = if (maxCount > 0) count.toFloat() / maxCount else 0f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "$count",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(28.dp),
                                textAlign = TextAlign.End
                            )
                        }
                        if (idx != dist.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ---------- 口径说明 ----------
        item {
            Section("计算说明") {
                Text(
                    "· 平均学分绩点 = Σ(课程绩点 × 学分) ÷ Σ学分\n" +
                        "· 绩点优先取教务成绩单给出的值；缺失时按 (分数 - 50) ÷ 10 换算，" +
                        "等级成绩按 优秀 4.5 / 良好 3.5 / 中等 2.5 / 及格 1.5 / 不及格 0\n" +
                        "· 百分制换算下 60 分 = 1.0、90 分 = 4.0、100 分 = 5.0\n" +
                        "· 学分为 0 的课程（如部分实践环节）不参与加权\n" +
                        "· 重修课程若在成绩单中保留多条记录，会按多条分别计入",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Insights,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "共 ${records.size} 条成绩记录，其中 ${filtered.size} 条参与本次统计",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ==================== 组件 ====================

/** 环形绩点进度：环长度 = 绩点 / 满绩 */
@Composable
private fun GpaRing(gpa: Double, modifier: Modifier = Modifier) {
    val ratio = (gpa / GpaCalc.MAX_POINT).toFloat().coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val accent = MaterialTheme.colorScheme.primary

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.1f
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (ratio > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * ratio,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (gpa > 0) "%.2f".format(gpa) else "--",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                "GPA",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun MetricCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

/** 学期一行：学期名 + 绩点 + 学分 + 迷你进度条 */
@Composable
private fun TermRow(term: String, stats: GpaCalc.Stats) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                term,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "%.2f".format(stats.gpa),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${trimZero(stats.credits)} 学分",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Bar(fraction = (stats.gpa / GpaCalc.MAX_POINT).toFloat().coerceIn(0f, 1f))
    }
}

/** 细长进度条（不依赖 LinearProgressIndicator，避免不同 Material3 版本的 API 差异） */
@Composable
private fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private fun trimZero(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
