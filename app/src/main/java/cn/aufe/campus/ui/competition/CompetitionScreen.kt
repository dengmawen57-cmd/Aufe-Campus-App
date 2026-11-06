package cn.aufe.campus.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.AwardProject
import cn.aufe.campus.data.model.CreditsSummary
import cn.aufe.campus.ui.FeatureViewModel
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.FeatureScaffold
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.TagChip

/**
 * 竞赛获奖（移植自 LoveACE）。
 *
 * 两段信息：
 * 1. **学分汇总**（学校口径的六个维度）—— 学生最关心的是"我还差多少分"
 * 2. **获奖明细** —— 每项竞赛的级别 / 等级 / 日期 / 认定学分
 *
 * 数据来自 ASP.NET 页面，翻页要靠隐藏字段，所以一次拉全（见 CompetitionApi），
 * 页面本身不用做分页。
 */
@Composable
fun CompetitionScreen(vm: FeatureViewModel, onBack: () -> Unit) {
    val state by vm.competition.collectAsState()
    LaunchedEffect(Unit) { vm.loadCompetition() }

    val awards = state.data.awards
    FeatureScaffold(
        title = "竞赛获奖",
        subtitle = when {
            awards.isEmpty() -> "第二课堂竞赛申报记录"
            else -> "共 ${awards.size} 项 · 累计认定 ${fmt(state.data.summary?.total ?: 0.0)} 学分"
        },
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.loadCompetition(force = true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
    ) { padding ->
        when {
            state.loading && awards.isEmpty() -> LoadingBox("正在获取竞赛信息…", 260.dp)
            state.error.isNotBlank() && awards.isEmpty() ->
                ErrorBox(state.error, onRetry = { vm.loadCompetition(force = true) }, height = 260.dp)
            awards.isEmpty() ->
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "还没有已申报的竞赛奖项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.data.summary?.let { summary ->
                    item { CreditsCard(summary) }
                }
                item {
                    Text(
                        "获奖明细",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(awards) { award -> AwardCard(award) }
            }
        }
    }
}

/** 六个维度的学分汇总 */
@Composable
private fun CreditsCard(summary: CreditsSummary) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "认定学分合计",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                fmt(summary.total),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            Spacer(Modifier.height(10.dp))
            val rows = listOf(
                "学科竞赛" to summary.disciplineCompetition,
                "科研成果" to summary.scientificResearch,
                "可折算竞赛" to summary.transferableCompetition,
                "创新创业" to summary.innovationPractice,
                "能力资格" to summary.abilityCertification,
                "其他项目" to summary.otherProject
            ).filter { it.second != null }
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                            Text(
                                fmt(value ?: 0.0),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun AwardCard(award: AwardProject) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    award.projectName.ifBlank { "未命名项目" },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (award.grade.isNotBlank()) TagChip(award.grade, filled = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    award.level.takeIf { it.isNotBlank() },
                    award.awardDate.takeIf { it.isNotBlank() },
                    if (award.order > 0) "第${award.order}完成人" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    "认定 ${fmt(award.credits)} 学分",
                    if (award.bonus > 0) "加分 ${fmt(award.bonus)}" else null,
                    award.status.takeIf { it.isNotBlank() },
                    award.verificationStatus.takeIf { it.isNotBlank() }?.let { "审核：$it" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 去掉多余的 .0，让 2.0 显示成 2 */
private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.CHINA, "%.1f", v)
