package cn.aufe.campus.ui.score

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.EmptyBox
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.StatItem
import cn.aufe.campus.ui.components.TagChip
import cn.aufe.campus.ui.theme.ColorSuccess
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreQueryScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val state by vm.scoreQuery.collectAsState()

    LaunchedEffect(Unit) { vm.loadScoreQuery() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
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
                state.loading && state.records.isEmpty() ->
                    LoadingBox("正在获取成绩数据…", 260.dp)
                state.error.isNotBlank() && state.records.isEmpty() ->
                    ErrorBox(state.error, { vm.loadScoreQuery(force = true) }, 260.dp)
                state.records.isEmpty() ->
                    EmptyBox("暂无成绩记录", 220.dp)
                else -> ScoreQueryContent(
                    records = state.records,
                    loadingMore = state.loadingMore,
                    noMore = state.noMore,
                    onLoadMore = { vm.loadMoreScoreQuery() }
                )
            }
        }
    }
}

@Composable
private fun ScoreQueryContent(
    records: List<ScoreRecord>,
    loadingMore: Boolean,
    noMore: Boolean,
    onLoadMore: () -> Unit
) {
    val passed = records.filter { it.passed }
    val passedCredits = passed.sumOf { it.credit }
    val byTerm = records.groupBy { it.term.ifBlank { "其他学期" } }
        .toList()
        .sortedByDescending { it.first }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 汇总 ----------
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.School, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("学分来源明细", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatItem(fmtCredit(passedCredits), "已修学分", Modifier.weight(1f))
                        StatItem("${records.size}", "课程门数", Modifier.weight(1f))
                        StatItem("${passed.size}", "通过门数", Modifier.weight(1f))
                        StatItem("${byTerm.size}", "学期数", Modifier.weight(1f))
                    }
                }
            }
        }

        // ---------- 按学期分组 ----------
        byTerm.forEach { (term, list) ->
            item(key = "term-$term") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        term,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "本学期 ${fmtCredit(list.sumOf { it.credit })} 学分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(list.size, key = { "course-$term-${list[it].courseNo}-${list[it].classSeq}-$it" }) { idx ->
                ScoreItem(list[idx])
            }
        }

        // ---------- 分页 ----------
        item {
            when {
                loadingMore -> Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                noMore -> Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "已加载全部成绩记录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedButton(onClick = onLoadMore) { Text("加载更多") }
                }
            }
        }
    }
}

@Composable
private fun ScoreItem(r: ScoreRecord) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        r.courseName.ifBlank { "未知课程" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            r.courseNo.ifBlank { null },
                            r.classSeq.takeIf { it.isNotBlank() }?.let { "课序号 $it" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                ScoreBadge(r.score)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagChip("${fmtCredit(r.credit)} 学分", MaterialTheme.colorScheme.primary)
                if (r.property.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    TagChip(r.property, MaterialTheme.colorScheme.secondary)
                }
                if (r.examType.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    TagChip(r.examType, MaterialTheme.colorScheme.tertiary)
                }
                if (r.gpa.isNotBlank()) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "绩点 ${r.gpa}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (r.failReason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "未通过原因：${r.failReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 成绩徽章：>=90 成功色，60~89 主色，<60 错误色，非数值成绩中性色 */
@Composable
private fun ScoreBadge(score: String) {
    val value = score.toDoubleOrNull()
    val bg = when {
        value == null -> MaterialTheme.colorScheme.surfaceVariant
        value >= 90 -> ColorSuccess.copy(alpha = 0.15f)
        value >= 60 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val fg = when {
        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
        value >= 90 -> ColorSuccess
        value >= 60 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            score.ifBlank { "—" },
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

private fun fmtCredit(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(Locale.CHINA, "%.1f", v)
