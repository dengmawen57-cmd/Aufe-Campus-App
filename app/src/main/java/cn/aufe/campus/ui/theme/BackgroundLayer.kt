package cn.aufe.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import cn.aufe.campus.data.local.AppearanceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局背景图层。
 *
 * ## 为什么不用 Modifier.blur / Coil
 *
 * - `Modifier.blur` 要 API 31+，本工程 minSdk 24，低版本只能降级成"不模糊"，
 *   体验割裂；而且它对全屏大图很吃 GPU，在教室里的老机器上会掉帧。
 * - 引入 Coil 需要新增依赖，而本工程是离线构建（依赖缓存里没有）。
 *
 * 所以这里用最经典也最省事的做法：**先降采样到很小，再用双线性放大画回去**。
 * 缩得越小、放大倍率越高，看起来就越糊——效果和真模糊几乎无差别，
 * 成本却是一个几十像素的小图，任何机器都跑得动。
 *
 * [blurLevel]（0..30）直接决定降采样倍率：0 基本原图，30 缩到约 1/25。
 */
@Composable
fun BackgroundLayer(modifier: Modifier = Modifier) {
    val uri = AppearanceState.bgUri ?: return
    val blur = AppearanceState.bgBlur
    val dim = AppearanceState.bgDim
    val context = LocalContext.current

    val image by produceState<ImageBitmap?>(initialValue = null, uri, blur) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadScaled(context, Uri.parse(uri), blur)?.asImageBitmap() }
                .getOrNull()
        }
    }

    val bitmap = image ?: return
    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 压暗：亮色壁纸会让黑字看不清，这里加一层可调的黑色遮罩
        if (dim > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim / 100f))
            )
        }
    }
}

/** 读取并按模糊强度降采样，结果尺寸约为原图的 1/(1+blur*0.8) */
private fun loadScaled(context: Context, uri: Uri, blurLevel: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    // 先按"不超过 1280 宽"做一次采样，避免把 4000px 的原图整个读进内存
    var sample = 1
    while (srcW / sample > 1280) sample *= 2
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val base = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    }.getOrNull() ?: return null

    // 再按模糊强度缩小：这一步就是"模糊"本身
    val factor = 1f + blurLevel * 0.8f
    if (factor <= 1.01f) return base
    val w = (base.width / factor).toInt().coerceIn(8, base.width)
    val h = (base.height / factor).toInt().coerceIn(8, base.height)
    val small = runCatching { Bitmap.createScaledBitmap(base, w, h, true) }.getOrNull()
        ?: return base
    if (small !== base) base.recycle()
    // 补两次轻量模糊，去掉缩小带来的块状感
    return runCatching { boxBlur(boxBlur(small)) }.getOrDefault(small)
}

/**
 * 一维可分离的 box blur（水平 + 垂直各一遍）。
 *
 * 作用对象是几十像素的小图，开销可以忽略；半径固定 1，
 * 只是用来磨掉降采样后的锯齿。
 */
private fun boxBlur(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    if (w < 3 || h < 3) return src
    val inPx = IntArray(w * h)
    src.getPixels(inPx, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    val out = IntArray(w * h)

    // 水平
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val c = inPx[row + xx]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
            tmp[row + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
    // 垂直
    for (x in 0 until w) {
        for (y in 0 until h) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                val c = tmp[yy * w + x]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
    val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    dst.setPixels(out, 0, w, 0, 0, w, h)
    src.recycle()
    return dst
}
