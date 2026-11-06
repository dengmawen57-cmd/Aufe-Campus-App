package cn.aufe.campus.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 课表小组件同步。
 *
 * 与参考实现（LoveACE 的 WidgetSyncHelper）的差异：安财的课表由 App 内仓库层拉取，
 * 小组件进程不联网，因此这里只负责「落盘 + 通知 Glance 重建」，数据拉取仍在 ViewModel 里完成。
 *
 * ## v1.9.6：刷新机制改成对齐一卡通余额小卡片
 *
 * 之前的表现是「改了背景图，要点好几次『保存并刷新』桌面才换过来」。
 * 根因有三层，叠加起来就成了「要多点几次」：
 *
 * 1. **保存时的超时会把更新取消掉**。设置页写的是
 *    `withTimeoutOrNull(4000) { updateAllNow() }` —— 超过 4 秒协程被取消，
 *    而 `runCatching` 连 `CancellationException` 一起吞了，于是「更新没做完、
 *    页面照常关闭」，用户什么都没看到。冷启动第一次组合两张卡片 + 解码用户照片，
 *    在这台 HONOR 上很容易超过 4 秒，所以第一下必失败、点几次才碰上一次没超时。
 * 2. **只推一枪**。余额卡片是「推 RemoteViews → 后台查完再 `finally` 刷一次」，
 *    而课表卡片只 `updateAll` 一次。配置页关闭时 launcher 才刚回到前台，
 *    这一枪很容易被它自己的重排吞掉。
 * 3. **失败完全静默**。余额卡片失败会写 `markBalanceError`，卡片上显示
 *    「刷新失败 · 点重试」；课表这边失败连日志都没有。
 *
 * 现在对齐余额那套：
 * - 更新跑在**应用级作用域**里：调用方等不等都不影响它跑完，超时只是不再等；
 * - 一次请求 = **连推两枪**（第二枪在 launcher 回前台之后），仍失败再发系统广播兜底；
 * - 成功 / 失败都落盘（[ScheduleWidgetPrefs.markApplied] / [markApplyFailed]），
 *   设置页据此显示「这次没刷上，再点一次保存」；
 * - 位图按「设置版本号」缓存（见 [WidgetBitmap]），第二枪几乎零成本。
 */
object ScheduleWidgetSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 同一时刻只允许一次重建，避免两枪叠在一起时桌面收到乱序的 RemoteViews */
    private val lock = Mutex()

    /**
     * 单例持有两个 Glance 卡片。
     *
     * Glance 的会话是挂在 `GlanceAppWidget` 实例/类上的，反复 new 新实例
     * 会让每次更新都开一个新会话（首帧组合的开销全都要重付一遍），
     * 这里固定一份，更新的开销只落在「组合 + binder」上。
     */
    private val dayWidget by lazy { ScheduleDayWidget() }
    private val weekWidget by lazy { ScheduleWeekWidget() }

    /** 两张卡片：receiver 用来问系统要 AppWidgetId，widget 用来触发重建 */
    private val targets: List<Pair<Class<out GlanceAppWidgetReceiver>, GlanceAppWidget>>
        get() = listOf(
            ScheduleDayWidgetReceiver::class.java to dayWidget,
            ScheduleWeekWidgetReceiver::class.java to weekWidget
        )

    /** 正在排队的那一串推送（新的请求会把它作废，只认最后一次设置） */
    @Volatile
    private var pendingJob: Job? = null

    /** 合并连点用：选图、拖滑杆、切配色常常连着触发好几次 */
    private const val DEBOUNCE_MS = 120L

    /** 第二枪的延迟：给 launcher 留出从后台回前台、真正 apply RemoteViews 的时间 */
    private const val REAPPLY_DELAY_MS = 450L

    /** 兜底广播前再等一会儿，避开 launcher 刚回前台的抖动 */
    private const val FALLBACK_DELAY_MS = 900L

    /** 一次应用的战果 */
    data class ApplyResult(val count: Int = 0, val error: String? = null) {
        val ok: Boolean get() = error == null && count > 0
    }

    /** 课表加载完成后调用：写缓存并刷新两个小组件 */
    fun sync(context: Context, courses: List<SelectedCourse>) {
        WidgetDataStore.save(context, courses)
        requestUpdate(context)
    }

    /**
     * 请求把卡片重绘到桌面（设置变化、跨天、回前台都走这里）。
     *
     * 立即返回，真正的推送在应用级作用域里跑，页面关掉也会跑完 ——
     * 这正是旧实现做不到的一点（旧实现把推送绑在 `rememberCoroutineScope` 上，
     * `finish()` 一到就一起取消了）。
     */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        // 只认最后一次设置：连点开关时把上一串推送作废，避免十几枪排队
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            // 第一枪：设置刚变，先把新内容推上去
            val first = applyNow(app)

            // 第二枪：launcher 从后台回前台后往往会重排一次卡片，
            // 第一枪可能被这次重排覆盖掉；补一枪把新图重新按上去
            delay(REAPPLY_DELAY_MS)
            val second = applyNow(app)

            // 两枪都没成，就发系统广播让 Glance 的 receiver 自己走一遍 onUpdate
            val result = second.takeIf { it.ok } ?: first
            if (!result.ok) {
                delay(FALLBACK_DELAY_MS)
                broadcastUpdate(app)
            }
        }
    }

    /**
     * 立刻推一次并**等它跑完**（设置页「保存并刷新」用）。
     *
     * 关键：任务跑在 [scope] 里，`withTimeoutOrNull` 只取消「等待」，
     * 不会取消任务本身。旧实现把 `updateAll` 直接写在超时块里，
     * 一超时就真取消了 —— 那是「点多几次才行」的第一号原因。
     *
     * @return 结果；超时仍没跑完返回 null（任务会在后台继续，并自动补枪）
     */
    suspend fun applyAndWait(context: Context, timeoutMs: Long = 6000L): ApplyResult? {
        val app = context.applicationContext
        val job = scope.async { applyNow(app) }
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    /**
     * 把所有卡片各推一次。
     *
     * 不抛异常：失败信息写进 [ScheduleWidgetPrefs]，由设置页展示；
     * 调用方（广播 / 服务）不需要处理。
     */
    suspend fun applyNow(context: Context): ApplyResult {
        val app = context.applicationContext
        val started = System.currentTimeMillis()
        var count = 0
        var firstError: String? = null

        lock.withLock {
            for ((receiver, widget) in targets) {
                val r = push(app, receiver, widget)
                count += r.count
                if (firstError == null) firstError = r.error
            }
        }

        val cost = System.currentTimeMillis() - started
        if (count > 0) {
            ScheduleWidgetPrefs.markApplied(app)
            if (firstError == null) ScheduleWidgetPrefs.clearApplyError(app)
            AppLog.i("ScheduleWidget", "已推到桌面 ${count} 张卡片，耗时 ${cost}ms")
        }
        if (firstError != null && count == 0) {
            ScheduleWidgetPrefs.markApplyFailed(app, firstError!!)
            AppLog.w("ScheduleWidget", "推送失败（${cost}ms）：$firstError")
        }
        return ApplyResult(count, firstError)
    }

    /**
     * 逐个 AppWidgetId 更新，而不是 `updateAll`。
     *
     * `updateAll` 是批量路径，两个卡片同时推时和 launcher 的重排撞在一起的概率更高；
     * 逐个 update 还能拿到每个 id 的成败，失败原因才留得下来。
     *
     * 这里用 receiver 的 ComponentName 直接问系统要 id（Glance 的
     * `getGlanceIds(Class)` 要传的是 widget 类，语义上绕一层），
     * 再用 `getGlanceIdBy(int)` 换成 GlanceId —— 结果和 Glance 内部完全一致。
     */
    private suspend fun push(
        app: Context,
        receiver: Class<out GlanceAppWidgetReceiver>,
        widget: GlanceAppWidget
    ): ApplyResult {
        val widgetIds = runCatching {
            AppWidgetManager.getInstance(app)
                .getAppWidgetIds(ComponentName(app, receiver))
        }.getOrNull()

        if (widgetIds == null) {
            // 系统查询本身失败：退回 updateAll，至少不什么都不做
            return runCatching {
                widget.updateAll(app)
                ApplyResult(1)
            }.getOrElse {
                ApplyResult(0, it.message ?: it::class.java.simpleName)
            }
        }
        // 桌面上没放这张卡片 → 不是错误，直接算成功（设置照旧落盘，加卡片时会用上）
        if (widgetIds.isEmpty()) return ApplyResult(1)

        val manager = GlanceAppWidgetManager(app)
        var count = 0
        var error: String? = null
        for (widgetId in widgetIds) {
            runCatching { widget.update(app, manager.getGlanceIdBy(widgetId)) }
                .onSuccess { count++ }
                .onFailure { if (error == null) error = it.message ?: it::class.java.simpleName }
        }
        return ApplyResult(count, error)
    }

    /**
     * 兜底：直接发 `ACTION_APPWIDGET_UPDATE` 广播。
     *
     * Glance 的 receiver 收到后会自己走一遍 `onUpdate → updateAll`。
     * 这条路绕过我们自己的协程和锁，是最后的保险。
     * （余额卡片其实一直在用类似的思路：`fetchAsync` 的 `finally` 里再刷一次。）
     */
    fun broadcastUpdate(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val receivers = listOf(
            ScheduleDayWidgetReceiver::class.java,
            ScheduleWeekWidgetReceiver::class.java
        )
        for (receiver in receivers) {
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(app, receiver))
            }.getOrNull()
            if (ids == null || ids.isEmpty()) continue
            val intent = Intent(app, receiver).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            runCatching { app.sendBroadcast(intent) }
                .onFailure { AppLog.w("ScheduleWidget", "兜底广播失败：${it.message}") }
        }
    }

    /** 取消排队中的推送（退出登录 / 清缓存时用，免得旧设置再推一枪） */
    fun cancelPending() {
        pendingJob?.cancel()
        pendingJob = null
    }
}
