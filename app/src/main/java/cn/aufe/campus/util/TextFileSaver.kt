package cn.aufe.campus.util

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 文本文件落盘（下载目录优先）。
 *
 * 从 `ScoreCsvExporter` 里抽出来的通用逻辑，现在成绩 CSV 和诊断日志共用：
 * - Android 10+ 走 MediaStore，不需要任何存储权限；
 * - Android 9 及以下：有权限写公共下载目录，没权限退到应用专属目录
 *   （`Android/data/包名/files/Download`），绝不为了导一个文件去弹权限框。
 *
 * 返回可读的落点描述，空串表示失败。
 */
object TextFileSaver {

    /** UTF-8 BOM。中文 CSV 在 Windows Excel 里不加 BOM 会整片乱码 */
    const val BOM = "\uFEFF"

    fun save(context: Context, fileName: String, mimeType: String, content: String): String =
        runCatching { saveInternal(context, fileName, mimeType, content.toByteArray(Charsets.UTF_8)) }
            .getOrDefault("")

    fun saveBytes(context: Context, fileName: String, mimeType: String, bytes: ByteArray): String =
        runCatching { saveInternal(context, fileName, mimeType, bytes) }.getOrDefault("")

    private fun saveInternal(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): String {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return ""
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return ""
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "下载目录/$fileName"
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir.exists() || dir.mkdirs()) {
                File(dir, fileName).writeBytes(bytes)
                return "下载目录/$fileName"
            }
        }
        val fallback = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return ""
        if (!fallback.exists() && !fallback.mkdirs()) return ""
        File(fallback, fileName).writeBytes(bytes)
        return "应用文件目录（Android/data/…/files/Download）/ $fileName"
    }
}
