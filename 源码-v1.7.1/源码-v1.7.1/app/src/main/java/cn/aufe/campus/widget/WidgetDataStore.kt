package cn.aufe.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cn.aufe.campus.MainActivity
import cn.aufe.campus.data.model.SelectedCourse
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桌面小卡片数据存储：
 * - 课表加载成功后由 ViewModel 写入，供 ScheduleWidgetProvider 读取
 * - 课表变化 / 定时更新时广播刷新小卡片
 */
object WidgetDataStore {

    private const val PREFS = "aufe_widget"
    private const val KEY_COURSES = "courses_json"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_BALANCE = "ykt_balance"
    private const val KEY_BALANCE_AT = "ykt_balance_at"

    data class WidgetSlot(
        val day: Int,
        val startSession: Int,
        val sessionCount: Int,
        val weekDesc: String,
        val place: String
    )

    data class WidgetCourse(
        val courseNo: String,
        val courseName: String,
        val credit: Double,
        val slots: List<WidgetSlot>,
        val teacher: String = ""
    )

    fun save(context: Context, courses: List<SelectedCourse>) {
        val arr = JSONArray()
        courses.forEach { c ->
            val o = JSONObject()
            o.put("courseNo", c.courseNo)
            o.put("courseName", c.courseName)
            o.put("credit", c.credit)
            o.put("teacher", c.teacher)
            val slots = JSONArray()
            c.slots.forEach { s ->
                val so = JSONObject()
                so.put("day", s.day)
                so.put("startSession", s.startSession)
                so.put("sessionCount", s.sessionCount)
                so.put("weekDesc", s.weekDesc)
                so.put("place", s.placeText)
                slots.put(so)
            }
            o.put("slots", slots)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COURSES, arr.toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): List<WidgetCourse> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COURSES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<WidgetCourse>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slots = mutableListOf<WidgetSlot>()
                val sa = o.optJSONArray("slots") ?: JSONArray()
                for (j in 0 until sa.length()) {
                    val so = sa.optJSONObject(j) ?: continue
                    slots.add(
                        WidgetSlot(
                            day = so.optInt("day"),
                            startSession = so.optInt("startSession"),
                            sessionCount = so.optInt("sessionCount", 1),
                            weekDesc = so.optString("weekDesc"),
                            place = so.optString("place")
                        )
                    )
                }
                result.add(
                    WidgetCourse(
                        courseNo = o.optString("courseNo"),
                        courseName = o.optString("courseName"),
                        credit = o.optDouble("credit"),
                        slots = slots,
                        teacher = o.optString("teacher")
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- 一卡通余额（供余额桌面小卡片使用） ----------

    /** 保存余额及更新时间 */
    fun saveBalance(context: Context, balance: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_BALANCE, balance.toFloat())
            .putLong(KEY_BALANCE_AT, System.currentTimeMillis())
            .apply()
    }

    /** 读取缓存余额：返回 (余额, 更新时间戳)，无缓存时余额为 null */
    fun loadBalance(context: Context): Pair<Double?, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BALANCE)) return null to 0L
        return prefs.getFloat(KEY_BALANCE, 0f).toDouble() to prefs.getLong(KEY_BALANCE_AT, 0L)
    }

    /**
     * 缓存余额是否需要重新拉取。
     * 判定：无缓存，或缓存已超过 [STALE_MS]（默认 30 分钟，与小卡片更新周期一致）。
     * 这样即使余额缓存非空但很久没更新（长时间未登录），小卡片也会主动刷新。
     */
    fun isBalanceStale(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val (balance, at) = loadBalance(context)
        if (balance == null || at <= 0L) return true
        return now - at > STALE_MS
    }

    /** 余额缓存有效期（毫秒） */
    private const val STALE_MS = 30 * 60 * 1000L

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_COURSES)
            .remove(KEY_BALANCE)
            .remove(KEY_BALANCE_AT)
            .apply()
    }

    /** 刷新课表小组件（Glance：今日课程 + 一周课表） */
    fun refreshWidgets(context: Context) = ScheduleWidgetSync.requestUpdate(context)

    /** 广播通知一卡通余额小卡片刷新 */
    fun refreshYktWidgets(context: Context) = broadcast(context, YktWidgetProvider::class.java)

    /** 刷新全部小卡片 */
    fun refreshAll(context: Context) {
        refreshWidgets(context)
        refreshYktWidgets(context)
    }

    private fun broadcast(context: Context, provider: Class<*>) {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, provider))
        if (ids.isEmpty()) return
        val intent = Intent(context, provider).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }

    /** 点击小卡片打开 App 的 Intent */
    fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
