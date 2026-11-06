package cn.aufe.campus.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.aufe.campus.service.KeepAliveManager
import cn.aufe.campus.widget.ClassReminderScheduler
import cn.aufe.campus.widget.ReminderPrefs

/**
 * 「上课提醒」设置弹窗。
 *
 * 从 ProfileScreen 里搬出来的（v1.9.1 把设置类入口收进设置板块）。
 * 搬出来时保持"自己在内部读写 SharedPreferences"的写法：
 * 开关状态不是业务状态，没必要让 ViewModel 知道。
 *
 * 一个坑：Android 13 起发通知要先要 POST_NOTIFICATIONS 权限。
 * 所以打开开关不等于"已开启"——必须授权成功才真正打开，
 * 授权失败就保持关闭，避免用户以为设了提醒、结果什么都没响。
 */
@Composable
fun RemindSettingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ReminderPrefs.PREFS, Context.MODE_PRIVATE)
    }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(ReminderPrefs.KEY_ENABLED, false))
    }
    var advance by remember {
        mutableIntStateOf(prefs.getInt(ReminderPrefs.KEY_ADVANCE, ReminderPrefs.DEFAULT_ADVANCE))
    }
    var pendingEnable by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingEnable) {
            enabled = true
            prefs.edit().putBoolean(ReminderPrefs.KEY_ENABLED, true).apply()
            ClassReminderScheduler.schedule(context)
        }
        pendingEnable = false
    }

    val toggle: (Boolean) -> Unit = { on ->
        if (on) {
            val needPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            if (needPerm) {
                pendingEnable = true
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                enabled = true
                prefs.edit().putBoolean(ReminderPrefs.KEY_ENABLED, true).apply()
                ClassReminderScheduler.schedule(context)
            }
        } else {
            enabled = false
            prefs.edit().putBoolean(ReminderPrefs.KEY_ENABLED, false).apply()
            ClassReminderScheduler.cancel(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上课提醒") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开启上课提醒", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = toggle)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "根据课表数据，在每节课上课前发送通知提醒（需先加载过一次课表）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (enabled) {
                    Spacer(Modifier.height(12.dp))
                    Text("提前提醒时间", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReminderPrefs.ADVANCE_OPTIONS.forEach { m ->
                            FilterChip(
                                selected = advance == m,
                                onClick = {
                                    advance = m
                                    prefs.edit().putInt(ReminderPrefs.KEY_ADVANCE, m).apply()
                                    ClassReminderScheduler.schedule(context)
                                },
                                label = { Text("$m 分钟") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/**
 * 「后台常驻」设置弹窗。
 *
 * 这里的文案经过几轮线上问题打磨：
 * - 明确说"不会偷偷联网"，否则用户会担心耗流量
 * - 熔断时如实提示，避免"明明开了却没生效"的困惑
 */
@Composable
fun KeepAliveDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(KeepAliveManager.isEnabled(context)) }
    // 每次打开重新读系统状态（用户可能刚在系统设置里改过）
    val batteryReady = remember { KeepAliveManager.isIgnoringBatteryOptimizations(context) }
    val broken = remember { KeepAliveManager.isCircuitBroken(context) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权与否都继续：没有通知权限服务照样能跑，只是通知栏看不到常驻提示 */ }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("后台常驻") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保持后台运行", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            enabled = on
                            KeepAliveManager.setEnabled(context, on)
                        }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "开启后通知栏会有一条常驻提示。系统会把本应用当成「正在工作」的应用，" +
                        "上课提醒和桌面小卡片就不容易被深度睡眠推迟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "不会在后台偷偷联网：只做本机提醒判断和小组件刷新，不消耗流量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (broken) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "上次尝试启动时被系统拦下了，为避免反复弹错误已自动关闭。" +
                            "重新打开开关即可再试一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("电池优化", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (batteryReady) "已加入白名单 · 后台不会被打断"
                            else "未加入白名单 · 息屏久了仍可能被冻结",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (batteryReady) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    if (!batteryReady) {
                        TextButton(onClick = {
                            KeepAliveManager.requestIgnoreBatteryOptimizations(context)
                        }) { Text("去设置") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}
