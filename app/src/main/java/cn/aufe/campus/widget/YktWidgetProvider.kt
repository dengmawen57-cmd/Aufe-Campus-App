package cn.aufe.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver.PendingResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import cn.aufe.campus.CampusApp
import cn.aufe.campus.MainActivity
import cn.aufe.campus.R
import cn.aufe.campus.util.AppLog
import cn.aufe.campus.util.RecordTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一卡通余额桌面小卡片：
 * - 展示校园卡余额与更新时间（数据来自 WidgetDataStore 缓存）
 * - 点击卡片 → 打开 App 的一卡通页面
 * - 点击「刷新」按钮 → 后台实时查询余额后刷新
 * - 系统定时（30 分钟）+ 余额刷新时自动更新
 *
 * ## v1.9.0 的三处改造
 *
 * 1. **自由缩放**：`onAppWidgetOptionsChanged` 里按单元格实际尺寸换三档排版
 *    （紧凑 / 标准 / 宽松），字号、内边距、显示哪些行都跟着变，
 *    而不是把一套布局硬拉伸。
 * 2. **刷新按钮**：从一行 11sp 的小字改成真正的圆角按钮（54×32dp 起）。
 * 3. **自定义背景**：读 [YktWidgetPrefs] 里的图片，铺一张已缩放切圆角的位图，
 *    并根据图片平均亮度自动决定文字用深色还是浅色；背景可再压暗。
 */
class YktWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    /** 用户拖动改尺寸时触发：立刻按新尺寸重排，不必等下一次定时刷新 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
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
     *
     * ## v1.9.1：为什么要有重试
     *
     * 长时间不用之后，一卡通的 SSO 会话和 VPN 会话是**分别**过期的。
     * 第一次查询常常刚好撞在「网络层正在后台重登」的窗口上：
     * 请求先失败一次，重登成功后才有可用的 Cookie。原来没有重试，
     * 这一次失败就等于用户点了刷新却什么都没发生。
     * 现在失败后停 1.5 秒再查一次，绝大多数情况第二次就通了。
     */
    private fun fetchAsync(context: Context, pendingResult: PendingResult?) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val auth = (appContext as CampusApp).authManager
                // 会话可能已过期（长时间未打开 App），先静默恢复再查
                if (!auth.restoreSessionBlocking()) {
                    WidgetDataStore.markBalanceError(appContext)
                    return@launch
                }
                var ok = false
                var lastError: Exception? = null
                for (attempt in 1..FETCH_ATTEMPTS) {
                    val result = runCatching { auth.yktApi().getBalance() }
                    val balance = result.getOrNull()
                    if (balance != null) {
                        WidgetDataStore.saveBalance(appContext, balance.balance)
                        WidgetDataStore.clearBalanceError(appContext)
                        ok = true
                        break
                    }
                    lastError = result.exceptionOrNull() as? Exception
                    if (attempt < FETCH_ATTEMPTS) delay(FETCH_RETRY_DELAY_MS)
                }
                if (!ok) {
                    // 记一笔失败，卡片上会显示「刷新失败」，不再是点了没反应
                    WidgetDataStore.markBalanceError(appContext)
                    AppLog.w("YktWidget", "余额刷新失败：${lastError?.message}")
                }
            } catch (e: Exception) {
                WidgetDataStore.markBalanceError(appContext)
                AppLog.w("YktWidget", "余额刷新异常：${e.message}")
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
        views.setTextViewText(R.id.ykt_time, timeText(at, WidgetDataStore.balanceErrorAt(context)))

        // 尺寸自适应
        val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        applyMetrics(context, views, widthDp, heightDp)

        // 自定义背景 + 文字配色（背景图要按卡片真实像素尺寸裁，圆角才对得上）
        val density = context.resources.displayMetrics.density
        val wPx = ((if (widthDp > 0) widthDp else 180) * density).toInt()
        val hPx = ((if (heightDp > 0) heightDp else 90) * density).toInt()
        applyBackground(context, views, wPx, hPx)

        // 点击卡片 → 打开一卡通页
        views.setOnClickPendingIntent(R.id.ykt_root, openYktIntent(context))
        // 点击刷新 → 实时查询
        views.setOnClickPendingIntent(R.id.ykt_refresh, refreshIntent(context))

        runCatching { manager.updateAppWidget(widgetId, views) }
    }

    /**
     * 按单元格尺寸选一档排版。
     *
     * 尺寸单位是 dp（系统给的是 minWidth/minHeight 的 dp 值）。
     * 三档阈值按常见桌面网格估的：1×1 约 70dp、2×1 约 150dp、4×2 约 320dp。
     */
    private fun applyMetrics(context: Context, views: RemoteViews, widthDp: Int, heightDp: Int) {
        val density = context.resources.displayMetrics.density
        val w = if (widthDp > 0) widthDp else 180
        val h = if (heightDp > 0) heightDp else 90

        val compact = w < 130 || h < 62
        val roomy = w >= 250 && h >= 120

        val padDp = when {
            compact -> 8
            roomy -> 16
            else -> 12
        }
        val pad = (padDp * density).toInt()
        views.setViewPadding(R.id.ykt_content, pad, pad, pad, pad)

        views.setTextViewTextSize(
            R.id.ykt_balance, TypedValue.COMPLEX_UNIT_SP,
            when {
                compact -> 20f
                roomy -> 36f
                else -> 28f
            }
        )
        views.setTextViewTextSize(
            R.id.ykt_title, TypedValue.COMPLEX_UNIT_SP,
            if (compact) 11f else 13f
        )

        // 高度不够就把「更新时间」这一行藏掉，优先保证余额看得全
        views.setViewVisibility(
            R.id.ykt_time,
            if (h < 70) View.GONE else View.VISIBLE
        )
        // 太窄时标题只留「一卡通」，给刷新按钮腾地方
        views.setTextViewText(
            R.id.ykt_title,
            if (w < 120) "一卡通" else "一卡通余额"
        )
        // 极窄时按钮只留一个字，避免文字折行把布局撑坏
        views.setTextViewText(R.id.ykt_refresh, if (w < 110) "刷" else "刷新")
    }

    /**
     * 背景图 + 文字深浅。
     *
     * ## 圆角（v1.9.1 修复）
     *
     * 以前这里有三处会把圆角弄没：
     * 1. 位图交给 `centerCrop` 铺 —— 比例不符时裁掉四条边，圆角正好在边上；
     * 2. 压暗层是另一个 match_parent 的直角 ImageView，盖住四角；
     * 3. 无背景图时 `setBackgroundColor(WHITE)` 会**整块替换**掉 XML 里那个
     *    16dp 圆角的 drawable，变成直角白块。
     *
     * 现在：位图在 [YktWidgetPrefs.loadBitmap] 里已按卡片比例裁好、切好圆角、
     * 压暗也烤进去了；压暗层 View 常驻 GONE；无图时不碰根节点背景
     * （保留 XML 的圆角 drawable）。
     */
    private fun applyBackground(context: Context, views: RemoteViews, wPx: Int, hPx: Int) {
        val bitmap = YktWidgetPrefs.loadBitmap(context, wPx, hPx)

        // 压暗层已烤进位图，这层永远藏起来（留着节点只是为了兼容旧布局快照）
        views.setViewVisibility(R.id.ykt_scrim, View.GONE)

        if (bitmap == null) {
            views.setViewVisibility(R.id.ykt_bg, View.GONE)
            // 恢复 XML 里的圆角白底（之前可能被上一次的 setBackgroundColor 覆盖过）
            views.setInt(R.id.ykt_root, "setBackgroundResource", R.drawable.widget_bg)
            setTextColors(views, title = 0xFF1A1C1E.toInt(), accent = 0xFF1A5FB4.toInt(), sub = 0xFF44474E.toInt())
            return
        }

        views.setViewVisibility(R.id.ykt_bg, View.VISIBLE)
        runCatching { views.setImageViewBitmap(R.id.ykt_bg, bitmap) }
        // 有背景图时把根节点的白底去掉，否则图片圆角外会露出一圈白边
        views.setInt(R.id.ykt_root, "setBackgroundColor", Color.TRANSPARENT)

        // 位图已经压过暗，这里按压暗 0 算亮度，别再折一次
        val lightText = when (YktWidgetPrefs.tone(context)) {
            YktWidgetPrefs.TONE_LIGHT -> true
            YktWidgetPrefs.TONE_DARK -> false
            else -> isDarkBitmap(bitmap, 0)
        }
        if (lightText) {
            setTextColors(views, title = Color.WHITE, accent = Color.WHITE, sub = 0xE6FFFFFF.toInt())
        } else {
            setTextColors(views, title = 0xE61A1C1E.toInt(), accent = 0xFF1A5FB4.toInt(), sub = 0xCC44474E.toInt())
        }
    }

    private fun setTextColors(views: RemoteViews, title: Int, accent: Int, sub: Int) {
        views.setTextColor(R.id.ykt_title, title)
        views.setTextColor(R.id.ykt_balance, accent)
        views.setTextColor(R.id.ykt_time, sub)
    }

    /**
     * 粗略判断背景图偏暗还是偏亮（每 16 像素采一个点算平均亮度）。
     *
     * 采样而非全量遍历：一张 400×250 的图全扫是 10 万次循环，虽然也不慢，
     * 但小组件可能在 launcher 主线程被反复重绘，能省则省。
     */
    private fun isDarkBitmap(bitmap: Bitmap, scrimPercent: Int): Boolean = runCatching {
        var sum = 0.0
        var count = 0
        val stepX = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                // 亮度加权（人眼对绿最敏感）
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return@runCatching false
        var avg = sum / count
        // 压暗层会让图变暗，按比例折算后再判断
        avg *= (1.0 - scrimPercent / 100.0)
        avg < 140
    }.getOrDefault(false)

    private fun timeText(at: Long, errorAt: Long = 0L): String {
        // 失败时间比成功时间新 → 说明最近一次刷新没成，如实告诉用户
        val failedRecently = errorAt > at
        return when {
            at <= 0L && failedRecently -> "刷新失败 · 点右上角重试"
            at <= 0L -> "暂无数据 · 点右上角刷新"
            failedRecently -> "更新于 " + RecordTime.stamp(at) + " · 刷新失败"
            else -> "更新于 " + RecordTime.stamp(at)
        }
    }

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
            .getAppWidgetIds(ComponentName(context, YktWidgetProvider::class.java))
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

        /** 失败重试次数与间隔：撞在后台重登窗口上时，第二次基本就通了 */
        private const val FETCH_ATTEMPTS = 2
        private const val FETCH_RETRY_DELAY_MS = 1_500L
    }
}
