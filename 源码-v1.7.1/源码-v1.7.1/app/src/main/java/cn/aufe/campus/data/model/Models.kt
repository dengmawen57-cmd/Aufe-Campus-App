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
    val failReason: String = ""       // 未通过原因
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
}

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
