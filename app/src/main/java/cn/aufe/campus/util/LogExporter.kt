package cn.aufe.campus.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志导出。
 *
 * 两种出口，覆盖不同性格的用户：
 * 1. **保存到下载目录** —— 想自己看/存档的；
 * 2. **直接分享** —— 想发微信/邮件给我的。走 FileProvider，
 *    不需要存储权限，也不用先把文件写到公共目录再找一遍。
 */
object LogExporter {

    private const val FILE_PREFIX = "安财校园通_日志_"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())

    /** 保存到下载目录，返回可读落点（空串=失败） */
    fun saveToDownloads(context: Context): String {
        val name = "$FILE_PREFIX${stamp()}.txt"
        return TextFileSaver.save(
            context = context,
            fileName = name,
            mimeType = "text/plain",
            content = AppLog.buildReport(context)
        )
    }

    /**
     * 分享日志。
     *
     * 写到 `cacheDir/logs` 再用 FileProvider 授权，避免把用户下载目录
     * 塞满临时文件；系统分享面板关掉后缓存文件留着也无所谓，
     * `cacheDir` 本来就会被系统按需清理。
     */
    fun share(context: Context): Boolean = runCatching {
        val dir = File(context.cacheDir, "logs").apply { if (!exists()) mkdirs() }
        // 只保留最近一份，避免缓存堆积
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "$FILE_PREFIX${stamp()}.txt")
        file.writeText(AppLog.buildReport(context))

        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "安财校园通诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
