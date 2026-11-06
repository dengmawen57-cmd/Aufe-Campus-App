package cn.aufe.campus.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 外观设置（主题色 / 背景图 / 图标）。
 *
 * 分两层：
 * - [AppearancePrefs]：只负责读写 SharedPreferences，无状态；
 * - [AppearanceState]：带 Compose 可观察状态的门面，改一个值 UI 立刻重组。
 *
 * 为什么不直接把值塞进 ViewModel：主题与背景要在 `setContent` 最外层生效，
 * 比任何 ViewModel 都早；用单例状态最省事，也不会因为导航重建而闪一下。
 */
object AppearancePrefs {

    const val PREFS = "aufe_appearance"

    const val KEY_THEME = "theme_id"
    const val KEY_BG_URI = "bg_uri"
    const val KEY_BG_BLUR = "bg_blur"
    const val KEY_BG_DIM = "bg_dim"
    const val KEY_ICON = "icon_id"

    const val DEFAULT_THEME = "aufe_blue"
    const val DEFAULT_ICON = "default"

    /** 虚化半径上限（dp）。再高就糊成一团，反而看不到壁纸内容 */
    const val MAX_BLUR = 30

    fun themeId(context: Context): String = sp(context).getString(KEY_THEME, DEFAULT_THEME)
        ?: DEFAULT_THEME

    fun setThemeId(context: Context, id: String) {
        sp(context).edit().putString(KEY_THEME, id).apply()
    }

    fun bgUri(context: Context): String? =
        sp(context).getString(KEY_BG_URI, null)?.takeIf { it.isNotBlank() }

    fun setBgUri(context: Context, uri: String?) {
        sp(context).edit().putString(KEY_BG_URI, uri).apply()
    }

    /** 虚化程度：0..MAX_BLUR（dp） */
    fun bgBlur(context: Context): Int =
        sp(context).getInt(KEY_BG_BLUR, 12).coerceIn(0, MAX_BLUR)

    fun setBgBlur(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_BG_BLUR, v.coerceIn(0, MAX_BLUR)).apply()
    }

    /** 背景暗化：0..70(%)，压住亮色壁纸以免文字看不清 */
    fun bgDim(context: Context): Int = sp(context).getInt(KEY_BG_DIM, 35).coerceIn(0, 70)

    fun setBgDim(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_BG_DIM, v.coerceIn(0, 70)).apply()
    }

    fun iconId(context: Context): String = sp(context).getString(KEY_ICON, DEFAULT_ICON)
        ?: DEFAULT_ICON

    fun setIconId(context: Context, id: String) {
        sp(context).edit().putString(KEY_ICON, id).apply()
    }

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Compose 可观察的外观状态。
 *
 * 使用方式：`MainActivity.onCreate` 里调 [load]，然后在 composable 里
 * 读 `AppearanceState.themeId` 等字段，改动会自动重组整个界面。
 */
object AppearanceState {

    var themeId by mutableStateOf(AppearancePrefs.DEFAULT_THEME)
        private set

    var bgUri by mutableStateOf<String?>(null)
        private set

    var bgBlur by mutableIntStateOf(12)
        private set

    var bgDim by mutableIntStateOf(35)
        private set

    var iconId by mutableStateOf(AppearancePrefs.DEFAULT_ICON)
        private set

    /** 是否设置了背景图（各页面据此决定要不要走半透明配色） */
    val hasBackground: Boolean get() = !bgUri.isNullOrBlank()

    fun load(context: Context) {
        themeId = AppearancePrefs.themeId(context)
        bgUri = AppearancePrefs.bgUri(context)
        bgBlur = AppearancePrefs.bgBlur(context)
        bgDim = AppearancePrefs.bgDim(context)
        iconId = AppearancePrefs.iconId(context)
    }

    fun setTheme(context: Context, id: String) {
        AppearancePrefs.setThemeId(context, id)
        themeId = id
    }

    fun setBgUri(context: Context, uri: String?) {
        AppearancePrefs.setBgUri(context, uri)
        bgUri = uri
    }

    fun setBgBlur(context: Context, v: Int) {
        AppearancePrefs.setBgBlur(context, v)
        bgBlur = v
    }

    fun setBgDim(context: Context, v: Int) {
        AppearancePrefs.setBgDim(context, v)
        bgDim = v
    }

    fun setIcon(context: Context, id: String) {
        AppearancePrefs.setIconId(context, id)
        iconId = id
    }
}

/**
 * 背景图 Uri 相关的工具。
 *
 * 选图用的是系统图片选择器（不需要存储权限），但返回的 Uri 默认只有
 * 本次进程有效的临时授权——重启就失效。所以选完必须
 * `takePersistableUriPermission`，否则用户第二天打开应用背景就白了。
 */
object BackgroundImageUtil {

    /** 选择一个背景图后调用：申请长期读权限 */
    fun persistPermission(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        true
    }.getOrDefault(false)

    /** 读取文件名，用于设置页展示"当前背景：xxx.jpg" */
    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment.orEmpty()
    }.getOrDefault("背景图")

    /** 清掉权限（换图/取消背景时调用，避免授权越积越多） */
    fun releasePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** 当前进程是否还能读到这张图（用户可能已把文件删了） */
    fun canRead(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    fun parse(uri: String?): Uri? = runCatching { uri?.let(Uri::parse) }.getOrNull()
}
