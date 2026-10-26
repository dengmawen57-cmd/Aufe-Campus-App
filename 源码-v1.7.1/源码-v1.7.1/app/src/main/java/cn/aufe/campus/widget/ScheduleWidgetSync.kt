package cn.aufe.campus.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import cn.aufe.campus.data.model.SelectedCourse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 课表小组件同步（对齐 LoveACE 的 WidgetSyncHelper）。
 *
 * 与参考实现的差异：安财的课表由 App 内仓库层拉取，小组件进程不联网，
 * 因此这里只负责「落盘 + 通知 Glance 重建」，数据拉取仍在 ViewModel 里完成。
 */
object ScheduleWidgetSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 课表加载完成后调用：写缓存并刷新两个小组件 */
    fun sync(context: Context, courses: List<SelectedCourse>) {
        WidgetDataStore.save(context, courses)
        requestUpdate(context)
    }

    /** 仅请求重建（数据未变，例如跨天/回前台时） */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { ScheduleDayWidget().updateAll(app) }
            runCatching { ScheduleWeekWidget().updateAll(app) }
        }
    }
}
