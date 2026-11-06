package cn.aufe.campus.ui.electric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.ui.CampusViewModel

/** 快捷金额（学校页面只收正整数） */
private val QUICK_AMOUNTS = listOf(10, 20, 50, 100, 200)

/** 单笔上限，防止手滑多打一个 0 */
private const val MAX_AMOUNT = 1000

/**
 * 电费充值弹层。
 *
 * 链路移植自 LoveACE：从**校园卡余额**扣款给指定房间充电。
 * 这里刻意做三件事保护用户：
 * 1. 金额超出现有校园卡余额时直接不给提交；
 * 2. 提交前弹二次确认，写清楚「扣多少钱 / 充到哪个房间」；
 * 3. 结果未知时按失败展示，并提示去余额核对，绝不谎报成功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeSheet(
    vm: CampusViewModel,
    state: CampusViewModel.RechargeState,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by rememberSaveable { mutableStateOf("") }
    var askConfirm by remember { mutableStateOf(false) }
    var pickedQuick by remember { mutableStateOf<Int?>(null) }

    val amount = amountText.toIntOrNull() ?: 0
    val account = state.account
    val overBalance = account != null && amount > account.balance
    val canSubmit = state.ready && amount in 1..MAX_AMOUNT && !overBalance && !state.submitting

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "电费充值",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "从校园卡余额扣款，充进宿舍电表",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(Modifier.height(14.dp))

            // 校园卡信息
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Wallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                state.accountLoading -> "正在读取校园卡…"
                                account == null -> "校园卡信息未加载"
                                account.name.isNotBlank() -> "${account.name} · ${account.studentId}"
                                else -> "校园卡"
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            if (account == null) "—"
                            else "余额 ¥%.2f".format(account.balance),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (state.accountLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 四级联动：校区 → 楼栋 → 楼层 → 房间
            Text(
                "充电到哪个房间",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BillPicker(
                    label = "校区",
                    value = state.dorm?.name ?: "请选择",
                    options = state.dorms,
                    enabled = state.dorms.isNotEmpty() && !state.optionsLoading,
                    modifier = Modifier.weight(1f),
                    onSelect = vm::selectPayDorm
                )
                BillPicker(
                    label = "楼栋",
                    value = state.building?.name ?: "请选择",
                    options = state.buildings,
                    enabled = state.buildings.isNotEmpty() && !state.optionsLoading,
                    modifier = Modifier.weight(1f),
                    onSelect = vm::selectPayBuilding
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BillPicker(
                    label = "楼层",
                    value = state.floor?.name ?: "请选择",
                    options = state.floors,
                    enabled = state.floors.isNotEmpty() && !state.optionsLoading,
                    modifier = Modifier.weight(1f),
                    onSelect = vm::selectPayFloor
                )
                BillPicker(
                    label = "房间",
                    value = state.room?.name ?: "请选择",
                    options = state.rooms,
                    enabled = state.rooms.isNotEmpty() && !state.optionsLoading,
                    modifier = Modifier.weight(1f),
                    onSelect = vm::selectPayRoom
                )
            }
            if (state.optionsLoading) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在获取选项…",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 金额
            Text(
                "充值金额",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_AMOUNTS.forEach { q ->
                    FilterChip(
                        selected = pickedQuick == q,
                        onClick = {
                            pickedQuick = q
                            amountText = q.toString()
                        },
                        label = { Text("$q", fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = { input ->
                    // 只留数字，最多 4 位；手输时清掉快捷选择的高亮
                    amountText = input.filter { it.isDigit() }.take(4)
                    pickedQuick = null
                },
                label = { Text("自定义金额（元）", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = overBalance,
                supportingText = {
                    when {
                        overBalance -> Text(
                            "超过校园卡余额 ¥%.2f".format(account?.balance ?: 0.0),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        amount > MAX_AMOUNT -> Text(
                            "单笔最多 $MAX_AMOUNT 元",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> Text(
                            "学校只支持整数元，建议按需充值",
                            fontSize = 11.sp
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // 提示
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(Modifier.padding(12.dp)) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text(
                            "这是真实扣款：确认后立即从校园卡余额扣除，电量通常几分钟内到账。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!state.ready) {
                            Text(
                                "请先把校区、楼栋、楼层、房间四项都选好。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            if (state.error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { askConfirm = true },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("提交中…")
                } else {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("确认充值 ¥$amount")
                }
            }
        }
    }

    // 二次确认
    if (askConfirm) {
        AlertDialog(
            onDismissRequest = { askConfirm = false },
            title = { Text("确认充值") },
            text = {
                Text(
                    "将从校园卡扣除 ¥$amount，" +
                        "充到「${state.dorm?.name} ${state.building?.name} " +
                        "${state.floor?.name} ${state.room?.name}」。\n\n扣款后无法撤销，请核对房间号。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askConfirm = false
                    vm.submitRecharge(amount)
                }) { Text("确认扣款") }
            },
            dismissButton = {
                TextButton(onClick = { askConfirm = false }) { Text("再想想") }
            }
        )
    }

    // 结果
    state.result?.let { result ->
        AlertDialog(
            onDismissRequest = { vm.closeRecharge() },
            title = { Text(if (result.success) "充值成功" else "充值未成功") },
            text = {
                Column {
                    Text(result.message.ifBlank { if (result.success) "已提交成功" else "未知原因" })
                    if (result.success) {
                        Text(
                            "电表余额稍后刷新即可看到变化。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        Text(
                            "如果校园卡已扣款但电量没加，请带上时间到宿管处核对。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.closeRecharge() }) { Text("知道了") }
            }
        )
    }
}
