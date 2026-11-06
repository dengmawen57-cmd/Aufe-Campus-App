package cn.aufe.campus.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/**
 * 一卡通余额小卡片的配置界面。
 *
 * 两种进入方式：
 * 1. **长按桌面小卡片 → 编辑 / 重新配置**（Android 12+ 直接可用）；
 * 2. 从添加小卡片的流程进入（部分启动器添加即弹配置）。
 *
 * 设置是**全局**的（所有余额小卡片共用一套背景），因为用户通常只放一个；
 * 变成为每张小卡片单独存图既浪费内存，也没人真的需要。
 */
class YktWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 默认结果设为「取消」：用户按返回键时不应该让系统认为配置成功
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setContent {
            CampusAppTheme {
                ConfigScreen(
                    onClose = { finish() },
                    onSave = { saveAndFinish() }
                )
            }
        }
    }

    private fun saveAndFinish() {
        WidgetDataStore.refreshYktWidgets(this)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, result)
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(onClose: () -> Unit, onSave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var bgUri by remember { mutableStateOf(YktWidgetPrefs.bgUri(context)) }
    var tone by remember { mutableStateOf(YktWidgetPrefs.tone(context)) }
    var scrim by remember { mutableIntStateOf(YktWidgetPrefs.scrim(context)) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    // 选了图立刻重读一次预览（顺带申请长期读权限，否则重启后图就没了）
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            BackgroundImageUtil.persistPermission(context, uri)
            YktWidgetPrefs.setBgUri(context, uri.toString())
            bgUri = uri.toString()
        }
    }

    // 每次换图重新解码预览（用和小组件相同的裁剪/圆角逻辑，所见即所得）
    // 传 bakeScrim = false：预览自己用 Compose 叠了一层压暗，不然会压暗两次
    LaunchedEffect(bgUri) {
        val dm = context.resources.displayMetrics
        val previewW = (dm.widthPixels - 32 * dm.density).toInt().coerceAtLeast(1)
        val previewH = (120 * dm.density).toInt().coerceAtLeast(1)
        preview = YktWidgetPrefs.loadBitmap(context, previewW, previewH, bakeScrim = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("余额小卡片") },
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
                "拖住小卡片右下角可以任意拉伸，界面会自动切换紧凑 / 标准 / 宽松三档排版。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // 预览
            WidgetPreview(bitmap = preview, tone = tone, scrim = scrim)

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
                        YktWidgetPrefs.clearBg(context)
                        bgUri = null
                    }) {
                        Text("恢复默认", fontSize = 13.sp)
                    }
                }
            }
            Text(
                if (bgUri == null) "当前：默认纯白卡片"
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
                    YktWidgetPrefs.TONE_AUTO to "自动",
                    YktWidgetPrefs.TONE_DARK to "深色字",
                    YktWidgetPrefs.TONE_LIGHT to "浅色字"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = tone == value,
                        onClick = {
                            tone = value
                            YktWidgetPrefs.setTone(context, value)
                        },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("背景压暗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("$scrim%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = scrim.toFloat(),
                onValueChange = { scrim = it.toInt() },
                onValueChangeFinished = { YktWidgetPrefs.setScrim(context, scrim) },
                valueRange = 0f..70f,
                steps = 13
            )
            Text(
                "图片浅色发白、字看不清时，把这里调大一些。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    // 滑动条可能还没松手就被点了保存，这里补一次落盘
                    YktWidgetPrefs.setScrim(context, scrim)
                    onSave()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) { Text("保存并刷新小卡片") }

            Spacer(Modifier.height(8.dp))
            Text(
                "小卡片上的「刷新」按钮会实时查一次校园卡余额；长按小卡片选「编辑」可以再回到这里。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 小卡片外观预览：尽量还原 RemoteViews 的观感（圆角、压暗、配色） */
@Composable
private fun WidgetPreview(bitmap: Bitmap?, tone: String, scrim: Int) {
    val darkText = when (tone) {
        YktWidgetPrefs.TONE_LIGHT -> false
        YktWidgetPrefs.TONE_DARK -> true
        else -> true
    }
    val titleColor = if (darkText) Color(0xFF1A1C1E) else Color.White
    val accent = if (darkText) Color(0xFF1A5FB4) else Color.White
    val subColor = if (darkText) Color(0xFF44474E) else Color(0xE6FFFFFF)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
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
                        .clip(RoundedCornerShape(16.dp))
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrim / 100f))
                )
            }
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "一卡通余额",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFF1A5FB4))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("刷新", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Text("¥128.50", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = accent)
                Text("更新于 10月27日 22:40", fontSize = 10.sp, color = subColor)
            }
        }
    }
}
