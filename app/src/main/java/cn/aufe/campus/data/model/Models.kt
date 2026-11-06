package cn.aufe.campus.data.model

/** 用户基本信息 */
data class UserInfo(
    val name: String = "",
    val studentNo: String = "",
    val grade: String = "",
    val className: String = "",
    val college: String = ""
)

/** 课程的一个时间段/地点 */
data class CourseSlot(
    val weekDesc: String = "",     // 如 "5-18周"、"第9周"
    val day: Int = 0,              // 星期 1~7
    val startSession: Int = 0,     // 开始节次
    val sessionCount: Int = 1,     // 持续节次
    val campus: String = "",
    val building: String = "",
    val room: String = ""
) {
    val timeText: String
        get() = buildString {
            append(weekDesc.ifEmpty { "每周" })
            append(" 周")
            append(dayOfWeekText)
            append(" 第")
            append(startSession)
            if (sessionCount > 1) append("~").append(startSession + sessionCount - 1)
            append("节")
        }

    val placeText: String
        get() = listOf(campus, building, room).filter { it.isNotBlank() }.joinToString(" · ")

    private val dayOfWeekText: String
        get() = when (day) {
            1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; 7 -> "日"
            else -> ""
        }
}

/** 已选课程（课表数据，来自 yxkclist） */
data class SelectedCourse(
    val courseNo: String = "",
    val classSeq: String = "",
    val termCode: String = "",
    val courseName: String = "",
    val credit: Double = 0.0,
    val category: String = "",
    val examType: String = "",
    val teacher: String = "",
    val selectStatus: String = "",
    val studyMode: String = "",
    val slots: List<CourseSlot> = emptyList()
)

/** 可选课程（选课数据，来自 rwfalist / rwRxkZlList） */
data class SelectableCourse(
    val termCode: String = "",
    val courseNo: String = "",
    val classSeq: String = "",
    val courseName: String = "",
    val credit: Double = 0.0,
    val category: String = "",
    val courseProperty: String = "",
    val examType: String = "",
    val teacher: String = "",
    val dept: String = "",
    val capacity: Int = 0,
    val slots: List<CourseSlot> = emptyList(),
    val note: String = "",
    val restrict: String = "",
    val selectRule: String = "",
    val fromPlan: Boolean = true
) {
    /** 选课提交用的课程标识：课程号@课序号@学期计划号 */
    val selectKey: String get() = "$courseNo@$classSeq@$termCode"
    /** 提交用的课程名编码（名称(课程号@课序号)） */
    val selectName: String get() = "$courseName($courseNo@$classSeq)"
}

/** 教务成绩记录（本科综合教务系统·全部成绩查询） */
data class ScoreRecord(
    val term: String = "",            // 学期，如 "2026-2027-1"
    val courseNo: String = "",
    val classSeq: String = "",
    val courseName: String = "",
    val enName: String = "",
    val credit: Double = 0.0,
    val hours: Double = 0.0,
    val property: String = "",        // 课程属性（必修/选修等）
    val examType: String = "",        // 考试类型（考试/考查等）
    val score: String = "",           // 成绩
    val gpa: String = "",             // 绩点
    val failReason: String = "",      // 未通过原因
    val examTime: String = ""         // 考试时间：仅"本学期成绩"接口下发，查成绩明细要用
) {
    val scoreValue: Double? get() = score.trim().toDoubleOrNull()

    /**
     * 是否通过。
     * 教务成绩既有百分制（"85"）也有等级制（"优秀"/"合格"/"不及格"），
     * 等级制没有数值，不能直接判为未通过。
     */
    val passed: Boolean
        get() {
            scoreValue?.let { return it >= 60 }
            val s = score.trim()
            if (s.isEmpty()) return false
            return when {
                s.contains("不及格") || s.contains("不合格") || s.contains("未通过") -> false
                s.contains("优秀") || s.contains("良好") || s.contains("中等") -> true
                s.contains("及格") || s.contains("合格") || s.contains("通过") -> true
                else -> false
            }
        }

    /** 是否已有成绩（用于判断能不能点开明细） */
    val hasScore: Boolean get() = score.trim().isNotEmpty()
}

/**
 * 学业概览：来自教务 `/main/academicInfo`，是**学校口径**的统计，
 * 与本地按成绩单逐门加权算出来的绩点不是一回事（学校会剔除某些课程/按培养方案折算）。
 * 界面上两个都给，并标注来源，避免"为什么和教务系统显示的不一样"。
 */
data class AcademicInfo(
    val completedCourses: Int = 0,      // 已修课程门数
    val failedCourses: Int = 0,         // 未通过门数
    val gpa: Double = 0.0,              // 官方平均学分绩点
    val averageScorePoint: Double = 0.0,// sspjf_jd：平均学分绩点（原始值）
    val averageRank: Int = 0,           // 平均学分绩点排名
    val averageRankTotal: Int = 0,      // 排名总人数
    val pendingCourses: Int = 0,        // 待修（必修未修）门数
    val currentTerm: String = ""        // 当前学期代码，如 2026-2027-1
) {
    val hasAverageRank: Boolean get() = averageRank > 0 && averageRankTotal > 0

    /** 排名处于前百分之多少（0~1，越靠前越小） */
    val rankPercent: Float
        get() = if (hasAverageRank) averageRank.toFloat() / averageRankTotal else 1f

    /** 超越了多少比例的同学（0~1，用于进度条） */
    val rankProgress: Float
        get() = if (hasAverageRank) {
            (1f - (averageRank - 1).toFloat() / averageRankTotal).coerceIn(0f, 1f)
        } else 0f
}

/** 教务学期（来自教务系统学期下拉框） */
data class TermItem(
    val termCode: String = "",   // 学期计划号，如 2026-2027-1
    val termName: String = "",   // 展示名，如 2026-2027上学期
    val isCurrent: Boolean = false
) {
    val shortName: String
        get() {
            // "2026-2027 上学期" → "26-27 上"；"2026-2027 短学期" → "26-27 短"
            val m = Regex("(\\d{4})-(\\d{4})\\s*([上下短])").find(termName)
            return if (m != null) {
                "${m.groupValues[1].takeLast(2)}-${m.groupValues[2].takeLast(2)} ${m.groupValues[3]}"
            } else termName.ifBlank { termCode }
        }
}

/** 一门课的成绩构成明细（平时 / 期中 / 期末 / 总评） */
data class ScoreDetailItem(
    val scoreType: String = "",     // 课堂成绩 / 实验成绩 / 实践成绩
    val usualScore: String = "",    // 平时
    val midtermScore: String = "",  // 期中
    val finalScore: String = "",    // 期末
    val categoryScore: String = "", // 分类总评
    val remark: String = ""
)

/** 爱安财第二课堂成绩单 */
data class ScoreReport(
    val userName: String = "",
    val userNo: String = "",
    val grade: String = "",
    val className: String = "",
    val college: String = "",
    val totalScore: Double = 0.0,
    val evaluation: String = "",
    val evaluationNote: String = "",
    val jobs: List<ScoreItem> = emptyList(),
    val awards: List<ScoreItem> = emptyList(),
    val groups: List<ScoreGroup> = emptyList()
)

data class ScoreItem(val name: String = "", val award: String = "", val score: String = "")

data class ScoreGroup(
    val groupName: String = "",   // 如 "智圆行方（智育）"
    val totalScore: String = "",
    val items: List<ScoreItem> = emptyList()
)

/** 培养方案总览 */
data class PlanOverview(
    val planName: String = "",
    val totalCourses: Int = 0,
    val totalCredits: Double = 0.0,
    val totalHours: Double = 0.0
)

/** 培养方案课组 */
data class PlanGroup(
    val name: String = "",
    val minCredit: Double = 0.0,
    val passedCredit: Double = 0.0,
    val requiredMissing: Int = 0,
    val children: List<PlanGroup> = emptyList()
) {
    val progress: Float
        get() = if (minCredit <= 0) 1f else (passedCredit / minCredit).coerceIn(0.0, 1.0).toFloat()
}

// ==================== 宿舍电费 ====================

/** 剩余电量：购电部分 + 补助部分 */
data class ElectricityBalance(
    val remainingPurchased: Double = 0.0,   // 剩余购电（度）
    val remainingSubsidy: Double = 0.0      // 剩余补助（度）
) {
    val total: Double get() = remainingPurchased + remainingSubsidy
}

/** 一条用电记录 */
data class ElectricityUsageRecord(
    val recordTime: String = "",
    val usageAmount: Double = 0.0,
    val meterName: String = ""
)

/** 一条缴费记录 */
data class PaymentRecord(
    val paymentTime: String = "",
    val amount: Double = 0.0,
    val paymentType: String = ""
)

/** 电费查询结果 */
data class ElectricityInfo(
    val balance: ElectricityBalance = ElectricityBalance(),
    val usageRecords: List<ElectricityUsageRecord> = emptyList(),
    val payments: List<PaymentRecord> = emptyList()
)

// ==================== 电费充值（走一卡通通道） ====================

/**
 * 电费充值页上的账号信息。
 *
 * [accId] 是提交充值表单时的必填项，藏在页面 `<input name="accId" value="...">` 里，
 * 值为纯数字，与学号不是一回事——所以不能拿学号顶替。
 */
data class PayAccountInfo(
    val studentId: String = "",
    val name: String = "",
    val accountStatus: String = "",
    val cardStatus: String = "",
    val balance: Double = 0.0,
    val accId: String = ""
) {
    /** 能否继续充值：拿到 accId 且卡里还有钱 */
    val canPay: Boolean get() = accId.isNotBlank() && balance > 0

    companion object {
        private fun clean(s: String): String = s
            .replace("&nbsp;", " ")
            .replace('\u00A0', ' ')
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** 取「`标签</label> <label>值</label>`」里的值 */
        private fun labelValue(html: String, label: String): String {
            val re = Regex(
                Regex.escape(label) + """\s*</label>\s*<label[^>]*>([\s\S]{0,40}?)</label>""",
                RegexOption.IGNORE_CASE
            )
            return re.find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
        }

        fun fromHtml(html: String): PayAccountInfo {
            val balance = labelValue(html, "校园余额").let {
                Regex("""-?[\d.]+""").find(it)?.value?.toDoubleOrNull()
            } ?: 0.0
            // accId 有写在 value 前的，也有写在后面的，两种都试
            val accId = Regex("""name\s*=\s*["']accId["'][^>]*?value\s*=\s*["'](\d+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)
                ?: Regex("""value\s*=\s*["'](\d+)["'][^>]*?name\s*=\s*["']accId["']""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.get(1)
                ?: ""

            return PayAccountInfo(
                studentId = labelValue(html, "编号").filter { it.isDigit() },
                name = labelValue(html, "姓名"),
                accountStatus = labelValue(html, "账户状态"),
                cardStatus = labelValue(html, "卡状态"),
                balance = balance,
                accId = accId
            )
        }
    }
}

/** 充值时的一级选项（校区 / 楼栋 / 楼层 / 房间） */
data class BillOption(val value: String, val name: String) {
    companion object {
        /**
         * 解析 `值,名称|值,名称|...` 这样的裸串。
         *
         * 服务端偶尔会多带一层 JSON 包装，所以这里先按 `|` 切、再按首个 `,` 切；
         * 名称里本身可能带逗号（如「东校区,1 号楼」），因此只切第一个逗号。
         */
        fun parseList(body: String): List<BillOption> {
            if (body.isBlank()) return emptyList()
            val src = body.trim().trim('"', '\'', ' ', '\n', '\r')
            if (src.startsWith("[") && src.endsWith("]")) {
                // 形如 ["1,东校区","2,西校区"]
                return Regex("""["']([^"']+)["']""").findAll(src).mapNotNull { m ->
                    toOption(m.groupValues[1])
                }.filter { it != null }.map { it!! }.toList()
            }
            return src.split("|").mapNotNull { toOption(it) }
        }

        private fun toOption(raw: String): BillOption? {
            val item = raw.trim().trim('"', '\'')
            if (item.isEmpty()) return null
            val idx = item.indexOf(',')
            if (idx <= 0) return null
            val value = item.substring(0, idx).trim()
            val name = item.substring(idx + 1).trim().trim('"', '\'')
            if (value.isEmpty() || name.isEmpty() || name == "请选择") return null
            return BillOption(value, name)
        }
    }
}

/** 充值结果 */
data class UtilityPayResult(val success: Boolean, val message: String) {
    companion object {
        /**
         * 结果页的判定：优先读隐藏域 `#message` 的 value。
         *
         * 注意服务端成功时也可能返回空 message，所以还要回退到正文关键词；
         * 两者都拿不到就**判失败**——宁可让用户再看一眼余额，也不能误报成功。
         */
        fun fromHtml(html: String): UtilityPayResult {
            val msg = Regex("""id\s*=\s*["']message["'][^>]*?value\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)
                ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*?id\s*=\s*["']message["']""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.get(1)
                ?: ""

            val text = msg.trim()
            return when {
                text.contains("成功") -> UtilityPayResult(true, text)
                text.isNotEmpty() -> UtilityPayResult(false, text)
                html.contains("缴费成功") || html.contains("充值成功") ->
                    UtilityPayResult(true, "充值成功")
                html.contains("余额不足") -> UtilityPayResult(false, "校园卡余额不足，请先充值一卡通")
                else -> UtilityPayResult(false, "未能确认充值结果，请稍后到电费页面查看余额变化")
            }
        }
    }
}

// ==================== 一卡通 ====================

/** 卡片余额 */
data class CardBalance(
    val balance: Double = 0.0,
    val balanceText: String = ""
)

/** 一卡通交易记录（消费 / 充值） */
data class TransactionRecord(
    val accountingTime: String = "",   // 记账时间
    val transactionTime: String = "",  // 交易时间
    val expense: Double? = null,       // 支出
    val income: Double? = null,        // 收入
    val operationType: String = "",    // 操作类型（消费/充值/补助…）
    val balance: Double = 0.0,         // 当笔后余额
    val area: String = "",             // 区域（商户/食堂）
    val terminalId: String = ""        // 终端号
) {
    val isExpense: Boolean get() = expense != null && expense > 0
    val isIncome: Boolean get() = income != null && income > 0
    val amountText: String
        get() = when {
            isIncome -> "+${"%.2f".format(income)}元"
            isExpense -> "-${"%.2f".format(expense)}元"
            else -> "0.00元"
        }
}
