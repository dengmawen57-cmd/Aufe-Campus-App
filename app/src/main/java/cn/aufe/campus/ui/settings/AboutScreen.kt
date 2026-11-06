package cn.aufe.campus.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import cn.aufe.campus.ui.components.FeatureScaffold
import cn.aufe.campus.util.CompetitionProbe
import cn.aufe.campus.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「关于」页（v1.11.2）。
 *
 * ## 为什么把日志和页面采集搬到这里
 *
 * 这两块本质上是**排查问题**时才用的东西，以前混在「外观与工具」里：
 * 想换个主题色的人会先看到"诊断日志""页面结构采集"两个看不懂的按钮，
 * 而真想反馈问题的人又想不到要去"外观"里找。
 *
 * 现在按"用户想干什么"重新归位：
 * - 「外观与工具」只留**能看见外观变化**的东西（主题色 / 背景 / 图标）
 * - 「关于」= 这个应用是谁做的 + 出问题了怎么找我（日志、采集、邮箱）
 *
 * 页面采集保留在这里而不是藏进 debug 构建：它只读页面、只切标签，
 * 不提交任何东西，普通用户误点也不会造成影响。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var probing by remember { mutableStateOf(false) }
    var probeMsg by remember { mutableStateOf("") }

    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    FeatureScaffold(title = "关于", subtitle = "安财校园通 v$appVersion", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeroCard(appVersion) }

            item { GroupTitle("出问题了？先导一份日志") }

            // ---------- 诊断日志 ----------
            item {
                AboutCard(
                    icon = Icons.Filled.Description,
                    title = "诊断日志",
                    subtitle = "版本、机型、近期操作和崩溃堆栈。不含密码，但会带课程名与接口路径"
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                val path = withContext(Dispatchers.IO) {
                                    LogExporter.saveToDownloads(context)
                                }
                                Toast.makeText(
                                    context,
                                    if (path.isBlank()) "导出失败，请检查存储空间" else "已导出：$path",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }) {
                            Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("保存到下载")
                        }
                        TextButton(onClick = {
                            val ok = LogExporter.share(context)
                            if (!ok) Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("直接分享")
                        }
                    }
                }
            }

            // ---------- 学科竞赛页面结构采集 ----------
            item {
                AboutCard(
                    icon = Icons.Filled.BugReport,
                    title = "学科竞赛页面结构采集",
                    subtitle = "把校内竞赛平台的四个标签页抓下来打包，用来修原生界面。只读页面、只切标签"
                ) {
                    TextButton(
                        enabled = !probing,
                        onClick = {
                            if (probing) return@TextButton
                            probing = true
                            probeMsg = "正在准备…"
                            scope.launch {
                                val r = CompetitionProbe.collect(context) { probeMsg = it }
                                probing = false
                                probeMsg = ""
                                if (r.ok) {
                                    Toast.makeText(context, "已采集：${r.message}", Toast.LENGTH_SHORT).show()
                                    CompetitionProbe.share(context, r.zipFile!!)
                                } else {
                                    Toast.makeText(context, "采集失败：${r.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (probing) probeMsg.ifBlank { "正在采集…" } else "采集并分享")
                    }
                }
            }

            item { GroupTitle("反馈与说明") }

            // ---------- 反馈邮箱 ----------
            item {
                AboutCard(
                    icon = Icons.Filled.Mail,
                    title = "问题反馈",
                    subtitle = "1964689817@qq.com · 附上诊断日志能省很多来回"
                ) {
                    TextButton(
                        contentPadding = PaddingValues(0.dp),
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:1964689817@qq.com")
                                putExtra(Intent.EXTRA_SUBJECT, "安财校园通问题反馈")
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    ) { Text("发送邮件") }
                }
            }

            // ---------- 免责声明 ----------
            item {
                AboutCard(
                    icon = Icons.Filled.Shield,
                    title = "使用说明",
                    subtitle = "本应用由 26 信工 1 班完成，非学校官方出品"
                ) {
                    Text(
                        "聚合课程表、选课、成绩查询、第二课堂成绩单、考试安排、竞赛获奖、" +
                            "零星维修与评教等校园数据，所有信息仅存储于本机，不上传任何服务器。" +
                            "数据来自学校官方系统，请遵守学校规定使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 第三方组件（v1.12.0）----------
            item {
                AboutCard(
                    icon = Icons.Filled.LocalShipping,
                    title = "取件码（第三方）",
                    subtitle = "移植自 HandyCode · Apache-2.0"
                ) {
                    Text(
                        "「取件码」沿用 HandyCode（github.com/xianhe51920/handycode，Apache-2.0）的实现：" +
                            "只在系统 WebView 里加载淘宝 / 拼多多的官方页面，登录、验证码与验证都由官方页面完成，" +
                            "本应用不读取、不保存、不上传账号密码与 Cookie，也不在原生层代发业务请求。" +
                            "身份码与待取包裹只在页面内展示，不写入文件、不导出。\n\n" +
                            "页面内的按站点清除（退出登录）用到 AndroidX WebKit 1.13.0（Apache-2.0），" +
                            "见随源码附带的 libs/androidx-webkit-LICENSE.txt。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/** 顶部信息卡：图标 + 名字 + 版本 + 作者 */
@Composable
private fun HeroCard(appVersion: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    ),
                    RoundedCornerShape(16.dp)
                )
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(Color.White.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.School, null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "安财校园通",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "v$appVersion · 26 信工 1 班",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.88f)
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

/** 带图标标题的卡片，和设置页的观感保持一致 */
@Composable
private fun AboutCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
