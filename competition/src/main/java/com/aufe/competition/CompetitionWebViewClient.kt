package com.aufe.competition

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebView 导航拦截。
 *
 * 三个要点：
 * 1. 站内链接留在 WebView（*.aufe.edu.cn / 校内 IP）；
 * 2. 外链（赛事官网、PDF 预览站等）交给系统浏览器，避免在本模块里迷路；
 * 3. tel: / mailto: / weixin: 等自定义 scheme 交给系统处理，不能返回 false，
 *    否则 WebView 会报 ERR_UNKNOWN_URL_SCHEME 并弹一个难看的错误页。
 */
internal class CompetitionWebViewClient(
    private val onPageStarted: (String) -> Unit,
    private val onPageFinished: (String) -> Unit,
    private val onMainFrameError: (Int, String) -> Unit,
    private val onExternalIntent: (Intent) -> Boolean,
    private val isInternalHost: (String?) -> Boolean
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()

        return when (scheme) {
            "http", "https" -> {
                if (isInternalHost(uri.host)) {
                    false // 站内，WebView 自己加载
                } else {
                    onExternalIntent(Intent(Intent.ACTION_VIEW, uri))
                }
            }

            "javascript" -> false

            else -> {
                // tel: mailto: sms: weixin: alipays: intent: ...
                try {
                    onExternalIntent(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    true
                }
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted(url.orEmpty())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished(url.orEmpty())
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        // 只处理主文档错误：子资源（图片、css）失败不该整页报错
        if (request.isForMainFrame) {
            onMainFrameError(
                error.errorCode,
                error.description?.toString().orEmpty()
            )
        }
    }
}
