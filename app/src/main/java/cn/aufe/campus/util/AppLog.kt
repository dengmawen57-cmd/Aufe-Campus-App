package cn.aufe.campus.util

import android.content.Context
import cn.aufe.campus.data.local.AppearancePrefs
import cn.aufe.campus.service.KeepAliveManager
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 运行事件日志（内存环形缓冲）。
 *
 * 为什么不做成写文件的 logger：本项目不需要长时间追溯，用户真正要的
 * 是"出问题时能把现场发给我"。所以只在内存里留最近 [MAX] 条，
 * 点「导出日志」时一次性拼成文本。零 IO 开销，也不用担心日志文件
 * 把用户存储占满。
 *
 * 进程被系统杀掉时这些记录会丢——但崩溃堆栈有 [CrashLogger] 单独写盘，
 * 两者结合足够定位问题。
 *
 * 只在**关键节点**打点（登录、接口失败、外观切换、服务状态），
 * 不做逐请求记录，避免把日志写成噪音。
 */
object AppLog {

    private const val MAX = 400

    private val buffer = ArrayDeque<String>()

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @Synchronized
    fun i(tag: String, msg: String) = add("I", tag, msg)

    @Synchronized
    fun w(tag: String, msg: String) = add("W", tag, msg)

    @Synchronized
    fun e(tag: String, msg: String, error: Throwable? = null) {
        val detail = error?.let { "${it::class.java.simpleName}: ${it.message}" }
        add("E", tag, if (detail != null) "$msg | $detail" else msg)
    }

    private fun add(level: String, tag: String, msg: String) {
        if (buffer.size >= MAX) buffer.removeFirst()
        buffer.addLast("${timeFmt.format(Date())} $level/$tag: $msg")
    }

    @Synchronized
    fun clear() = buffer.clear()

    /** 导出用：把内存记录 + 环境信息拼成可直接阅读的文本 */
    fun buildReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("安财校园通 · 诊断日志")
        sb.appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
        sb.appendLine()
        sb.appendLine("========== 环境 ==========")
        sb.appendLine("应用版本：${appVersion(context)}")
        sb.appendLine("Android：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("机型：${android.os.Build.BRAND} ${android.os.Build.MODEL}")
        sb.appendLine("ABI：${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()
        sb.appendLine("========== 外观与设置 ==========")
        sb.appendLine("主题色：${AppearancePrefs.themeId(context)}")
        sb.appendLine("背景图：${AppearancePrefs.bgUri(context) ?: "未设置"}")
        sb.appendLine("虚化：${AppearancePrefs.bgBlur(context)} / 压暗：${AppearancePrefs.bgDim(context)}%")
        sb.appendLine("应用图标：${AppearancePrefs.iconId(context)}")
        sb.appendLine()
        sb.appendLine("========== 登录状态 ==========")
        // 只写状态，不写账号密码（见 AuthManager.statusSummary 的注释）
        sb.appendLine(
            runCatching {
                (context.applicationContext as? cn.aufe.campus.CampusApp)
                    ?.authManager?.statusSummary()
            }.getOrNull().orEmpty().ifBlank { "(读取失败)" }
        )
        sb.appendLine()
        sb.appendLine("========== 后台常驻 ==========")
        sb.appendLine("开关：${if (KeepAliveManager.isEnabled(context)) "开" else "关"}")
        sb.appendLine("已熔断：${if (KeepAliveManager.isCircuitBroken(context)) "是（连续启动失败已自动关闭）" else "否"}")
        sb.appendLine("电池优化白名单：${if (KeepAliveManager.isIgnoringBatteryOptimizations(context)) "已加入" else "未加入"}")
        sb.appendLine()
        sb.appendLine("========== 运行事件（最近 ${buffer.size} 条）==========")
        synchronized(this) {
            if (buffer.isEmpty()) sb.appendLine("(无记录)") else buffer.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("========== 最近一次崩溃 ==========")
        val crash = CrashLogger.lastCrash(context)
        sb.appendLine(crash.ifBlank { "(无崩溃记录)" })
        return sb.toString()
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName}($code)"
    }.getOrDefault("unknown")
}
