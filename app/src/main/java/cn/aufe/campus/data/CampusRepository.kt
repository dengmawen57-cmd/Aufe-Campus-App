package cn.aufe.campus.data

import cn.aufe.campus.auth.AuthManager
import cn.aufe.campus.data.api.CompetitionApi
import cn.aufe.campus.data.api.CourseQueryApi
import cn.aufe.campus.data.api.EvaluationApi
import cn.aufe.campus.data.api.ExamApi
import cn.aufe.campus.data.api.JwApi
import cn.aufe.campus.data.api.IsimApi
import cn.aufe.campus.data.api.PowerPayApi
import cn.aufe.campus.data.api.RepairApi
import cn.aufe.campus.data.model.AcademicInfo
import cn.aufe.campus.data.model.BillOption
import cn.aufe.campus.data.model.CardBalance
import cn.aufe.campus.data.model.CompetitionData
import cn.aufe.campus.data.model.CourseQuery
import cn.aufe.campus.data.model.CourseQueryPage
import cn.aufe.campus.data.model.ElectricityInfo
import cn.aufe.campus.data.model.EvalCourse
import cn.aufe.campus.data.model.EvalCourseList
import cn.aufe.campus.data.model.EvalPrepared
import cn.aufe.campus.data.model.EvalResult
import cn.aufe.campus.data.model.ExamItem
import cn.aufe.campus.data.model.PayAccountInfo
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.data.model.QueryOptions
import cn.aufe.campus.data.model.RepairDetail
import cn.aufe.campus.data.model.RepairFormData
import cn.aufe.campus.data.model.RepairOrder
import cn.aufe.campus.data.model.RepairSubmit
import cn.aufe.campus.data.model.ScoreDetailItem
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.data.model.ScoreReport
import cn.aufe.campus.data.model.SelectableCourse
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.data.model.TermItem
import cn.aufe.campus.data.model.TransactionRecord
import cn.aufe.campus.data.model.UtilityPayResult
import cn.aufe.campus.util.WeekProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 数据仓库：把各系统 API 封装为挂起函数，全部在 IO 线程执行 */
class CampusRepository(private val auth: AuthManager) {

    // ---------- 教务 ----------

    suspend fun loadSchedule(): JwApi.CourseListResult = withContext(Dispatchers.IO) {
        auth.jwApi.fetchCourseList()
    }

    /**
     * 自动同步教学周次：从教务课表页读取"当前周"，成功就记下来作为权威值。
     * 读不到（页面没有该标记 / 网络异常）就保持本地推算，不影响课表展示。
     * 6 小时内不重复请求。
     */
    suspend fun syncTeachingWeek(): Int? = withContext(Dispatchers.IO) {
        if (!WeekProvider.shouldSync()) return@withContext null
        WeekProvider.markSyncTried()
        val week = runCatching { auth.jwApi.fetchTeachingWeek() }.getOrNull()
        if (week != null && week in 1..30) {
            WeekProvider.saveServerWeek(week)
            week
        } else null
    }

    suspend fun loadPlanCourses(): JwApi.CourseListResult = withContext(Dispatchers.IO) {
        auth.jwApi.fetchCourseList()
    }

    suspend fun searchFreeCourses(
        courseNo: String = "",
        courseName: String = "",
        teacher: String = ""
    ): JwApi.CourseListResult = withContext(Dispatchers.IO) {
        auth.jwApi.fetchFreeCourseList(courseNo = courseNo, courseName = courseName, teacher = teacher)
    }

    suspend fun submitSelection(courses: List<SelectableCourse>, dealType: Int): String =
        withContext(Dispatchers.IO) {
            auth.jwApi.submitSelection(courses, dealType)
        }

    suspend fun loadPlan(): Pair<PlanOverview, List<PlanGroup>> = withContext(Dispatchers.IO) {
        auth.jwApi.fetchPlan()
    }

    /** 教务成绩查询（分页，pageSize=100） */
    suspend fun loadScoreQueryPage(pageNum: Int = 1): JwApi.ScorePage = withContext(Dispatchers.IO) {
        auth.jwApi.fetchTermScores(pageNum = pageNum)
    }

    /** 学业概览：学校口径的 GPA / 排名 / 门数 */
    suspend fun loadAcademicInfo(): AcademicInfo = withContext(Dispatchers.IO) {
        auth.jwApi.fetchAcademicInfo()
    }

    /** 教务学期列表（用于按学期查成绩） */
    suspend fun loadTerms(): List<TermItem> = withContext(Dispatchers.IO) {
        auth.jwApi.fetchTerms()
    }

    /** 本学期成绩（多带 examTime，可查明细） */
    suspend fun loadThisTermScores(): List<ScoreRecord> = withContext(Dispatchers.IO) {
        auth.jwApi.fetchThisTermScores()
    }

    /** 指定学期的成绩（allTermScores + 学期计划号过滤，可分页） */
    suspend fun loadTermScores(termCode: String, pageNum: Int = 1): JwApi.ScorePage =
        withContext(Dispatchers.IO) {
            auth.jwApi.fetchTermScores(termPlanNo = termCode, pageNum = pageNum)
        }

    /** 单门课的成绩构成明细 */
    suspend fun loadScoreDetail(record: ScoreRecord): List<ScoreDetailItem> =
        withContext(Dispatchers.IO) {
            auth.jwApi.fetchScoreDetail(record)
        }

    // ---------- 爱安财 ----------

    suspend fun loadScoreDetail(): ScoreReport = withContext(Dispatchers.IO) {
        auth.dektApi.fetchScoreDetail(auth.dektToken())
    }

    suspend fun loadCertificate(): ScoreReport = withContext(Dispatchers.IO) {
        auth.dektApi.fetchCertificate(auth.dektToken())
    }

    // ---------- 一卡通 ----------
    // 余额接口很快（约 0.1s），消费明细服务端每页可达数十秒，故拆成两个入口分别加载

    suspend fun loadCardBalance(): CardBalance = withContext(Dispatchers.IO) {
        auth.yktApi().getBalance()
    }

    suspend fun loadCardRecords(startDate: String, endDate: String): List<TransactionRecord> =
        withContext(Dispatchers.IO) {
            auth.yktApi().getTransactions(startDate, endDate)
        }

    /** 默认统计范围：本月 1 号 ~ 今天 */
    suspend fun defaultCardRange(): Pair<String, String> = withContext(Dispatchers.IO) {
        auth.yktApi().defaultMonthRange()
    }

    // ---------- 宿舍电费 ----------

    /** 学号（电费系统 openid）：取登录时使用的账号（不依赖"记住密码"勾选） */
    private fun studentId(): String = auth.loginUser()

    private fun isimApi() = IsimApi(auth.client, studentId())

    suspend fun loadElectricBuildings(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { isimApi().getBuildings() }

    suspend fun loadElectricFloors(buildingCode: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { isimApi().getFloors(buildingCode) }

    suspend fun loadElectricRooms(floorCode: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { isimApi().getRooms(floorCode) }

    suspend fun loadElectricInfo(roomCode: String, roomName: String): ElectricityInfo =
        withContext(Dispatchers.IO) { isimApi().getElectricityInfo(roomCode, roomName) }

    // ---------- 电费充值（一卡通通道） ----------
    // 单例持有：SSO Cookie 换一次要发一个请求，级联选择要连发 4 次，
    // 每次新建实例会把「换 Cookie」重复 4 遍。

    private val powerPayApi by lazy { PowerPayApi(auth.client) }

    suspend fun loadPayAccount(): PayAccountInfo = withContext(Dispatchers.IO) {
        powerPayApi.fetchAccount()
    }

    suspend fun loadPayDorms(): List<BillOption> = withContext(Dispatchers.IO) {
        powerPayApi.fetchDorms()
    }

    suspend fun loadPayBuildings(dorm: BillOption): List<BillOption> = withContext(Dispatchers.IO) {
        powerPayApi.fetchBuildings(dorm.value, dorm.name)
    }

    suspend fun loadPayFloors(dorm: BillOption, building: BillOption): List<BillOption> =
        withContext(Dispatchers.IO) {
            powerPayApi.fetchFloors(dorm.value, building.value, dorm.name)
        }

    suspend fun loadPayRooms(
        dorm: BillOption,
        building: BillOption,
        floor: BillOption
    ): List<BillOption> = withContext(Dispatchers.IO) {
        powerPayApi.fetchRooms(dorm.value, building.value, floor.value, dorm.name)
    }

    /** 提交充值（真实扣款，调用方必须先二次确认） */
    suspend fun payElectricity(
        room: BillOption,
        dorm: BillOption,
        building: BillOption,
        floor: BillOption,
        account: PayAccountInfo,
        money: Int
    ): UtilityPayResult = withContext(Dispatchers.IO) {
        powerPayApi.pay(room, dorm, building, floor, account, money)
    }

    // ---------- v1.9.1 新增：开课查询 / 考试安排 / 竞赛获奖 / 零星维修 / 学生评教 ----------

    private val courseQueryApi by lazy { CourseQueryApi(auth.client) }
    private val examApi by lazy { ExamApi(auth.client) }
    private val competitionApi by lazy { CompetitionApi(auth.client) }
    private val repairApi by lazy { RepairApi(auth.client) }
    private val evaluationApi by lazy { EvaluationApi(auth.client) }

    /** 开课查询首页：学期 + 四类下拉选项 */
    suspend fun loadCourseQueryIndex(): QueryOptions = withContext(Dispatchers.IO) {
        courseQueryApi.loadIndex()
    }

    /** 开课查询分页 */
    suspend fun queryCourses(query: CourseQuery, pageNum: Int): CourseQueryPage =
        withContext(Dispatchers.IO) {
            courseQueryApi.queryPage(query, pageNum)
        }

    /** 考试安排（校统考 + 其他考试合并排序） */
    suspend fun loadExams(): List<ExamItem> = withContext(Dispatchers.IO) {
        // 学期计划号要现取：写死会在跨学期时查空
        val term = runCatching { auth.jwApi.fetchAcademicInfo().currentTerm }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { auth.jwApi.fetchTerms().firstOrNull()?.termCode }.getOrNull()
            ?: ""
        examApi.loadExams(term)
    }

    /** 竞赛获奖 + 学分汇总 */
    suspend fun loadCompetitions(): CompetitionData = withContext(Dispatchers.IO) {
        competitionApi.load()
    }

    suspend fun loadRepairOrders(): List<RepairOrder> = withContext(Dispatchers.IO) {
        repairApi.loadOrders()
    }

    suspend fun loadRepairDetail(taskId: String): RepairDetail = withContext(Dispatchers.IO) {
        repairApi.loadDetail(taskId)
    }

    suspend fun loadRepairForm(): RepairFormData = withContext(Dispatchers.IO) {
        repairApi.loadFormData()
    }

    suspend fun submitRepair(request: RepairSubmit): String = withContext(Dispatchers.IO) {
        repairApi.submit(request)
    }

    suspend fun loadEvaluationCourses(): EvalCourseList = withContext(Dispatchers.IO) {
        evaluationApi.loadCourses()
    }

    /**
     * 评教第一步：打开评价页、生成待提交表单。
     *
     * 和下面的 [submitEvaluation] 分开，是因为学校要求评价页停留 2 分钟以上，
     * 调用方必须在两步之间插倒计时。
     */
    suspend fun prepareEvaluation(
        course: EvalCourse,
        pendingCount: Int,
        indexToken: String
    ): EvalPrepared = withContext(Dispatchers.IO) {
        evaluationApi.prepare(course, pendingCount, indexToken)
    }

    /** 评教第二步：提交表单。失败不等同于没评上，需再核对列表 */
    suspend fun submitEvaluation(prepared: EvalPrepared): EvalResult = withContext(Dispatchers.IO) {
        evaluationApi.submit(prepared)
    }

    /** 评教首页最新 token */
    suspend fun refreshEvaluationToken(): String = withContext(Dispatchers.IO) {
        evaluationApi.fetchIndexToken()
    }

    /** 登录态变了：让 PHP 维修站重建自己的会话 */
    fun onSessionChanged() {
        runCatching { repairApi.invalidateSession() }
    }
}
