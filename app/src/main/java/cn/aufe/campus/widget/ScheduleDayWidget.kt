package cn.aufe.campus.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.lazy.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.color.ColorProvider
import cn.aufe.campus.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ==================== 配色 ====================
//
// 两套值：没背景图时按原来的浅/深色主题走；一旦用户铺了背景照片，
// 文字必须整体翻成白色，否则深灰字压在照片上完全看不清。
//
// 用 CompositionLocal 而不是给每个 composable 加参数：颜色常量被
// 十几个嵌套函数引用（HeaderBar / WeekdayIndicatorRow / CourseCard …），
// 一路透传既啰嗦又容易漏。取值集中在 @Composable getter 里，只在
// 根节点切换一次开关。

private val DefaultOnSurface = ColorProvider(Color(0xFF1A1C1E), Color(0xFFE2E2E6))
private val DefaultOnSurfaceVariant = ColorProvider(Color(0xFF44474E), Color(0xFFC4C6D0))
private val DefaultSurface = ColorProvider(Color(0xFFFDFDFF), Color(0xFF15181C))
private val DefaultPrimary = ColorProvider(Color(0xFF1A5FB4), Color(0xFFA8C8F0))
private val DefaultPrimaryContainer = ColorProvider(Color(0xFFD6E4F7), Color(0xFF1B3F66))
private val DefaultOnPrimaryContainer = ColorProvider(Color(0xFF0F3D73), Color(0xFFD6E4F7))
private val DefaultOutline = ColorProvider(Color(0x14000000), Color(0x1FFFFFFF))
private val DefaultDanger = ColorProvider(Color(0xFFBA1A1A), Color(0xFFFFB4AB))

/** 有背景图时统一用的白字 / 半透明白 */
private val OverImageOnSurface = ColorProvider(Color.White, Color.White)
private val OverImageOnSurfaceVariant = ColorProvider(Color(0xE6FFFFFF), Color(0xE6FFFFFF))
private val OverImageSurface = ColorProvider(Color(0x00000000), Color(0x00000000))
private val OverImagePrimary = ColorProvider(Color(0xFFFFFFFF), Color(0xFFFFFFFF))
private val OverImagePrimaryContainer = ColorProvider(Color(0x33FFFFFF), Color(0x33FFFFFF))
private val OverImageOnPrimaryContainer = ColorProvider(Color.White, Color.White)
private val OverImageOutline = ColorProvider(Color(0x33FFFFFF), Color(0x33FFFFFF))
private val OverImageDanger = ColorProvider(Color(0xFFFFC9C4), Color(0xFFFFC9C4))

private val LocalOverImage = staticCompositionLocalOf { false }

/** 文字是否用白色：换图时按明暗自动定，也可以在设置里强制成深色字 */
private val LocalWhiteText = staticCompositionLocalOf { false }

/** 「有图但强制深色字」用：不能跟随系统深浅色，否则暗色模式下字会变白 */
private val OverImageForceDark = ColorProvider(Color(0xFF1A1C1E), Color(0xFF1A1C1E))
private val OverImageForceDarkVariant = ColorProvider(Color(0xFF3C4046), Color(0xFF3C4046))
private val OverImageForceDarkPrimary = ColorProvider(Color(0xFF1A5FB4), Color(0xFF1A5FB4))

private val OnSurface: androidx.glance.unit.ColorProvider
    @Composable get() = when {
        LocalWhiteText.current -> OverImageOnSurface
        LocalOverImage.current -> OverImageForceDark
        else -> DefaultOnSurface
    }

private val OnSurfaceVariant: androidx.glance.unit.ColorProvider
    @Composable get() = when {
        LocalWhiteText.current -> OverImageOnSurfaceVariant
        LocalOverImage.current -> OverImageForceDarkVariant
        else -> DefaultOnSurfaceVariant
    }

private val Surface: androidx.glance.unit.ColorProvider
    @Composable get() = if (LocalOverImage.current) OverImageSurface else DefaultSurface

private val Primary: androidx.glance.unit.ColorProvider
    @Composable get() = when {
        LocalWhiteText.current -> OverImagePrimary
        LocalOverImage.current -> OverImageForceDarkPrimary
        else -> DefaultPrimary
    }

private val PrimaryContainer: androidx.glance.unit.ColorProvider
    @Composable get() = if (LocalOverImage.current) OverImagePrimaryContainer else DefaultPrimaryContainer

private val OnPrimaryContainer: androidx.glance.unit.ColorProvider
    @Composable get() = when {
        LocalWhiteText.current -> OverImageOnPrimaryContainer
        LocalOverImage.current -> OverImageForceDarkPrimary
        else -> DefaultOnPrimaryContainer
    }

private val Outline: androidx.glance.unit.ColorProvider
    @Composable get() = if (LocalOverImage.current) OverImageOutline else DefaultOutline

private val Danger: androidx.glance.unit.ColorProvider
    @Composable get() = if (LocalOverImage.current) OverImageDanger else DefaultDanger

/** 卡片圆角：和 [ScheduleWidgetPrefs.CORNER_DP] 必须一致，位图上的圆角才对得齐 */
private val WIDGET_CORNER = ScheduleWidgetPrefs.CORNER_DP.dp

private val WEEKDAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 今日课程小组件（对齐 LoveACE 的 SemesterDayWidget）。
 *
 * 版面：周次/日期头部 → 星期指示行 → 课程卡片列表（可滚动）→ 学期将尽提示
 * 状态：学期中 / 今日无课（显示下一节课）/ 未开学 / 无缓存 / 加载失败
 */
class ScheduleDayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = try {
            fetchWidgetState(context)
        } catch (_: Throwable) {
            WidgetState.Error
        }
        val dark = isNightMode(context)
        provideContent { DayWidgetContent(state, dark) }
    }
}

@Composable
private fun DayWidgetContent(state: WidgetState, dark: Boolean) {
    val colors = widgetColors(dark)
    val context = LocalContext.current
    val size = LocalSize.current

    // 背景位图在这里裁好、切圆角、压暗，随 RemoteViews 一起推给桌面。
    //
    // 两个要点：
    // 1. 必须在**组合里**取，不能放在 provideGlance —— 一次更新只是让内容 lambda
    //    重新组合，provideGlance 不会重跑，位图会一直停在第一次的那张。
    // 2. 不再用 content:// 让桌面自己去读：那条路的解码是异步的，一次读失败
    //    ImageView 就安静地留着上一张（详见 ScheduleWidgetPrefs 顶部）。
    val bg = if (ScheduleWidgetPrefs.hasBackground(context)) {
        runCatching { ScheduleWidgetPrefs.renderCard(
                context,
                ScheduleWidgetPrefs.cardWidthDp(size),
                ScheduleWidgetPrefs.cardHeightDp(size)
            ) }
            .getOrNull()
    } else {
        null
    }
    val whiteText = ScheduleWidgetPrefs.shouldUseWhiteText(context, bg)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(WIDGET_CORNER)
            .background(Surface)
            .appWidgetBackground()
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (bg != null) {
            // 圆角、压暗、比例都已经烤进位图里（见 WidgetBitmap），这里只负责铺满。
            // 必须是 FillBounds：Crop 会把位图四条边裁掉，正好裁在圆角上。
            Image(
                provider = ImageProvider(bg),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize()
            )
        }
        CompositionLocalProvider(
            LocalOverImage provides (bg != null),
            LocalWhiteText provides whiteText
        ) {
            when (state) {
                is WidgetState.InSession -> TodayContent(state, colors)
                is WidgetState.NoCourseToday -> NoCourseContent(state, colors)
                is WidgetState.BeforeTerm -> BeforeTermContent(state)
                WidgetState.NoCache -> PlaceholderContent("📚", "打开 App 同步课表", "点一下进入课表页")
                WidgetState.Error -> PlaceholderContent("⚠️", "课表加载失败", "点一下打开 App")
            }
        }
    }
}

// ==================== 有课 ====================

@Composable
private fun TodayContent(state: WidgetState.InSession, colors: List<WidgetCourseColor>) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        HeaderBar(
            week = state.currentWeek,
            dayText = state.dayOfWeekText,
            dateText = state.dateText,
            courseCount = state.todayCourses.size
        )
        WeekdayIndicatorRow(state.weekDays, state.dayOfWeek)
        Box(GlanceModifier.fillMaxWidth().height(1.dp).padding(horizontal = 10.dp).background(Outline)) {}

        LazyColumn(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            items(state.todayCourses, itemId = { c ->
                // 同一节次可能撞两门课，带上课程名哈希保证 id 唯一
                c.sessionStart * 4096L + c.sessionEnd + (c.courseName.hashCode().toLong() and 0xFFF)
            }) { course ->
                CourseCard(course, colors)
            }
        }

        if (state.isEnding) {
            EndingFooter(state.currentWeek, state.totalWeeks)
        }
    }
}

// ==================== 今日无课 ====================

@Composable
private fun NoCourseContent(state: WidgetState.NoCourseToday, colors: List<WidgetCourseColor>) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        HeaderBar(
            week = state.currentWeek,
            dayText = state.dayOfWeekText,
            dateText = state.dateText,
            courseCount = 0
        )
        WeekdayIndicatorRow(state.weekDays, state.dayOfWeek)
        Box(GlanceModifier.fillMaxWidth().height(1.dp).padding(horizontal = 10.dp).background(Outline)) {}

        Column(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (state.termOver) "🎉" else "😴",
                style = TextStyle(fontSize = 30.sp)
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                if (state.termOver) "本学期课程已结束" else "今日无课",
                style = TextStyle(color = OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )

            val next = state.nextCourse
            if (next != null) {
                Spacer(GlanceModifier.height(10.dp))
                Text(
                    "下一节课 · ${state.nextCourseDay ?: ""}",
                    style = TextStyle(color = OnSurfaceVariant, fontSize = 10.sp)
                )
                Spacer(GlanceModifier.height(4.dp))
                NextCourseCard(next, colors)
            }
        }

        if (state.isEnding && !state.termOver) {
            EndingFooter(state.currentWeek, state.totalWeeks)
        }
    }
}

// ==================== 未开学 / 占位 ====================

@Composable
private fun BeforeTermContent(state: WidgetState.BeforeTerm) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🏖️", style = TextStyle(fontSize = 32.sp))
        Spacer(GlanceModifier.height(8.dp))
        Text("假期中", style = TextStyle(color = OnSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(4.dp))
        Text(
            if (state.daysUntilStart > 0) "${state.daysUntilStart} 天后开学" else "即将开学",
            style = TextStyle(color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            "开学日 ${state.startDateText}",
            style = TextStyle(color = OnSurfaceVariant, fontSize = 10.sp)
        )
    }
}

@Composable
private fun PlaceholderContent(icon: String, title: String, subtitle: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, style = TextStyle(fontSize = 28.sp))
        Spacer(GlanceModifier.height(8.dp))
        Text(title, style = TextStyle(color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        if (subtitle.isNotEmpty()) {
            Spacer(GlanceModifier.height(4.dp))
            Text(subtitle, style = TextStyle(color = OnSurfaceVariant, fontSize = 11.sp))
        }
    }
}

// ==================== 通用组件 ====================

@Composable
private fun HeaderBar(week: Int, dayText: String, dateText: String, courseCount: Int) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "第${week}周",
            style = TextStyle(color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            dayText,
            style = TextStyle(color = OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(dateText, style = TextStyle(color = OnSurfaceVariant, fontSize = 11.sp))
        Spacer(GlanceModifier.defaultWeight())
        if (courseCount > 0) {
            Text(
                "${courseCount}节课",
                style = TextStyle(color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            )
        }
    }
}

@Composable
private fun WeekdayIndicatorRow(weekDays: List<WeekDayInfo>, today: Int) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        weekDays.forEachIndexed { idx, info ->
            Box(
                modifier = GlanceModifier.defaultWeight().height(22.dp),
                contentAlignment = Alignment.Center
            ) {
                if (idx + 1 == today) {
                    Box(
                        modifier = GlanceModifier
                            .width(22.dp).height(22.dp)
                            .cornerRadius(11.dp)
                            .background(PrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            WEEKDAY_LABELS[idx],
                            style = TextStyle(
                                color = OnPrimaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                } else {
                    Text(
                        WEEKDAY_LABELS[idx],
                        style = TextStyle(
                            color = if (info.isPast) OnSurfaceVariant else OnSurface,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseCard(course: WidgetCourseEntry, colors: List<WidgetCourseColor>) {
    val palette = colors[course.colorIndex % colors.size]
    val bg = ColorProvider(palette.bg, palette.bg)
    val bar = ColorProvider(palette.bar, palette.bar)
    val accentText = ColorProvider(palette.text, palette.text)
    val nameColor = if (course.finished) OnSurfaceVariant else OnSurface

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .cornerRadius(10.dp)
            .background(bg)
            .padding(start = 4.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(34.dp)
                .cornerRadius(2.dp)
                .background(bar)
        ) {}
        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.width(56.dp)) {
            Text(
                course.sessionText,
                style = TextStyle(color = accentText, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            Text(
                course.timeText,
                style = TextStyle(color = OnSurfaceVariant, fontSize = 9.sp),
                maxLines = 1
            )
        }

        Spacer(GlanceModifier.width(6.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                course.courseName,
                style = TextStyle(color = nameColor, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            val sub = listOf(course.location, course.teacherName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = TextStyle(color = OnSurfaceVariant, fontSize = 10.sp),
                    maxLines = 1
                )
            }
        }

        if (course.ongoing) {
            Spacer(GlanceModifier.width(4.dp))
            Text(
                "上课中",
                style = TextStyle(color = Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            )
        } else if (course.finished) {
            Spacer(GlanceModifier.width(4.dp))
            Text(
                "已下课",
                style = TextStyle(color = OnSurfaceVariant, fontSize = 9.sp)
            )
        }
    }
}

@Composable
private fun NextCourseCard(course: WidgetCourseEntry, colors: List<WidgetCourseColor>) {
    val palette = colors[course.colorIndex % colors.size]
    val bg = ColorProvider(palette.bg, palette.bg)
    val bar = ColorProvider(palette.bar, palette.bar)

    Row(
        modifier = GlanceModifier
            .cornerRadius(10.dp)
            .background(bg)
            .padding(start = 4.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(30.dp)
                .cornerRadius(2.dp)
                .background(bar)
        ) {}
        Spacer(GlanceModifier.width(8.dp))
        Column {
            Text(
                course.courseName,
                style = TextStyle(color = OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            val detail = buildString {
                append(course.sessionText)
                if (course.location.isNotBlank()) append(" · ").append(course.location)
            }
            Text(
                detail,
                style = TextStyle(color = OnSurfaceVariant, fontSize = 10.sp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun EndingFooter(week: Int, totalWeeks: Int) {
    Box(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "第${week}/${totalWeeks}周 · 学期即将结束",
            style = TextStyle(color = Danger, fontSize = 9.sp)
        )
    }
}

/** 小组件宿主 */
class ScheduleDayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleDayWidget()
}
