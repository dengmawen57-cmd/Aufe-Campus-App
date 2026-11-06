package cn.aufe.campus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.aufe.campus.CampusApp
import cn.aufe.campus.data.CampusRepository
import cn.aufe.campus.data.model.CompetitionData
import cn.aufe.campus.data.model.CourseQuery
import cn.aufe.campus.data.model.EvalCourse
import cn.aufe.campus.data.model.EvalResult
import cn.aufe.campus.data.model.ExamItem
import cn.aufe.campus.data.model.OfferedCourse
import cn.aufe.campus.data.model.QueryOptions
import cn.aufe.campus.data.model.RepairDetail
import cn.aufe.campus.data.model.RepairFormData
import cn.aufe.campus.data.model.RepairOrder
import cn.aufe.campus.data.model.RepairSubmit
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 一门课的稳定标识：教师 + 课程名 + 课程号。
 * 光用课程名不够——同一门课不同老师、同名不同课的情况都真实存在。
 */
private fun keyOf(c: EvalCourse): String =
    listOf(c.teacher, c.name, c.contentNumber).joinToString("|")

/**
 * v1.9.1 新增五个模块的视图模型。
 *
 * 为什么不塞进 [CampusViewModel]：那个类已经承担课表 / 选课 / 成绩 / 一卡通 /
 * 电费等核心链路，再加五个模块会变成一个谁都改不动的巨型类。
 * 这五个模块彼此独立、都只在各自页面用，单独一个 VM 更清楚，
 * 也不会因为某个新模块的 bug 影响主流程。
 */
class FeatureViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = (app as CampusApp).authManager
    private val repo = CampusRepository(auth)

    // ==================== 开课查询 ====================

    data class CourseQueryState(
        val options: QueryOptions = QueryOptions(),
        val query: CourseQuery = CourseQuery(),
        val records: List<OfferedCourse> = emptyList(),
        val total: Int = 0,
        val page: Int = 0,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String = "",
        val optionsLoaded: Boolean = false,
        /** 已经查过一次（用来区分"还没查"和"查了但没结果"） */
        val searched: Boolean = false
    ) {
        val canLoadMore: Boolean get() = records.size < total
    }

    private val _courseQuery = MutableStateFlow(CourseQueryState())
    val courseQuery = _courseQuery.asStateFlow()

    /** 只加载一次首页筛选项（学期 / 院系 / 校区 …） */
    fun loadCourseQueryOptions(force: Boolean = false) {
        val s = _courseQuery.value
        if (s.optionsLoaded && !force) return
        if (s.loading) return
        _courseQuery.value = s.copy(loading = true, error = "")
        viewModelScope.launch {
            runCatching { repo.loadCourseQueryIndex() }
                .onSuccess { options ->
                    val defaultTerm = options.terms.firstOrNull { it.name.isNotBlank() }?.code ?: ""
                    _courseQuery.value = _courseQuery.value.copy(
                        options = options,
                        optionsLoaded = true,
                        loading = false,
                        query = _courseQuery.value.query.copy(
                            termCode = _courseQuery.value.query.termCode.ifBlank { defaultTerm }
                        )
                    )
                }
                .onFailure {
                    _courseQuery.value = _courseQuery.value.copy(
                        loading = false,
                        error = it.message ?: "获取查询条件失败"
                    )
                }
        }
    }

    fun updateCourseQuery(block: (CourseQuery) -> CourseQuery) {
        _courseQuery.value = _courseQuery.value.copy(query = block(_courseQuery.value.query))
    }

    /** 第一页（点"查询"或首次进入） */
    fun searchCourses() {
        val s = _courseQuery.value
        if (s.loading) return
        if (s.query.termCode.isBlank()) {
            _courseQuery.value = s.copy(error = "请先选择学期")
            return
        }
        _courseQuery.value = s.copy(loading = true, error = "", searched = true)
        viewModelScope.launch {
            runCatching { repo.queryCourses(s.query, 1) }
                .onSuccess { page ->
                    _courseQuery.value = _courseQuery.value.copy(
                        records = page.records,
                        total = page.total,
                        page = 1,
                        loading = false,
                        error = ""
                    )
                }
                .onFailure {
                    _courseQuery.value = _courseQuery.value.copy(
                        records = emptyList(),
                        total = 0,
                        page = 0,
                        loading = false,
                        error = it.message ?: "查询失败"
                    )
                }
        }
    }

    /** 滚到底部加载下一页 */
    fun loadMoreCourses() {
        val s = _courseQuery.value
        if (s.loading || s.loadingMore || !s.canLoadMore) return
        _courseQuery.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            runCatching { repo.queryCourses(s.query, s.page + 1) }
                .onSuccess { page ->
                    val merged = _courseQuery.value.records + page.records
                    _courseQuery.value = _courseQuery.value.copy(
                        records = merged,
                        total = if (page.total > 0) page.total else merged.size,
                        page = s.page + 1,
                        loadingMore = false
                    )
                }
                .onFailure {
                    _courseQuery.value = _courseQuery.value.copy(
                        loadingMore = false,
                        error = it.message ?: "加载更多失败"
                    )
                }
        }
    }

    // ==================== 考试安排 ====================

    data class ExamState(
        val items: List<ExamItem> = emptyList(),
        val loading: Boolean = false,
        val error: String = "",
        val loaded: Boolean = false
    )

    private val _exam = MutableStateFlow(ExamState())
    val exam = _exam.asStateFlow()

    fun loadExams(force: Boolean = false) {
        val s = _exam.value
        if (s.loading) return
        if (s.loaded && !force) return
        _exam.value = s.copy(loading = true, error = "")
        viewModelScope.launch {
            runCatching { repo.loadExams() }
                .onSuccess {
                    _exam.value = ExamState(items = it, loading = false, loaded = true)
                }
                .onFailure {
                    _exam.value = ExamState(
                        items = s.items,
                        loading = false,
                        loaded = true,
                        error = it.message ?: "获取考试安排失败"
                    )
                }
        }
    }

    // ==================== 竞赛获奖 ====================

    data class CompetitionState(
        val data: CompetitionData = CompetitionData(),
        val loading: Boolean = false,
        val error: String = "",
        val loaded: Boolean = false
    )

    private val _competition = MutableStateFlow(CompetitionState())
    val competition = _competition.asStateFlow()

    fun loadCompetition(force: Boolean = false) {
        val s = _competition.value
        if (s.loading) return
        if (s.loaded && !force) return
        _competition.value = s.copy(loading = true, error = "")
        viewModelScope.launch {
            runCatching { repo.loadCompetitions() }
                .onSuccess {
                    _competition.value = CompetitionState(data = it, loading = false, loaded = true)
                }
                .onFailure {
                    _competition.value = CompetitionState(
                        data = s.data,
                        loading = false,
                        loaded = true,
                        error = it.message ?: "获取竞赛信息失败"
                    )
                }
        }
    }

    // ==================== 零星维修 ====================

    data class RepairState(
        val orders: List<RepairOrder> = emptyList(),
        val detail: RepairDetail? = null,
        val form: RepairFormData? = null,
        val loading: Boolean = false,
        val detailLoading: Boolean = false,
        val formLoading: Boolean = false,
        val submitting: Boolean = false,
        val error: String = "",
        val message: String = "",
        val loaded: Boolean = false
    ) {
        val pending: List<RepairOrder>
            get() = orders.filter { it.status == 0 || it.status == 1 }
        val finished: List<RepairOrder>
            get() = orders.filter { it.status == 2 || it.status == 3 }
    }

    private val _repair = MutableStateFlow(RepairState())
    val repair = _repair.asStateFlow()

    fun loadRepairOrders(force: Boolean = false) {
        val s = _repair.value
        if (s.loading) return
        if (s.loaded && !force) return
        _repair.value = s.copy(loading = true, error = "", message = "")
        viewModelScope.launch {
            runCatching { repo.loadRepairOrders() }
                .onSuccess {
                    _repair.value = _repair.value.copy(
                        orders = it, loading = false, loaded = true, error = ""
                    )
                }
                .onFailure {
                    _repair.value = _repair.value.copy(
                        loading = false, loaded = true, error = it.message ?: "获取工单失败"
                    )
                }
        }
    }

    fun openRepairDetail(taskId: String) {
        _repair.value = _repair.value.copy(detailLoading = true, detail = null, error = "")
        viewModelScope.launch {
            runCatching { repo.loadRepairDetail(taskId) }
                .onSuccess {
                    _repair.value = _repair.value.copy(detail = it, detailLoading = false)
                }
                .onFailure {
                    _repair.value = _repair.value.copy(
                        detailLoading = false,
                        error = it.message ?: "获取工单详情失败"
                    )
                }
        }
    }

    fun closeRepairDetail() {
        _repair.value = _repair.value.copy(detail = null)
    }

    fun loadRepairForm(force: Boolean = false) {
        val s = _repair.value
        if (s.formLoading) return
        if (s.form != null && !force) return
        _repair.value = s.copy(formLoading = true, error = "")
        viewModelScope.launch {
            runCatching { repo.loadRepairForm() }
                .onSuccess {
                    _repair.value = _repair.value.copy(form = it, formLoading = false)
                }
                .onFailure {
                    _repair.value = _repair.value.copy(
                        formLoading = false,
                        error = it.message ?: "获取报修信息失败"
                    )
                }
        }
    }

    fun submitRepair(request: RepairSubmit) {
        if (_repair.value.submitting) return
        _repair.value = _repair.value.copy(submitting = true, error = "", message = "")
        viewModelScope.launch {
            runCatching { repo.submitRepair(request) }
                .onSuccess { msg ->
                    _repair.value = _repair.value.copy(submitting = false, message = msg)
                    loadRepairOrders(force = true)
                }
                .onFailure {
                    _repair.value = _repair.value.copy(
                        submitting = false,
                        error = it.message ?: "提交报修失败"
                    )
                }
        }
    }

    fun clearRepairMessage() {
        _repair.value = _repair.value.copy(message = "", error = "")
    }

    // ==================== 学生评教 ====================

    /** 一门课的评教进度（v1.11.3：多门课同时跑，所以每门占一行） */
    data class EvalTask(
        val key: String,
        val name: String,
        val teacher: String,
        val phase: String = "排队中",
        /** 当前这门还要停留几秒（0 表示不在计时阶段） */
        val waitLeft: Int = 0,
        val waitTotal: Int = 0,
        val done: Boolean = false,
        val ok: Boolean = false
    )

    data class EvalState(
        val courses: List<EvalCourse> = emptyList(),
        val token: String = "",
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val closed: Boolean = false,
        val closedMessage: String = "",
        /** 首次访问保护倒计时剩余秒数，> 0 时不能提交 */
        val waitLeft: Int = 0,
        val running: Boolean = false,
        val progress: String = "",
        /** 已完成几门 / 共几门（只统计有结果的） */
        val finishedCount: Int = 0,
        val totalCount: Int = 0,
        val results: List<EvalResult> = emptyList(),
        /** 用户勾选了哪几门（key 见 [keyOf]）。默认全选待评，可手动取消 */
        val selected: Set<String> = emptySet(),
        /** 正在跑的每一门的实时状态 */
        val tasks: List<EvalTask> = emptyList(),
        val error: String = ""
    ) {
        val pending: List<EvalCourse> get() = courses.filter { !it.evaluated }
        val done: List<EvalCourse> get() = courses.filter { it.evaluated }

        /** 本次真要评的：勾了哪几门就评哪几门 */
        val chosen: List<EvalCourse> get() = pending.filter { keyOf(it) in selected }

        fun isSelected(course: EvalCourse): Boolean = keyOf(course) in selected
    }

    private val _eval = MutableStateFlow(EvalState())
    val evaluation = _eval.asStateFlow()

    private var countdownJob: Job? = null

    /** 批量评教任务（可被「停止」取消） */
    private var evalJob: Job? = null

    /**
     * 已跑过几轮倒计时。
     *
     * 学校的倒计时是按**服务端时间**算的，本地等完再拉，服务端可能还没到点。
     * 所以最多跑两轮，之后再拉就不等了，直接进入列表 ——
     * 否则可能无限"等完→重拉→又要等"。
     */
    private var evalWaitRounds = 0

    fun loadEvaluation(force: Boolean = false) {
        val s = _eval.value
        if (s.loading) return
        if (s.loaded && !force) return
        _eval.value = s.copy(loading = true, error = "")
        viewModelScope.launch {
            runCatching { repo.loadEvaluationCourses() }
                .onSuccess { list ->
                    if (list.closed) {
                        _eval.value = EvalState(
                            closed = true, closedMessage = list.closedMessage, loaded = true
                        )
                        return@onSuccess
                    }
                    if (list.waitSeconds > 0 && evalWaitRounds < 2) {
                        // 首次访问保护：先把倒计时跑完，再真正拉课程
                        evalWaitRounds++
                        _eval.value = EvalState(waitLeft = list.waitSeconds, loaded = false)
                        startCountdown()
                        return@onSuccess
                    }
                    val token = runCatching { repo.refreshEvaluationToken() }.getOrDefault("")
                    _eval.value = EvalState(
                        courses = list.courses,
                        token = token,
                        loaded = true,
                        // 默认全选待评：进来就能一键跑，想挑人的自己取消勾选
                        selected = list.courses.filter { !it.evaluated }
                            .mapTo(HashSet()) { keyOf(it) }
                    )
                }
                .onFailure {
                    _eval.value = _eval.value.copy(
                        loading = false, loaded = true, error = it.message ?: "获取评教课程失败"
                    )
                }
        }
    }

    /** 倒计时结束后自动重新拉列表 */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_eval.value.waitLeft > 0) {
                delay(1_000)
                _eval.value = _eval.value.copy(waitLeft = _eval.value.waitLeft - 1)
            }
            // 等完了再从首页拿一次 token 和课程
            _eval.value = _eval.value.copy(loaded = false)
            loadEvaluation(force = true)
        }
    }

    fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    // ---------- 勾选要评的课 ----------

    /** 勾选/取消一门课（教师 + 课程 + 课程号三者定位，同名课不会串） */
    fun toggleEvalSelection(course: EvalCourse) {
        val key = keyOf(course)
        val cur = _eval.value.selected
        _eval.value = _eval.value.copy(selected = if (key in cur) cur - key else cur + key)
    }

    /** 全选（只选待评的） */
    fun selectAllPending() {
        _eval.value = _eval.value.copy(
            selected = _eval.value.pending.mapTo(HashSet()) { keyOf(it) }
        )
    }

    /** 全不选 */
    fun clearEvalSelection() {
        _eval.value = _eval.value.copy(selected = emptySet())
    }

    /**
     * 开始批量评教（v1.11.3 改成「错峰 + 流水线」）。
     *
     * ## 为什么能快一个数量级
     *
     * 服务端要的是"这张评价页**打开过**、并且已经过了 2 分钟"，它记的是打开时间，
     * 不是"提交前连续停留 2 分钟"。所以完全可以叠着来：
     *
     * ```
     * t=0     打开第 1 门，开始计时
     * t=10s   打开第 2 门，开始计时
     * ...
     * t=140s  提交第 1 门
     * t=150s  提交第 2 门
     * ```
     *
     * 18 门课从 18×146 秒（≈44 分钟）压到 140 + 17×10 秒（≈5 分钟）。
     *
     * ## 但请求本身不并发
     *
     * 评教 token 是一次性的，服务端还靠 `count` 维护进度，同一瞬间打两个
     * `evaluationPage` 会把状态搅乱。所以真正的 HTTP 调用串在同一把锁上，
     * 只有"等计时"是重叠的 —— 省时间，又不至于把账号打进风控。
     */
    fun startEvaluation() {
        val s = _eval.value
        if (s.running || s.waitLeft > 0) return
        val chosen = s.chosen
        if (chosen.isEmpty()) return
        if (s.token.isBlank()) {
            _eval.value = s.copy(error = "评教 token 还没拿到，先点右上角刷新一次")
            return
        }

        evalJob?.cancel()

        val total = chosen.size
        val tasks = ConcurrentHashMap<String, EvalTask>()
        chosen.forEach {
            tasks[keyOf(it)] = EvalTask(key = keyOf(it), name = it.name, teacher = it.teacher)
        }

        val results = CopyOnWriteArrayList<EvalResult>()
        val failures = AtomicInteger(0)
        val aborted = AtomicBoolean(false)
        val callMutex = Mutex()
        val tokenRef = AtomicReference(s.token)

        // 任务状态在多条协程里并发更新：每次都从 map 重算一份列表交给界面，
        // 这样谁后写都不会把别人的进度覆盖掉
        val publish: () -> Unit = {
            val list = chosen.mapNotNull { tasks[keyOf(it)] }
            val done = list.count { it.done }
            val ticking = list.count { !it.done && it.waitLeft > 0 }
            _eval.value = _eval.value.copy(
                tasks = list,
                progress = buildString {
                    append("已完成 $done/$total")
                    if (ticking > 0) append(" · 计时中 $ticking 门")
                }
            )
        }

        _eval.value = s.copy(
            running = true,
            error = "",
            results = emptyList(),
            progress = "正在启动…",
            finishedCount = 0,
            totalCount = total,
            tasks = tasks.values.toList()
        )

        evalJob = viewModelScope.launch {
            val batch = this
            try {
                coroutineScope {
                    chosen.forEachIndexed { index, course ->
                        launch course@{
                            val key = keyOf(course)

                            // 更新"这门课现在走到哪一步了"并同步给界面
                            fun step(
                                phase: String,
                                waitLeft: Int = 0,
                                done: Boolean = false,
                                ok: Boolean = false
                            ) {
                                tasks[key] = EvalTask(
                                    key = key,
                                    name = course.name,
                                    teacher = course.teacher,
                                    phase = phase,
                                    waitLeft = waitLeft,
                                    waitTotal = if (waitLeft > 0) EVAL_READ_SECONDS else 0,
                                    done = done,
                                    ok = ok
                                )
                                publish()
                            }

                            // 停留倒计时：服务端只认"打开之后过了多久"，
                            // 所以这一段是可以多门课同时跑的
                            suspend fun dwell(prefix: String) {
                                for (left in EVAL_READ_SECONDS downTo 1) {
                                    step(if (prefix.isBlank()) "计时中" else "$prefix 计时中", left)
                                    delay(1_000)
                                }
                            }

                            fun record(result: EvalResult) {
                                results.add(result)
                                _eval.value = _eval.value.copy(
                                    results = results.toList(),
                                    finishedCount = results.size
                                )
                                if (!result.success) failures.incrementAndGet()
                                step(
                                    if (result.success) "已完成" else "失败：${result.message}",
                                    done = true,
                                    ok = result.success
                                )
                                // 连崩 3 门：多半是评教被关或会话失效，别让用户白等
                                if (failures.get() >= MAX_FAILURES_BEFORE_ABORT && !aborted.get()) {
                                    aborted.set(true)
                                    batch.cancel()
                                }
                            }

                            // 错峰：第 i 门在第 i*10 秒才打开，让计时彼此重叠
                            if (index > 0) delay(index * STAGGER_MS)

                            step("打开评价页")
                            val prepared = runCatching {
                                callMutex.withLock {
                                    repo.prepareEvaluation(course, total, tokenRef.get())
                                }
                            }.getOrElse {
                                record(EvalResult(course.name, false, it.message ?: "打开评价页失败"))
                                return@course
                            }

                            dwell("")

                            step("提交中")
                            var result = runCatching {
                                callMutex.withLock { repo.submitEvaluation(prepared) }
                            }.getOrElse {
                                EvalResult(course.name, false, it.message ?: "提交失败")
                            }

                            // 失败 ≠ 没评上（token 过期、重复提交都长这样）：
                            // 先核一遍服务端，真没评上才值得再花 140 秒
                            if (!result.success) {
                                step("核对结果")
                                if (isEvaluatedOnServer(course)) {
                                    result = EvalResult(course.name, true, "提交成功（服务端已确认）")
                                } else {
                                    val fresh = runCatching { repo.refreshEvaluationToken() }
                                        .getOrNull().orEmpty()
                                    if (fresh.isNotBlank()) tokenRef.set(fresh)
                                    val retry = runCatching {
                                        callMutex.withLock {
                                            repo.prepareEvaluation(course, total, tokenRef.get())
                                        }
                                    }.getOrNull()
                                    if (retry != null) {
                                        dwell("重试")
                                        step("重新提交")
                                        result = runCatching {
                                            callMutex.withLock { repo.submitEvaluation(retry) }
                                        }.getOrElse {
                                            EvalResult(course.name, false, it.message ?: "提交失败")
                                        }
                                    }
                                }
                            }

                            record(result)
                        }
                    }
                }
            } finally {
                // 用户点「停止」、或触发中止时，也要把状态和列表校准回来
                withContext(NonCancellable) {
                    var courses = _eval.value.courses
                    runCatching { repo.loadEvaluationCourses() }.getOrNull()?.let { list ->
                        if (list.courses.isNotEmpty()) courses = list.courses
                    }
                    val fresh = runCatching { repo.refreshEvaluationToken() }.getOrNull()
                    val cur = _eval.value
                    _eval.value = cur.copy(
                        running = false,
                        progress = "",
                        courses = courses,
                        token = fresh?.takeIf { it.isNotBlank() } ?: tokenRef.get(),
                        // 评完一轮把勾选重置成"还剩哪些没评"，省得用户手动改
                        selected = courses.filter { !it.evaluated }
                            .mapTo(HashSet()) { keyOf(it) },
                        error = if (aborted.get()) ABORT_MESSAGE else cur.error
                    )
                }
            }
        }
    }

    /** 服务端课程列表里这门课是否已标"已评"（提交失败时用来兜底核对） */
    private suspend fun isEvaluatedOnServer(course: EvalCourse): Boolean {
        val list = runCatching { repo.loadEvaluationCourses() }.getOrNull() ?: return false
        return list.courses.firstOrNull {
            it.name == course.name && it.teacher == course.teacher
        }?.evaluated == true
    }

    /** 中止批量评教（已提交的评价不会撤回） */
    fun stopEvaluation() {
        evalJob?.cancel()
        evalJob = null
        _eval.value = _eval.value.copy(
            running = false,
            progress = "",
            tasks = emptyList(),
            error = "已停止：剩下的课没评，已提交的不会撤回"
        )
    }

    fun clearEvalError() {
        _eval.value = _eval.value.copy(error = "")
    }

    /** 登录态变了：PHP 维修站要重建会话，各模块缓存也不再可信 */
    fun onSessionChanged() {
        repo.onSessionChanged()
        _repair.value = RepairState()
        _eval.value = EvalState()
        _exam.value = ExamState()
        _competition.value = CompetitionState()
    }

    companion object {
        /**
         * 评价页必须停留的秒数。
         *
         * 学校的要求是「2 分钟以上」，这里取 140 秒而不是 120：
         * 服务端按它自己收到的请求时间算，本地压着 120 秒整，
         * 网络往返和时钟误差很容易又差那几百毫秒被判无效。
         */
        private const val EVAL_READ_SECONDS = 140

        /**
         * 相邻两门课"打开评价页"的间隔（v1.11.3）。
         *
         * 服务端只认"页面打开后过了多久"，所以多门课可以同时计时。
         * 10 秒的间距足够让上一门的请求落地，又能把 18 门课的计时压进同一段时间。
         */
        private const val STAGGER_MS = 10_000L

        /** 连续失败这么多门就别再耗下去了 */
        private const val MAX_FAILURES_BEFORE_ABORT = 3

        private const val ABORT_MESSAGE =
            "连续多门提交失败，已中止（免得白等）。可能是评教已关闭或会话失效，刷新后再试"
    }
}
