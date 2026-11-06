package com.aufe.competition

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 文件选择回调的宿主接口（由 Activity 实现，负责拉起系统选择器）。
 */
interface CompetitionFileChooserHost {
    fun openFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>
    )
}

/**
 * WebView 的 Chrome 层能力。
 *
 * 关键点是 onShowFileChooser：获奖申报 / 报名参赛里的"上传佐证材料"
 * 是 <input type="file">，不接管就会点击无反应（很多接入方都栽在这）。
 */
internal class CompetitionWebChromeClient(
    private val host: CompetitionFileChooserHost,
    private val onProgress: (Int) -> Unit,
    private val onReceivedTitle: (String?) -> Unit
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        onReceivedTitle(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        host.openFileChooser(fileChooserParams, filePathCallback)
        return true // 返回 true 表示已接管，否则控件永远等不到结果
    }
}
