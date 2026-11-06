package cn.aufe.campus.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.local.FeaturePrefs
import cn.aufe.campus.ui.components.FeatureScaffold
import cn.aufe.campus.widget.ScheduleWidgetConfigActivity
import cn.aufe.campus.widget.YktWidgetConfigActivity

/**
 * 设置板块（v1.9.1 新增）。
 *
 * 背景：以前「上课提醒」「后台常驻」「外观与工具」「关于」「退出登录」
 * 全跟在「我的」页面的一串功能入口里，一眼看不出哪些是"功能"、哪些是"设置"。
 * 现在所有设置项收进这一页，我的页面只留真正的功能入口。
 *
 * 分组逻辑按"用户想干什么"：
 * - 界面：长什么样（自定义入口、外观）
 * - 通知与后台：怎么提醒我、能不能一直跑
 * - 桌面小卡片：独立的一块（有自己的配置页）
 * - 其他：关于、退出
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenFeatureCustomize: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    // 隐藏了几项：读 version 触发重组，用户拨完开关回来数字就是新的
    val version by FeaturePrefs.version.collectAsState()
    val hidden = remember(version) { FeaturePrefs.hiddenCount(context) }

    var showRemind by remember { mutableStateOf(false) }
    var showKeepAlive by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }

    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    FeatureScaffold(title = "设置", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionTitle("界面") }
            item {
                SettingItem(
                    icon = Icons.Filled.Tune,
                    title = "界面自定义",
                    subtitle = if (hidden == 0) "所有功能都显示中" else "已隐藏 $hidden 个功能入口",
                    onClick = onOpenFeatureCustomize
                )
            }
            item {
                SettingItem(
                    icon = Icons.Filled.Palette,
                    title = "外观与工具",
                    subtitle = "主题色 · 背景图 · 应用图标",
                    onClick = onOpenAppearance
                )
            }

            item { SectionTitle("通知与后台") }
            item {
                SettingItem(
                    icon = Icons.Filled.NotificationsActive,
                    title = "上课提醒",
                    subtitle = "按课表在上课前推送通知",
                    onClick = { showRemind = true }
                )
            }
            item {
                SettingItem(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "后台常驻",
                    subtitle = "减少系统冻结，提醒和小组件更准时",
                    onClick = { showKeepAlive = true }
                )
            }

            item { SectionTitle("桌面小卡片") }
            item {
                SettingItem(
                    icon = Icons.Filled.Widgets,
                    title = "余额小卡片",
                    subtitle = "背景图 · 文字颜色 · 压暗程度",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(context, YktWidgetConfigActivity::class.java)
                            )
                        }
                    }
                )
            }
            item {
                SettingItem(
                    icon = Icons.Filled.CalendarMonth,
                    title = "课程表小卡片",
                    subtitle = "背景图 · 文字颜色 · 压暗程度（今日 / 一周共用）",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(context, ScheduleWidgetConfigActivity::class.java)
                            )
                        }
                    }
                )
            }

            item { SectionTitle("其他") }
            item {
                SettingItem(
                    icon = Icons.Filled.Info,
                    title = "关于应用",
                    subtitle = "版本信息 · 诊断日志 · 问题反馈（v$appVersion）",
                    onClick = onOpenAbout
                )
            }
            item {
                SettingItem(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "退出登录",
                    subtitle = "清除本机保存的账号会话",
                    danger = true,
                    onClick = { showLogout = true }
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    "安财校园通 · 数据来自学校官方系统\n仅用于个人学习查询，请遵守学校规定",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    if (showRemind) RemindSettingDialog(onDismiss = { showRemind = false })
    if (showKeepAlive) KeepAliveDialog(onDismiss = { showKeepAlive = false })

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后将清除本机保存的登录会话，下次打开需要重新登录。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    onLogout()
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("取消") }
            }
        )
    }
}


@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

/** 设置条目：图标 + 标题 + 说明 + 箭头，整块可点 */
@Composable
internal fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, null,
                tint = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
