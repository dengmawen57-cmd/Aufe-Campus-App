package cn.aufe.campus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 主题配色表。
 *
 * 设计取舍：不引入 material-color-utilities（那会给包体加依赖，且本工程是
 * 离线构建）。改为**每套主题手写 4 个关键色**，其余由它们派生：
 *
 * - `primary`            主色（顶栏、强调文字、进度条）
 * - `primaryContainer`   主色浅底（标签、选中态底色）
 * - `secondary`          辅助色（少量点缀，与主色同色系但偏冷/偏暖）
 * - `background`/`surface` 保持中性灰白，保证 12 套主题下文字对比度都够
 *
 * 深色模式不单独手写：用 [lerp] 从主色推导亮/暗版本，色相保持一致。
 * 这样一个主题只维护 4 个色值，改色不会漏掉深色模式的某个槽位。
 */
data class ThemePalette(
    val id: String,
    val name: String,
    /** 给人看的一句话描述，设置页会显示 */
    val desc: String,
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color
)

private fun Color.lighten(amount: Float): Color = lerp(this, Color.White, amount)
private fun Color.darken(amount: Float): Color = lerp(this, Color.Black, amount)

/** 12 套主题：前 3 套是学校向的稳重色，中间 5 套偏年轻，后 4 套偏柔和/个性 */
val ThemePalettes: List<ThemePalette> = listOf(
    ThemePalette(
        "aufe_blue", "安财蓝", "校徽同色系，最稳的一张脸",
        Color(0xFF1F4E9C), Color(0xFFD6E3F8), Color(0xFF2E8B87)
    ),
    ThemePalette(
        "celadon", "青瓷", "青绿如玉，长时间看最不累眼",
        Color(0xFF1B6B5A), Color(0xFFCDE9E1), Color(0xFF3B7F6E)
    ),
    ThemePalette(
        "dusk", "暮山紫", "黄昏天色的紫，安静但不沉闷",
        Color(0xFF5B4B9E), Color(0xFFE1DCF5), Color(0xFF8A6FB8)
    ),
    ThemePalette(
        "coral", "珊瑚橙", "暖调高饱和，阴天里也提神",
        Color(0xFFB34A26), Color(0xFFFBDDCF), Color(0xFFC9713F)
    ),
    ThemePalette(
        "rose", "玫瑰粉", "玫红偏冷，不俗气的那种粉",
        Color(0xFFA63B62), Color(0xFFF8D6E2), Color(0xFFC86C8E)
    ),
    ThemePalette(
        "indigo", "深海靛", "比安财蓝更深一档，夜里更耐看",
        Color(0xFF263A6B), Color(0xFFD3DAEB), Color(0xFF4A6DA8)
    ),
    ThemePalette(
        "pine", "松石墨", "接近墨绿的灰调，极简风",
        Color(0xFF2E3A38), Color(0xFFDCE3E1), Color(0xFF54635F)
    ),
    ThemePalette(
        "amber", "琥珀金", "暖金偏棕，秋冬季很搭",
        Color(0xFF8A5A12), Color(0xFFF7E4C2), Color(0xFFB58436)
    ),
    ThemePalette(
        "aurora", "极光青", "青蓝过渡，像极光下半段",
        Color(0xFF0F6B7D), Color(0xFFCCEAF0), Color(0xFF2E9AAA)
    ),
    ThemePalette(
        "dawn", "晨曦橙", "浅橙配奶油白，最温柔的一套",
        Color(0xFFC2562E), Color(0xFFFBE0D2), Color(0xFFE08A63)
    ),
    ThemePalette(
        "sakura", "樱花奶油", "粉白调，配樱花壁纸最佳",
        Color(0xFFB8537A), Color(0xFFFADDE8), Color(0xFFD98CAC)
    ),
    ThemePalette(
        "midnight", "午夜蓝紫", "深蓝转紫，深色模式下的首选",
        Color(0xFF4A5FC1), Color(0xFFDDE2FA), Color(0xFF7B8CE8)
    )
)

fun paletteById(id: String?): ThemePalette =
    ThemePalettes.firstOrNull { it.id == id } ?: ThemePalettes[0]

/**
 * 由配色生成 Light ColorScheme。
 *
 * [translucent] 为 true 时（用户设置了背景图），把 background / surface
 * 换成半透明，让底层背景图透出来——这是"毛玻璃"效果的关键，
 * 各页面不用改代码，Scaffold 画半透明底色即可。
 *
 * ## v1.9.1 修复「设了背景图后，功能按钮上有一层发白的方块，但不是圆角」
 *
 * 原来 `background = 0x94FFFFFF`（58% 白）：这是一层**满屏的、直角的**白膜。
 * 卡片自己是圆角（14dp），但它底下垫着一整块方形的白膜，
 * 视觉上就变成「每个按钮上糊了一层比较深的白、而且没有圆角」。
 *
 * 现在把满屏白膜降到 12%，卡片反而提到 88%：
 * 背景图基本原样透出，卡片变成一块块**边界清晰的圆角毛玻璃**，
 * 方形白膜几乎看不见，圆角也就显出来了。
 */
fun ThemePalette.toLightScheme(translucent: Boolean = false): ColorScheme {
    // 满屏底色：半透明模式下只留极淡的一层，避免"白膜盖图"
    val bg = if (translucent) Color(0x1FFFFFFF) else Color(0xFFF6F8FC)
    // 卡片：半透明模式下更白更实，才能和背景图拉开层次、让圆角轮廓清楚
    val sf = if (translucent) Color(0xE6FFFFFF) else Color(0xFFFFFFFF)
    val sfv = if (translucent) Color(0xCCE8EEF8) else Color(0xFFE1E9F5)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = primary.darken(0.55f),
        secondary = secondary,
        onSecondary = Color.White,
        secondaryContainer = secondary.lighten(0.72f),
        onSecondaryContainer = secondary.darken(0.5f),
        tertiary = primary.lighten(0.18f),
        background = bg,
        onBackground = Color(0xFF1A1C1E),
        surface = sf,
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = sfv,
        onSurfaceVariant = Color(0xFF44474E),
        outline = Color(0xFFC3C9D4),
        outlineVariant = Color(0xFFDFE3EA),
        error = Color(0xFFB3261E),
        onError = Color.White
    )
}

fun ThemePalette.toDarkScheme(translucent: Boolean = false): ColorScheme {
    // 深色同理：满屏底色不压死背景图，卡片加深拉开层次
    val bg = if (translucent) Color(0x66000000) else Color(0xFF121417)
    val sf = if (translucent) Color(0xE61C1F23) else Color(0xFF1C1F23)
    val sfv = if (translucent) Color(0xD942474E) else Color(0xFF42474E)
    return darkColorScheme(
        primary = primary.lighten(0.42f),
        onPrimary = primary.darken(0.72f),
        primaryContainer = primary.darken(0.42f),
        onPrimaryContainer = primary.lighten(0.78f),
        secondary = secondary.lighten(0.4f),
        onSecondary = secondary.darken(0.7f),
        secondaryContainer = secondary.darken(0.45f),
        onSecondaryContainer = secondary.lighten(0.75f),
        tertiary = primary.lighten(0.55f),
        background = bg,
        onBackground = Color(0xFFE3E6EA),
        surface = sf,
        onSurface = Color(0xFFE3E6EA),
        surfaceVariant = sfv,
        onSurfaceVariant = Color(0xFFC3C7CE),
        outline = Color(0xFF8C9199),
        outlineVariant = Color(0xFF42474E),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005)
    )
}
