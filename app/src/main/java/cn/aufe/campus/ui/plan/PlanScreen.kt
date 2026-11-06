package cn.aufe.campus.ui.plan

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.EmptyBox
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.StatItem
import cn.aufe.campus.ui.components.TagChip
import cn.aufe.campus.util.AppLog

/**
 * 我的培养方案。
 *
 * ## v1.8.2 新增「收纳」
 *
 * 培养方案的课组往往有七八层，一屏塞满之后想找某个模块得滑很久。
 * 这里给每个课组卡片加了折叠：点标题栏收起/展开，选择会存到本地，
 * 下次进来保持原样。
 *
 * 折叠状态用**路径字符串**做 key（`0:通识必修/2:数学与自然科学`），
 * 而不是课组名——同名课组（比如好几个"选修课"）不会互相干扰。
 * 路径里带上同级序号，所以即使某个课组被删掉，其它课组的折叠状态也不会串。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(vm: CampusViewModel, onBack: () -> Unit) {
    val state by vm.plan.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadPlan() }

    var collapsed by remember { mutableStateOf(PlanFoldPrefs.load(context)) }

    fun persist(next: Set<String>) {
        collapsed = next
        PlanFoldPrefs.save(context, next)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("我的培养方案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 全部收起：把所有带子节点的课组都塞进折叠集合
                        persist(collectAllPaths(state.groups))
                        AppLog.i("Plan", "全部收起")
                    }) {
                        Icon(Icons.Filled.UnfoldLess, "全部收起")
                    }
                    IconButton(onClick = {
                        persist(emptySet())
                        AppLog.i("Plan", "全部展开")
                    }) {
                        Icon(Icons.Filled.UnfoldMore, "全部展开")
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
                state.loading && state.overview == null -> LoadingBox("正在获取培养方案…", 260.dp)
                state.error.isNotBlank() && state.overview == null ->
                    ErrorBox(state.error, { vm.loadPlan(force = true) }, 260.dp)
                else -> PlanContent(
                    overview = state.overview,
                    groups = state.groups,
                    collapsed = collapsed,
                    onToggle = { path ->
                        persist(
                            if (path in collapsed) collapsed - path else collapsed + path
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PlanContent(
    overview: PlanOverview?,
    groups: List<PlanGroup>,
    collapsed: Set<String>,
    onToggle: (String) -> Unit
) {
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
                                fmt(overview.totalCredits),
                                "总学分",
                                Modifier.weight(1f)
                            )
                            StatItem(
                                fmt(overview.totalHours),
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
            items(groups.size) { index ->
                GroupNode(
                    group = groups[index],
                    depth = 0,
                    path = "$index:${groups[index].name}",
                    collapsed = collapsed,
                    onToggle = onToggle
                )
            }
        }
    }
}

@Composable
private fun GroupNode(
    group: PlanGroup,
    depth: Int,
    path: String,
    collapsed: Set<String>,
    onToggle: (String) -> Unit
) {
    val indent = (depth * 14).dp
    val hasChildren = group.children.isNotEmpty()
    val isCollapsed = path in collapsed

    Column(Modifier.padding(start = indent)) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = if (depth == 0) 1.dp else 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                // 只有带子课组的卡片可点：叶子节点点了没反应会让人以为卡了
                .then(
                    if (hasChildren) Modifier.clickable { onToggle(path) } else Modifier
                )
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
                    if (hasChildren) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isCollapsed) Icons.Filled.ExpandMore
                            else Icons.Filled.ExpandLess,
                            contentDescription = if (isCollapsed) "展开" else "收起",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                // 收起时给一句摘要，否则用户看不出里面还有东西
                if (hasChildren && isCollapsed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已收起 ${group.children.size} 个下级课组 · 点此展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = hasChildren && !isCollapsed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                group.children.forEachIndexed { i, child ->
                    GroupNode(
                        group = child,
                        depth = depth + 1,
                        path = "$path/${i}:${child.name}",
                        collapsed = collapsed,
                        onToggle = onToggle
                    )
                }
            }
        }
    }
}

/** 收集所有"有子节点"的课组路径，供「全部收起」使用 */
private fun collectAllPaths(groups: List<PlanGroup>): Set<String> {
    val out = mutableSetOf<String>()
    fun walk(list: List<PlanGroup>, prefix: String) {
        list.forEachIndexed { i, g ->
            val path = if (prefix.isEmpty()) "$i:${g.name}" else "$prefix/$i:${g.name}"
            if (g.children.isNotEmpty()) {
                out.add(path)
                walk(g.children, path)
            }
        }
    }
    walk(groups, "")
    return out
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.CHINA, "%.1f", v)

/** 折叠状态持久化：只存被收起的路径，默认（空集）即全部展开 */
internal object PlanFoldPrefs {
    private const val PREFS = "aufe_plan_fold"
    private const val KEY = "collapsed_paths"

    fun load(context: Context): Set<String> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    fun save(context: Context, paths: Set<String>) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, paths).apply()
        }
    }
}
