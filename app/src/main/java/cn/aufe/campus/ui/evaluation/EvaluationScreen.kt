package cn.aufe.campus.ui.evaluation

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.EvalCourse
import cn.aufe.campus.ui.FeatureViewModel
import cn.aufe.campus.ui.FeatureViewModel.EvalTask
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.FeatureScaffold
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.TagChip

/**
 * 学生评教（移植自 LoveACE）。
 *
 * ## 三个"防误伤"的约束还在
 *
 * 1. **首次倒计时不跳过**：学校按服务端时间计时，提前交就是无效评价。
 * 2. **提交前确认**：一次性给多门课填同一套模板提交，不可撤销。
 * 3. **逐门有进度**：哪门在计时、哪门已交、哪门失败，一眼能看到。
 *
 * ## v1.11.3：可以自己挑老师，多门同时跑
 *
 * 每门课前面有勾选框，默认全选待评，想只评某几位老师就取消其他。
 * 执行方式从"一门接一门"改成"每 10 秒开下一门"—— 服务端只认
 * 「评价页打开之后过了多久」，所以 18 门课从 40 多分钟压到 5 分钟左右，
 * 界面上每条任务各自走自己的倒计时。
 */
@Composable
fun EvaluationScreen(vm: FeatureViewModel, onBack: () -> Unit) {
    val state by vm.evaluation.collectAsState()
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadEvaluation() }

    FeatureScaffold(
        title = "学生评教",
        subtitle = when {
            state.closed -> "学校已关闭评教"
            state.running -> "已完成 ${state.finishedCount}/${state.totalCount} 门 · 请保持本页打开"
            state.waitLeft > 0 -> "首次进入需等待 ${state.waitLeft} 秒"
            state.courses.isEmpty() -> "查看本学期待评课程"
            else -> "待评 ${state.pending.size} 门 · 已完成 ${state.done.size} 门"
        },
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.loadEvaluation(force = true) }, enabled = !state.running) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
    ) { padding ->
        when {
            state.closed -> CenterHint(
                padding = padding,
                title = "评教暂未开启",
                detail = state.closedMessage.ifBlank { "学校当前已关闭评教开关，等通知开放后再来。" }
            )

            state.waitLeft > 0 -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${state.waitLeft}", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "学校要求先浏览评教须知，倒计时结束后自动加载课程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "（这一步不能跳过：服务端按时间判定，提前提交会被作废）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            state.loading && state.courses.isEmpty() -> LoadingBox("正在获取评教课程…", 260.dp)

            state.error.isNotBlank() && state.courses.isEmpty() ->
                ErrorBox(state.error, onRetry = { vm.loadEvaluation(force = true) }, height = 260.dp)

            state.courses.isEmpty() -> CenterHint(
                padding = padding,
                title = "没有待评课程",
                detail = "本学期可能还没开放评教，或者你已经全部评完了。"
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Button(
                        onClick = { confirming = true },
                        enabled = !state.running && state.chosen.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        if (state.running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(state.progress.ifBlank { "正在处理…" })
                        } else {
                            Text(
                                when {
                                    state.pending.isEmpty() -> "全部课程已评价"
                                    state.chosen.isEmpty() -> "先勾选要评价的课程"
                                    else -> "评价选中的 ${state.chosen.size} 门" +
                                        "（约 ${estimatedMinutes(state.chosen.size)} 分钟）"
                                }
                            )
                        }
                    }
                }

                if (state.pending.isNotEmpty() && !state.running) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已选 ${state.chosen.size}/${state.pending.size} 门",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.selectAllPending() }) { Text("全选") }
                            TextButton(onClick = { vm.clearEvalSelection() }) { Text("清空") }
                        }
                    }
                }

                // 运行中：多门课并行，每条任务各自一行倒计时
                if (state.running && state.tasks.isNotEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        state.progress.ifBlank { "正在处理…" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { vm.stopEvaluation() },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("停止", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Text(
                                    "学校要求评价页打开满 2 分钟才承认提交，" +
                                        "所以多门课是叠着计时的，不用一门一门等。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                state.tasks.forEach { t ->
                                    Spacer(Modifier.height(8.dp))
                                    TaskRow(t)
                                }
                            }
                        }
                    }
                }

                if (state.error.isNotBlank() && state.courses.isNotEmpty()) {
                    item {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (state.results.isNotEmpty()) {
                    item {
                        Text(
                            "本次结果",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(state.results) { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle, null,
                                tint = if (r.success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${r.courseName}：${r.message}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (state.pending.isNotEmpty()) {
                    item {
                        Text(
                            "待评价（勾选要评的）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(state.pending) { c ->
                        CourseRow(
                            course = c,
                            checked = state.isSelected(c),
                            selectable = !state.running,
                            onToggle = { vm.toggleEvalSelection(c) }
                        )
                    }
                }
                if (state.done.isNotEmpty()) {
                    item {
                        Text(
                            "已完成",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    items(state.done) { c ->
                        CourseRow(course = c, checked = false, selectable = false, onToggle = {})
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "评教内容按模板生成：单选题 80% 选满分项、其余在次一档随机，" +
                            "文字题从几段通顺评语中随机，避免全班答案完全一致被判异常。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    if (confirming) {
        val n = state.chosen.size
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("评价选中的 $n 门课") },
            text = {
                Text(
                    "将按模板自动填写并提交这 $n 门课，内容偏正面、语气自然。" +
                        "提交后不能在本机修改，需要改动只能去教务系统里重评。\n\n" +
                        "每门课的评价页要打开满 2 分钟学校才认，这里会每 10 秒打开下一门、" +
                        "多门同时计时，所以总共大约 ${estimatedMinutes(n)} 分钟（不是 $n 倍）。" +
                        "这期间请保持本页打开、别锁屏。\n\n" +
                        "确认继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.startEvaluation()
                }) { Text("确认提交") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            }
        )
    }
}

/** 一条任务：课程 + 当前阶段 + 这门自己的倒计时 */
@Composable
private fun TaskRow(task: EvalTask) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.name.ifBlank { "未命名课程" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (task.waitLeft > 0) "${task.phase} ${task.waitLeft}s" else task.phase,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    task.ok -> MaterialTheme.colorScheme.primary
                    task.done -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (task.waitLeft > 0 && task.waitTotal > 0) {
            val done = (task.waitTotal - task.waitLeft).toFloat() / task.waitTotal.toFloat()
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { done },
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
        }
    }
}

@Composable
private fun CenterHint(padding: PaddingValues, title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CourseRow(
    course: EvalCourse,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable { onToggle() } else Modifier)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectable) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    course.name.ifBlank { "未命名课程" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                // 老师单独一行：一个老师带多门课时，勾错人的成本太高了
                if (course.teacher.isNotBlank()) {
                    Text(
                        course.teacher,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (course.evaluated) TagChip("已评", color = MaterialTheme.colorScheme.outline)
            else if (!selectable) TagChip("待评")
        }
    }
}

/**
 * 粗略估计整批要几分钟。
 *
 * 流水线模式下：第一门等 140 秒，之后每 10 秒开下一门，
 * 所以总时长 ≈ 140 + 10×(n-1) 秒，再留一点提交余量。
 * 向上取整成分钟，只说个量级，别给用户一个精确到秒的假承诺。
 */
private fun estimatedMinutes(count: Int): Int {
    if (count <= 0) return 0
    val seconds = 140 + 12 * (count - 1) + 20
    return (seconds + 59) / 60
}
