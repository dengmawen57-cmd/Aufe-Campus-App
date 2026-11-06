package cn.aufe.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache

/**
 * 桌面小组件背景图的通用处理：解码 → 按卡片比例中心裁剪 → 缩到预算内 → 切圆角（顺手压暗）。
 *
 * ## 为什么和 [YktWidgetPrefs] 里那套长得一样却是单独一份
 *
 * 一卡通小卡片走的是 `RemoteViews + setImageViewBitmap`，课表小组件走的是
 * `Glance + ImageProvider(Bitmap)`（v1.9.5 起不再走 URI，见 [ScheduleWidgetPrefs] 顶部），
 * 两边对「体积」「圆角」「压暗」的要求一致，但落点不同。把已验证过的余额卡片逻辑
 * 抽出来公用，风险比复制一份更大（那边刚修好圆角，不该为了复用再动一次），
 * 所以这里保持独立实现，修改时请两边对照着改。
 *
 * ## 体积为什么要卡
 *
 * 现在位图是随 RemoteViews 一起走 Binder 的，太大会抛
 * `TransactionTooLargeException` —— 而那个异常被外层 `runCatching` 吞掉后，
 * 表现是「卡片彻底不更新」，比图糊一点严重得多。
 */
internal object WidgetBitmap {

    /** 背景图长边上限（像素） */
    private const val MAX_EDGE = 512

    /**
     * 背景图像素总数上限。
     *
     * 位图是**随 RemoteViews 一起走 Binder** 发到桌面的（见 ScheduleWidgetPrefs
     * 顶部说明），ARGB_8888 下 1 像素 4 字节，11 万像素约 440KB ——
     * 加上 RemoteViews 本身的结构还有余量，不会撞上 1MB 的事务上限。
     * 再大就可能 TransactionTooLargeException，整次更新直接失败（比糊一点严重得多）。
     */
    private const val MAX_PIXELS = 110_000

    /**
     * 渲染结果缓存（v1.9.6）。
     *
     * ## 为什么要缓存
     *
     * 桌面卡片的刷新从 v1.9.6 起改成「多打几枪」（见 [ScheduleWidgetSync]：
     * 推一次 → 等 launcher 回前台再推一次 → 还不行就发广播兜底）。
     * 每推一次，两张卡片会按各自的尺寸各要一张背景图 —— 一次 `保存并刷新`
     * 就是 2 次整图解码（用户照片动辄 4000px）+ 裁剪 + 缩放 + 切圆角。
     *
     * 在 HONOR 这类中端机上，4 次解码轻松吃掉 1~3 秒；而旧代码在保存时用
     * `withTimeoutOrNull(4000)` 直接包住更新协程 —— **超时会把协程取消**，
     * `runCatching` 又把 CancellationException 吞掉，于是「更新根本没跑完、
     * 页面却照常关闭」：用户看到的就是「点了保存桌面没变，要多点几次才成」。
     *
     * 有了缓存，第二、三枪几乎是零成本（只剩一次 binder 调用），
     * 「多打几枪」这个策略才真正划得来。
     *
     * ## 为什么 key 里带版本号
     *
     * [ScheduleWidgetPrefs.bumpVersion] 每次改设置（换图 / 调压暗 / 切配色）都会 +1，
     * 版本号进 key 就等于「设置一变，缓存自然失效」，不用手动清缓存，
     * 也不会出现「换了图却还拿到旧位图」这种最要命的情况。
     *
     * ## 缓存里的位图永不 recycle
     *
     * 这些位图会被塞进 RemoteViews 交给桌面进程，可能被反复复用；
     * 谁 recycle 谁就可能让桌面拿到一张已回收的图（表现为卡片变白或崩溃）。
     * 淘汰靠 LRU + GC，不手动回收。
     */
    private val cache = LruCache<String, Bitmap>(4)

    /** 清空缓存（切号 / 退出登录时调用，顺手把内存还给系统） */
    fun clearCache() = cache.evictAll()

    /** 缓存里当前有几张（诊断用） */
    fun cacheSize(): Int = cache.size()

    /**
     * 读出处理好的背景位图；没设置、读不到（用户删了图）、权限失效都返回 null。
     *
     * @param targetW 卡片宽度（像素），0 表示未知
     * @param targetH 卡片高度（像素）
     * @param cornerDp 圆角（dp），必须和 Glance 那边 `cornerRadius()` 的值对齐
     * @param scrimPercent 0..70，压暗百分比；0 表示不压暗
     */
    fun load(
        context: Context,
        uriStr: String?,
        targetW: Int,
        targetH: Int,
        cornerDp: Float,
        scrimPercent: Int = 0
    ): Bitmap? {
        if (uriStr.isNullOrBlank()) return null

        // 命中缓存直接返回：卡片重推（第二枪 / 第三枪）与两个卡片同尺寸时全靠它
        val key = buildKey(context, uriStr, targetW, targetH, cornerDp, scrimPercent)
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = decode(context, uriStr, targetW, targetH, cornerDp, scrimPercent)
            ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun buildKey(
        context: Context,
        uriStr: String,
        targetW: Int,
        targetH: Int,
        cornerDp: Float,
        scrimPercent: Int
    ): String = buildString {
        append(uriStr)
        append('|').append(ScheduleWidgetPrefs.version(context))
        append('|').append(targetW).append('x').append(targetH)
        append('|').append(cornerDp)
        append('|').append(scrimPercent)
    }

    /** 真正干活的那一段：解码 → 裁剪 → 缩放 → 圆角压暗 */
    private fun decode(
        context: Context,
        uriStr: String,
        targetW: Int,
        targetH: Int,
        cornerDp: Float,
        scrimPercent: Int
    ): Bitmap? {
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 逐次翻倍采样，先粗降一次内存
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

        // 1) 按卡片比例中心裁剪（等价于 centerCrop，但在位图上做，不会把圆角裁掉）
        val cropped = cropToAspect(decoded, targetW, targetH)
        if (cropped !== decoded) decoded.recycle()

        // 2) 缩到预算内
        val scaled = scaleToBudget(cropped)
        if (scaled !== cropped) cropped.recycle()

        // 3) 圆角：位图比例已与卡片一致，圆角半径按缩放比换算
        val ratio = if (targetW > 0 && scaled.width > 0) {
            scaled.width.toFloat() / targetW.toFloat()
        } else {
            1f
        }
        val maxRadius = minOf(scaled.width, scaled.height) / 2f
        val radiusPx = (cornerDp * ratio).coerceIn(0f, maxRadius)
        return roundCorners(scaled, radiusPx, scrimPercent)
    }

    /**
     * 采样判断位图整体是不是偏暗（决定白字还是深字）。
     *
     * 用 24×24 网格采样而不是逐像素：整图算平均在 512px 图上要遍历 26 万点，
     * 而人的眼睛对这种整体明暗的判断不需要那么精确。
     */
    fun isDark(bitmap: Bitmap, scrimPercent: Int = 0): Boolean = runCatching {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                // 标准亮度权重（人眼对绿色最敏感）
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val avg = sum / count
        // 压暗会让整图变黑，判断时把压暗补回来，否则永远判成"暗"
        val adjusted = avg / (1.0 - scrimPercent.coerceIn(0, 90) / 100.0)
        adjusted < 140
    }.getOrDefault(true)

    /** 按目标宽高比中心裁剪；目标尺寸未知时原样返回 */
    private fun cropToAspect(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val targetRatio = targetW.toFloat() / targetH.toFloat()
        val srcRatio = w.toFloat() / h.toFloat()
        if (kotlin.math.abs(srcRatio - targetRatio) <= targetRatio * 0.02f) return src
        return runCatching {
            if (srcRatio > targetRatio) {
                val newW = (h * targetRatio).toInt().coerceIn(1, w)
                Bitmap.createBitmap(src, (w - newW) / 2, 0, newW, h)
            } else {
                val newH = (w / targetRatio).toInt().coerceIn(1, h)
                Bitmap.createBitmap(src, 0, (h - newH) / 2, w, newH)
            }
        }.getOrDefault(src)
    }

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
        return Bitmap.createScaledBitmap(
            src,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * 切圆角并压暗。
     *
     * 用 `canvas.clipPath()` 单步完成，不用 `xfermode = SRC_IN`：
     * 后者要求"先画 mask 再换模式叠加"，中间任一步抛异常就会被
     * `runCatching` 静默回退成直角原图。
     */
    private fun roundCorners(src: Bitmap, radiusPx: Float, scrimPercent: Int): Bitmap =
        runCatching {
            if (radiusPx <= 0f && scrimPercent <= 0) return src
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return src
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val path = Path().apply {
                addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radiusPx, radiusPx, Path.Direction.CW)
            }
            canvas.clipPath(path)
            canvas.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (scrimPercent > 0) {
                val alpha = scrimPercent.coerceIn(0, 100) * 255 / 100
                canvas.drawColor(Color.argb(alpha, 0, 0, 0))
            }
            if (out !== src) src.recycle()
            out
        }.getOrDefault(src)
}
