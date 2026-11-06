package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.BillOption
import cn.aufe.campus.data.model.PayAccountInfo
import cn.aufe.campus.data.model.UtilityPayResult
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import java.io.IOException

/**
 * 电费充值（移植自 LoveACE 的 YKTService.utility* 系列）。
 *
 * ## 与 [IsimApi] 的分工
 *
 * 学校其实有**两套**电费系统，各管一半：
 *
 * | | IsimApi（查询） | PowerPayApi（充值） |
 * |---|---|---|
 * | 域名 | `hqkd-aufe-edu-cn`（无端口） | `ykt-aufe-edu-cn-s:8118` |
 * | 会话 | `/go?openid=学号` 换 JSESSIONID | 复用一卡通 SSO Cookie |
 * | 能力 | 查余额 / 用电记录 / 缴费记录 | 选房间 + 从校园卡扣款充值 |
 *
 * 两个系统的房间编码**不通用**（ISIM 是 `roomdm`，这里是 `roomId`），
 * 所以充值必须自己再走一遍「校区 → 楼栋 → 楼层 → 房间」四级联动，
 * 不能把查询页选中的房间直接搬过来。
 *
 * ## 充值链路
 *
 * 1. `GET  utilityUnBindUserPowerPayInit.action` → 学生信息 + `accId`（提交时的必填隐藏项）
 * 2. `POST utilitBindXiaoQuData.action` → 逐级拉选项（返回 `值,名称|值,名称` 的裸串）
 * 3. `POST utilityUnBindUserPowerPay.action` → 扣校园卡余额给房间充电
 *
 * 第 3 步是**真实扣款**，调用方必须先做二次确认。
 */
class PowerPayApi(private val client: AufeClient) {

    @Volatile
    private var sessionReady = false

    /** 一卡通的 SSO Cookie 要先换一次，否则 init 页会跳登录 */
    private fun ensureSession() {
        if (sessionReady) return
        runCatching { client.get(Endpoints.YKT_SSO_LOGIN).close() }
        sessionReady = true
    }

    /** 读取充值页上的账号信息与 `accId` */
    fun fetchAccount(): PayAccountInfo {
        ensureSession()
        val html = client.getText(Endpoints.YKT_POWER_INIT)
        val info = PayAccountInfo.fromHtml(html)
        if (info.accId.isBlank()) {
            throw IOException("未能读取充值账户信息（可能登录已过期，请重新登录）")
        }
        return info
    }

    /** 校区列表（第一级，不传参） */
    fun fetchDorms(): List<BillOption> = fetchOptions("", "", "", "")

    fun fetchBuildings(dormId: String, dormName: String): List<BillOption> =
        fetchOptions(dormId, "", "", dormName)

    fun fetchFloors(dormId: String, buildingId: String, dormName: String): List<BillOption> =
        fetchOptions(dormId, buildingId, "", dormName)

    fun fetchRooms(dormId: String, buildingId: String, floorId: String, dormName: String): List<BillOption> =
        fetchOptions(dormId, buildingId, floorId, dormName)

    private fun fetchOptions(
        dormId: String,
        buildingId: String,
        floorId: String,
        dormName: String
    ): List<BillOption> {
        ensureSession()
        val body = client.postFormText(
            Endpoints.YKT_POWER_BIND_DATA,
            mapOf(
                "dormId" to dormId,
                "buildingId" to buildingId,
                "floorId" to floorId,
                "dormName" to dormName
            )
        )
        return BillOption.parseList(body)
    }

    /**
     * 提交充值。
     *
     * @param money 金额（整数元）。学校页面只接受正整数，传 0 或小数会被拒。
     */
    fun pay(
        room: BillOption,
        dorm: BillOption,
        building: BillOption,
        floor: BillOption,
        account: PayAccountInfo,
        money: Int
    ): UtilityPayResult {
        ensureSession()
        val html = client.postFormText(
            Endpoints.YKT_POWER_PAY,
            mapOf(
                "roomId" to room.value,
                "dormId" to dorm.value,
                "dormName" to dorm.name,
                "buildName" to building.name,
                "floorName" to floor.name,
                "roomName" to room.name,
                "accId" to account.accId,
                // balances 传的是校园卡当前余额，页面用它做「扣款前余额」展示与校验
                "balances" to "%.2f".format(account.balance),
                "payType" to "2",
                "choosePayType" to "1",
                "money" to money.toString()
            ),
            slow = true
        )
        return UtilityPayResult.fromHtml(html)
    }
}
