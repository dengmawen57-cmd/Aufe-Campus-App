package cn.aufe.campus.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 界面自定义：记录每个功能入口是否显示。
 *
 * ## 为什么用 SharedPreferences + 一个 version 流
 *
 * 界面自定义只需要存"开/关"这种布尔值，SharedPreferences 足够，
 * 没必要为此引入 DataStore。但是 Compose 需要感知变化才能重组 ——
 * 所以每次写入时把 [version] 加一，页面 `collectAsState()` 它，
 * 就能在用户拨完开关后立刻更新列表，不用返回上一层再进来。
 *
 * ## 核心功能不提供开关
 *
 * 课表、选课、成绩单这三个是底部 tab，藏掉了 App 就空了；
 * 它们不在 [toggleable] 里，界面自定义页也只列出可选项。
 */
object FeaturePrefs {

    const val PREFS = "aufe_features"

    // 可隐藏的功能
    const val COURSE_QUERY = "course_query"   // 开课查询
    const val EXAM = "exam"                   // 考试安排
    const val REPAIR = "repair"               // 零星维修
    /**
     * v1.11.2 起不再作为独立入口：旧版「竞赛获奖记录（网页版）」与「学科竞赛」重复，
     * 已并入后者（原生列表 + 右上角网页版）。常量保留只为兼容已写入的旧偏好。
     */
    @Deprecated("已并入 COMPETITION_HUB")
    const val COMPETITION = "competition"
    const val EVALUATION = "evaluation"       // 学生评教
    const val YKT = "ykt"                     // 一卡通
    const val ELECTRIC = "electric"           // 宿舍电费
    const val PLAN = "plan"                   // 培养方案
    const val GPA = "gpa"                     // 绩点查询
    const val SCORE_QUERY = "score_query"     // 成绩查询
    const val CAMPUS_NET = "campus_net"       // 校园网（v1.9.7，跳微信打开）
    const val COMPETITION_HUB = "competition_hub" // 学科竞赛（v1.9.9，WebView 承载）
    const val SMART_CAMPUS = "smart_campus"   // 智慧校园（v1.11.3，网页版承载）
    const val PARCEL = "parcel"               // 取件码（v1.12.0，淘宝/拼多多身份码）

    /** 全部可配置项（顺序即设置页里的显示顺序） */
    val toggleable: List<String> = listOf(
        COURSE_QUERY, EXAM, EVALUATION, COMPETITION_HUB, SMART_CAMPUS, PARCEL, REPAIR,
        YKT, ELECTRIC, CAMPUS_NET, PLAN, GPA, SCORE_QUERY
    )

    /** 变更信号：写入后 +1，页面靠它触发重组 */
    val version = MutableStateFlow(0)

    /** 某个功能是否显示（默认全显示） */
    fun isVisible(context: Context, id: String): Boolean =
        sp(context).getBoolean(id, true)

    fun setVisible(context: Context, id: String, visible: Boolean) {
        sp(context).edit().putBoolean(id, visible).apply()
        version.value += 1
    }

    /** 一键全部显示（"恢复默认"） */
    fun showAll(context: Context) {
        val editor = sp(context).edit()
        toggleable.forEach { editor.putBoolean(it, true) }
        editor.apply()
        version.value += 1
    }

    /** 隐藏了几个（设置页显示"已隐藏 N 项"） */
    fun hiddenCount(context: Context): Int = toggleable.count { !isVisible(context, it) }

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
