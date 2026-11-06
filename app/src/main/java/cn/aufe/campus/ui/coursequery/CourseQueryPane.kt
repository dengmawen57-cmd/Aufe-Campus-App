package cn.aufe.campus.ui.coursequery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.OfferedCourse
import cn.aufe.campus.data.model.QueryOption
import cn.aufe.campus.ui.FeatureViewModel
import cn.aufe.campus.ui.components.EmptyActionBox
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.TagChip

/**
 * 开课查询（嵌在「选课」板块的第四个标签页里）。
 *
 * ## 和「自由选课」的区别
 *
 * - 自由选课：查的是**这学期你能选什么**（受培养方案、选课阶段限制，能直接选）
 * - 开课查询：查的是**全校这学期开了什么课**（只读，看清容量、时间、地点、任课老师）
 *
 * 两者数据源不同（`courseSelect` vs `integratedQuery/courseSchdule`），
 * 所以是两套接口、两个页面，不是同一个列表换个标题。
 *
 * ## 分页
 *
 * 学校这个接口一次只给 20 条，整学期几百条全拉会很慢。这里做成
 * 「滚到底部点加载更多」，既不给服务端压力，也不至于一次等十几秒。
 */
@Composable
fun CourseQueryPane(vm: FeatureViewModel) {
    val state by vm.courseQuery.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(false) }

    // 「更多筛选」里已经选了几项，折叠着也要能看出来，否则用户会以为查询没生效
    val advCount = with(state.query) {
        listOf(
            department, campus, building, category,
            weekday?.toString() ?: "",
            startSession?.toString() ?: ""
        ).count { it.isNotBlank() }
    }

    LaunchedEffect(Unit) { vm.loadCourseQueryOptions() }

    Column(Modifier.fillMaxSize()) {
        // ---------- 查询条件 ----------
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 学期（必选）
                Box(Modifier.weight(1f)) {
                    OptionPicker(
                        label = "学期",
                        selected = state.options.terms.firstOrNull { it.code == state.query.termCode }?.name
                            ?: "选择学期",
                        options = state.options.terms,
                        onPick = { vm.updateCourseQuery { q -> q.copy(termCode = it.code) } }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.searchCourses() },
                    enabled = !state.loading,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.Search, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("查询")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query.courseName,
                    onValueChange = { v -> vm.updateCourseQuery { it.copy(courseName = v) } },
                    label = { Text("课程名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.query.teacher,
                    onValueChange = { v -> vm.updateCourseQuery { it.copy(teacher = v) } },
                    label = { Text("教师") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // 更多筛选：院系 / 校区 / 教学楼 / 类别
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { advanced = !advanced }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (advCount > 0) "更多筛选 · 已选 $advCount 项" else "更多筛选",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OptionPicker(
                                label = "院系",
                                selected = state.options.departments
                                    .firstOrNull { it.code == state.query.department }?.name ?: "不限",
                                options = state.options.departments,
                                includeAll = true,
                                onPick = { vm.updateCourseQuery { q -> q.copy(department = it.code) } }
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            OptionPicker(
                                label = "校区",
                                selected = state.options.campuses
                                    .firstOrNull { it.code == state.query.campus }?.name ?: "不限",
                                options = state.options.campuses,
                                includeAll = true,
                                onPick = { vm.updateCourseQuery { q -> q.copy(campus = it.code) } }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OptionPicker(
                                label = "教学楼",
                                selected = state.options.buildings
                                    .firstOrNull { it.code == state.query.building }?.name ?: "不限",
                                options = state.options.buildings,
                                includeAll = true,
                                onPick = { vm.updateCourseQuery { q -> q.copy(building = it.code) } }
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            OptionPicker(
                                label = "课程类别",
                                selected = state.options.categories
                                    .firstOrNull { it.code == state.query.category }?.name ?: "不限",
                                options = state.options.categories,
                                includeAll = true,
                                onPick = { vm.updateCourseQuery { q -> q.copy(category = it.code) } }
                            )
                        }
                    }
                    // 按上课时间筛：星期 + 起始节次。
                    // 只筛「起始节次」，因为教务的 skjc 参数含义就是"第几节开始"，
                    // 想找「周二下午有没有空」就是 周二 + 第5节 起。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OptionPicker(
                                label = "星期",
                                selected = WEEKDAYS.firstOrNull { it.code == state.query.weekday?.toString() }?.name
                                    ?: "不限",
                                options = WEEKDAYS,
                                includeAll = true,
                                onPick = { q ->
                                    vm.updateCourseQuery { it.copy(weekday = q.code.toIntOrNull()) }
                                }
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            OptionPicker(
                                label = "从第几节开始",
                                selected = SESSIONS.firstOrNull { it.code == state.query.startSession?.toString() }?.name
                                    ?: "不限",
                                options = SESSIONS,
                                includeAll = true,
                                onPick = { q ->
                                    vm.updateCourseQuery { it.copy(startSession = q.code.toIntOrNull()) }
                                }
                            )
                        }
                    }
                }
            }
        }

        // ---------- 结果 ----------
        when {
            state.loading && state.records.isEmpty() -> LoadingBox("正在查询开课信息…", 220.dp)
            state.error.isNotBlank() && state.records.isEmpty() ->
                ErrorBox(state.error, onRetry = { vm.searchCourses() }, height = 220.dp)
            !state.searched ->
                EmptyActionBox(
                    "选好学期后点「查询」，可以看到全校这门课开了哪些班、谁教、在哪上。",
                    "查一下",
                    onAction = { vm.searchCourses() }
                )
            state.records.isEmpty() ->
                EmptyActionBox("没有符合条件的开课记录", "重新查询", onAction = { vm.searchCourses() })
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "共 ${state.total} 条 · 已显示 ${state.records.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(state.records) { course -> OfferedCourseCard(course) }
                item {
                    if (state.canLoadMore) {
                        TextButton(
                            onClick = { vm.loadMoreCourses() },
                            enabled = !state.loadingMore,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("加载中…")
                            } else {
                                Text("加载更多")
                            }
                        }
                    } else {
                        Text(
                            "已经到底了",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 星期筛选项：code 就是教务接口的 skxq（1=周一 … 7=周日）。
 * 单独列出来而不是从首页 HTML 抓 —— 星期是固定 7 天，没必要多一次请求。
 */
private val WEEKDAYS = (1..7).map { QueryOption(it.toString(), "周" + "一二三四五六日"[it - 1]) }

/** 节次筛选项：学校一天最多 12 节（上午 1-4 / 下午 5-8 / 晚上 9-12） */
private val SESSIONS = (1..12).map { QueryOption(it.toString(), "第${it}节") }

/** 一门开课记录 */
@Composable
private fun OfferedCourseCard(course: OfferedCourse) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    course.courseName.ifBlank { "未命名课程" },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (course.categoryName.isNotBlank()) {
                    TagChip(course.categoryName)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 课程号 / 教学班 / 学分，用一行紧凑展示
            Text(
                listOfNotNull(
                    course.courseNo.takeIf { it.isNotBlank() }?.let { "课号 $it" },
                    course.classNo.takeIf { it.isNotBlank() }?.let { "班 $it" },
                    course.credits.takeIf { it.isNotBlank() }?.let { "$it 学分" },
                    course.hours.takeIf { it.isNotBlank() }?.let { "$it 学时" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "教师：${course.teacher.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "时间：${course.timeText}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "地点：${course.placeText}",
                style = MaterialTheme.typography.bodySmall
            )
            val extra = listOfNotNull(
                course.weeks.takeIf { it.isNotBlank() }?.let { "周次 $it" },
                if (course.capacity.isNotBlank()) "容量 ${course.capacity}" else null,
                if (course.selectedCount.isNotBlank()) "已选 ${course.selectedCount}" else null,
                course.examType.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (extra.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    extra,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * 下拉选择器。
 *
 * @param includeAll true 时在列表最前面加一个「不限」，用于非必选的筛选项
 */@Composable
internal fun OptionPicker(
    label: String,
    selected: String,
    options: List<QueryOption>,
    includeAll: Boolean = false,
    onPick: (QueryOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = options.isNotEmpty()) { expanded = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    selected.ifBlank { "不限" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (includeAll) {
                DropdownMenuItem(
                    text = { Text("不限") },
                    onClick = {
                        onPick(QueryOption("", "不限"))
                        expanded = false
                    }
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name, maxLines = 1) },
                    onClick = {
                        onPick(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
