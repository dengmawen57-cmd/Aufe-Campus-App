package cn.aufe.campus.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize

/**
 * 课程表小组件（今日课程 / 一周课表）的背景图设置。
 *
 * ## 和余额小卡片设置分开存
 *
 * 两个小组件观感完全不同（一个是横条卡片、一个是网格），
 * 用户大概率只给课表换图而不动余额卡片，所以各存一套。
 *
 * ## v1.9.5：为什么改成「直接把位图塞进 RemoteViews」
 *
 * v1.9.2 ~ v1.9.4 走的是 `content://` + `ImageProvider(Uri)`：URI 只是字符串，
 * 由桌面进程自己去解。问题在于**解码是异步的**：RemoteViews 应用完，桌面才起
 * 后台线程读图。这张图第一次读的时候才现场生成（解码用户照片 + 裁剪 + 压缩 PNG），
 * 一旦这次异步读被打断（桌面重排、卡片重绘、页面刚 finish 又触发一次 apply），
 * `ImageView` 会安静地保留上一张 —— 而 `ImageView.setImageURI()` 对**相同 URI**
 * 是直接 return 的，于是同一个 URI 永远不会重试。表现就是：改了设置没反应，
 * 再点一次（版本号变了、URI 变了）才有反应。
 *
 * 现在改成在 App 进程里就裁好、切好圆角、压好暗，再通过
 * `RemoteViews.setImageViewBitmap` 直接推给桌面 —— 没有异步解码、没有
 * 中间文件、没有 URI 缓存，一次更新必然生效。（一卡通小卡片一直就是这么做的，
 * 在这台机器上已验证没问题。）
 *
 * 代价是位图要走 Binder，所以 [WidgetBitmap] 的像素预算卡在 11 万像素
 * （ARGB_8888 约 440KB），仍在 RemoteViews 事务上限之内。
 */
object ScheduleWidgetPrefs {

    const val PREFS = "aufe_schedule_widget"

    /** 卡片圆角（dp），必须与 Glance 里的 `cornerRadius()` 保持一致 */
    const val CORNER_DP = 22f

    private const val KEY_BG_URI = "bg_uri"
    private const val KEY_TONE = "tone"
    private const val KEY_SCRIM = "scrim"
    private const val KEY_VERSION = "bg_version"
    private const val KEY_APPLIED_AT = "apply_ok_at"
    private const val KEY_APPLY_ERR_AT = "apply_err_at"
    private const val KEY_APPLY_ERR_MSG = "apply_err_msg"

    const val TONE_AUTO = "auto"
    const val TONE_DARK = "dark"
    const val TONE_LIGHT = "light"

    fun bgUri(context: Context): String? =
        sp(context).getString(KEY_BG_URI, null)?.takeIf { it.isNotBlank() }

    fun setBgUri(context: Context, uri: String?) {
        sp(context).edit().putString(KEY_BG_URI, uri).apply()
        bumpVersion(context)
    }

    fun clearBg(context: Context) {
        sp(context).edit().remove(KEY_BG_URI).apply()
        bumpVersion(context)
    }

    fun hasBackground(context: Context): Boolean = bgUri(context) != null

    fun tone(context: Context): String = sp(context).getString(KEY_TONE, TONE_AUTO) ?: TONE_AUTO

    fun setTone(context: Context, tone: String) {
        sp(context).edit().putString(KEY_TONE, tone).apply()
        bumpVersion(context)
    }

    /** 压暗 0..70（%）：图太花或者太亮时调大，保证字压得住 */
    fun scrim(context: Context): Int = sp(context).getInt(KEY_SCRIM, 20).coerceIn(0, 70)

    fun setScrim(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_SCRIM, v.coerceIn(0, 70)).apply()
        bumpVersion(context)
    }

    /** 每次改设置 +1，用于判断「明暗判断/预览是否过期」 */
    fun version(context: Context): Int = sp(context).getInt(KEY_VERSION, 0)

    fun bumpVersion(context: Context) {
        sp(context).edit().putInt(KEY_VERSION, version(context) + 1).apply()
    }

    // ---------- 「这次到底推上桌面了没有」（v1.9.6） ----------
    //
    // 对齐一卡通余额小卡片的那套做法：刷新失败不再静默。
    // 余额卡片是把失败时间戳写进 WidgetDataStore，卡片上显示「刷新失败 · 点重试」；
    // 课表卡片没有可点的按钮，所以把状态回给设置页，由页面告诉用户「这次没刷上」。

    /** 记一次「成功推到桌面」 */
    fun markApplied(context: Context) {
        sp(context).edit().putLong(KEY_APPLIED_AT, System.currentTimeMillis()).apply()
    }

    fun appliedAt(context: Context): Long = sp(context).getLong(KEY_APPLIED_AT, 0L)

    /** 记一次失败：时间戳 + 原因，设置页会展示 */
    fun markApplyFailed(context: Context, message: String) {
        sp(context).edit()
            .putLong(KEY_APPLY_ERR_AT, System.currentTimeMillis())
            .putString(KEY_APPLY_ERR_MSG, message)
            .apply()
    }

    fun clearApplyError(context: Context) {
        sp(context).edit()
            .remove(KEY_APPLY_ERR_AT)
            .remove(KEY_APPLY_ERR_MSG)
            .apply()
    }

    fun applyErrorAt(context: Context): Long = sp(context).getLong(KEY_APPLY_ERR_AT, 0L)

    /** 最近一次失败比最近一次成功更「新」→ 桌面上的还是旧内容 */
    fun applyFailed(context: Context): Boolean = applyErrorAt(context) > appliedAt(context)

    /** 设置页展示用文案；没有失败时返回空串 */
    fun applyErrorText(context: Context): String {
        if (!applyFailed(context)) return ""
        val msg = sp(context).getString(KEY_APPLY_ERR_MSG, null)?.takeIf { it.isNotBlank() }
        return msg ?: "桌面卡片这次没刷上"
    }

    /** 供配置页预览使用：按目标像素尺寸生成位图（已含圆角与压暗） */
    fun loadBitmap(
        context: Context,
        targetW: Int,
        targetH: Int,
        bakeScrim: Boolean = true
    ): Bitmap? = WidgetBitmap.load(
        context = context,
        uriStr = bgUri(context),
        targetW = targetW,
        targetH = targetH,
        cornerDp = CORNER_DP,
        scrimPercent = if (bakeScrim) scrim(context) else 0
    )

    /**
     * 给小组件用的完整背景位图：位图比例 == 卡片比例，圆角、压暗都已烤好。
     *
     * @param wDp 卡片宽度（dp），见 [cardSizeDp]
     * @param hDp 卡片高度（dp）
     */
    fun renderCard(context: Context, wDp: Int, hDp: Int): Bitmap? {
        if (!hasBackground(context)) return null
        val density = context.resources.displayMetrics.density
        val w = (wDp.coerceAtLeast(1) * density).toInt().coerceAtLeast(1)
        val h = (hDp.coerceAtLeast(1) * density).toInt().coerceAtLeast(1)
        return loadBitmap(context, w, h)
    }

    /** 文字是否用白色：用户指定的优先，否则按图的明暗自动判断 */
    fun shouldUseWhiteText(context: Context, bitmap: Bitmap?): Boolean = when (tone(context)) {
        TONE_LIGHT -> true
        TONE_DARK -> false
        else -> bitmap != null && WidgetBitmap.isDark(bitmap, scrim(context))
    }

    /**
     * 卡片宽高（dp）。Glance 的 `LocalSize` 就是卡片的 dp 尺寸，位图比例要和它一致，
     * 否则会被 `FillBounds` 拉伸、圆角被拉成椭圆。
     *
     * `Dp.Unspecified` 是 NaN，`toInt()` 会变成 0 —— 所以先判 `isSpecified`，
     * 拿不到就用一个 4×2 左右的常见比例兜底（宁可糊一点，也不能 0 像素）。
     */
    fun cardWidthDp(size: DpSize): Int = dpToInt(size.width.value, 250)

    fun cardHeightDp(size: DpSize): Int = dpToInt(size.height.value, 160)

    private fun dpToInt(v: Float, fallback: Int): Int =
        if (v.isNaN() || v <= 0f) fallback else v.toInt().coerceIn(1, 2000)

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
