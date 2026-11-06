package cn.aufe.campus.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import cn.aufe.campus.data.local.AppearancePrefs

/**
 * 应用图标切换。
 *
 * ## 原理
 *
 * Android 没有"运行时改 launcher 图标"的 API。标准做法是在 manifest 里
 * 为每个图标声明一个 `<activity-alias>`，它们都指向同一个 MainActivity、
 * 但各带不同的 `android:icon`。切换时用 `PackageManager.`
 * `setComponentEnabledSetting` 把目标 alias 设为 ENABLED、
 * 其余设为 DISABLED。桌面上就会认为"应用换了张脸"。
 *
 * ## 三个必须注意的坑
 *
 * 1. **必须恰好保留一个启用项**。全都 DISABLED 会让应用在桌面彻底消失，
 *    用户只能去系统设置里找。所以 [apply] 里做完切换会再自检一次。
 * 2. **默认项要最后禁用**。如果先把默认项关掉，系统可能在某些 ROM 上
 *    立刻把快捷方式摘掉，再启用新项时桌面残留两个图标。顺序是
 *    "先开新的、再关旧的"。
 * 3. **切换后 Activity 会重启**。这是系统行为（组件状态变了），
 *    所以要在切换前提示用户，并尽量在用户"确认"后再执行。
 */
object IconSwitcher {

    /** 可选图标：id → activity-alias 组件名后缀（与 manifest 保持一致） */
    data class IconOption(val id: String, val name: String, val desc: String, val alias: String)

    val options = listOf(
        IconOption("default", "经典蓝", "原本的校徽蓝，最不突兀", "IconDefault"),
        IconOption("sakura", "樱花少女", "二次元粉紫调，樱花与制服", "IconSakura"),
        IconOption("starry", "星夜书卷", "深蓝星空 + 发光书页", "IconStarry"),
        IconOption("cat", "猫耳学士", "橘猫戴学士帽，暖橙配色", "IconCat"),
        IconOption("aurora", "极光几何", "青紫渐变，抽象书本轮廓", "IconAurora"),
        IconOption("ink", "水墨青竹", "国风水墨，青绿竹叶", "IconInk")
    )

    private fun aliasClass(alias: String) = "cn.aufe.campus.$alias"

    private fun component(context: Context, alias: String) =
        ComponentName(context.packageName, aliasClass(alias))

    /** 当前生效的图标 id（以本地设置为准；实际组件状态由系统持有） */
    fun current(context: Context): String = AppearancePrefs.iconId(context)

    /**
     * 切换图标。
     *
     * @return true 表示已提交（Activity 可能被系统重启）
     */
    fun apply(context: Context, targetId: String): Boolean {
        val target = options.firstOrNull { it.id == targetId } ?: return false
        val pm = context.packageManager
        return runCatching {
            // 1) 先启用目标
            pm.setComponentEnabledSetting(
                component(context, target.alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // 2) 再禁用其它（默认项最后处理，避免中间状态桌面无图标）
            val others = options.filter { it.id != targetId }
                .sortedBy { if (it.id == AppearancePrefs.DEFAULT_ICON) 1 else 0 }
            others.forEach { opt ->
                pm.setComponentEnabledSetting(
                    component(context, opt.alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            AppearancePrefs.setIconId(context, targetId)
            AppLog.i("IconSwitcher", "切换图标 → ${target.id}")
            true
        }.getOrDefault(false)
    }

    /**
     * 自检并修复：若所有 alias 都被禁用（极端情况，比如上次切换中途被杀），
     * 把默认图标重新启用，避免应用从桌面消失。
     */
    fun repairIfNeeded(context: Context) {
        runCatching {
            val pm = context.packageManager
            val enabled = options.count { opt ->
                pm.getComponentEnabledSetting(component(context, opt.alias)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (enabled == 0) {
                pm.setComponentEnabledSetting(
                    component(context, "IconDefault"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                AppLog.w("IconSwitcher", "检测到无启用图标，已恢复默认")
            }
        }
    }
}
