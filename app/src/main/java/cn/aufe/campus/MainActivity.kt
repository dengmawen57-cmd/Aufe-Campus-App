package cn.aufe.campus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.aufe.campus.auth.AuthState
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.FeatureViewModel
import cn.aufe.campus.ui.competition.CompetitionPortalScreen
import cn.aufe.campus.ui.evaluation.EvaluationScreen
import cn.aufe.campus.ui.exam.ExamScreen
import cn.aufe.campus.ui.repair.RepairScreen
import cn.aufe.campus.ui.settings.AboutScreen
import cn.aufe.campus.ui.settings.FeatureCustomizeScreen
import cn.aufe.campus.ui.settings.SettingsScreen
import cn.aufe.campus.ui.appearance.AppearanceScreen
import cn.aufe.campus.ui.electric.ElectricScreen
import cn.aufe.campus.ui.login.LoginScreen
import cn.aufe.campus.ui.gpa.GpaScreen
import cn.aufe.campus.ui.main.MainScreen
import cn.aufe.campus.ui.plan.PlanScreen
import cn.aufe.campus.ui.score.ScoreQueryScreen
import cn.aufe.campus.ui.theme.CampusAppTheme
import cn.aufe.campus.ui.ykt.YktScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent 附加参数：指定启动后直达的页面 */
        const val EXTRA_ROUTE = "route"
        const val ROUTE_YKT = "ykt"

        private const val ROUTE_SPLASH = "splash"
        private const val ROUTE_LOGIN = "login"
        private const val ROUTE_MAIN = "main"
    }

    /** 待直达的页面路由（由桌面小卡片点击传入，支持 Activity 复用时更新） */
    private val routeFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 外观要在 setContent 之前读好：否则首帧会用默认蓝色闪一下再切主题
        runCatching { cn.aufe.campus.data.local.AppearanceState.load(this) }
        routeFlow.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            CampusAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // v1.8.2：改为透明。设了背景图时，配色本身已是半透明，
                    // 这里再叠一层不透明底会把背景图彻底盖住。
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    val navController = rememberNavController()
                    val vm: CampusViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val fvm: FeatureViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val context = LocalContext.current
                    val auth = remember {
                        (context.applicationContext as CampusApp).authManager
                    }

                    // 从小卡片进入时直达的页面（如 route=ykt）
                    val pendingRoute by routeFlow.collectAsState()

                    /** 已在主页时，跳转到小卡片指定的页面（只消费一次） */
                    fun navigatePendingRoute() {
                        val r = routeFlow.value
                        if (!r.isNullOrBlank() && navController.currentDestination?.route == ROUTE_MAIN) {
                            routeFlow.value = null
                            navController.navigate(r)
                        }
                    }

                    /**
                     * 启动即静默恢复会话（对齐参考 app 的 restoreSession）。
                     * 期间停留在启动页，避免"先闪登录页再跳转"。
                     */
                    LaunchedEffect(Unit) { auth.restoreSession() }

                    val authState by auth.authState.collectAsState()
                    val sessionDead by auth.sessionDead.collectAsState()

                    // 恢复完成 / 登录成功 → 主页
                    LaunchedEffect(authState) {
                        if (authState == AuthState.Authenticated &&
                            navController.currentDestination?.route != ROUTE_MAIN
                        ) {
                            navController.navigate(ROUTE_MAIN) {
                                popUpTo(0) { inclusive = true }
                            }
                            navigatePendingRoute()
                            // 登录/恢复成功后立即刷新一卡通小卡片，
                            // 避免小卡片仍显示长时间未登录前的旧余额
                            refreshYktWidgetInBackground(context)
                        }
                    }

                    // 确认需要手动登录 → 登录页
                    LaunchedEffect(authState) {
                        if (authState == AuthState.Unauthenticated &&
                            navController.currentDestination?.route != ROUTE_LOGIN
                        ) {
                            navController.navigate(ROUTE_LOGIN) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }

                    // 会话彻底失效（静默重连也失败）→ 回登录页
                    // 注意：这里**不清除**本地凭据。网络抖动导致的一次重登失败，
                    // 不该让用户丢掉已保存的账号密码；登录页会自动再静默恢复一次。
                    LaunchedEffect(sessionDead) {
                        if (sessionDead && navController.currentDestination?.route != ROUTE_LOGIN) {
                            auth.stopHeartbeat()
                            navController.navigate(ROUTE_LOGIN) {
                                popUpTo(ROUTE_MAIN) { inclusive = true }
                            }
                        }
                    }

                    LaunchedEffect(pendingRoute) { navigatePendingRoute() }

                    NavHost(navController = navController, startDestination = ROUTE_SPLASH) {
                        composable(ROUTE_SPLASH) {
                            SplashScreen()
                        }
                        composable(ROUTE_LOGIN) {
                            LoginScreen(
                                onLoggedIn = {
                                    auth.onLoginSuccess(auth.dektToken())
                                    navigatePendingRoute()
                                }
                            )
                        }
                        composable(ROUTE_MAIN) {
                            MainScreen(
                                vm = vm,
                                fvm = fvm,
                                onOpenPlan = {
                                    navController.navigate("plan")
                                },
                                onOpenScoreQuery = {
                                    navController.navigate("scoreQuery")
                                },
                                onOpenGpa = {
                                    navController.navigate("gpa")
                                },
                                onOpenYkt = {
                                    navController.navigate("ykt")
                                },
                                onOpenElectric = {
                                    navController.navigate("electric")
                                },
                                onOpenExam = { navController.navigate("exam") },
                                onOpenRepair = { navController.navigate("repair") },
                                onOpenCompetitionHub = { navController.navigate("competitionPortal") },
                                // v1.11.9：智慧校园改为**系统浏览器**打开。
                                // 门户是深信服 wui 电脑端控制台，App 内嵌 WebView 里
                                // 导航条能出来、卡片区始终一片白（v1.11.4～1.11.8 试过
                                // 灌 Cookie、换 UA、多级兜底，都不成），浏览器里却完全正常。
                                // 与其继续在 App 里硬撑，不如把活儿交给浏览器。
                                onOpenSmartCampus = {
                                    context.openInBrowser(
                                        cn.aufe.campus.data.net.Endpoints.SMART_CAMPUS
                                    )
                                },
                                // 取件码（v1.12.0 移植自 HandyCode）：原生 Activity，直接拉起
                                onOpenParcel = { context.openParcelCode() },
                                onOpenEvaluation = { navController.navigate("evaluation") },
                                onOpenSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("plan") {
                            PlanScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable("scoreQuery") {
                            ScoreQueryScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable("gpa") {
                            GpaScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable("ykt") {
                            YktScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable("electric") {
                            ElectricScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable("appearance") {
                            AppearanceScreen(onBack = { navController.popBackStack() })
                        }
                        // ---------- v1.9.1 新增页面 ----------
                        composable("exam") {
                            ExamScreen(vm = fvm, onBack = { navController.popBackStack() })
                        }
                        composable("repair") {
                            RepairScreen(vm = fvm, onBack = { navController.popBackStack() })
                        }
                        // ---------- v1.11.0 学科竞赛全原生 ----------
                        composable("competitionPortal") {
                            CompetitionPortalScreen(
                                onBack = { navController.popBackStack() },
                                onOpenWeb = { tab ->
                                    // 兜底：解析失败时退回内嵌页。先把 OkHttp 的会话 Cookie
                                    // 灌进系统 CookieManager，进去就是已登录态
                                    val app = application as CampusApp
                                    app.authManager.importCookiesToWebView {
                                        com.aufe.competition.CompetitionModule.start(this@MainActivity, tab)
                                    }
                                }
                            )
                        }
                        composable("evaluation") {
                            EvaluationScreen(vm = fvm, onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenAppearance = { navController.navigate("appearance") },
                                onOpenFeatureCustomize = { navController.navigate("featureCustomize") },
                                onOpenAbout = { navController.navigate("about") },
                                onLogout = {
                                    vm.logout()
                                    fvm.onSessionChanged()
                                    // 学科竞赛（WebView）与主 App 共用同一个 CookieManager，
                                    // 退出时不清会串号：下次换账号进来还是上一个人的会话
                                    runCatching { com.aufe.competition.CompetitionModule.clearSession() }
                                    navController.navigate(ROUTE_LOGIN) {
                                        popUpTo(ROUTE_MAIN) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("featureCustomize") {
                            FeatureCustomizeScreen(onBack = { navController.popBackStack() })
                        }
                        // ---------- v1.11.2：关于（含诊断日志与页面采集） ----------
                        composable("about") {
                            AboutScreen(onBack = { navController.popBackStack() })
                        }
                        // v1.11.9：智慧校园的内嵌 WebView 路由已撤除，改为直接用系统浏览器打开
                        // （见上面的 onOpenSmartCampus）。门户是深信服电脑端控制台，
                        // App 内嵌时导航条能出来、卡片区始终空白，浏览器里则一切正常。
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFlow.value = intent.getStringExtra(EXTRA_ROUTE)
    }

    /**
     * 每次回到前台补一次会话探活（含从桌面小卡片、通知栏冷启动/热启动）。
     * 覆盖"手机放了一整夜 / 后台待了几小时"——会话过期会在后台静默重登，
     * 用户不会看到登录页。
     */
    override fun onStart() {
        super.onStart()
        runCatching { (application as CampusApp).authManager.onAppForeground() }
        // v1.8.1：后台常驻的启动入口放这里。
        // 此时 Activity 可见，属于 Android 12+ 允许启动前台服务的场景；
        // v1.8.0 把它放在 Application.onCreate 里，导致提醒/开机/小组件广播
        // 拉起进程时都会在后台尝试启动前台服务 → 系统杀进程 → 无限崩溃循环。
        runCatching { cn.aufe.campus.service.KeepAliveManager.restoreIfEnabled(this) }
        // 回前台重建课表小组件：跨天、下课、学期周次变化都能立刻反映到桌面
        runCatching { cn.aufe.campus.widget.ScheduleWidgetSync.requestUpdate(this) }
    }
}

/**
 * 用系统浏览器打开一个校内网页（v1.11.9）。
 *
 * 智慧校园门户是深信服的**电脑端**控制台（wui）。App 内嵌 WebView 里
 * 导航条能出来、卡片区却永远一片白（v1.11.4～v1.11.8 依次试过灌 Cookie、
 * 换桌面 UA、多级兜底，都没救过来），但在系统浏览器里完全正常。
 * 与其继续在 App 内硬撑，不如交给浏览器。
 *
 * 加了 `FLAG_ACTIVITY_NEW_TASK`：调用方可能不是 Activity 上下文。
 */
private fun Context.openInBrowser(url: String) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 拉起「取件码」页（v1.12.0，移植自 HandyCode）。
 *
 * 它是一整个原生 Activity（淘宝 + 拼多多两张卡片），不走 Compose 导航，
 * 所以菜单和桌面小卡片都用这个方法进。
 *
 * @param provider 可选 `"taobao"` / `"pinduoduo"`，直接定位到对应平台；
 *                 不传则沿用上次停留的平台（页面自己记在 SharedPreferences 里）。
 */
private fun Context.openParcelCode(provider: String? = null) {
    runCatching {
        val intent = Intent(this, com.fairycrane.handycode.ParcelCodeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!provider.isNullOrBlank()) intent.putExtra("provider", provider)
        startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(this, "取件码页面打不开：${it.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * 登录成功后后台刷新一卡通余额小卡片。
 * 失败静默忽略（不影响主流程），小卡片会在下次周期更新时自行重试。
 */
private fun refreshYktWidgetInBackground(context: Context) {
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching {
            val auth = (appContext as CampusApp).authManager
            if (!auth.hasCredentials()) return@runCatching
            val balance = auth.yktApi().getBalance()
            cn.aufe.campus.widget.WidgetDataStore.saveBalance(appContext, balance.balance)
            cn.aufe.campus.widget.WidgetDataStore.refreshYktWidgets(appContext)
        }
    }
}

/**
 * 启动页：会话恢复期间展示，避免直接闪出登录页。
 * 恢复成功 → 跳主页；失败 → 跳登录页（均由 MainActivity 的 LaunchedEffect 驱动）。
 */
@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "安财校园通",
                fontSize = 22.sp,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "正在恢复登录状态…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}