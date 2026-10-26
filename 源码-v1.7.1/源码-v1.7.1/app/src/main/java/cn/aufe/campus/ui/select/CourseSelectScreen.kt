package cn.aufe.campus.ui.select

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.SelectableCourse
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.EmptyBox
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.TagChip
import kotlinx.coroutines.launch

private val TABS = listOf("方案内选课", "自由选课", "已选课程")

@Composable
fun CourseSelectScreen(vm: CampusViewModel) {
    val state by vm.select.collectAsState()
    val cart by vm.cart.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var keyword by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadSelectData() }

    val dealType = if (tab == 0) 2 else 5
    val pool = if (tab == 0) state.planCourses else state.freeCourses
    val filtered = if (keyword.isBlank()) pool
    else pool.filter {
        it.courseName.contains(keyword, true) || it.courseNo.contains(keyword, true) ||
            it.teacher.contains(keyword, true)
    }

    Column(Modifier.fillMaxWidth()) {
        // 标题
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, bottom = 8.dp, start = 20.dp, end = 20.dp)
        ) {
            Column {
                Text(
                    "选课中心",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    if (state.notInPhase) "当前为非选课阶段" else "共 ${pool.size} 门可选课程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        TabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { idx, t ->
                Tab(
                    selected = tab == idx,
                    onClick = { tab = idx },
                    text = { Text(t) }
                )
            }
        }

        when (tab) {
            0, 1 -> {
                // 搜索栏
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        placeholder = { Text("课程名称 / 课程号 / 教师") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                when {
                    state.loading && pool.isEmpty() -> LoadingBox("正在获取选课列表…", 240.dp)
                    state.error.isNotBlank() && pool.isEmpty() ->
                        ErrorBox(state.error, { vm.loadSelectData(force = true) }, 240.dp)
                    filtered.isEmpty() && state.notInPhase ->
                        EmptyBox("当前为非选课阶段\n可选课程列表暂未开放", 220.dp)
                    filtered.isEmpty() ->
                        EmptyBox("没有匹配的课程", 220.dp)
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.selectKey }) { c ->
                            SelectableCourseCard(
                                course = c,
                                checked = c.selectKey in cart,
                                onToggle = { vm.toggleCart(c.selectKey) }
                            )
                        }
                    }
                }
            }
            else -> {
                // 已选课程
                when {
                    state.loading && state.selected.isEmpty() -> LoadingBox("正在获取已选课程…", 240.dp)
                    state.selected.isEmpty() -> EmptyBox("暂无已选课程", 220.dp)
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.selected, key = { it.courseNo + it.classSeq + it.courseName }) { c ->
                            SelectedCourseCard(c)
                        }
                    }
                }
            }
        }
    }

    // ---------- 底部提交栏 ----------
    if (cart.isNotEmpty() && tab != 2) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${cart.size} 门",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.width(14.dp))
                    Button(
                        onClick = { confirming = true },
                        enabled = !state.submitting,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("提交选课")
                    }
                }
            }
        }
    }

    // ---------- 确认弹窗 ----------
    if (confirming) {
        val names = pool.filter { it.selectKey in cart }.map { it.courseName }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("确认提交选课") },
            text = {
                Column {
                    Text("即将提交以下课程：")
                    Spacer(Modifier.height(8.dp))
                    names.forEach {
                        Text("· $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "提交后如需退课，请在教务系统规定时间内操作。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.submitSelection(dealType) { r -> resultText = r }
                }) { Text("确认提交") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            }
        )
    }

    // ---------- 结果弹窗 ----------
    resultText?.let { r ->
        AlertDialog(
            onDismissRequest = { resultText = null },
            title = { Text("选课结果") },
            text = { Text(r) },
            confirmButton = {
                TextButton(onClick = { resultText = null }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun SelectableCourseCard(course: SelectableCourse, checked: Boolean, onToggle: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    null,
                    tint = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        course.courseName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TagChip("${course.credit.let { if (it == it.toLong().toDouble()) it.toLong() else it }}学分")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "课程号 ${course.courseNo} · 课序号 ${course.classSeq}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        course.teacher.ifBlank { "教师待定" } + if (course.dept.isNotBlank()) " · ${course.dept}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (course.slots.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            course.slots.joinToString("；") { "${it.timeText} ${it.placeText}" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (course.category.isNotBlank()) TagChip(course.category)
                    if (course.courseProperty.isNotBlank()) TagChip(course.courseProperty, MaterialTheme.colorScheme.secondary)
                    if (course.examType.isNotBlank()) TagChip(course.examType, MaterialTheme.colorScheme.tertiary)
                    if (course.capacity > 0) TagChip("余量${course.capacity}", MaterialTheme.colorScheme.error)
                }
                if (course.restrict.isNotBlank() || course.selectRule.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf(course.restrict, course.selectRule).filter { it.isNotBlank() }.joinToString("；"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedCourseCard(course: SelectedCourse) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    course.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TagChip("${course.credit.let { if (it == it.toLong().toDouble()) it.toLong() else it }}学分")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "课程号 ${course.courseNo} · 课序号 ${course.classSeq}" +
                    if (course.teacher.isNotBlank()) " · ${course.teacher}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            course.slots.forEach { s ->
                Text(
                    "${s.timeText} · ${s.placeText.ifBlank { "地点待定" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (course.selectStatus.isNotBlank() || course.examType.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (course.selectStatus.isNotBlank()) TagChip(course.selectStatus, MaterialTheme.colorScheme.secondary)
                    if (course.examType.isNotBlank()) TagChip(course.examType)
                }
            }
        }
    }
}
