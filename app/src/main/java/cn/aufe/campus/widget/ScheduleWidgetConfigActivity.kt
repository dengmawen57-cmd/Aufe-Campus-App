package cn.aufe.campus.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.local.BackgroundImageUtil
import cn.aufe.campus.ui.theme.CampusAppTheme
import kotlinx.coroutines.launch

/**
 * 课程表小组件（今日课程 / 一周课表）的背景图设置。
 *
 * 三种进入方式：
 * 1. 添加课表小组件时，启动器弹出配置（`android:configure`）；
 * 2. 长按桌面课表卡片 → 编辑（Android 12+，`reconfigurable`）；
 * 3. App 内「设置 → 桌面小卡片 → 课程表小卡片背景」直接进入。
 *
 * 设置是全局的：今日和一周两个卡片共用同一张背景图，
 * 因为用户心理上它们是"同一张课表的两种视图"，分开设反而奇怪。
 */
class ScheduleWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 默认返回「取消」：用户按返回键不该被当成配置成功
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setContent {
            CampusAppTheme {
                ScheduleConfigScreen(onClose = { finish() }, onSave = { saveAndFinish() })
            }
        }
    }

    /**
     * 收尾：写结果 + 关闭页面。
     *
     * v1.9.6 起推送不在这里发起：界面按钮先 `ScheduleWidgetSync.applyAndWait`
     * 把新内容推上桌面（任务跑在应用级作用域，页面关掉也不会被取消），
     * 再调这里关页；`finish()` 之后 [ScheduleWidgetSync] 还会补一枪 ——
     * launcher 从后台回前台时常常会重排一次卡片，这一枪正好盖住它。
     */
    private fun saveAndFinish() {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleConfigScreen(onClose: () -> Unit, onSave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 改一项就刷一次桌面，做到"所见即所得"；保存时再等刷新完成才关页面。
    // v1.9.6：推送交给应用级作用域（ScheduleWidgetSync），本页只管发起，
    // 页面关掉也不会把推送一起掐断（旧实现在这里被 finish() 连带取消过）。
    val scope = rememberCoroutineScope()
    var applying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf(ScheduleWidgetPrefs.applyErrorText(context)) }
    val refreshWidgets: () -> Unit = { ScheduleWidgetSync.requestUpdate(context) }

    var bgUri by remember { mutableStateOf(ScheduleWidgetPrefs.bgUri(context)) }
    var tone by remember { mutableStateOf(ScheduleWidgetPrefs.tone(context)) }
    var scrim by remember { mutableIntStateOf(ScheduleWidgetPrefs.scrim(context)) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewDark by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 必须持久化读权限，否则重启后 provider 读这张图会被拒
            BackgroundImageUtil.persistPermission(context, uri)
            ScheduleWidgetPrefs.setBgUri(context, uri.toString())
            bgUri = uri.toString()
            refreshWidgets()
        }
    }

    // 预览用和桌面相同的裁剪 / 圆角逻辑；压暗由 Compose 叠一层，所以 bakeScrim = false
    LaunchedEffect(bgUri, scrim) {
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels - 32 * dm.density).toInt().coerceAtLeast(1)
        val h = (150 * dm.density).toInt().coerceAtLeast(1)
        preview = ScheduleWidgetPrefs.loadBitmap(context, w, h, bakeScrim = false)
        previewDark = preview?.let { WidgetBitmap.isDark(it, scrim) } ?: true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课程表小卡片") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "今日课程和一周课表两个小卡片共用这张背景图；有图时会自动把文字翻成白色，" +
                    "也可以在下面强制成深色字。改完点底部的保存即可立刻生效。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            SchedulePreview(bitmap = preview, scrim = scrim, whiteText = shouldWhite(tone, previewDark))

            Spacer(Modifier.height(20.dp))

            Text("背景图", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择图片", fontSize = 13.sp)
                }
                if (bgUri != null) {
                    OutlinedButton(onClick = {
                        val old = BackgroundImageUtil.parse(bgUri)
                        if (old != null) BackgroundImageUtil.releasePermission(context, old)
                        ScheduleWidgetPrefs.clearBg(context)
                        bgUri = null
                        refreshWidgets()
                    }) {
                        Text("恢复默认", fontSize = 13.sp)
                    }
                }
            }
            Text(
                if (bgUri == null) "当前：默认纯色卡片"
                else "当前：" + BackgroundImageUtil.displayName(
                    context, BackgroundImageUtil.parse(bgUri) ?: Uri.EMPTY
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(20.dp))

            Text("文字颜色", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ScheduleWidgetPrefs.TONE_AUTO to "自动",
                    ScheduleWidgetPrefs.TONE_DARK to "深色字",
                    ScheduleWidgetPrefs.TONE_LIGHT to "浅色字"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = tone == value,
                        onClick = {
                            tone = value
                            ScheduleWidgetPrefs.setTone(context, value)
                            refreshWidgets()
                        },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            Text(
                "「自动」会按图的明暗决定；觉得字看不清就手动选浅色字，并把下面的压暗调大。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("背景压暗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("$scrim%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = scrim.toFloat(),
                onValueChange = { scrim = it.toInt() },
                onValueChangeFinished = {
                    ScheduleWidgetPrefs.setScrim(context, scrim)
                    refreshWidgets()
                },
                valueRange = 0f..70f,
                steps = 13
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (!applying) {
                        // 滑杆可能还没松手就点了保存，这里补一次落盘
                        ScheduleWidgetPrefs.setScrim(context, scrim)
                        applying = true
                        applyError = ""
                        scope.launch {
                            // 关键：**等**重绘真的做完再关页面，但等待本身不设"取消型"超时。
                            //
                            // v1.9.6 修的就是这里：以前写 withTimeoutOrNull(4000) { updateAllNow() }，
                            // 超过 4 秒协程被取消 → runCatching 把 CancellationException 一起吞掉
                            // → 更新没做完、页面照样关闭。冷启动首帧 + 解码用户照片常常超过 4 秒，
                            // 于是"第一下必失败、点几次才成"。现在超时只是不再等，任务会继续跑完，
                            // 并在后台自动补一枪。
                            val result = ScheduleWidgetSync.applyAndWait(context, 6000)
                            applying = false
                            applyError = if (result != null && !result.ok) {
                                ScheduleWidgetPrefs.applyErrorText(context)
                                    .ifBlank { "桌面卡片这次没刷上，请再点一次保存" }
                            } else {
                                ""
                            }
                            onSave()
                        }
                    }
                },
                enabled = !applying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (applying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在应用到桌面…")
                } else {
                    Text("保存并刷新课表卡片")
                }
            }

            // 失败不再静默（对齐余额卡片的「刷新失败 · 点重试」）
            if (applyError.isNotBlank()) {
                Text(
                    "⚠️ $applyError",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Text(
                "设置改完会立刻推到桌面；如果长按卡片选「编辑」进来的，桌面回到前台后会自动再推一次，不需要反复点保存。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun shouldWhite(tone: String, dark: Boolean): Boolean = when (tone) {
    ScheduleWidgetPrefs.TONE_LIGHT -> true
    ScheduleWidgetPrefs.TONE_DARK -> false
    else -> dark
}

/** 课表卡片外观预览：还原「周次头部 + 两节课」的最小版式 */
@Composable
private fun SchedulePreview(bitmap: Bitmap?, scrim: Int, whiteText: Boolean) {
    val titleColor = if (whiteText) Color.White else Color(0xFF1A1C1E)
    val subColor = if (whiteText) Color(0xE6FFFFFF) else Color(0xFF44474E)
    val accent = if (whiteText) Color.White else Color(0xFF1A5FB4)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (bitmap == null) Color(0xFFFDFDFF) else Color(0xFF15181C)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(22.dp))
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrim / 100f))
                )
            }
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第8周", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                    Spacer(Modifier.width(8.dp))
                    Text("周三", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = titleColor)
                    Spacer(Modifier.width(6.dp))
                    Text("10月28日", fontSize = 11.sp, color = subColor)
                    Spacer(Modifier.weight(1f))
                    Text("2节课", fontSize = 11.sp, color = accent)
                }
                Spacer(Modifier.height(10.dp))
                listOf(
                    "第1-2节 · 08:00" to "高等数学",
                    "第3-4节 · 10:00" to "大学英语"
                ).forEach { (time, name) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (whiteText) Color(0x26FFFFFF) else Color(0x141A5FB4))
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            time,
                            fontSize = 10.sp,
                            color = subColor,
                            modifier = Modifier.width(88.dp)
                        )
                        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = titleColor)
                    }
                }
            }
        }
    }
}
