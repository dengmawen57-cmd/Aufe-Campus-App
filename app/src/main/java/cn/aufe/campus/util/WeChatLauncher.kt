package cn.aufe.campus.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * 把任意网页**交给微信打开**，绝不落到外部浏览器。
 *
 * ## 为什么不能一步到位（重要，别"优化"成一条 Intent）
 *
 * 微信没有对外开放「用内置浏览器打开指定网址」的接口。外面对微信可用的 scheme
 * （`weixin://`）只有小程序短链 `weixin://dl/business/?t=`、朋友圈/扫一扫等固定入口，
 * 都需要微信开放平台的 appid + 服务端（这个 H5 没有），硬拼 scheme 的后果是：
 * 微信被唤起、停在首页、网址被丢掉 —— 比失败更让人困惑。
 *
 * 所以这里走两级，**两级都停留在微信内**：
 *
 * 1. `ACTION_VIEW` + `setPackage("com.tencent.mm")` —— 少数微信版本声明了
 *    http/https 的 VIEW，声明了就能直接进微信浏览器。没声明时 `resolveActivity`
 *    返回 null，我们连试都不试（不会出现"弹系统选择框让你选浏览器"）。
 * 2. 退到 `ACTION_SEND` + `setPackage("com.tencent.mm")` —— 微信的「选择聊天」
 *    面板。链接发给自己（建议文件传输助手）后点开，就是在微信里打开。
 *
 * 关键是**全程 setPackage**：不 setPackage 时系统会弹分享面板，里面有浏览器、
 * 有 QQ、有邮件，那就违反"只能跳转微信"了。
 *
 * ## Android 11 的包可见性
 *
 * targetSdk 30 起默认看不见别的 App，不自查就 `getPackageInfo` 直接抛
 * NameNotFoundException —— 表现出来的现象是"明明装了微信却提示未安装"。
 * 对应清单里的 `<queries><package android:name="com.tencent.mm" />`，
 * 删了那条声明这个类就全哑。
 */
object WeChatLauncher {

    const val WX_PACKAGE = "com.tencent.mm"

    private const val TAG = "WeChatLauncher"

    /** 跳转结果。调用方拿它决定提示什么，不在本类里弹 Toast，方便以后换 Snackbar */
    sealed class Outcome {
        /** 微信直接打开了这个链接（微信内置浏览器） */
        object Opened : Outcome()

        /** 已进入微信的"选择聊天"面板，用户发给自己后点开即可 */
        object HandedToShare : Outcome()

        /** 手机上没有微信 */
        object NotInstalled : Outcome()

        /** 微信装了但不接受这次请求 / 启动被系统拦下 */
        class Failed(val detail: String) : Outcome()
    }

    /** 微信是否已安装（必须配合清单里的 queries，否则 Android 11+ 恒为 false） */
    fun isInstalled(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                WX_PACKAGE, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(WX_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    /**
     * 在微信里打开 [url]。
     *
     * 会阻塞地做 2 次 resolve + 1 次 startActivity，耗时在毫秒级，
     * 但仍然是主线程可接受的范围（不涉及 IPC 网络）。不要在后台线程调用，
     * 因为 `startActivity` 从后台发送在 Android 10+ 会被拦截。
     */
    fun open(context: Context, url: String): Outcome {
        if (url.isBlank()) return Outcome.Failed("链接为空")
        if (!isInstalled(context)) {
            AppLog.w(TAG, "未检测到微信，放弃跳转")
            return Outcome.NotInstalled
        }

        // ---------- 第一级：直接 VIEW，锁定微信包名 ----------
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(WX_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (canResolve(context, viewIntent)) {
            runCatching { context.startActivity(viewIntent) }
                .onSuccess {
                    AppLog.i(TAG, "微信声明了 VIEW，直接打开：$url")
                    return Outcome.Opened
                }
                .onFailure { AppLog.w(TAG, "VIEW 启动失败：${it.message}") }
        } else {
            AppLog.i(TAG, "当前微信不接受 VIEW 链接，改走分享路径")
        }

        // ---------- 第二级：分享到微信（仍然只认微信） ----------
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "安财校园网")
            putExtra(Intent.EXTRA_TEXT, url)
            setPackage(WX_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolve(context, shareIntent)) {
            AppLog.e(TAG, "微信既不能 VIEW 也不能 SEND", null)
            return Outcome.Failed("微信没有可用的打开入口")
        }
        return runCatching { context.startActivity(shareIntent) }
            .map { Outcome.HandedToShare }
            .getOrElse {
                AppLog.e(TAG, "分享启动失败", it)
                Outcome.Failed(it.message ?: it::class.java.simpleName)
            }
    }

    /**
     * 是否真的有 Activity 接得住。
     *
     * 只 setPackage 不判断就直接 startActivity 的话，接不住会抛
     * ActivityNotFoundException，那一瞬间的 ANR 长尾比先问一句更贵。
     */
    private fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent, PackageManager.ResolveInfoFlags.of(0)
            ) != null
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0) != null
        }
    }.getOrDefault(false)

    /** 统一的提示文案。分享路径要教用户怎么点，否则他会以为"点了没反应" */
    fun toastFor(context: Context, outcome: Outcome) {
        val (text, long) = when (outcome) {
            is Outcome.Opened -> "正在微信中打开校园网…" to false
            is Outcome.HandedToShare ->
                "已跳到微信：选「文件传输助手」发送，再点开链接即可（微信外的浏览器打不开校园网）" to true
            is Outcome.NotInstalled -> "没装微信，校园网需要在微信里打开" to true
            is Outcome.Failed -> "跳转微信失败：${outcome.detail}" to true
        }
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
