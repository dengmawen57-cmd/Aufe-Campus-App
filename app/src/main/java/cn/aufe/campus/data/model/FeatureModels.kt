package cn.aufe.campus.data.model

/**
 * v1.9.1 新增五个模块的数据模型（移植自 LoveACE）。
 *
 * 为什么要单独一个文件：这五个模块的数据结构彼此独立、
 * 又都不属于"课表/成绩/一卡通"这类核心数据，混进现有 Models.kt
 * 会把那个文件撑到看不出结构。这里按模块分段，一段一个模块。
 */

// ==================== 开课查询 ====================

/** 下拉筛选项（学期 / 院系 / 校区 / 教学楼 / 课程类别通用） */
data class QueryOption(
    val code: String,
    val name: String
)

/** 开课查询首页解析出来的全部筛选项 */
data class QueryOptions(
    val terms: List<QueryOption> = emptyList(),
    val departments: List<QueryOption> = emptyList(),
    val campuses: List<QueryOption> = emptyList(),
    val buildings: List<QueryOption> = emptyList(),
    val categories: List<QueryOption> = emptyList()
)

/** 查询条件：空串表示不限 */
data class CourseQuery(
    val termCode: String = "",
    val courseNo: String = "",
    val courseName: String = "",
    val teacher: String = "",
    val department: String = "",
    val campus: String = "",
    val building: String = "",
    val category: String = "",
    val weekday: Int? = null,
    val startSession: Int? = null
)

/** 一条开课记录（一门课的某个教学班） */
data class OfferedCourse(
    val courseNo: String = "",
    val classNo: String = "",
    val courseName: String = "",
    val credits: String = "",
    val hours: String = "",
    val teacher: String = "",
    /** 上课时间，如「周一 第1-2节」 */
    val timeText: String = "",
    /** 上课地点，如「东校区 明德楼 301」 */
    val placeText: String = "",
    /** 开课校区 */
    val campusName: String = "",
    /** 课程类别，如「专业必修」 */
    val categoryName: String = "",
    /** 起止周描述 */
    val weeks: String = "",
    /** 考试类型 */
    val examType: String = "",
    /** 容量 */
    val capacity: String = "",
    /** 选课人数 */
    val selectedCount: String = "",
    /** 备注/说明 */
    val remark: String = ""
)

/** 一页开课记录 + 总条数（用于"加载更多"判断到底了没） */
data class CourseQueryPage(
    val records: List<OfferedCourse> = emptyList(),
    val total: Int = 0
)

// ==================== 零星维修 ====================

/** 报修工单 */
data class RepairOrder(
    val taskId: String = "",
    val title: String = "",
    val orderNumber: String = "",
    val workHours: String = "",
    val reporter: String = "",
    val location: String = "",
    val createTime: String = "",
    /** 0 待接单 / 1 待完工 / 2 待评价 / 3 已完成 */
    val status: Int = 0,
    val statusText: String = ""
)

/** 维修进度节点 */
data class RepairProgress(
    val stage: String = "",
    val time: String = "",
    val desc: String = ""
)

/** 工单详情 */
data class RepairDetail(
    val taskId: String = "",
    val faultArea: String = "",
    val repairProject: String = "",
    val phone: String = "",
    val faultAddress: String = "",
    val description: String = "",
    val progress: List<RepairProgress> = emptyList(),
    val settlements: List<RepairSettlement> = emptyList()
)

/** 决算单一行 */
data class RepairSettlement(
    val serviceName: String = "",
    val material: String = "",
    val workPoints: String = ""
)

/** 报修可选项（区域 / 维修项目共用） */
data class RepairOption(
    val id: String,
    val name: String
)

/** 带分组的可选项（页面按楼栋/类别分组展示） */
data class RepairOptionGroup(
    val name: String,
    val items: List<RepairOption> = emptyList()
)

/** 报修表单的可选数据 */
data class RepairFormData(
    val areas: List<RepairOptionGroup> = emptyList(),
    val projects: List<RepairOptionGroup> = emptyList()
)

/** 提交报修请求 */
data class RepairSubmit(
    val areaId: String,
    val areaName: String,
    val projectId: String,
    val projectName: String,
    val phone: String,
    val address: String,
    val description: String
)

// ==================== 竞赛获奖 ====================

/** 一条获奖申报 */
data class AwardProject(
    val projectId: String = "",
    val projectName: String = "",
    val level: String = "",
    val grade: String = "",
    val awardDate: String = "",
    val applicantId: String = "",
    val applicantName: String = "",
    val order: Int = 0,
    val credits: Double = 0.0,
    val bonus: Double = 0.0,
    val status: String = "",
    val verificationStatus: String = ""
)

/** 学分汇总（学校口径的六个维度） */
data class CreditsSummary(
    val disciplineCompetition: Double? = null,
    val scientificResearch: Double? = null,
    val transferableCompetition: Double? = null,
    val innovationPractice: Double? = null,
    val abilityCertification: Double? = null,
    val otherProject: Double? = null
) {
    val total: Double
        get() = listOfNotNull(
            disciplineCompetition,
            scientificResearch,
            transferableCompetition,
            innovationPractice,
            abilityCertification,
            otherProject
        ).sum()
}

/** 竞赛页整体数据 */
data class CompetitionData(
    val awards: List<AwardProject> = emptyList(),
    val summary: CreditsSummary? = null
)

// ==================== 自动评价 ====================

/** 待评价课程 */
data class EvalCourse(
    val name: String = "",
    val teacher: String = "",
    val evaluatedPeople: String = "",
    val evaluatedPeopleNumber: String = "",
    val courseSeq: String = "",
    val contentNumber: String = "",
    val questionnaireCode: String = "",
    val questionnaireName: String = "",
    val evaluated: Boolean = false
)

/**
 * 评教课程列表的加载结果。
 *
 * - [closed]：学校关了评估开关，没什么可评的
 * - [waitSeconds] > 0：首次进入的倒计时保护，必须等够了学校才认；
 *   提前提交会被判无效，所以这段时间只能等，不能"帮用户跳过"
 */
data class EvalCourseList(
    val closed: Boolean = false,
    val closedMessage: String = "",
    val waitSeconds: Int = 0,
    val courses: List<EvalCourse> = emptyList()
) {
    val pending: List<EvalCourse> get() = courses.filter { !it.evaluated }
    val done: List<EvalCourse> get() = courses.filter { it.evaluated }
}

/** 单门课的提交结果 */
data class EvalResult(
    val courseName: String = "",
    val success: Boolean = false,
    val message: String = ""
)

/**
 * 已经填好答案、等倒计时走完就能提交的一份评价表单（v1.9.8）。
 *
 * 拆出来只为一件事：教务处的评教页要求**在页面上停留 2 分钟以上**才承认提交，
 * 所以"打开评价页"和"提交"之间必须能插进一段等待。
 * 一次性 token 也决定了两步不能拆得太远 —— 表单要连着用，不能缓存复用。
 */
data class EvalPrepared(
    val course: EvalCourse = EvalCourse(),
    val form: Map<String, String> = emptyMap()
)

// ==================== 考试安排 ====================

/** 一场考试 */
data class ExamItem(
    val courseName: String = "",
    val date: String = "",
    val time: String = "",
    val location: String = "",
    /** 校统考 / 其他考试 */
    val type: String = "",
    /** 座位号等备注 */
    val note: String = ""
)
