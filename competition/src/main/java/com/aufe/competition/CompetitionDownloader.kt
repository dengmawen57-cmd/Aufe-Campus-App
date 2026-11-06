package com.aufe.competition

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import android.webkit.URLUtil

/**
 * 附件下载。
 *
 * 平台的附件出口是 download.aspx?id=xxx 这类地址，靠 Content-Disposition 给文件名。
 * DownloadManager 不会自动带 Cookie，而附件又必须带会话，所以这里手动补 Cookie 头。
 */
internal object CompetitionDownloader {

    /**
     * @param onMessage 给用户看的提示（成功/失败都从这里出）
     * @return DownloadManager 的任务 id，失败返回 null
     */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        onMessage: (String) -> Unit
    ): Long? {
        // blob:/data: 是前端内存数据，DownloadManager 拿不到，直接告知用户
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            onMessage(context.getString(R.string.competition_download_unsupported))
            return null
        }

        val fileName = resolveFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription(context.getString(R.string.competition_download_desc))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            CompetitionCookies.cookie(url)?.let { addRequestHeader("Cookie", it) }
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)

            val relativePath = "${CompetitionConfig.DOWNLOAD_DIR}/$fileName"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 写公共下载目录不需要存储权限
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, relativePath)
            } else {
                // 低版本写 App 私有目录，避免申请 WRITE_EXTERNAL_STORAGE
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, relativePath)
            }
        }

        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            onMessage(context.getString(R.string.competition_download_started))
            id
        } catch (e: Exception) {
            onMessage(context.getString(R.string.competition_download_failed, e.message ?: ""))
            null
        }
    }

    /**
     * download.aspx 这类没有扩展名的地址，URLUtil 猜出来的是 "download.aspx"，
     * 用 MimeTypeMap 补一个真实后缀，用户下载后才知道怎么打开。
     */
    private fun resolveFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        var name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (!name.contains('.')) {
            val ext = mimeType
                ?.substringBefore(';')
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!ext.isNullOrBlank()) name = "$name.$ext"
        }
        // 去掉 Windows/安卓都不允许的字符
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank {
            "attachment_${System.currentTimeMillis()}"
        }
    }
}
