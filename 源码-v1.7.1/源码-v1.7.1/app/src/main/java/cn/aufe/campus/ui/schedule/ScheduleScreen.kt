package cn.aufe.campus.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.CourseSlot
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.InfoRow
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.SectionCard
import cn.aufe.campus.ui.components.TagChip
import cn.aufe.campus.util.ScheduleUtils
import cn.aufe.campus.util.WeekProvider
import java.util.Locale

/** 课程块配色（按课程名哈希取色） */
private val BLOCK_COLORS = listOf(
    Color(0xFF1A5FB4), Color(0xFF00897B), Color(0xFF8E24AA), Color(0xFFE64A19),
    Color(0xFF3949AB), Color(0xFFC2185B), Color(0xFF00796B), Color(0xFF5D4037),
    Color(0xFF0288D1), Color(0xFF689F38)
)

private fun colorOf(courseName: String): Color {
    val idx = (courseName.hashCode().let { if (it < 0) -it else it }) % BLOCK_COLORS.size
    return BLOCK_COLORS[idx]
}

/** 解析 "5-18周"、"第9周"、"1-8,10-16周" 等周次描述为区间列表（委托公共工具） */
private fun parseWeeks(desc: String): List<IntRange> = ScheduleUtils.parseWeeks(desc)

private fun slotInWeek(slot: CourseSlot, week: Int): Boolean =
    ScheduleUtils.slotInWeek(slot.weekDesc, week)

/** 当前是第几周（相对 SEMESTER_START） */
private fun currentWeek(): Int = ScheduleUtils.currentWeek()

/** 今天是星期几（1=周一 … 7=周日） */
private fun todayDay(): Int = ScheduleUtils.todayDay()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: CampusViewModel, onOpenYkt: () -> Unit = {}) {
    val state by vm.schedule.collectAsState()
    val ykt by vm.ykt.collectAsState()
    val serverWeek = currentWeek()
    var autoWeek by remember { mutableIntStateOf(serverWeek) }
    var week by rememberSaveable { mutableIntStateOf(serverWeek) }
    /** 是否跟随"本周"：用户手动翻到别的周后就不再自动跳回 */
    var followNow by rememberSaveable { mutableStateOf(true) }
    var showCalibrate by remember { mutableStateOf(false) }
    var detailCourse by rememberSaveable { mutableStateOf<String?>(null) }
    val today = todayDay()
    val isCurrentWeek = week == autoWeek

    LaunchedEffect(Unit) {
        vm.loadSchedule()
        vm.loadYktBalance()
    }

    // 课表加载完（可能刚和教务对过周次）后，重新读一次本周并保持选中
    LaunchedEffect(state.loaded, state.loading) {
        val w = currentWeek()
        if (w != autoWeek) autoWeek = w
        if (followNow) week = w
    }

    // 详情弹窗中的课程对象
    val detail = state.courses.firstOrNull { "${it.courseNo}|${it.classSeq}|${it.courseName}" == detailCourse }

    Column(Modifier.fillMaxWidth()) {
        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(start = 20.dp, end = 8.dp, top = 44.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "我的课表",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                val total = state.courses.size
                val credits = state.courses.sumOf { it.credit }
                Text(
                    "本学期 $total 门课程 · ${trimZero(credits)} 学分",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
                // 一卡通余额：点击进一卡通页；获取失败时点击重试
                val balanceText = when {
                    ykt.balanceLoading && ykt.balance == null -> "校园卡余额查询中…"
                    ykt.balance != null -> "校园卡 · ¥${"%.2f".format(ykt.balance!!.balance)}"
                    ykt.balanceError.isNotBlank() -> "余额获取失败 · 点击重试"
                    else -> "校园卡 · 点击查看余额"
                }
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f))
                        .clickable {
                            if (ykt.balanceError.isNotBlank()) vm.loadYktBalance(force = true)
                            else onOpenYkt()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        balanceText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f)
                    )
                }
            }
            IconButton(onClick = { vm.loadSchedule(force = true) }) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.Refresh, "刷新", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        // 周次选择
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (w in 1..ScheduleUtils.MAX_WEEK) {
                FilterChip(
                    selected = week == w,
                    onClick = {
                        week = w
                        followNow = w == autoWeek
                    },
                    label = { Text(if (w == autoWeek) "第${w}周·本周" else "第${w}周") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
            FilterChip(
                selected = false,
                onClick = { showCalibrate = true },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text("校准") }
            )
        }

        when {
            state.loading && state.courses.isEmpty() -> LoadingBox("正在获取课程表…", 300.dp)
            state.error.isNotBlank() && state.courses.isEmpty() ->
                ErrorBox(state.error, { vm.loadSchedule(force = true) }, 300.dp)
            else -> ScheduleContent(
                courses = state.courses,
                week = week,
                today = today,
                isCurrentWeek = isCurrentWeek,
                onCourseClick = { detailCourse = "${it.courseNo}|${it.classSeq}|${it.courseName}" }
            )
        }
    }

    // ---------- 周次校准 ----------
    if (showCalibrate) {
        CalibrateWeekDialog(
            autoWeek = autoWeek,
            pickedWeek = week,
            source = WeekProvider.sourceLabel(),
            onSetPicked = {
                WeekProvider.setManualWeek(week)
                autoWeek = currentWeek()
                followNow = true
                week = autoWeek
                showCalibrate = false
                vm.loadSchedule(force = true)
            },
            onReset = {
                WeekProvider.resetToDefault()
                autoWeek = currentWeek()
                followNow = true
                week = autoWeek
                showCalibrate = false
                vm.loadSchedule(force = true)
            },
            onDismiss = { showCalibrate = false }
        )
    }

    // ---------- 课程详情底部弹窗 ----------
    if (detail != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { detailCourse = null },
            sheetState = sheetState
        ) {
            CourseDetail(detail)
        }
    }
}

@Composable
private fun ScheduleContent(
    courses: List<SelectedCourse>,
    week: Int,
    today: Int,
    isCurrentWeek: Boolean,
    onCourseClick: (SelectedCourse) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // 周课表网格
            SectionCard(title = "第${week}周课表", icon = Icons.Filled.Schedule) {
                if (courses.isEmpty()) {
                    Text(
                        "暂无课程数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    WeekGrid(courses, week, today, isCurrentWeek, onCourseClick)
                }
            }
        }
        item {
            Text(
                "课程明细（点击查看详情）",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
        items(courses, key = { it.courseNo + it.classSeq + it.courseName }) { c ->
            CourseCard(c, week) { onCourseClick(c) }
        }
    }
}

/** 周课表网格：行=节次，列=周一~周日 */
@Composable
private fun WeekGrid(
    courses: List<SelectedCourse>,
    week: Int,
    today: Int,
    isCurrentWeek: Boolean,
    onCourseClick: (SelectedCourse) -> Unit
) {
    // 构建占用图：(day, session) -> course
    val occupancy = HashMap<Pair<Int, Int>, SelectedCourse>()
    courses.forEach { c ->
        c.slots.forEach slot@{ s ->
            if (!slotInWeek(s, week)) return@slot
            val start = if (s.startSession in 1..ScheduleUtils.MAX_SESSION) s.startSession else return@slot
            val count = s.sessionCount.coerceIn(1, ScheduleUtils.MAX_SESSION - start + 1)
            for (k in 0 until count) {
                occupancy[s.day to (start + k)] = c
            }
        }
    }

    val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    Column(Modifier.fillMaxWidth()) {
        // 表头
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                Text(
                    "节次 / 时间",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            dayLabels.forEachIndexed { idx, d ->
                val isToday = isCurrentWeek && today == idx + 1
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "周$d",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            isToday -> MaterialTheme.colorScheme.primary
                            d == "六" || d == "日" -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
        // 行
        for (session in 1..ScheduleUtils.MAX_SESSION) {
            val time = ScheduleUtils.sessionTime(session)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Box(
                    Modifier.width(56.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "第${session}节",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (time != null) {
                            Text(
                                "${time.first}-${time.second}",
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                for (day in 1..7) {
                    val course = occupancy[day to session]
                    val isTodayCol = isCurrentWeek && today == day
                    // 判断是否为某个课程时间段的起始格（无论是否跨节）
                    val isSpanStart = course != null &&
                        course.slots.any slotAny@{ s ->
                            s.day == day && s.startSession == session && slotInWeek(s, week)
                        }
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(1.dp)
                            .background(
                                if (isTodayCol) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                else Color.Transparent
                            )
                    ) {
                        if (course != null && isSpanStart) {
                            CourseBlock(course) { onCourseClick(course) }
                        } else if (course != null) {
                            // 跨节课程的中间格：同色浅色背景
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        colorOf(course.courseName).copy(alpha = 0.16f),
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseBlock(course: SelectedCourse, onClick: () -> Unit) {
    val color = colorOf(course.courseName)
    val room = course.slots.firstOrNull { it.room.isNotBlank() }?.room ?: ""
    Box(
        Modifier
            .fillMaxSize()
            .background(color, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(3.dp)
    ) {
        Column {
            Text(
                course.courseName,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (room.isNotBlank()) {
                Text(
                    "@$room",
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 课程详情底部卡片：教师 / 学分 / 课程性质 / 类别 / 地点 / 周次 / 时间 / 考试方式 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetail(course: SelectedCourse) {
    val slots = course.slots
    val rows = buildList {
        add("教师" to course.teacher.ifBlank { "待定" })
        add("学分" to trimZero(course.credit))
        if (course.category.isNotBlank()) add("课程性质" to course.category)
        if (course.examType.isNotBlank()) add("考试方式" to course.examType)
        if (course.selectStatus.isNotBlank()) add("修课状态" to course.selectStatus)
        if (course.studyMode.isNotBlank()) add("学习模式" to course.studyMode)
        if (course.courseNo.isNotBlank()) {
            add("课程号" to course.courseNo + if (course.classSeq.isNotBlank()) "（课序号 ${course.classSeq}）" else "")
        }
        if (slots.isNotEmpty()) {
            add("周次" to slots.map { it.weekDesc.ifBlank { "每周" } }.distinct().joinToString("；"))
            add("时间" to slots.joinToString("；") { it.timeText })
            add("地点" to slots.map { it.placeText.ifBlank { "待定" } }.distinct().joinToString("；"))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
    ) {
        // 标题行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(5.dp)
                    .height(24.dp)
                    .background(colorOf(course.courseName), RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                course.courseName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagChip("${trimZero(course.credit)} 学分", MaterialTheme.colorScheme.primary)
            if (course.category.isNotBlank()) TagChip(course.category, MaterialTheme.colorScheme.secondary)
            if (course.examType.isNotBlank()) TagChip(course.examType, MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(12.dp))
        rows.forEach { (label, value) ->
            InfoRow(label, value)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "数据来自本科综合教务系统",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun CourseCard(course: SelectedCourse, week: Int, onClick: () -> Unit) {
    val thisWeek = course.slots.filter { slotInWeek(it, week) }
    val showSlots = if (thisWeek.isNotEmpty()) thisWeek else course.slots
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .background(colorOf(course.courseName), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        course.courseName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TagChip("${trimZero(course.credit)}学分")
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Person, null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        course.teacher.ifBlank { "教师待定" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Schedule, null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        showSlots.joinToString("；") { it.timeText },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LocationOn, null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        showSlots.map { it.placeText }.distinct().joinToString("；").ifBlank { "地点待定" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (course.category.isNotBlank() || course.examType.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (course.category.isNotBlank()) TagChip(course.category)
                        if (course.examType.isNotBlank()) TagChip(course.examType, MaterialTheme.colorScheme.secondary)
                        if (course.selectStatus.isNotBlank()) TagChip(course.selectStatus, MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

private fun trimZero(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.CHINA, "%.1f", v)

@Composable
private fun CalibrateWeekDialog(
    autoWeek: Int,
    pickedWeek: Int,
    source: String,
    onSetPicked: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校准教学周次") },
        text = {
            Column {
                Text(
                    "系统当前认定：第 ${autoWeek} 周（$source）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "如果对不上，先在周次条里点到正确的那一周（现在是第 ${pickedWeek} 周），" +
                        "再点下面的按钮把它设为「本周」，之后每周会按周自动 +1。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSetPicked) { Text("第${pickedWeek}周设为本周") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("恢复自动") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
