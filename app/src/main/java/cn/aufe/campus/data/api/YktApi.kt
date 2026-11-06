package cn.aufe.campus.data.api

import cn.aufe.campus.data.model.CardBalance
import cn.aufe.campus.data.model.TransactionRecord
import cn.aufe.campus.data.net.AufeClient
import cn.aufe.campus.data.net.Endpoints
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 一卡通系统：余额查询、消费记录（移植自彩带小工具 YKTService）。
 *
 * 注意：查询前必须先调用 [initSession]，用已有 VPN 会话换取一卡通自身的 SSO Cookie，
 * 否则所有接口都会返回登录页（表现为余额为空）。
 *
 * ## v1.9.1 修复「长时间不登录，重新登录后余额刷新不出来，必须杀进程才恢复」
 *
 * 旧实现的 [sessionReady] 一旦置 true 就**永不重置**，而一卡通的 SSO 会话
 * 是学校服务端单独维护的，长时间不用会失效；VPN/CAS 那边重新登录
 * （得到新的 TWFID）并不会自动把一卡通的 SSO Cookie 也换成新的。
 *
 * 于是状态就卡住了：`sessionReady == true` → 跳过 SSO 换取 → 余额页返回的是
 * 一卡通自己的登录页 → 正则匹配不到"余额" → 抛 IOException → 小卡片的
 * `catch (_: Exception)` 静默吞掉 → 用户点一百次刷新也没反应。
 * 只有杀进程（`sessionReady` 归零）才会重新换 SSO，所以"退出停掉后台再登录"能好。
 *
 * 现在三层保险：
 * 1. [invalidateSession] 在每次登录成功时被调用（见 `AuthManager`），主动归零；
 * 2. 解析不出余额 / 命中一卡通登录页 → [initSession] 强制重建一次再试；
 * 3. `sessionReady` 只在 SSO 那一步真的成功时才置位，失败不再谎报就绪。
 */
class YktApi(private val client: AufeClient) {

    @Volatile
    private var sessionReady = false

    /** 主动作废 SSO 会话标记：登录态换了（重登/切号）必须调用 */
    fun invalidateSession() {
        sessionReady = false
    }

    /** 初始化一卡通会话：VPN 会话 → 一卡通 SSO Cookie。查询前必须调用一次 */
    fun initSession(force: Boolean = false) {
        if (sessionReady && !force) return
        // 只有真的没被网关拦下才算换到 SSO，否则保持 false，
        // 让下一次调用还会再试（以前这里无条件置 true，是本次 bug 的根源）
        val ok = runCatching {
            client.get(Endpoints.YKT_SSO_LOGIN).use { it.isSuccessful }
        }.getOrDefault(false)
        if (ok) sessionReady = true
    }

    /** 余额 */
    fun getBalance(): CardBalance {
        initSession()
        var html = client.getText(Endpoints.YKT_BALANCE)
        var value = parseBalance(html)

        // 一卡通的 SSO Cookie 过期 → 页面变成它自己的登录页。
        // 这里强制重建一次 SSO 再查，用户不必杀进程。
        if (value == null) {
            initSession(force = true)
            html = client.getText(Endpoints.YKT_BALANCE)
            value = parseBalance(html)
        }

        val amount = value?.toDoubleOrNull()
            ?: throw java.io.IOException("未能解析一卡通余额（可能未登录或页面结构变化）")
        return CardBalance(amount, "%.2f".format(amount) + "元")
    }

    /** 从页面里抠出余额数字；抠不到返回 null */
    private fun parseBalance(html: String): String? {
        // 一卡通登录页里也有"元"字，先排除掉，免得误匹配
        if (html.contains("userPassword") || html.contains("一卡通登录")) return null
        return BALANCE_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
    }

    /** 消费记录，日期格式 yyyy-MM-dd（服务端较慢，走 slow 客户端） */
    fun getTransactions(startDate: String, endDate: String): List<TransactionRecord> {
        initSession()
        val html = client.postFormText(
            Endpoints.YKT_COST_LIST,
            mapOf("startDate" to startDate, "endDate" to endDate),
            slow = true
        )
        return parseTransactions(html)
    }

    /** 默认统计范围：本月 1 号 ~ 今天 */
    fun defaultMonthRange(): Pair<String, String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance()
        val end = fmt.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = fmt.format(cal.time)
        return start to end
    }

    private fun parseTransactions(html: String): List<TransactionRecord> {
        val records = mutableListOf<TransactionRecord>()
        val rows = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE).findAll(html)
        for (row in rows) {
            val cells = Regex("<t[dh][^>]*>([\\s\\S]*?)</t[dh]>", RegexOption.IGNORE_CASE)
                .findAll(row.groupValues[1])
                .map { clean(it.groupValues[1]) }
                .toList()
            if (cells.size < 8) continue
            // 跳过表头行
            if (cells[0].contains("记账") || cells[0].contains("时间") && cells[2].contains("支出")) continue

            val expense = cells[2].replace(Regex("[^\\d.\\-]"), "").let {
                if (it.isBlank() || it == "-") null else it.toDoubleOrNull()
            }
            val income = cells[3].replace(Regex("[^\\d.\\-]"), "").let {
                if (it.isBlank() || it == "-") null else it.toDoubleOrNull()
            }
            val balance = cells[5].replace(Regex("[^\\d.\\-]"), "").toDoubleOrNull() ?: 0.0
            if (expense == null && income == null && balance == 0.0) continue

            records.add(
                TransactionRecord(
                    accountingTime = cells[0],
                    transactionTime = cells[1],
                    expense = expense,
                    income = income,
                    operationType = cells[4],
                    balance = balance,
                    area = cells[6],
                    terminalId = cells[7]
                )
            )
        }
        return records
    }

    private fun clean(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()

    companion object {
        /** 余额解析候选（页面结构可能微调，逐个尝试） */
        private val BALANCE_PATTERNS = listOf(
            Regex("余额[：:]?\\s*</label>\\s*<label>\\s*([\\d.]+)\\s*元", RegexOption.IGNORE_CASE),
            Regex("余额[：:]?\\s*([\\d.]+)\\s*元", RegexOption.IGNORE_CASE),
            Regex("balance[\"']?\\s*[:=]\\s*[\"']?([\\d.]+)", RegexOption.IGNORE_CASE),
            Regex("([\\d.]+)\\s*元")
        )
    }
}
