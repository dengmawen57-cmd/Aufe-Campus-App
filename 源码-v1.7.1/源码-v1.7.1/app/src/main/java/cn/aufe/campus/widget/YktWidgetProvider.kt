package cn.aufe.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver.PendingResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import cn.aufe.campus.CampusApp
import cn.aufe.campus.MainActivity
import cn.aufe.campus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一卡通余额桌面小卡片：
 * - 展示校园卡余额与更新时间（数据来自 WidgetDataStore 缓存）
 * - 点击卡片 → 打开 App 的一卡通页面
 * - 点击刷新 → 后台实时查询余额后刷新小卡片
 * - 系统定时（30 分钟）+ 余额刷新时自动更新
 */
class YktWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> fetchAsync(context, goAsync())
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                // 缓存为空、或缓存已过期（长时间未登录导致余额陈旧）时主动拉一次，
                // 否则小卡片会一直显示旧余额甚至"—"
                if (WidgetDataStore.isBalanceStale(context)) {
                    fetchAsync(context, goAsync())
                } else {
                    appWidgetIdsOrEmpty(context).forEach { id ->
                        updateWidget(context, AppWidgetManager.getInstance(context), id)
                    }
                }
            }
        }
    }

    private fun appWidgetIdsOrEmpty(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, YktWidgetProvider::class.java))
            ?: IntArray(0)

    /**
     * 后台查询余额并刷新小卡片（goAsync 允许在广播中执行异步任务）。
     *
     * 关键：小卡片在后台运行时 App 可能并未登录/会话已过期，
     * 因此这里先尝试静默恢复会话（本地加密凭据），再查余额；
     * 恢复失败才放弃（保留旧缓存），避免小卡片长期显示陈旧数据。
     */
    private fun fetchAsync(context: Context, pendingResult: PendingResult?) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val auth = (appContext as CampusApp).authManager
                // 会话可能已过期（长时间未打开 App），先静默恢复再查
                if (!auth.restoreSessionBlocking()) {
                    WidgetDataStore.refreshYktWidgets(appContext)
                    return@launch
                }
                val balance = auth.yktApi().getBalance()
                WidgetDataStore.saveBalance(appContext, balance.balance)
            } catch (_: Exception) {
                // 查询失败（网络异常/页面结构变化）时保持原缓存，仅重绘
            } finally {
                WidgetDataStore.refreshYktWidgets(appContext)
                runCatching { pendingResult?.finish() }
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_ykt)
        val (balance, at) = WidgetDataStore.loadBalance(context)

        views.setTextViewText(
            R.id.ykt_balance,
            if (balance == null) "—" else "¥${"%.2f".format(balance)}"
        )
        views.setTextViewText(R.id.ykt_time, timeText(at))

        // 点击卡片 → 打开一卡通页
        views.setOnClickPendingIntent(R.id.ykt_root, openYktIntent(context))
        // 点击刷新 → 实时查询
        views.setOnClickPendingIntent(R.id.ykt_refresh, refreshIntent(context))

        manager.updateAppWidget(widgetId, views)
    }

    private fun timeText(at: Long): String =
        if (at <= 0L) "暂无数据 · 点击刷新"
        else "更新于 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))

    private fun openYktIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_YKT)
        }
        return PendingIntent.getActivity(
            context, 21, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refreshIntent(context: Context): PendingIntent {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(android.content.ComponentName(context, YktWidgetProvider::class.java))
        val intent = Intent(context, YktWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        return PendingIntent.getBroadcast(
            context, 22, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_REFRESH = "cn.aufe.campus.action.YKT_WIDGET_REFRESH"
    }
}
