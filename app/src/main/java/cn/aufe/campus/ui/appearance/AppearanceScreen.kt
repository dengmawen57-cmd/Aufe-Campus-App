package cn.aufe.campus.ui.appearance

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import cn.aufe.campus.R
import cn.aufe.campus.data.local.AppearancePrefs
import cn.aufe.campus.data.local.AppearanceState
import cn.aufe.campus.data.local.BackgroundImageUtil
import cn.aufe.campus.ui.theme.ThemePalettes
import cn.aufe.campus.util.AppLog
import cn.aufe.campus.util.IconSwitcher

/**
 * 外观设置：主题色 / 背景图 / 应用图标。
 *
 * 这里只放「改完立刻能看见外观变化」的东西 —— 用户来找的是"让它好看点"。
 *
 * v1.11.2 把两类不该在这儿的搬走了：
 * - **桌面小卡片** → 「设置 → 桌面小卡片」（余额和课表并排，这里只给了一半，容易以为只有余额卡能配）
 * - **诊断日志 / 学科竞赛页面采集** → 「设置 → 关于」（排查工具，和外观无关）
 *
 * 页面本身铺在真实背景上，改任何一项都是**即时预览**——不需要"应用"按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var themeId by remember { androidx.compose.runtime.mutableStateOf(AppearanceState.themeId) }
    var bgUri by remember { androidx.compose.runtime.mutableStateOf(AppearanceState.bgUri) }
    // 滑杆用本地状态：拖动时只改本地，松手才落盘，避免每帧都重新解码背景图
    var blur by remember { mutableFloatStateOf(AppearanceState.bgBlur.toFloat()) }
    var dim by remember { mutableFloatStateOf(AppearanceState.bgDim.toFloat()) }
    var iconId by remember { androidx.compose.runtime.mutableStateOf(AppearanceState.iconId) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = BackgroundImageUtil.persistPermission(context, uri)
        if (!ok) {
            Toast.makeText(context, "没拿到长期访问权限，重启后可能失效", Toast.LENGTH_LONG).show()
        }
        AppearanceState.setBgUri(context, uri.toString())
        bgUri = uri.toString()
        AppLog.i("Appearance", "更换背景图")
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("外观与工具") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- 主题色 ----------
            item {
                SettingsCard("主题色", "点击任意色块立即切换，深浅色跟随系统") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemePalettes.chunked(3).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { p ->
                                    val selected = p.id == themeId
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) p.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                AppearanceState.setTheme(context, p.id)
                                                themeId = p.id
                                                AppLog.i("Appearance", "主题 → ${p.id}")
                                            }
                                            .padding(vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(p.primary, p.secondary)
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selected) {
                                                Icon(
                                                    Icons.Filled.Check, null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            p.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold
                                            else FontWeight.Normal
                                        )
                                    }
                                }
                                // 最后一行不足 3 个时补空位，保持网格对齐
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // ---------- 背景图 ----------
            item {
                SettingsCard("应用背景", "选一张图铺在界面底下，可调虚化与压暗") {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }) {
                                Icon(Icons.Filled.Image, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (bgUri == null) "选择图片" else "更换图片")
                            }
                            if (bgUri != null) {
                                TextButton(onClick = {
                                    val old = BackgroundImageUtil.parse(bgUri)
                                    AppearanceState.setBgUri(context, null)
                                    bgUri = null
                                    if (old != null) BackgroundImageUtil.releasePermission(context, old)
                                    AppLog.i("Appearance", "移除背景图")
                                }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline, null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("移除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        if (bgUri == null) {
                            Text(
                                "未设置背景：界面使用纯色底，最省电、文字最清晰。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "当前：${BackgroundImageUtil.displayName(context, Uri.parse(bgUri))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            SliderRow(
                                label = "虚化程度",
                                value = blur,
                                valueText = "${blur.toInt()}",
                                range = 0f..AppearancePrefs.MAX_BLUR.toFloat(),
                                onChange = { blur = it },
                                onChangeFinished = {
                                    AppearanceState.setBgBlur(context, blur.toInt())
                                    AppLog.i("Appearance", "背景虚化 → ${blur.toInt()}")
                                }
                            )
                            SliderRow(
                                label = "压暗程度",
                                value = dim,
                                valueText = "${dim.toInt()}%",
                                range = 0f..70f,
                                onChange = { dim = it },
                                onChangeFinished = {
                                    AppearanceState.setBgDim(context, dim.toInt())
                                    AppLog.i("Appearance", "背景压暗 → ${dim.toInt()}%")
                                }
                            )
                            Text(
                                "虚化越高越接近毛玻璃，文字也更好读；压暗用于压住过亮的壁纸。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---------- 应用图标 ----------
            item {
                SettingsCard(
                    "应用图标",
                    "切换后桌面图标会更新，系统可能短暂重排（Activity 会被重启）"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconSwitcher.options.chunked(3).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { opt ->
                                    val selected = opt.id == iconId
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                if (opt.id == iconId) return@clickable
                                                val ok = IconSwitcher.apply(context, opt.id)
                                                if (ok) {
                                                    iconId = opt.id
                                                    Toast.makeText(
                                                        context,
                                                        "已切换为「${opt.name}」，返回桌面看一下",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        context, "切换失败，系统可能不支持",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                            .padding(vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        IconThumb(opt.id, 48.dp)
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            opt.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold
                                            else FontWeight.Normal
                                        )
                                        Text(
                                            opt.desc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // 桌面小卡片、诊断日志、页面采集都不在这里了：
            // - 小卡片归「设置 → 桌面小卡片」（那里余额和课表两张卡片并排，比这里只给一半更清楚）
            // - 日志与采集归「设置 → 关于」（排查问题时才用，混在外观里没人找得到）

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/**
 * 图标预览缩略图。
 *
 * ## 为什么不能直接 painterResource(R.mipmap.ic_launcher_xxx)（v1.8.3 崩溃修复）
 *
 * 每套图标在 Android 8+ 上解析到的是 `mipmap-anydpi-v26/ic_launcher_xxx.xml`，
 * 内容是一个 `<adaptive-icon>`。Compose 的 `painterResource` 只能处理
 * **位图**和**矢量图**，走到非矢量分支时会用 `BitmapFactory.decodeResource`，
 * 而它读不懂 adaptive-icon，返回 null → 直接抛异常 → **整页闪退**。
 *
 * 所以预览改用同一套图标的前景位图 `_fg`（WebP，一定可解码）；
 * 默认图标没有前景位图，就用代码画一个有校徽意象的方块。
 * 真正装到桌面上时系统读的是 adaptive-icon，跟这里无关。
 */
@Composable
private fun IconThumb(id: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val fgRes = when (id) {
        "sakura" -> R.mipmap.ic_launcher_sakura_fg
        "starry" -> R.mipmap.ic_launcher_starry_fg
        "cat" -> R.mipmap.ic_launcher_cat_fg
        "aurora" -> R.mipmap.ic_launcher_aurora_fg
        "ink" -> R.mipmap.ic_launcher_ink_fg
        else -> 0
    }
    // 不用 painterResource：它对非位图资源会直接抛异常（v1.8.2 闪退的根因）。
    // 这里手动解码并允许失败——解不出来就退到占位图，绝不让整个页面崩掉。
    val bitmap = remember(fgRes) {
        if (fgRes == 0) null
        else runCatching { BitmapFactory.decodeResource(context.resources, fgRes) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 默认图标 & 解码失败时的占位：主色渐变底 + 校徽意象
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.School, null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
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
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range
        )
    }
}
