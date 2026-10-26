package cn.aufe.campus.ui.login

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.aufe.campus.CampusApp
import cn.aufe.campus.auth.AuthState
import cn.aufe.campus.auth.LoginController
import cn.aufe.campus.auth.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STEPS = listOf(
    "VPN 门户" to "正在连接校园 VPN…",
    "统一身份认证" to "正在登录统一身份认证…",
    "教务系统" to "正在验证本科教务系统…",
    "爱安财" to "正在获取第二课堂授权…"
)

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val auth = (context.applicationContext as CampusApp).authManager
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var vpnUser by remember { mutableStateOf(auth.lastUsedUser()) }
    var vpnPass by remember { mutableStateOf(auth.savedVpnPass()) }
    var sameAccount by remember { mutableStateOf(true) }
    var jwUser by remember { mutableStateOf(auth.savedJwUser()) }
    var jwPass by remember { mutableStateOf(auth.savedJwPass()) }
    var rememberPass by remember { mutableStateOf(auth.hasRemembered()) }
    var showPass by remember { mutableStateOf(false) }

    // WebView 登录覆盖层状态
    var webLoginActive by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(0) }
    var webError by remember { mutableStateOf("") }

    /** 会话恢复进行中（启动页已展示，此处用于登录页再次触发恢复时的反馈） */
    val restoring by auth.restoring.collectAsState()

    /** 当前登录态（决定是否自动补一次静默恢复） */
    val authState by auth.authState.collectAsState()

    /** 是否具备一键快速登录条件（本地存有加密凭据 / 记住密码） */
    val canQuickLogin = auth.hasCredentials()

    /** 本次登录提交的账号密码快照：**只有登录成功后才落盘** */
    var pendingCreds by remember { mutableStateOf<Array<String>?>(null) }

    /**
     * 进入登录页时自动重试一次静默恢复。
     *
     * 启动恢复偶尔会因为"网络刚就绪/校园网还没连上"失败，这时用户看到登录页
     * 并不代表凭据失效。自动补一次，能救回绝大多数"明明存了密码还要输一遍"的场景。
     */
    var autoRetried by remember { mutableStateOf(false) }
    LaunchedEffect(authState) {
        // 主动退出登录后不要自动再登回来，否则"退出"就形同虚设
        if (!autoRetried && authState == AuthState.Unauthenticated &&
            canQuickLogin && !auth.hasLoggedOut()
        ) {
            autoRetried = true
            val ok = withContext(Dispatchers.IO) { auth.restoreSessionBlocking(force = true) }
            if (!ok) {
                val hint = when (auth.lastRestore.value) {
                    RestoreResult.BadCredentials -> "保存的密码已失效，请重新输入密码"
                    RestoreResult.PasswordChangeRequired -> "学校要求先修改统一身份认证密码"
                    else -> ""
                }
                if (hint.isNotBlank()) snackbar.showSnackbar(hint)
            }
        }
    }

    /** 一键快速登录：用本地加密凭据静默重登（对齐参考 app 的 QuickLogin） */
    fun quickLogin() {
        if (restoring) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { auth.restoreSessionBlocking(force = true) }
            if (!ok) {
                snackbar.showSnackbar(
                    when (auth.lastRestore.value) {
                        RestoreResult.BadCredentials -> "登录状态已失效，请重新输入密码"
                        RestoreResult.PasswordChangeRequired -> "学校要求先修改统一认证密码后再登录"
                        else -> "网络异常，请检查校园网络后重试"
                    }
                )
            }
        }
    }

    fun startLogin() {
        val u = vpnUser.trim()
        val p = vpnPass
        val ju = if (sameAccount) u else jwUser.trim()
        val jp = if (sameAccount) p else jwPass
        if (u.isBlank() || p.isBlank()) {
            scope.launch { snackbar.showSnackbar("请输入 VPN 账号和密码") }
            return
        }
        if (ju.isBlank() || jp.isBlank()) {
            scope.launch { snackbar.showSnackbar("请输入教务系统账号和密码") }
            return
        }
        // 注意：这里只记录本次输入的快照，**不写盘**。
        // 写盘放在登录成功之后，避免一次输错密码就把原本可用的凭据覆盖掉
        //（那会导致之后每次开机静默恢复都失败，用户被迫反复手动登录）。
        pendingCreds = arrayOf(u, p, ju, jp)
        currentStep = 0
        webError = ""
        webLoginActive = true
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // ---------- 登录表单 ----------
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(56.dp))
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(20.dp).size(44.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "安财校园通",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "课表 · 选课 · 成绩单 · 成绩查询",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))

                OutlinedTextField(
                    value = vpnUser,
                    onValueChange = { vpnUser = it },
                    label = { Text("VPN 账号（学号）") },
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vpnPass,
                    onValueChange = { vpnPass = it },
                    label = { Text("VPN 密码") },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                null
                            )
                        }
                    },
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { startLogin() }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))

                AnimatedVisibility(visible = !sameAccount) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = jwUser,
                            onValueChange = { jwUser = it },
                            label = { Text("教务系统账号") },
                            leadingIcon = { Icon(Icons.Filled.Person, null) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = jwPass,
                            onValueChange = { jwPass = it },
                            label = { Text("教务系统密码") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sameAccount, onCheckedChange = { sameAccount = it })
                    Text("教务系统使用相同账号", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberPass, onCheckedChange = { rememberPass = it })
                    Text("记住密码", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))

                // 快速登录：本地有加密凭据时免去重复输入
                if (canQuickLogin) {
                    OutlinedButton(
                        onClick = { quickLogin() },
                        enabled = !restoring,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        if (restoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在恢复登录…", fontSize = 15.sp)
                        } else {
                            Icon(Icons.Filled.Bolt, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("快速登录", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Button(
                    onClick = { startLogin() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("登 录", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "登录一次即可长期保持：会话过期会在后台自动重新登录\n账号密码加密保存在本机，不会上传到任何第三方",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
            }

            // ---------- WebView 登录覆盖层 ----------
            AnimatedVisibility(visible = webLoginActive) {
                WebLoginOverlay(
                    vpnUser = vpnUser.trim(),
                    vpnPass = vpnPass,
                    jwUser = if (sameAccount) vpnUser.trim() else jwUser.trim(),
                    jwPass = if (sameAccount) vpnPass else jwPass,
                    currentStep = currentStep,
                    onStepChange = { currentStep = it },
                    onSuccess = {
                        webLoginActive = false
                        // 登录成功才提交凭据：此后启动走静默恢复、运行期走心跳与掉线自愈
                        pendingCreds?.let { c ->
                            auth.commitLogin(
                                vpnUser = c[0],
                                vpnPass = c[1],
                                jwUser = c[2],
                                jwPass = c[3],
                                remember = rememberPass,
                                dektToken = auth.dektToken()
                            )
                        }
                        onLoggedIn()
                    },
                    onError = { msg ->
                        webError = msg
                        webLoginActive = false
                        scope.launch { snackbar.showSnackbar(msg) }
                    },
                    onCancel = { webLoginActive = false }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebLoginOverlay(
    vpnUser: String,
    vpnPass: String,
    jwUser: String,
    jwPass: String,
    currentStep: Int,
    onStepChange: (Int) -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val auth = (context.applicationContext as CampusApp).authManager
    val scope = rememberCoroutineScope()
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // 顶栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("正在登录", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        // 步骤进度
        STEPS.forEachIndexed { idx, (name, _) ->
            val done = idx < currentStep
            val active = idx == currentStep
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier.size(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(
                            Icons.Filled.Check,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    } else if (active) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Filled.Lock,
                            null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    STEPS[idx].first + if (active) " · ${STEPS[idx].second}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        done -> MaterialTheme.colorScheme.primary
                        active -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.outline
                    },
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = (currentStep + 1f) / STEPS.size,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        // WebView（登录页面）
        Card(
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        webViewClient = WebViewClient()
                        webViewRef.value = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // 启动登录状态机（等待 WebView 就绪，仅一次）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var wv = webViewRef.value
        var waited = 0
        while (wv == null && waited < 100) {
            kotlinx.coroutines.delay(50)
            wv = webViewRef.value
            waited++
        }
        val webView = wv ?: run {
            onError("登录组件初始化失败，请重试")
            return@LaunchedEffect
        }
        LoginController(
            authManager = auth,
            onStep = { step, _ -> onStepChange(step) },
            onSuccess = { onSuccess() },
            onError = { onError(it) }
        ).run(webView, vpnUser, vpnPass, jwUser, jwPass)
    }
}
