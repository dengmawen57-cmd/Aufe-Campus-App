package cn.aufe.campus.ui.score

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.AcademicInfo
import cn.aufe.campus.data.model.ScoreDetailItem
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.data.model.TermItem
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.EmptyBox
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.StatItem
import cn.aufe.campus.ui.components.TagChip
import cn.aufe.campus.ui.theme.ColorSuccess
import cn.aufe.campus.util.ScoreCsvExporter
import java.util.Locale

/**
 * 成绩查询（对齐 LoveACE 的分学期查看体验）。
 *
 * 结构：学校口径学业卡 → 学期 Tab → 该学期成绩列表 → 点课程看明细。
 * 与 v1.7 的区别：
 * 1. 成绩不再是一条扁平长列表，而是按教务学期切换；
 * 2. 顶部直接展示学校算的 GPA 与均分排名，不用自己估；
 * 3. 本学期课程可点开看平时/期中/期末的构成；
 * 4. 当前学期成绩可导出为 CSV。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreQueryScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val state by vm.scoreQuery.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadScoreQuery() }

    val detail = state.detailRecord
    if (detail != null) {
        ModalBottomSheet(onDismissRequest = vm::dismissScoreDetail) {
            ScoreDetailSheet(
                record = detail,
                items = state.detailItems,
                loading = state.detailLoading,
                error = state.detailError
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (state.records.isNotEmpty()) {
                        IconButton(onClick = {
                            val termName = state.selectedTerm?.termName ?: "成绩"
                            val savedTo = runCatching {
                                ScoreCsvExporter.export(context, termName, state.records)
                            }.getOrDefault("")
                            Toast.makeText(
                                context,
                                if (savedTo.isNotBlank()) "已导出：$savedTo"
                                else "导出失败，请稍后重试",
                                Toast.LENGTH_LONG
                            ).show()
                        }) {
                            Icon(Icons.Filled.Download, "导出 CSV")
                        }
                    }
                    IconButton(onClick = {
                        vm.loadScoreQuery(force = true)
                        vm.loadAllScores(force = true)
                    }) {
                        if (state.loading) {
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
                state.loading && state.terms.isEmpty() ->
                    LoadingBox("正在获取成绩数据…", 260.dp)

                state.error.isNotBlank() && state.terms.isEmpty() ->
                    ErrorBox(state.error, { vm.loadScoreQuery(force = true) }, 260.dp)

                state.terms.isEmpty() ->
                    EmptyBox("未获取到学期信息", 220.dp)

                else -> {
                    AcademicHero(
                        academic = state.academic,
                        loading = state.academicLoading,
                        error = state.academicError,
                        onRetry = { vm.loadAcademic(force = true) }
                    )
                    TermTabs(
                        terms = state.terms,
                        selected = state.selectedTerm,
                        onSelect = vm::selectTerm
                    )
                    TermSummary(
                        term = state.selectedTerm,
                        records = state.records,
                        loading = state.loading
                    )
                    ScoreList(
                        state = state,
                        onOpenDetail = { record ->
                            if (state.selectedTerm?.isCurrent == true) vm.loadScoreDetail(record)
                        },
                        onLoadMore = { vm.loadMoreScoreQuery() },
                        onRetry = {
                            state.selectedTerm?.let { vm.loadTermScores(it, force = true) }
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 学业概览卡

@Composable
private fun AcademicHero(
    academic: AcademicInfo?,
    loading: Boolean,
    error: String,
    onRetry: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            when {
                academic != null -> AcademicHeroContent(academic)

                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在获取学业概览…",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "学业概览暂不可用",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            error.ifBlank { "教务未返回 GPA / 排名数据" },
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(onClick = onRetry) {
                        Text("重试", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicHeroContent(info: AcademicInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            GpaRing(
                progress = (info.gpa / 5.0).toFloat().coerceIn(0f, 1f),
                modifier = Modifier.size(78.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(Locale.CHINA, "%.2f", info.gpa),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    "绩点",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "学业概览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                info.currentTerm.ifBlank { "当前学期" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "学校口径 · 满分 5.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
            )
        }
        if (info.hasAverageRank) {
            Box(
                Modifier
                    .background(
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "均分排名",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    )
                    Text(
                        "#${info.averageRank}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "/ ${info.averageRankTotal}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f))
    Spacer(Modifier.height(12.dp))

    Row(Modifier.fillMaxWidth()) {
        HeroStat("${info.completedCourses}", "已修门数")
        HeroStat("${info.failedCourses}", "未通过")
        HeroStat("${info.pendingCourses}", "待修")
        HeroStat(
            if (info.hasAverageRank) {
                "前 ${String.format(Locale.CHINA, "%.0f", info.rankPercent * 100)}%"
            } else "—",
            "排名位置"
        )
    }
}

@Composable
private fun RowScope.HeroStat(value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)
        )
    }
}

/** 环形进度（绩点 / 5.0），270° 开口环 */
@Composable
private fun GpaRing(progress: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
    val arc = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = arc,
            startAngle = 135f,
            sweepAngle = 270f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

// ---------------------------------------------------------------- 学期切换

@Composable
private fun TermTabs(
    terms: List<TermItem>,
    selected: TermItem?,
    onSelect: (TermItem) -> Unit
) {
    val index = terms.indexOfFirst { it.termCode == selected?.termCode }.coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = index,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 12.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {}
    ) {
        terms.forEach { term ->
            Tab(
                selected = term.termCode == selected?.termCode,
                onClick = { onSelect(term) },
                text = {
                    Text(
                        term.termName.ifBlank { term.shortName },
                        maxLines = 1,
                        fontSize = 13.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun TermSummary(term: TermItem?, records: List<ScoreRecord>, loading: Boolean) {
    if (loading || records.isEmpty()) return
    val credits = records.sumOf { it.credit }
    val passed = records.count { it.passed }
    val avg = records.mapNotNull { it.score.toDoubleOrNull() }
        .let { if (it.isEmpty()) null else it.average() }

    Column {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatItem("${records.size}", "门课程", Modifier.weight(1f))
                StatItem(fmtCredit(credits), "学分", Modifier.weight(1f))
                StatItem("$passed", "通过", Modifier.weight(1f))
                StatItem(
                    avg?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "—",
                    "平均分",
                    Modifier.weight(1f),
                    valueColor = avg?.let { scoreColor(it) } ?: MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (term?.isCurrent == true && records.isNotEmpty()) {
            Text(
                "本学期课程可点击查看平时 / 期中 / 期末构成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- 成绩列表

@Composable
private fun ScoreList(
    state: CampusViewModel.ScoreQueryState,
    onOpenDetail: (ScoreRecord) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit
) {
    when {
        state.loading -> LoadingBox("正在加载本学期成绩…", 200.dp)
        state.error.isNotBlank() -> ErrorBox(state.error, onRetry, 200.dp)
        state.records.isEmpty() -> EmptyBox("该学期暂无成绩记录", 180.dp)
        else -> LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.records.size, key = { i ->
                val r = state.records[i]
                "${state.selectedTerm?.termCode}_${r.courseNo}_${r.classSeq}_$i"
            }) { index ->
                val record = state.records[index]
                ScoreCard(
                    record = record,
                    clickable = state.selectedTerm?.isCurrent == true && record.hasScore,
                    onClick = { onOpenDetail(record) }
                )
            }
            item {
                when {
                    state.loadingMore -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    !state.noMore && state.selectedTerm?.isCurrent != true -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        OutlinedButton(onClick = onLoadMore) { Text("加载更多") }
                    }

                    else -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "共 ${state.records.size} 门课程",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(record: ScoreRecord, clickable: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    record.courseName.ifBlank { "未知课程" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${fmtCredit(record.credit)} 学分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (record.property.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        TagChip(record.property, MaterialTheme.colorScheme.secondary)
                    }
                    if (record.examType.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        TagChip(record.examType, MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (record.enName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        record.enName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                ScoreBadge(record.score)
                if (record.gpa.isNotBlank() && record.score.toDoubleOrNull() != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "绩点 ${record.gpa}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (clickable) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "查看成绩明细",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 成绩徽章：>=90 绿、60~89 主色、<60 红；非数值（优秀/合格）中性色 */
@Composable
private fun ScoreBadge(score: String) {
    val value = score.toDoubleOrNull()
    val bg = if (value == null) MaterialTheme.colorScheme.surfaceVariant
    else scoreColor(value).copy(alpha = 0.14f)
    val fg = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else scoreColor(value)
    Box(
        Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            score.ifBlank { "—" },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

/**
 * 分数配色：不及格红，60→100 由红经黄到绿。
 * 用分段线性插值而不是纯色档位，整列成绩扫一眼就能看出哪几门在拉分。
 */
private fun scoreColor(score: Double): Color {
    if (score < 60) return Color(0xFFD32F2F)
    val t = ((score - 60.0) / 40.0).coerceIn(0.0, 1.0).toFloat()
    return when {
        t < 0.25f -> lerp(Color(0xFFE53935), Color(0xFFF57C00), t / 0.25f)
        t < 0.5f -> lerp(Color(0xFFF57C00), Color(0xFFFBC02D), (t - 0.25f) / 0.25f)
        t < 0.75f -> lerp(Color(0xFFFBC02D), Color(0xFF7CB342), (t - 0.5f) / 0.25f)
        else -> lerp(Color(0xFF7CB342), ColorSuccess, (t - 0.75f) / 0.25f)
    }
}

private fun lerp(a: Color, b: Color, f: Float): Color {
    val t = f.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t
    )
}

// ---------------------------------------------------------------- 成绩明细

@Composable
private fun ScoreDetailSheet(
    record: ScoreRecord,
    items: List<ScoreDetailItem>,
    loading: Boolean,
    error: String
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp)
    ) {
        Text(
            record.courseName.ifBlank { "课程" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(
                "${fmtCredit(record.credit)} 学分",
                record.property.ifBlank { "—" },
                "总评 ${record.score.ifBlank { "—" }}"
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text("正在获取成绩构成…", style = MaterialTheme.typography.bodyMedium)
            }

            error.isNotBlank() -> Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            items.isEmpty() -> Text(
                "教务未提供这门课的成绩构成",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    item.scoreType,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                DetailRow("平时", item.usualScore)
                DetailRow("期中", item.midtermScore)
                DetailRow("期末", item.finalScore)
                DetailRow("总评", item.categoryScore, emphasize = true)
                if (item.remark.isNotBlank()) DetailRow("备注", item.remark)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium,
            color = if (emphasize) {
                value.toDoubleOrNull()?.let { scoreColor(it) }
                    ?: MaterialTheme.colorScheme.onSurface
            } else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun fmtCredit(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(Locale.CHINA, "%.1f", v)
