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
        routeFlow.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            CampusAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val vm: CampusViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
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
                                onLogout = {
                                    vm.logout()
                                    navController.navigate(ROUTE_LOGIN) {
                                        popUpTo(ROUTE_MAIN) { inclusive = true }
                                    }
                                },
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
                                }
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
        // 回前台重建课表小组件：跨天、下课、学期周次变化都能立刻反映到桌面
        runCatching { cn.aufe.campus.widget.ScheduleWidgetSync.requestUpdate(this) }
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