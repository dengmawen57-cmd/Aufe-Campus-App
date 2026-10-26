package cn.aufe.campus.ui.plan

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.EmptyBox
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.SectionCard
import cn.aufe.campus.ui.components.StatItem
import cn.aufe.campus.ui.components.TagChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val state by vm.plan.collectAsState()

    LaunchedEffect(Unit) { vm.loadPlan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的培养方案") },
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
                state.loading && state.overview == null -> LoadingBox("正在获取培养方案…", 260.dp)
                state.error.isNotBlank() && state.overview == null ->
                    ErrorBox(state.error, { vm.loadPlan(force = true) }, 260.dp)
                else -> PlanContent(state.overview, state.groups)
            }
        }
    }
}

@Composable
private fun PlanContent(overview: PlanOverview?, groups: List<PlanGroup>) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (overview != null) {
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
                                Icons.Filled.AccountTree, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                overview.planName.ifBlank { "培养方案" },
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatItem("${overview.totalCourses}", "总门数", Modifier.weight(1f))
                            StatItem(
                                "${overview.totalCredits.let { if (it == it.toLong().toDouble()) it.toLong() else it }}",
                                "总学分",
                                Modifier.weight(1f)
                            )
                            StatItem(
                                "${overview.totalHours.let { if (it == it.toLong().toDouble()) it.toLong() else it }}",
                                "总课时",
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            item { EmptyBox("暂无培养方案数据", 200.dp) }
        } else {
            items(groups) { g ->
                GroupNode(g, depth = 0)
            }
        }
    }
}

@Composable
private fun GroupNode(group: PlanGroup, depth: Int) {
    val indent = (depth * 14).dp
    Column(Modifier.padding(start = indent)) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = if (depth == 0) 1.dp else 0.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name.ifBlank { "未命名课组" },
                        style = if (depth == 0) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (group.requiredMissing > 0) {
                        TagChip("缺修${group.requiredMissing}门", MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        "${fmt(group.passedCredit)}/${fmt(group.minCredit)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (group.minCredit > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = group.progress,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = when {
                            group.progress >= 1f -> cn.aufe.campus.ui.theme.ColorSuccess
                            group.progress >= 0.6f -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已通过 ${fmt(group.passedCredit)} / 需修 ${fmt(group.minCredit)} 学分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        group.children.forEach { child ->
            GroupNode(child, depth + 1)
        }
    }
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.CHINA, "%.1f", v)
