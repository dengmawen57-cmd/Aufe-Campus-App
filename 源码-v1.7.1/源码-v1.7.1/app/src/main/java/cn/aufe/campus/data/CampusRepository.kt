package cn.aufe.campus.data

import cn.aufe.campus.auth.AuthManager
import cn.aufe.campus.data.api.JwApi
import cn.aufe.campus.data.api.IsimApi
import cn.aufe.campus.data.model.CardBalance
import cn.aufe.campus.data.model.ElectricityInfo
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.data.model.ScoreReport
import cn.aufe.campus.data.model.SelectableCourse
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.data.model.TransactionRecord
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
}
