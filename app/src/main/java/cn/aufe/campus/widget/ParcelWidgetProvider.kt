package cn.aufe.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import cn.aufe.campus.R
import com.fairycrane.handycode.ParcelCodeActivity

/**
 * 取件码桌面小卡片（v1.12.0）。
 *
 * 职责只有一个：**一步打开取件码**。
 *
 * ## 为什么不显示二维码 / 身份码本身
 *
 * 淘宝和拼多多的身份码是**现取现用**的，且需要已登录状态；把它缓存到桌面
 *  RemoteViews 里，等于把凭据长期暴露在锁屏外，也一定会过期。所以小卡片
 * 只做入口，点了才去官方页面取实时码。
 *
 * ## 三个点击目标
 *
 * - 整张卡片 → 打开上次停留的平台（[ParcelCodeActivity] 自己记着）
 * - 「淘宝」  → 直接定位淘宝栏
 * - 「拼多多」→ 直接定位拼多多栏
 *
 * ⚠️ 三个 PendingIntent 必须用**不同的 requestCode**：
 * `Intent.filterEquals` 不比较 extras，同 requestCode 时后创建的会把前面的
 * 覆盖掉（FLAG_UPDATE_CURRENT），结果就是点"拼多多"却进了淘宝。
 */
class ParcelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    /** 用户拖动改尺寸：高度太矮时把副标题藏掉，优先保住两个按钮 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_parcel)

        val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0

        // 副标题在 2×1 以下就是纯占地方：<78dp 直接藏掉
        views.setViewVisibility(
            R.id.parcel_hint,
            if (heightDp in 1..77) View.GONE else View.VISIBLE
        )
        // 极窄时标题让位给按钮
        views.setTextViewText(R.id.parcel_title, if (widthDp in 1..119) "取件" else "取件码")

        views.setOnClickPendingIntent(R.id.parcel_root, openIntent(context, null, REQ_ROOT))
        views.setOnClickPendingIntent(
            R.id.parcel_taobao, openIntent(context, PROVIDER_TAOBAO, REQ_TAOBAO)
        )
        views.setOnClickPendingIntent(
            R.id.parcel_pdd, openIntent(context, PROVIDER_PDD, REQ_PDD)
        )

        runCatching { manager.updateAppWidget(widgetId, views) }
    }

    /**
     * 打开取件码页的 PendingIntent。
     *
     * 用 `FLAG_IMMUTABLE`：Android 12+ 不给可变标记会直接抛异常；
     * 这里也不需要系统往里塞 extras。
     */
    private fun openIntent(context: Context, provider: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, ParcelCodeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (provider != null) putExtra("provider", provider)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val PROVIDER_TAOBAO = "taobao"
        private const val PROVIDER_PDD = "pinduoduo"

        // requestCode 必须互不相同，否则 extras 不同的 PendingIntent 会互相覆盖
        private const val REQ_ROOT = 101
        private const val REQ_TAOBAO = 102
        private const val REQ_PDD = 103
    }
}
