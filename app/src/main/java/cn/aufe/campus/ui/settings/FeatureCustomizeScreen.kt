package cn.aufe.campus.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.local.FeaturePrefs
import cn.aufe.campus.ui.components.FeatureScaffold

/** 一个可配置的功能入口 */
private data class FeatureSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

/**
 * 功能清单。
 *
 * 顺序按"学生一天里用到的频率"排：学习类在前，生活类在后。
 * 这里只放**可隐藏**的项；底部 tab（课表/选课/成绩单）不放进来，
 * 藏掉它们 App 就只剩一个空壳了。
 */
private val FEATURES: List<FeatureSpec> = listOf(
    FeatureSpec(FeaturePrefs.COURSE_QUERY, "开课查询", "查全校这门课开了哪些班", Icons.AutoMirrored.Filled.MenuBook),
    FeatureSpec(FeaturePrefs.EXAM, "考试安排", "考试时间、地点与座位号", Icons.Filled.Schedule),
    FeatureSpec(FeaturePrefs.EVALUATION, "学生评教", "一键完成本学期评教", Icons.Filled.RateReview),
    FeatureSpec(FeaturePrefs.COMPETITION_HUB, "学科竞赛", "申报 / 报名 / 学分认定（含网页版）", Icons.Filled.WorkspacePremium),
    FeatureSpec(FeaturePrefs.SMART_CAMPUS, "智慧校园", "校内一站式门户（网页版）", Icons.Filled.Apartment),
    FeatureSpec(FeaturePrefs.PARCEL, "取件码", "淘宝 / 拼多多的身份码与待取包裹", Icons.Filled.LocalShipping),
    FeatureSpec(FeaturePrefs.SCORE_QUERY, "成绩查询", "全部成绩与学分明细", Icons.Filled.Grade),
    FeatureSpec(FeaturePrefs.GPA, "绩点查询", "平均学分绩点与趋势", Icons.Filled.Insights),
    FeatureSpec(FeaturePrefs.PLAN, "培养方案", "课组学分完成情况", Icons.Filled.AccountTree),
    FeatureSpec(FeaturePrefs.YKT, "一卡通", "余额与消费明细", Icons.Filled.CreditCard),
    FeatureSpec(FeaturePrefs.ELECTRIC, "宿舍电费", "剩余电量与充值", Icons.Filled.Bolt),
    FeatureSpec(FeaturePrefs.CAMPUS_NET, "校园网", "跳微信办理与充值", Icons.Filled.Wifi),
    FeatureSpec(FeaturePrefs.REPAIR, "零星维修", "报修与工单进度", Icons.Filled.Search)
)

/**
 * 界面自定义（v1.9.1 新增）。
 *
 * 用户明确说要"选择性的隐藏和显示某功能"——比如毕业班的同学不需要电费，
 * 或者只想留课表和成绩。这里给每个入口一个开关，关掉后：
 * - 「我的」页面的功能列表里不再出现
 * - 「选课」里的开课查询标签页也会消失
 *
 * 关掉的不是"删除"，随时可以打开回来，配置存在本机 SharedPreferences。
 */
@Composable
fun FeatureCustomizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 依赖 version 让开关拨动后立刻反映到界面
    val version by FeaturePrefs.version.collectAsState()
    val visibility = remember(version) {
        FEATURES.associate { it.id to FeaturePrefs.isVisible(context, it.id) }
    }
    val hidden = visibility.count { !it.value }

    FeatureScaffold(
        title = "界面自定义",
        subtitle = if (hidden == 0) "所有功能都显示中" else "已隐藏 $hidden 个入口",
        onBack = onBack,
        actions = {
            TextButton(onClick = { FeaturePrefs.showAll(context) }) {
                Text("全部显示", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "关掉的功能只是从界面上隐藏，「选课」里的开课查询标签页也会跟着消失。" +
                        "数据不会删除，随时可以打开回来。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(FEATURES.size) { index ->
                val spec = FEATURES[index]
                FeatureToggleRow(
                    spec = spec,
                    checked = visibility[spec.id] ?: true,
                    onCheckedChange = { on ->
                        FeaturePrefs.setVisible(context, spec.id, on)
                    }
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    "提示：课表、选课、成绩单是底部固定标签页，不能隐藏。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun FeatureToggleRow(
    spec: FeatureSpec,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                spec.icon, null,
                tint = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    spec.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (checked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline
                )
                Text(
                    spec.subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
