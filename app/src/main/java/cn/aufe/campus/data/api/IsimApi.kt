package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.ElectricityBalance
import cn.aufe.campus.data.model.ElectricityInfo
import cn.aufe.campus.data.model.ElectricityUsageRecord
import cn.aufe.campus.data.model.PaymentRecord
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import cn.aufe.campus.util.RecordTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿舍电费（移植自彩带小工具 ISIMService）。
 *
 * 注意两点与其它系统不同：
 * 1) 主机**无 :8118 端口**
 * 2) 楼层/房间接口返回的是 JS 对象字面量（键无引号），需先补引号再用 org.json 解析
 */
class IsimApi(
    private val client: AufeClient,
    private val studentId: String   // 学号（openid）
) {
    @Volatile
    private var sessionReady = false

    /** 用 VPN 会话换取电费系统的 JSESSIONID（/go 建立会话，Cookie 自动入 CookieStore） */
    private fun ensureSession(force: Boolean = false) {
        if (sessionReady && !force) return
        if (studentId.isBlank()) throw java.io.IOException("缺少学号，无法查询电费")
        runCatching {
            client.get("${Endpoints.ISIM_GO}?openid=$studentId&sn=sn").close()
        }
        sessionReady = true
    }

    /** 楼栋列表：(楼栋码, 楼栋名) */
    fun getBuildings(): List<Pair<String, String>> {
        ensureSession()
        val html = client.getText(Endpoints.ISIM_ABOUT)
        val script = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }
            .firstOrNull { it.contains("pickerBuilding") }
            ?: throw java.io.IOException("未能定位楼栋数据（页面结构可能已变化）")
        val values = Regex("""values\s*:\s*\[([^\]]*)]""").find(script)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().trim('"', '\'') }?.filter { it.isNotEmpty() } ?: emptyList()
        val displays = Regex("""displayValues\s*:\s*\[([^\]]*)]""").find(script)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().trim('"', '\'') }
            ?.filter { it.isNotEmpty() && it != "请选择" } ?: emptyList()
        // 两个数组可能长度不同（display 过滤了"请选择"），按名称对齐：从 values 尾部取与 displays 等长的项
        val n = minOf(values.size, displays.size)
        if (n == 0) return emptyList()
        val offset = values.size - n
        return (0 until n).map { values[offset + it] to displays[it] }
    }

    /** 楼层列表 */
    fun getFloors(buildingCode: String): List<Pair<String, String>> {
        ensureSession()
        val body = client.getText(
            "${Endpoints.ISIM_FLOORS}/$buildingCode",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
        return parseCodeName(body, "floordm", "floorname")
    }

    /** 房间列表 */
    fun getRooms(floorCode: String): List<Pair<String, String>> {
        ensureSession()
        val body = client.getText(
            "${Endpoints.ISIM_ROOMS}/$floorCode",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
        return parseCodeName(body, "roomdm", "roomname")
    }

    /** 绑定房间后拉取用电记录（含余额）与缴费记录 */
    fun getElectricityInfo(roomCode: String, roomName: String): ElectricityInfo {
        ensureSession()
        client.postFormText(
            Endpoints.ISIM_REBIND,
            mapOf(
                "roomdm" to roomCode, "room" to roomName,
                "openid" to studentId, "sn" to "sn", "mode" to "u"
            )
        )
        val usageHtml = client.getText(Endpoints.ISIM_USE_RECORD)
        val payHtml = client.getText(Endpoints.ISIM_PAY_RECORD)
        return ElectricityInfo(
            balance = parseBalance(usageHtml),
            usageRecords = parseUsage(usageHtml),
            payments = parsePayments(payHtml)
        )
    }

    // ---------- 解析 ----------

    /** JS 对象字面量 → code/name 平行列表（下标 0 通常是"请选择"，跳过） */
    private fun parseCodeName(body: String, codeKey: String, nameKey: String): List<Pair<String, String>> {
        val quoted = body.replace(Regex("""(?<!["'])([a-zA-Z_][a-zA-Z0-9_]*)\s*:"""), "\"$1\":")
        val obj = try {
            val arr = JSONArray(quoted)
            if (arr.length() > 0) arr.optJSONObject(0) else null
        } catch (_: Exception) {
            null
        } ?: try {
            JSONObject(quoted)
        } catch (_: Exception) {
            return emptyList()
        }
        val codes = obj.optJSONArray(codeKey) ?: return emptyList()
        val names = obj.optJSONArray(nameKey) ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 1 until minOf(codes.length(), names.length())) {
            val c = codes.optString(i)
            val n = names.optString(i)
            if (c.isNotBlank() && n != "请选择" && n.isNotBlank()) out.add(c to n)
        }
        return out
    }

    /**
     * 余额：采用"上下文窗口"策略——在数值行的上下 3 行内找"购电/补助"关键词，
     * 比固定选择器更耐 HTML 结构微调。
     */
    private fun parseBalance(html: String): ElectricityBalance {
        var purchased = 0.0
        var subsidy = 0.0
        val lines = html.split("\n")
        lines.forEachIndexed { i, line ->
            val amount = Regex("([\\d.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            if (amount != null) {
                val ctx = lines.subList(maxOf(0, i - 3), minOf(lines.size, i + 3)).joinToString(" ")
                when {
                    ctx.contains("购电") && !ctx.contains("补助") -> purchased = amount
                    ctx.contains("补助") -> subsidy = amount
                }
            }
        }
        return ElectricityBalance(purchased, subsidy)
    }

    private fun parseUsage(html: String): List<ElectricityUsageRecord> {
        val out = mutableListOf<ElectricityUsageRecord>()
        liChunks(html).forEach { chunk ->
            val flat = plainText(chunk)
            val usage = Regex("""(-?[\d.]+)\s*度""").find(flat)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: return@forEach
            out.add(
                ElectricityUsageRecord(
                    recordTime = detailedTime(chunk, flat),
                    usageAmount = usage,
                    meterName = meterOf(chunk, flat)
                )
            )
        }
        return out
    }

    private fun parsePayments(html: String): List<PaymentRecord> {
        val out = mutableListOf<PaymentRecord>()
        liChunks(html).forEach { chunk ->
            val flat = plainText(chunk)
            val amount = Regex("""(-?[\d.]+)\s*元""").find(flat)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: return@forEach
            out.add(
                PaymentRecord(
                    paymentTime = detailedTime(chunk, flat),
                    amount = amount,
                    paymentType = typeOf(chunk, flat)
                )
            )
        }
        return out
    }

    // ---------- 记录字段抽取 ----------

    private fun liChunks(html: String): List<String> =
        Regex("<li[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()

    /** 去掉标签，把换行/连续空白压成单个空格 */
    private fun plainText(chunk: String): String =
        chunk.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 抽「详细时间」。
     *
     * 旧实现直接对 `item-title` 取 `([^<]*)`，一旦时间被拆成
     * `10-27<span>14:32</span>` 就只剩前半截——这是界面上时间不全的根因。
     *
     * 现在改成「多候选 + 打分」：
     * 1. 把每个文本节点、以及整条记录的合并文本都作为候选交给 [RecordTime]；
     * 2. 由它按「有日期/有时刻/自带年份」打分，取最完整的一条；
     * 3. 再归一化成 `yyyy-MM-dd HH:mm` 这种好读的形式。
     */
    private fun detailedTime(chunk: String, flat: String): String {
        val nodes = textNodes(chunk) + flat
        return RecordTime.best(nodes)?.fullText?.takeIf { it.isNotBlank() } ?: "时间未知"
    }

    /** 所有文本节点 + 属性里可能藏着的 datetime/data-time */
    private fun textNodes(chunk: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""(?:datetime|data-time|data-date|data-timestamp|title)\s*=\s*["']([^"']{4,40})["']""",
            RegexOption.IGNORE_CASE
        ).findAll(chunk).forEach { out.add(it.groupValues[1]) }
        Regex(">([^<>]{1,60})<").findAll(chunk).forEach { out.add(it.groupValues[1]) }
        return out.map { it.replace("&nbsp;", " ").trim() }.filter { it.isNotEmpty() }
    }

    /** 电表名：优先从 item-subtitle 取，取不到就在整条文本里找「电表: xxx」 */
    private fun meterOf(chunk: String, flat: String): String {
        val sub = subtitleOf(chunk)
        Regex("""电表\s*[:：]\s*(.+)""").find(sub)?.groupValues?.get(1)?.trim()?.let {
            if (it.isNotBlank()) return it
        }
        return Regex("""电表\s*[:：]?\s*([^\s，,;；|]{1,24})""").find(flat)
            ?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 缴费类型：优先 item-subtitle，其次整条文本 */
    private fun typeOf(chunk: String, flat: String): String {
        val sub = subtitleOf(chunk)
        Regex("""类型\s*[:：]\s*(.+)""").find(sub)?.groupValues?.get(1)?.trim()?.let {
            if (it.isNotBlank()) return it
        }
        return Regex("""类型\s*[:：]?\s*([^\s，,;；|]{1,24})""").find(flat)
            ?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun subtitleOf(chunk: String): String {
        val m = Regex("""item-subtitle[^>]*>([\s\S]{0,160}?)</""", RegexOption.IGNORE_CASE).find(chunk)
            ?: return ""
        return m.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
    }
}
