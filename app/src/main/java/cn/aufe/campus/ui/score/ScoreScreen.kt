package cn.aufe.campus.ui.score

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.ScoreGroup
import cn.aufe.campus.data.model.ScoreItem
import cn.aufe.campus.data.model.ScoreReport
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.TagChip

@Composable
fun ScoreScreen(vm: CampusViewModel) {
    val state by vm.score.collectAsState()

    LaunchedEffect(Unit) { vm.loadScore() }

    Column(Modifier.fillMaxWidth()) {
        // 标题栏
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.EmojiEvents, null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "第二课堂成绩单",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "爱安财 · 安徽财经大学第二课堂平台",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        }

        when {
            state.loading && state.report == null -> LoadingBox("正在获取成绩单…", 260.dp)
            state.error.isNotBlank() && state.report == null ->
                ErrorBox(state.error, { vm.loadScore(force = true) }, 260.dp)
            state.report != null -> ScoreContent(state.report!!)
        }
    }
}

@Composable
private fun ScoreContent(report: ScoreReport) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 总分卡片
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    cn.aufe.campus.ui.theme.AufeBlueDark
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "总分",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(bottom = 6.dp, end = 6.dp)
                        )
                        Text(
                            String.format(java.util.Locale.CHINA, "%.2f", report.totalScore),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            " 分",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (report.evaluation.isNotBlank() || report.evaluationNote.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Info, null,
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                listOf("评定：${report.evaluation}", report.evaluationNote)
                                    .filter { it.isNotBlank() && it != "评定：" }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        InfoCell("姓名", report.userName, Modifier.weight(1f))
                        InfoCell("学号", report.userNo, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        InfoCell("年级", report.grade, Modifier.weight(1f))
                        InfoCell("班级", report.className, Modifier.weight(1f))
                    }
                    if (report.college.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        InfoCell("学院", report.college, Modifier.weight(1f))
                    }
                }
            }
        }

        // ---------- 学分明细（可展开，展示分数来源） ----------
        if (report.groups.isNotEmpty()) {
            item {
                Text(
                    "学分明细 · 点击展开查看每项记录",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
            items(report.groups.size, key = { "group-${report.groups[it].groupName}-$it" }) { idx ->
                GroupCard(report.groups[idx])
            }
        }

        // ---------- 工作履历 ----------
        item {
            Text(
                "工作履历",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
        item {
            RecordSection(
                icon = Icons.Filled.WorkOutline,
                items = report.jobs,
                emptyText = "暂无工作履历记录"
            )
        }

        // ---------- 获奖信息 ----------
        item {
            Text(
                "获奖信息",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
        item {
            RecordSection(
                icon = Icons.Filled.EmojiEvents,
                items = report.awards,
                emptyText = "暂无获奖记录"
            )
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f)
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1
        )
    }
}

/** 可展开课组卡：左侧分数圆标 + 组名 + 记录数，展开显示每条记录 */
@Composable
private fun GroupCard(g: ScoreGroup) {
    // 有记录的课组默认展开，方便直接看到分数来源
    var expanded by remember { mutableStateOf(g.items.isNotEmpty()) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 分数圆标
                Box(
                    Modifier
                        .size(40.dp)
                        .background(groupScoreColor(g.totalScore), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        g.totalScore.ifBlank { "0" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        g.groupName.ifBlank { "未命名课组" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "${g.items.size} 项记录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${g.totalScore.ifBlank { "0" }} 分",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    if (g.items.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "暂无记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            g.items.forEach { item ->
                                ItemRow(item, showAwardChip = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 课组分数圆标配色：0 分灰色，>0 主色 */
@Composable
private fun groupScoreColor(score: String): Color {
    val v = score.toDoubleOrNull() ?: 0.0
    return if (v > 0) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
}

/** 通用记录行：名称 + 奖项标签 + 分值 */
@Composable
private fun ItemRow(item: ScoreItem, showAwardChip: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name.ifBlank { "未命名项目" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2
            )
            if (showAwardChip && item.award.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                TagChip(item.award, MaterialTheme.colorScheme.secondary)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${item.score.ifBlank { "0" }}分",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 工作履历 / 获奖信息区块 */
@Composable
private fun RecordSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<ScoreItem>,
    emptyText: String
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            if (items.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon, null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "共 ${items.size} 条",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        ItemRow(item, showAwardChip = true)
                    }
                }
            }
        }
    }
}
