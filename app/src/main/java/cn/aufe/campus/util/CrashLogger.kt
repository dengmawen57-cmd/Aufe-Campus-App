package cn.aufe.campus.util

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简崩溃记录器。
 *
 * 为什么需要：Android 的「应用已停止运行」弹窗一消失，现场就没了。
 * v1.8.0 的连续崩溃只能靠用户口述现象反推，绕了很大弯子。这里把最近一次
 * 崩溃的堆栈写到应用私有目录，下次排查可以直接读文件。
 *
 * 设计上的三条底线，确保它自己**绝不会**成为新的崩溃源：
 * 1. 全部逻辑包 `runCatching`，任何异常静默丢弃；
 * 2. 用 `AtomicBoolean` 防止写日志过程中再次崩溃导致无限递归；
 * 3. 写完**必须**把异常交回原 handler，否则系统不会正常终止进程，
 *    会表现为一闪而过的 ANR，反而更难查。
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"

    private val writing = AtomicBoolean(false)

    fun install(context: Context) {
        val app = context.applicationContext
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                if (writing.compareAndSet(false, true)) {
                    runCatching { writeFile(app, thread.name, error) }
                }
                // 一定要交回系统，保证正常终止 + 弹出系统崩溃提示
                runCatching {
                    if (previous != null) {
                        previous.uncaughtException(thread, error)
                    } else {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }
        }
    }

    private fun writeFile(app: Context, threadName: String, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val text = buildString {
            appendLine("time=${System.currentTimeMillis()}")
            appendLine("appVersion=${appVersion(app)}")
            appendLine("thread=$threadName")
            appendLine()
            append(sw.toString())
        }
        runCatching {
            app.filesDir.resolve(FILE_NAME).writeText(text)
        }.onFailure {
            // 文件写不进去就算了，绝不再抛
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        // longVersionCode 是 API 28 才有的，minSdk 24 要走老字段
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName}($code)"
    }.getOrDefault("unknown")

    /** 读取最近一次崩溃（供诊断页/导出使用），没有则返回空串 */
    fun lastCrash(context: Context): String = runCatching {
        context.filesDir.resolve(FILE_NAME).takeIf { it.exists() }?.readText().orEmpty()
    }.getOrDefault("")

    /** 清除记录（用户点"我知道了"或反馈完成后调用） */
    fun clear(context: Context) {
        runCatching { context.filesDir.resolve(FILE_NAME).delete() }
    }
}
