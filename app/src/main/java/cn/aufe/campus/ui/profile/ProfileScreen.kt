package cn.aufe.campus.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import cn.aufe.campus.CampusApp
import cn.aufe.campus.data.local.FeaturePrefs
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.util.WeChatLauncher
import com.aufe.competition.CompetitionModule
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 打开内嵌网页前，把 App 的会话 Cookie 交给系统的 CookieManager。
 *
 * 否则冷启动后 WebView 的 Cookie 桶是空的，一进去就被踢到 VPN 登录页 ——
 * "App 明明已经登录了"却要再登一次。
 *
 * 3 秒超时是兜底：个别 ROM 的 setCookie 回调不回来，不能因此卡住入口。
 */
private suspend fun syncSessionToWebView(context: android.content.Context) {
    val auth = runCatching { (context.applicationContext as CampusApp).authManager }.getOrNull()
        ?: return
    withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            auth.importCookiesToWebView {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}

/**
 * 校园网办理页（校园网自助服务，v1.9.7 新增）。
 *
 * 这是学校的第三方 H5（uni-app 打包），**只在微信里才能正常登录**：
 * 走微信内置浏览器时才能拿到微信的授权/免密，外部浏览器打开会卡在登录引导上。
 * 所以这个入口不做内嵌 WebView，直接交给微信 —— 见 [WeChatLauncher]。
 *
 * v1.11.3：落地页从「校园网办理」改成「校趣多首页」，用户进去还能逛超市、
 * 查电费、看通知，不用再自己找回去的路。
 */
private const val CAMPUS_NET_URL = "https://xqd.zocedu.com/pages/home/home"

/**
 * 「我的」页（v1.9.1 重构）。
 *
 * ## 改了什么
 *
 * 原来这一页把「功能入口」和「设置项」混在一串卡片里：
 * 培养方案、成绩查询、一卡通…… 中间夹着上课提醒、后台常驻、外观、关于、退出。
 * 现在按职责拆开：
 * - 这一页只放**功能入口**，并且按 [FeaturePrefs] 的开关过滤
 * - 所有设置项搬进 [cn.aufe.campus.ui.settings.SettingsScreen]，右上角齿轮进入
 *
 * 功能入口的显隐由「设置 → 界面自定义」控制，关掉的项直接不渲染，
 * 而不是变灰 —— 用户要的是"眼不见为净"，不是"看得见点不动"。
 */
@Composable
fun ProfileScreen(
    vm: CampusViewModel,
    onOpenPlan: () -> Unit,
    onOpenScoreQuery: () -> Unit,
    onOpenGpa: () -> Unit,
    onOpenYkt: () -> Unit,
    onOpenElectric: () -> Unit,
    onOpenExam: () -> Unit,
    onOpenRepair: () -> Unit,
    onOpenCompetitionHub: () -> Unit,
    onOpenSmartCampus: () -> Unit = {},
    onOpenParcel: () -> Unit = {},
    onOpenEvaluation: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val score by vm.score.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val user = score.report

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openingHub by remember { mutableStateOf(false) }
    // 读 version 让"界面自定义"改完返回时列表立即更新
    val version by FeaturePrefs.version.collectAsState()
    val visible = remember(version) {
        FeaturePrefs.toggleable.associateWith { FeaturePrefs.isVisible(context, it) }
    }
    fun on(id: String): Boolean = visible[id] ?: true

    LaunchedEffect(Unit) {
        if (score.report == null && !score.loaded) vm.loadScore()
        if (!schedule.loaded) vm.loadSchedule()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // ---------- 头部 ----------
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
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
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
                // 设置入口：挪到头部右上角，比原来夹在列表里更好找
                Box(
                    Modifier
                        .size(38.dp)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable { onOpenSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Settings, "设置",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- 数据概览 ----------
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
                        score.report?.totalScore?.let {
                            String.format(java.util.Locale.CHINA, "%.1f", it)
                        } ?: "—",
                        "二课总分",
                        Modifier.weight(1f)
                    )
                    Stat(fmtCredits(schedule.courses.sumOf { it.credit }), "总学分", Modifier.weight(1f))
                }
            }

            // ---------- 功能入口（按开关过滤） ----------
            if (on(FeaturePrefs.SCORE_QUERY)) {
                MenuItem(Icons.Filled.Grade, "成绩查询", "本科教务系统 · 全部成绩与学分明细", onOpenScoreQuery)
            }
            if (on(FeaturePrefs.GPA)) {
                MenuItem(Icons.Filled.Insights, "绩点查询", "平均学分绩点 · 学期趋势与绩点分布", onOpenGpa)
            }
            if (on(FeaturePrefs.PLAN)) {
                MenuItem(Icons.Filled.AccountTree, "我的培养方案", "查看课组学分完成情况", onOpenPlan)
            }
            if (on(FeaturePrefs.EXAM)) {
                MenuItem(Icons.Filled.Schedule, "考试安排", "考试时间地点与座位号", onOpenExam)
            }
            if (on(FeaturePrefs.EVALUATION)) {
                MenuItem(Icons.Filled.RateReview, "学生评教", "一键完成本学期待评课程", onOpenEvaluation)
            }
            // v1.11.2：「竞赛获奖记录（网页版）」这个独立入口去掉了。
            // 它和下面的「学科竞赛」是同一件事的两种呈现，而且原生页右上角
            // 已经能一键进网页版 —— 留两个入口只会让人犹豫该点哪个。
            // 现在只保留「学科竞赛」一个入口：原生看列表，要看原站就点右上角地球。
            if (on(FeaturePrefs.COMPETITION_HUB)) {
                MenuItem(
                    Icons.Filled.WorkspacePremium,
                    "学科竞赛",
                    "申报 · 报名 · 学分认定（校内平台）",
                    onClick = {
                        if (!openingHub) {
                            openingHub = true
                            scope.launch {
                                // 保留一份 Cookie 给"在网页中打开"兜底用。
                                // 原生路径走的是 OkHttp 会话，不依赖它。
                                syncSessionToWebView(context)
                                openingHub = false
                                onOpenCompetitionHub()
                            }
                        }
                    }
                )
            }
            if (on(FeaturePrefs.SMART_CAMPUS)) {
                // 智慧校园只有网页版，而且是个**电脑端**的深信服门户（wui），
                // App 内 WebView 里渲染不全（v1.11.4～v1.11.8 反复试过：
                // 导航条能出来、卡片区一片白，UA 和 Cookie 都不是根因）。
                // 既然浏览器里是好的，就别在 App 里硬撑 —— 直接交给系统浏览器。
                MenuItem(
                    Icons.Filled.School,
                    "智慧校园",
                    "一站式门户 · 用浏览器打开",
                    onOpenSmartCampus
                )
            }
            if (on(FeaturePrefs.PARCEL)) {
                // 取件码（v1.12.0 移植自 HandyCode）：淘宝 / 拼多多的官方身份码
                // 与待取包裹合并成一页，登录仍走各平台官方网页，App 不碰账号密码。
                MenuItem(
                    Icons.Filled.LocalShipping,
                    "取件码",
                    "淘宝 / 拼多多的身份码与待取包裹",
                    onOpenParcel
                )
            }
            if (on(FeaturePrefs.YKT)) {
                MenuItem(Icons.Filled.CreditCard, "一卡通", "校园卡余额与消费明细", onOpenYkt)
            }
            if (on(FeaturePrefs.ELECTRIC)) {
                MenuItem(Icons.Filled.Bolt, "宿舍电费", "剩余电量 · 用电与缴费记录", onOpenElectric)
            }
            if (on(FeaturePrefs.REPAIR)) {
                MenuItem(Icons.Filled.Build, "零星维修", "报修与工单进度查询", onOpenRepair)
            }
            if (on(FeaturePrefs.CAMPUS_NET)) {
                MenuItem(
                    Icons.Filled.Wifi,
                    "校园网",
                    "办理与充值 · 用微信打开",
                    onClick = {
                        // 只跳微信：外面套一层 setPackage("com.tencent.mm")，
                        // 绝不弹系统选择框，避免落到浏览器（浏览器里登不上）
                        WeChatLauncher.toastFor(
                            context,
                            WeChatLauncher.open(context, CAMPUS_NET_URL)
                        )
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "开课查询在「选课」板块的最后一个标签页；功能入口可以在「设置 → 界面自定义」里开关。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun fmtCredits(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.CHINA, "%.1f", v)

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
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
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
