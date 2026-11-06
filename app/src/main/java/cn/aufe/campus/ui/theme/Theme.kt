package cn.aufe.campus.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import cn.aufe.campus.data.local.AppearanceState

/**
 * 全局主题。
 *
 * v1.8.2 起支持：
 * 1. **12 套主题色**（见 [ThemePalettes]），由 [AppearanceState.themeId] 驱动；
 * 2. **自定义背景图**：设了背景图就把配色切成半透明版本，底层画图片，
 *    各页面的 Scaffold 自然成为一层"毛玻璃"；
 * 3. 深浅色自动跟随系统（没有单独的开关，减少一个用户要理解的概念）。
 *
 * 层级（从下到上）：
 * 纯色兜底 → 背景图（已虚化+压暗） → 内容（半透明 Scaffold / 卡片）
 * 这样即使背景图解码失败，也只是回落到纯色，不会白屏。
 */
@Composable
fun CampusAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = paletteById(AppearanceState.themeId)
    val translucent = AppearanceState.hasBackground
    val colorScheme = if (darkTheme) {
        palette.toDarkScheme(translucent)
    } else {
        palette.toLightScheme(translucent)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            runCatching {
                val window = (view.context as Activity).window
                window.statusBarColor = colorScheme.primary.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CampusTypography
    ) {
        Box(Modifier.fillMaxSize()) {
            // 1) 不透明兜底色：background 在半透明模式下带 alpha，
            //    不能拿它当最底层，否则会透出窗口底色（发黑）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (darkTheme) Color(0xFF0E1013) else Color(0xFFF2F5FA))
            )
            // 2) 背景图（只在用户设置过时绘制）
            BackgroundLayer()
            // 3) 页面内容
            content()
        }
    }
}
