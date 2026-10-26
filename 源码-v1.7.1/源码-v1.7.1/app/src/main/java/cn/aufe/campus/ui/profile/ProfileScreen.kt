package cn.aufe.campus.ui.profile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.widget.ClassReminderScheduler
import cn.aufe.campus.widget.ReminderPrefs

@Composable
fun ProfileScreen(
    vm: CampusViewModel,
    onLogout: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenScoreQuery: () -> Unit,
    onOpenGpa: () -> Unit = {},
    onOpenYkt: () -> Unit,
    onOpenElectric: () -> Unit,
) {
    val score by vm.score.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val user = score.report
    var showLogout by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showRemind by remember { mutableStateOf(false) }

    // ---------- 上课提醒设置 ----------
    val context = LocalContext.current
    // 版本号从包信息读，避免每次发版忘了改文案
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val notifyPrefs = remember {
        context.getSharedPreferences(ReminderPrefs.PREFS, Context.MODE_PRIVATE)
    }
    var remindEnabled by remember {
        mutableStateOf(notifyPrefs.getBoolean(ReminderPrefs.KEY_ENABLED, false))
    }
    var remindAdvance by remember {
        mutableIntStateOf(
            notifyPrefs.getInt(ReminderPrefs.KEY_ADVANCE, ReminderPrefs.DEFAULT_ADVANCE)
        )
    }
    var pendingEnableAfterPerm by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingEnableAfterPerm) {
            remindEnabled = true
            notifyPrefs.edit().putBoolean(ReminderPrefs.KEY_ENABLED, true).apply()
            ClassReminderScheduler.schedule(context)
        }
        pendingEnableAfterPerm = false
    }
    val setRemindEnabled: (Boolean) -> Unit = { on ->
        if (on) {
            val needPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needPerm) {
                pendingEnableAfterPerm = true
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                remindEnabled = true
                notifyPrefs.edit().putBoolean(ReminderPrefs.KEY_ENABLED, true).apply()
                ClassReminderScheduler.schedule(context)
            }
        } else {
            remindEnabled = false
            notifyPrefs.edit().putBoolean(ReminderPrefs.KEY_ENABLED, false).apply()
            ClassReminderScheduler.cancel(context)
        }
    }

    // 尝试加载用户信息（若尚未加载）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (score.report == null && !score.loaded) vm.loadScore()
        if (!schedule.loaded) vm.loadSchedule()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 头部
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            cn.aufe.campus.ui.theme.AufeBlueDark
                        )
                    )
                )
                .padding(top = 52.dp, bottom = 28.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Person, null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        user?.userName?.ifBlank { "同学" } ?: "同学",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            user?.userNo?.ifBlank { null },
                            user?.className?.ifBlank { null },
                            user?.college?.ifBlank { null }
                        ).joinToString(" · ").ifBlank { "安徽财经大学" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 数据概览
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Stat("${schedule.courses.size}", "本学期课程", Modifier.weight(1f))
                    Stat(
                        "${score.report?.totalScore?.let { String.format(java.util.Locale.CHINA, "%.1f", it) } ?: "—"}",
                        "二课总分",
                        Modifier.weight(1f)
                    )
                    Stat(
                        "${schedule.courses.sumOf { it.credit }.let { if (it == it.toLong().toDouble()) it.toLong() else it }}",
                        "总学分",
                        Modifier.weight(1f)
                    )
                }
            }

            MenuItem(
                icon = Icons.Filled.AccountTree,
                title = "我的培养方案",
                subtitle = "查看课组学分完成情况",
                onClick = onOpenPlan
            )
            MenuItem(
                icon = Icons.Filled.Grade,
                title = "成绩查询",
                subtitle = "本科教务系统 · 全部成绩与学分明细",
                onClick = onOpenScoreQuery
            )
            MenuItem(
                icon = Icons.Filled.Insights,
                title = "绩点查询",
                subtitle = "平均学分绩点 · 学期趋势与绩点分布",
                onClick = onOpenGpa
            )
            MenuItem(
                icon = Icons.Filled.CreditCard,
                title = "一卡通",
                subtitle = "校园卡余额与消费明细",
                onClick = onOpenYkt
            )
            MenuItem(
                icon = Icons.Filled.Bolt,
                title = "宿舍电费",
                subtitle = "剩余电量 · 用电与缴费记录",
                onClick = onOpenElectric
            )
            MenuItem(
                icon = Icons.Filled.NotificationsActive,
                title = "上课提醒",
                subtitle = if (remindEnabled) "已开启 · 提前 ${remindAdvance} 分钟提醒"
                else "未开启 · 点击设置提前提醒时间",
                onClick = { showRemind = true }
            )
            MenuItem(
                icon = Icons.Filled.Info,
                title = "关于应用",
                subtitle = "安财校园通 v$appVersion",
                onClick = { showAbout = true }
            )
            MenuItem(
                icon = Icons.Filled.Logout,
                title = "退出登录",
                subtitle = "清除本机保存的账号会话",
                danger = true,
                onClick = { showLogout = true }
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "安财校园通 · 数据来自学校官方系统\n仅用于个人学习查询，请遵守学校规定",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }

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

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于应用") },
            text = {
                Column {
                    Text("安财校园通 v1.5.1")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本应用由26信工1班邓马文和mawenbot共同完成",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "聚合课程表、选课、成绩查询、第二课堂成绩单等校园数据，" +
                            "所有信息仅存储于本机。数据来自学校官方系统，请遵守学校规定使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:1964689817@qq.com")
                                putExtra(Intent.EXTRA_SUBJECT, "安财校园通问题反馈")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            "问题反馈邮箱：1964689817@qq.com",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            }
        )
    }

    if (showRemind) {
        AlertDialog(
            onDismissRequest = { showRemind = false },
            title = { Text("上课提醒") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开启上课提醒", modifier = Modifier.weight(1f))
                        Switch(checked = remindEnabled, onCheckedChange = setRemindEnabled)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "根据课表数据，在每节课上课前发送通知提醒。" +
                            "需先打开过一次课表页（数据同步后生效）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (remindEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Text("提前提醒时间", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReminderPrefs.ADVANCE_OPTIONS.forEach { m ->
                                FilterChip(
                                    selected = remindAdvance == m,
                                    onClick = {
                                        remindAdvance = m
                                        notifyPrefs.edit()
                                            .putInt(ReminderPrefs.KEY_ADVANCE, m)
                                            .apply()
                                    },
                                    label = { Text("$m 分钟") }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRemind = false }) { Text("完成") }
            }
        )
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MenuItem(
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
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        if (danger) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = if (danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
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
