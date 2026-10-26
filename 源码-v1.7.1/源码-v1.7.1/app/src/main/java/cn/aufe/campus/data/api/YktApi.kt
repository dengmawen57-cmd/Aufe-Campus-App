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
 */
class YktApi(private val client: AufeClient) {

    @Volatile
    private var sessionReady = false

    /** 初始化一卡通会话：VPN 会话 → 一卡通 SSO Cookie。查询前必须调用一次 */
    fun initSession(force: Boolean = false) {
        if (sessionReady && !force) return
        runCatching {
            client.get(Endpoints.YKT_SSO_LOGIN).close()
        }
        sessionReady = true
    }

    /** 余额 */
    fun getBalance(): CardBalance {
        initSession()
        val html = client.getText(Endpoints.YKT_BALANCE)
        val value = BALANCE_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
            ?: throw java.io.IOException("未能解析一卡通余额（可能未登录或页面结构变化）")
        val amount = value.toDoubleOrNull()
            ?: throw java.io.IOException("余额格式异常：$value")
        return CardBalance(amount, "%.2f".format(amount) + "元")
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
