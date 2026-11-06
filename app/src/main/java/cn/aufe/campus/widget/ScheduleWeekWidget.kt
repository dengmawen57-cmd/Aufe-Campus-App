package cn.aufe.campus.widget

import android.content.Context
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

// 配色同今日卡片：没背景图走原主题，铺了背景图整体翻白（详见 ScheduleDayWidget 的注释）

private val DefaultOnSurface = ColorProvider(Color(0xFF1A1C1E), Color(0xFFE2E2E6))
private val DefaultOnSurfaceVariant = ColorProvider(Color(0xFF44474E), Color(0xFFC4C6D0))
private val DefaultSurface = ColorProvider(Color(0xFFFDFDFF), Color(0xFF15181C))
private val DefaultPrimary = ColorProvider(Color(0xFF1A5FB4), Color(0xFFA8C8F0))
private val DefaultPrimaryContainer = ColorProvider(Color(0xFFD6E4F7), Color(0xFF1B3F66))
private val DefaultOnPrimaryContainer = ColorProvider(Color(0xFF0F3D73), Color(0xFFD6E4F7))
private val DefaultOutline = ColorProvider(Color(0x14000000), Color(0x1FFFFFFF))
private val DefaultDanger = ColorProvider(Color(0xFFBA1A1A), Color(0xFFFFB4AB))

private val OverImageOnSurface = ColorProvider(Color.White, Color.White)
private val OverImageOnSurfaceVariant = ColorProvider(Color(0xE6FFFFFF), Color(0xE6FFFFFF))
private val OverImageSurface = ColorProvider(Color(0x00000000), Color(0x00000000))
private val OverImagePrimary = ColorProvider(Color.White, Color.White)
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

/** 今日整列的淡色底：有背景图时改成半透明白，否则会把照片糊成一块 */
private val TodayColumnBg: androidx.glance.unit.ColorProvider
    @Composable get() = if (LocalOverImage.current) {
        ColorProvider(Color(0x1AFFFFFF), Color(0x1AFFFFFF))
    } else {
        ColorProvider(Color(0x0C1A5FB4), Color(0x14A8C8F0))
    }

private val WEEKDAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")

private val ROW_HEIGHT = 34.dp
private val TIME_COL_WIDTH = 20.dp
private val HEADER_HEIGHT = 18.dp

/** 卡片圆角，与 ScheduleWidgetPrefs.CORNER_DP 对齐 */
private val WIDGET_CORNER = ScheduleWidgetPrefs.CORNER_DP.dp

/**
 * 一周课表小组件（对齐 LoveACE 的 SemesterWeekWidget）。
 *
 * 版面：周次头部 → 星期表头（今天高亮）→ 7 列 × 节次网格（可滚动）
 * 同一节课跨多节时合并为一个色块（rowSpan），今天整列铺淡色底。
 */
class ScheduleWeekWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = try {
            fetchWidgetState(context)
        } catch (_: Throwable) {
            WidgetState.Error
        }
        val dark = isNightMode(context)
        provideContent { WeekWidgetContent(state, dark) }
    }
}

@Composable
private fun WeekWidgetContent(state: WidgetState, dark: Boolean) {
    val colors = widgetColors(dark)
    val context = LocalContext.current
    val size = LocalSize.current

    // 同今日卡片：位图必须在组合里取，provideGlance 每次更新不会重跑
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
                is WidgetState.InSession -> WeekGridContent(
                    week = state.currentWeek,
                    totalWeeks = state.totalWeeks,
                    today = state.dayOfWeek,
                    dateText = state.dateText,
                    weekDays = state.weekDays,
                    isEnding = state.isEnding,
                    weekCourses = state.weekCourses,
                    colors = colors
                )

                is WidgetState.NoCourseToday -> WeekGridContent(
                    week = state.currentWeek,
                    totalWeeks = state.totalWeeks,
                    today = state.dayOfWeek,
                    dateText = state.dateText,
                    weekDays = state.weekDays,
                    isEnding = state.isEnding,
                    weekCourses = state.weekCourses,
                    colors = colors
                )

                is WidgetState.BeforeTerm -> PlaceholderContent(
                    "🏖️",
                    "假期中",
                    if (state.daysUntilStart > 0) "${state.daysUntilStart} 天后开学" else "即将开学"
                )

                WidgetState.NoCache -> PlaceholderContent("📚", "打开 App 同步课表", "点一下进入课表页")
                WidgetState.Error -> PlaceholderContent("⚠️", "课表加载失败", "点一下打开 App")
            }
        }
    }
}

@Composable
private fun PlaceholderContent(icon: String, title: String, subtitle: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, style = TextStyle(fontSize = 30.sp))
        Spacer(GlanceModifier.height(8.dp))
        Text(title, style = TextStyle(color = OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(4.dp))
        Text(subtitle, style = TextStyle(color = OnSurfaceVariant, fontSize = 11.sp))
    }
}

// ==================== 网格 ====================

@Composable
private fun WeekGridContent(
    week: Int,
    totalWeeks: Int,
    today: Int,
    dateText: String,
    weekDays: List<WeekDayInfo>,
    isEnding: Boolean,
    weekCourses: Map<Int, List<WidgetCourseEntry>>,
    colors: List<WidgetCourseColor>
) {
    val grid = buildWidgetGrid(weekCourses)
    val maxSession = widgetGridMaxSession(grid)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // 头部
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "第${week}周",
                style = TextStyle(color = OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(dateText, style = TextStyle(color = OnSurfaceVariant, fontSize = 10.sp))
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "共${totalWeeks}周",
                style = TextStyle(color = OnSurfaceVariant, fontSize = 9.sp)
            )
        }

        // 星期表头
        Row(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Box(
                modifier = GlanceModifier.width(TIME_COL_WIDTH).height(HEADER_HEIGHT),
                contentAlignment = Alignment.Center
            ) {}
            weekDays.forEachIndexed { idx, info ->
                Box(
                    modifier = GlanceModifier.defaultWeight().height(HEADER_HEIGHT),
                    contentAlignment = Alignment.Center
                ) {
                    if (idx + 1 == today) {
                        Box(
                            modifier = GlanceModifier
                                .width(17.dp).height(16.dp)
                                .cornerRadius(8.dp)
                                .background(PrimaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                WEEKDAY_LABELS[idx],
                                style = TextStyle(
                                    color = OnPrimaryContainer,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    } else {
                        Text(
                            WEEKDAY_LABELS[idx],
                            style = TextStyle(
                                color = if (info.isPast) OnSurfaceVariant else OnSurface,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
        }

        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Outline)) {}

        // 网格主体：LazyColumn 内单行 Row，保证超出高度时可滚动
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(horizontal = 2.dp)) {
            item(itemId = 0L) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    // 节次列
                    Column(modifier = GlanceModifier.width(TIME_COL_WIDTH)) {
                        for (s in 1..maxSession) {
                            Box(
                                modifier = GlanceModifier.height(ROW_HEIGHT).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$s",
                                    style = TextStyle(
                                        color = OnSurfaceVariant,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }

                    // 7 天列
                    for (day in 1..7) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            var session = 1
                            while (session <= maxSession) {
                                val cell = grid["$day-$session"]
                                if (cell != null && cell.entry.sessionStart == session) {
                                    CourseCell(
                                        cell = cell,
                                        colors = colors,
                                        modifier = GlanceModifier
                                            .fillMaxWidth()
                                            .height(ROW_HEIGHT * cell.rowSpan)
                                            .padding(1.dp)
                                    )
                                    session += cell.rowSpan
                                } else if (cell != null) {
                                    session++
                                } else {
                                    if (day == today) {
                                        Box(
                                            modifier = GlanceModifier
                                                .fillMaxWidth()
                                                .height(ROW_HEIGHT)
                                                .padding(1.dp)
                                                .cornerRadius(3.dp)
                                                .background(TodayColumnBg)
                                        ) {}
                                    } else {
                                        Spacer(GlanceModifier.fillMaxWidth().height(ROW_HEIGHT))
                                    }
                                    session++
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isEnding) {
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
    }
}

private fun truncateName(name: String, maxLen: Int = 5): String =
    if (name.length > maxLen) name.take(maxLen) + "…" else name

@Composable
private fun CourseCell(
    cell: WidgetGridCell,
    colors: List<WidgetCourseColor>,
    modifier: GlanceModifier
) {
    val palette = colors[cell.entry.colorIndex % colors.size]
    val bg = ColorProvider(palette.bg, palette.bg)
    val bar = ColorProvider(palette.bar, palette.bar)
    val text = ColorProvider(palette.text, palette.text)

    Box(
        modifier = modifier
            .cornerRadius(5.dp)
            .background(bg)
            .padding(start = 3.dp, top = 2.dp, end = 2.dp, bottom = 2.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            Box(
                modifier = GlanceModifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .cornerRadius(1.dp)
                    .background(bar)
            ) {}
            Spacer(GlanceModifier.width(3.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    truncateName(cell.entry.courseName),
                    style = TextStyle(color = text, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                    maxLines = if (cell.rowSpan >= 2) 2 else 1
                )
                if (cell.rowSpan >= 2 && cell.entry.location.isNotBlank()) {
                    Text(
                        cell.entry.location,
                        style = TextStyle(color = text, fontSize = 7.sp),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/** 小组件宿主 */
class ScheduleWeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleWeekWidget()
}
