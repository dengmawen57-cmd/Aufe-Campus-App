package cn.aufe.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri

/**
 * 一卡通余额小卡片的个性化设置（自定义背景图 / 文字深浅 / 压暗程度）。
 *
 * ## 为什么要单独一份设置
 *
 * 主界面的背景图（[cn.aufe.campus.data.local.AppearancePrefs]）是给 App 内用的，
 * 走的是 Compose 里的实时模糊；桌面小卡片是另一个进程（launcher）渲染的
 * RemoteViews，既没法用 Compose，也没法用实时模糊，只能塞一张**已缩放好**的
 * 位图进去。两者需求不同，所以设置分开存。
 *
 * ## 位图为什么要压到这么小
 *
 * `RemoteViews.setImageViewBitmap` 是把位图**写进 Binder 事务**传给 launcher 的，
 * 单个 widget 的事务上限约 1MB。一张 1080p 的图直接塞进去必崩
 * （TransactionTooLargeException）。所以这里统一按长边 [MAX_EDGE] 缩放、
 * 用 RGB_565（2 字节/像素）解码，实际载荷控制在 300KB 以内。
 */
object YktWidgetPrefs {

    const val PREFS = "aufe_ykt_widget"

    private const val KEY_BG_URI = "bg_uri"
    private const val KEY_TONE = "text_tone"
    private const val KEY_SCRIM = "scrim"

    /** 文字色调：auto 跟随背景明暗，dark 深色字，light 浅色字 */
    const val TONE_AUTO = "auto"
    const val TONE_DARK = "dark"
    const val TONE_LIGHT = "light"

    /** 背景图长边上限（像素），配合 [MAX_PIXELS] 一起卡 Binder 事务体积 */
    private const val MAX_EDGE = 512

    /**
     * 背景图像素总数上限。
     *
     * 一张位图进 RemoteViews 是按**原始像素**走 Binder 的（RGB_565 每像素 2 字节，
     * ARGB_8888 每像素 4 字节），不压缩。取 10 万像素 → ARGB 约 400KB，
     * 加上其它控件数据仍远低于 1MB 上限。
     */
    private const val MAX_PIXELS = 100_000

    fun bgUri(context: Context): String? =
        sp(context).getString(KEY_BG_URI, null)?.takeIf { it.isNotBlank() }

    fun setBgUri(context: Context, uri: String?) {
        sp(context).edit().putString(KEY_BG_URI, uri).apply()
    }

    fun clearBg(context: Context) {
        sp(context).edit().remove(KEY_BG_URI).apply()
    }

    fun tone(context: Context): String = sp(context).getString(KEY_TONE, TONE_DARK) ?: TONE_DARK

    fun setTone(context: Context, tone: String) {
        sp(context).edit().putString(KEY_TONE, tone).apply()
    }

    /** 背景压暗 0..70（%），保证文字在任何图上都能看清 */
    fun scrim(context: Context): Int = sp(context).getInt(KEY_SCRIM, 25).coerceIn(0, 70)

    fun setScrim(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_SCRIM, v.coerceIn(0, 70)).apply()
    }

    /**
     * 读出可用的背景位图；没设置、读不到（用户删了图）、或权限失效都返回 null，
     * 调用方据此回落到默认纯色背景。
     *
     * @param targetW 卡片实际宽度（像素）。传 0 表示未知，退化为按原图比例处理。
     * @param targetH 卡片实际高度（像素）。
     * @param bakeScrim 是否把「压暗层」直接烤进位图。桌面小组件必须为 true ——
     *   单独铺一层的 ImageView 是直角矩形，会把已切好的圆角四角重新盖成方块；
     *   设置页的实时预览自己用 Compose 叠了一层，传 false 免得压暗两次。
     *
     * ## 为什么一定要传目标尺寸（v1.9.1 修的就是这里）
     *
     * 旧实现把图缩到预算内、切好 16dp 圆角，然后交给布局里 `scaleType="centerCrop"`
     * 的 ImageView。**问题就在 centerCrop**：位图比例（比如 4:3）和卡片比例
     * （比如 2:1）不一致时，centerCrop 会等比放大到铺满，再沿长边各裁一刀 ——
     * 而圆角恰好切在四个角上，被裁掉了，于是桌面上看到的就是直角。
     *
     * 现在的顺序是：
     *   1. [cropToAspect] 先按卡片真实宽高比做**中心裁剪**（裁的是原图，不是圆角）；
     *   2. [scaleToBudget] 缩到 Binder 事务预算内；
     *   3. [roundCorners] 用 `canvas.clipPath()` 切圆角，顺手把压暗层一起烤进去 ——
     *      此时位图比例 == 卡片比例，圆角半径按「卡片 16dp」等比换算，铺到桌面对齐。
     *
     * 布局里同时把 `centerCrop` 改成 `fitXY`（比例已经一致，fitXY 不会变形），
     * 双保险：即便 launcher 忽略了尺寸、位图略有出入，也不会再裁角。
     *
     * 圆角必须在位图上切，因为 RemoteViews 里的 ImageView 没有
     * `clipToOutline` 能力（那是 View 层的事，launcher 不会替我们做）。
     */
    fun loadBitmap(
        context: Context,
        targetW: Int = 0,
        targetH: Int = 0,
        bakeScrim: Boolean = true
    ): Bitmap? {
        val uriStr = bgUri(context) ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 逐次翻倍采样，先粗降一次内存，避免把原图整张解出来
        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull() ?: return null

        // 1) 先按卡片比例中心裁剪（这一步替代了原来 ImageView 的 centerCrop）
        val cropped = cropToAspect(decoded, targetW, targetH)
        if (cropped !== decoded) decoded.recycle()

        // 2) 缩到 Binder 预算内
        val scaled = scaleToBudget(cropped)
        if (scaled !== cropped) cropped.recycle()

        // 3) 圆角：位图现在和卡片同比例，把「卡片 16dp 圆角」等比换算到位图像素
        val density = context.resources.displayMetrics.density
        val cornerDp = 16f * density
        val ratio = if (targetW > 0 && scaled.width > 0) {
            scaled.width.toFloat() / targetW.toFloat()
        } else {
            1f
        }
        // 半径不能超过短边一半，否则 addRoundRect 会退化成椭圆/异常
        val maxRadius = minOf(scaled.width, scaled.height) / 2f
        val radiusPx = (cornerDp * ratio).coerceIn(0f, maxRadius).coerceAtLeast(
            // 图特别小的时候给个下限，否则圆角肉眼看不见
            minOf(maxRadius, 2f * ratio.coerceAtLeast(1f))
        )
        return roundCorners(scaled, radiusPx, if (bakeScrim) scrim(context) else 0)
    }

    /**
     * 按目标宽高比做中心裁剪（等价于 ImageView 的 centerCrop，但在位图上做）。
     *
     * 目标尺寸缺失（0）时原样返回 —— 此时比例未知，宁可保留整张图交给
     * fitXY 轻微拉伸，也不能瞎裁剪把用户图的重点剪掉。
     */
    private fun cropToAspect(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        val targetRatio = targetW.toFloat() / targetH.toFloat()
        val srcRatio = w.toFloat() / h.toFloat()

        // 比例已经很接近（2% 容差）就不裁了，省一次内存拷贝
        if (kotlin.math.abs(srcRatio - targetRatio) <= targetRatio * 0.02f) return src

        return runCatching {
            if (srcRatio > targetRatio) {
                // 图比卡片更宽 → 裁左右，保留中间
                val newW = (h * targetRatio).toInt().coerceIn(1, w)
                val x = (w - newW) / 2
                Bitmap.createBitmap(src, x, 0, newW, h)
            } else {
                // 图比卡片更高 → 裁上下，保留中间
                val newH = (w / targetRatio).toInt().coerceIn(1, h)
                val y = (h - newH) / 2
                Bitmap.createBitmap(src, 0, y, w, newH)
            }
        }.getOrDefault(src)
    }

    /** 按「长边 ≤ MAX_EDGE 且总像素 ≤ MAX_PIXELS」等比缩放 */
    private fun scaleToBudget(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val scale = minOf(
            1f,
            MAX_EDGE.toFloat() / maxOf(w, h),
            kotlin.math.sqrt(MAX_PIXELS.toFloat() / (w.toFloat() * h))
        )
        if (scale >= 0.999f) return src
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /**
     * 切圆角（并把压暗层烤进去）：用 `canvas.clipPath()` 把绘制区域裁成圆角矩形再画。
     *
     * 为什么不用 `xfermode = SRC_IN` 那套：它要求先把 mask 画上去、再换
     * xfermode 叠加，其中任一步被 ROM 优化掉或抛异常，`runCatching` 就会
     * 静默回退成**原图**（直角）——正是桌面上看到直角的另一种可能。
     * clipPath 是单步操作，成功就是圆角、失败整段 catch 掉，行为确定。
     *
     * 压暗必须在**同一个 clip 内**画：以前压暗是布局里另一个 match_parent 的
     * ImageView，它自己是直角矩形，会把圆角四角重新涂成方块，
     * 于是「图已经是圆角、看起来却是直角」。
     *
     * @param scrimPercent 0..70，0 表示不压暗
     */
    private fun roundCorners(src: Bitmap, radiusPx: Float, scrimPercent: Int = 0): Bitmap =
        runCatching {
            if (radiusPx <= 0f && scrimPercent <= 0) return src
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return src

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val path = Path().apply { addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW) }
            canvas.clipPath(path)
            canvas.drawBitmap(
                src, 0f, 0f,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            if (scrimPercent > 0) {
                val alpha = (scrimPercent.coerceIn(0, 100) * 255 / 100)
                canvas.drawColor(android.graphics.Color.argb(alpha, 0, 0, 0))
            }
            if (out !== src) src.recycle()
            out
        }.getOrDefault(src)

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
