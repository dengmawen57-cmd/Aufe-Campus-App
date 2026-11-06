package cn.aufe.campus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.aufe.campus.CampusApp
import cn.aufe.campus.data.CampusRepository
import cn.aufe.campus.data.api.JwApi
import cn.aufe.campus.data.model.AcademicInfo
import cn.aufe.campus.data.model.BillOption
import cn.aufe.campus.data.model.CardBalance
import cn.aufe.campus.data.model.ElectricityInfo
import cn.aufe.campus.data.model.PayAccountInfo
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.data.model.ScoreDetailItem
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.data.model.ScoreReport
import cn.aufe.campus.data.model.SelectableCourse
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.data.model.TermItem
import cn.aufe.campus.data.model.TransactionRecord
import cn.aufe.campus.data.model.UtilityPayResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 通用界面状态 */
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

/** 全局视图模型：课表 / 选课 / 成绩单 / 成绩查询 / 培养方案 */
class CampusViewModel(app: Application) : AndroidViewModel(app) {

    val auth = (app as CampusApp).authManager
    private val repo = CampusRepository(auth)

    private companion object {
        /** 教务成绩接口单页条数 */
        const val SCORE_PAGE_SIZE = 100
    }

    init {
        // 会话被后台静默重登后（loginEpoch 递增），把之前失败/过期的缓存清掉重拉，
        // 这样"掉线→自动重登"对用户来说就是页面自己恢复了，而不是一直显示错误。
        viewModelScope.launch {
            var epoch = auth.loginEpoch.value
            auth.loginEpoch.collect { e ->
                if (e == epoch) return@collect
                epoch = e
                onSessionRestored()
            }
        }
    }

    /** 静默重登成功后的缓存修复策略：只重拉"用户看过/拉失败"的数据，避免无谓流量 */
    private fun onSessionRestored() {
        if (_schedule.value.loaded || _schedule.value.error.isNotBlank()) {
            _schedule.value = _schedule.value.copy(loaded = false)
            loadSchedule(force = true)
        }
        if (_ykt.value.balance != null || _ykt.value.balanceError.isNotBlank()) {
            loadYktBalance(force = true)
        }
        // 其余页面下次进入时自然重新加载
        _score.value = ScoreState()
        _scoreQuery.value = ScoreQueryState()
        termScoreCache.clear()
        _plan.value = PlanState()
        _select.value = SelectState()
        if (_electric.value.buildings.isNotEmpty()) {
            _electric.value = _electric.value.copy(error = "")
            loadElectricBuildings(force = true)
        }
    }

    // ---------- 课表 ----------
    data class ScheduleState(
        val loading: Boolean = false,
        val courses: List<SelectedCourse> = emptyList(),
        val error: String = "",
        val loaded: Boolean = false
    )

    private val _schedule = MutableStateFlow(ScheduleState())
    val schedule = _schedule.asStateFlow()

    fun loadSchedule(force: Boolean = false) {
        if (_schedule.value.loaded && !force && _schedule.value.error.isBlank()) return
        _schedule.value = _schedule.value.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val r = repo.loadSchedule()
                _schedule.value = ScheduleState(
                    loading = false,
                    courses = r.selected,
                    error = "",
                    loaded = true
                )
                // 教学周校准丢到后台：它只是拿页面上的"第几周"去纠正本地推算，
                // 放在关键路径上等于让用户多等一次请求（v1.11.3）
                viewModelScope.launch {
                    runCatching { repo.syncTeachingWeek() }
                }
                // 刷新桌面小组件缓存并重建（今日课程 + 一周课表）
                val ctx = getApplication<CampusApp>().applicationContext
                runCatching {
                    cn.aufe.campus.widget.ScheduleWidgetSync.sync(ctx, r.selected)
                }
            } catch (e: Exception) {
                _schedule.value = _schedule.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    // ---------- 选课 ----------
    data class SelectState(
        val loading: Boolean = false,
        val planCourses: List<SelectableCourse> = emptyList(),
        val freeCourses: List<SelectableCourse> = emptyList(),
        val selected: List<SelectedCourse> = emptyList(),
        val notInPhase: Boolean = false,
        val message: String = "",
        val submitting: Boolean = false,
        val submitResult: String = "",
        val error: String = ""
    )

    private val _select = MutableStateFlow(SelectState())
    val select = _select.asStateFlow()

    /** 已勾选待提交的课程（key = selectKey） */
    private val _cart = MutableStateFlow<Set<String>>(emptySet())
    val cart = _cart.asStateFlow()

    fun toggleCart(key: String) {
        val cur = _cart.value
        _cart.value = if (key in cur) cur - key else cur + key
    }

    fun clearCart() {
        _cart.value = emptySet()
    }

    fun loadSelectData(force: Boolean = false) {
        if (_select.value.loading) return
        if (_select.value.planCourses.isNotEmpty() && !force) return
        _select.value = _select.value.copy(loading = true, error = "", message = "")
        viewModelScope.launch {
            try {
                // 方案内 / 自由选课两张表互不依赖，串着拉等于白白多等一次往返（v1.11.3）
                val (plan, free) = coroutineScope {
                    val planDeferred = async { repo.loadPlanCourses() }
                    val freeDeferred = async {
                        try {
                            repo.searchFreeCourses()
                        } catch (_: Exception) {
                            JwApi.CourseListResult()
                        }
                    }
                    planDeferred.await() to freeDeferred.await()
                }
                _select.value = SelectState(
                    loading = false,
                    planCourses = plan.planCourses,
                    freeCourses = free.planCourses,
                    selected = plan.selected,
                    notInPhase = plan.planCourses.isEmpty() && free.planCourses.isEmpty(),
                    message = if (plan.planCourses.isEmpty() && free.planCourses.isEmpty())
                        "当前为非选课阶段，暂无可选课程" else ""
                )
            } catch (e: Exception) {
                _select.value = _select.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** 提交选课：dealType 2=方案内, 5=自由 */
    fun submitSelection(dealType: Int, onDone: (String) -> Unit) {
        val keys = _cart.value
        val pool = if (dealType == 2) _select.value.planCourses else _select.value.freeCourses
        val chosen = pool.filter { it.selectKey in keys }
        if (chosen.isEmpty()) {
            onDone("请先勾选要提交的课程")
            return
        }
        _select.value = _select.value.copy(submitting = true, submitResult = "")
        viewModelScope.launch {
            val result = try {
                repo.submitSelection(chosen, dealType)
            } catch (e: Exception) {
                "提交失败：${e.message}"
            }
            _select.value = _select.value.copy(submitting = false, submitResult = result)
            clearCart()
            onDone(result)
            // 刷新已选列表
            runCatching { repo.loadPlanCourses() }.onSuccess { r ->
                _select.value = _select.value.copy(selected = r.selected)
            }
        }
    }

    // ---------- 成绩单 ----------
    data class ScoreState(
        val loading: Boolean = false,
        val report: ScoreReport? = null,
        val error: String = "",
        val loaded: Boolean = false
    )

    private val _score = MutableStateFlow(ScoreState())
    val score = _score.asStateFlow()

    fun loadScore(force: Boolean = false) {
        if (_score.value.loaded && !force) return
        _score.value = _score.value.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val r = repo.loadScoreDetail()
                _score.value = ScoreState(loading = false, report = r, loaded = true)
            } catch (e: Exception) {
                _score.value = _score.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    // ---------- 教务成绩查询 ----------

    /**
     * 成绩查询状态。
     *
     * 这里刻意分成三块互不干扰的数据：
     * - [records]：当前选中学期的成绩（按学期切换查看，对齐教务"分学期成绩单"）
     * - [allRecords]：全量成绩（绩点统计必须基于全量，分页只拿前 100 条会算错）
     * - [academic]：学校口径的 GPA/排名概览
     */
    data class ScoreQueryState(
        val loading: Boolean = false,
        val records: List<ScoreRecord> = emptyList(),
        val totalCount: Int = 0,
        val loadingMore: Boolean = false,
        val noMore: Boolean = false,
        val error: String = "",

        // 学业概览（学校口径）
        val academic: AcademicInfo? = null,
        val academicLoading: Boolean = false,
        val academicError: String = "",

        // 学期
        val terms: List<TermItem> = emptyList(),
        val selectedTerm: TermItem? = null,

        // 全量成绩（绩点页）
        val allRecords: List<ScoreRecord> = emptyList(),
        val allLoading: Boolean = false,
        val allNoMore: Boolean = false,
        val allError: String = "",

        // 成绩明细
        val detailRecord: ScoreRecord? = null,
        val detailItems: List<ScoreDetailItem> = emptyList(),
        val detailLoading: Boolean = false,
        val detailError: String = ""
    ) {
        /** 首屏是否已经拿到过数据（学期列表已就绪） */
        val loaded: Boolean get() = terms.isNotEmpty()
    }

    private val _scoreQuery = MutableStateFlow(ScoreQueryState())
    val scoreQuery = _scoreQuery.asStateFlow()

    /** 学期成绩内存缓存：来回切 Tab 不重复打教务（按学期计划号） */
    private val termScoreCache = mutableMapOf<String, List<ScoreRecord>>()

    /**
     * 首屏：并行拉「学业概览」和「学期列表」，然后加载当前学期的成绩。
     *
     * 学业概览失败不影响成绩列表（有些账号没这个接口权限），
     * 因此两块状态分开记，界面各自降级。
     */
    fun loadScoreQuery(force: Boolean = false) {
        val s = _scoreQuery.value
        if (s.loading || s.loadingMore) return
        if (s.loaded && !force && s.error.isBlank()) return
        if (force) termScoreCache.clear()
        _scoreQuery.value = s.copy(loading = true, error = "")
        loadAcademic(force = force)
        viewModelScope.launch {
            try {
                val terms = repo.loadTerms()
                // 学期列表解析万一不准（教务页面改版时会出现），用「学业概览」里
                // 的当前学期号（zxjxjhh）再校正一次：这个值来自接口，最可信。
                val official = _scoreQuery.value.academic?.currentTerm.orEmpty()
                val fixed = mergeOfficialTerm(terms, official)
                val current = fixed.firstOrNull { it.isCurrent } ?: fixed.firstOrNull()
                _scoreQuery.value = _scoreQuery.value.copy(
                    loading = false,
                    terms = fixed,
                    selectedTerm = current,
                    error = if (current == null) "未获取到学期列表" else ""
                )
                if (current != null) loadTermScores(current, force = force)
            } catch (e: Exception) {
                _scoreQuery.value = _scoreQuery.value.copy(
                    loading = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }

    /**
     * 用官方当前学期号校正学期列表。
     *
     * 两种修正：
     * 1. 官方学期号已存在于列表 → 把它的 `isCurrent` 置为 true，其余清掉；
     * 2. 不存在 → 插到最前面并标记为当前（说明下拉框解析漏了本学期）。
     *
     * 名称统一由学期号推导，避免再出现颜色码之类的内容被当成学期名。
     */
    private fun mergeOfficialTerm(terms: List<TermItem>, officialTerm: String): List<TermItem> {
        val code = officialTerm.trim()
        if (code.isBlank()) return terms
        val name = termNameOf(code)
        val exists = terms.any { it.termCode == code }
        return if (exists) {
            terms.map { it.copy(isCurrent = it.termCode == code) }
        } else {
            listOf(TermItem(code, name, true)) + terms.map { it.copy(isCurrent = false) }
        }
    }

    /** `2026-2027-1` → `2026-2027 上学期` */
    private fun termNameOf(code: String): String {
        val m = Regex("(\\d{4})\\s*[-–—]\\s*(\\d{4})(?:\\s*[-–—]\\s*(\\d))?").find(code)
            ?: return code
        val part = m.groupValues.getOrNull(3).orEmpty()
        val suffix = when (part) {
            "1" -> " 上学期"
            "2" -> " 下学期"
            "3" -> " 短学期"
            else -> ""
        }
        return "${m.groupValues[1]}-${m.groupValues[2]}$suffix"
    }

    /** 学业概览：学校口径 GPA / 排名 / 已修门数 */
    fun loadAcademic(force: Boolean = false) {
        val s = _scoreQuery.value
        if (s.academicLoading) return
        if (!force && s.academic != null) return
        _scoreQuery.value = s.copy(academicLoading = true, academicError = "")
        viewModelScope.launch {
            try {
                val info = repo.loadAcademicInfo()
                _scoreQuery.value = _scoreQuery.value.copy(
                    academicLoading = false,
                    academic = info,
                    academicError = ""
                )
            } catch (e: Exception) {
                _scoreQuery.value = _scoreQuery.value.copy(
                    academicLoading = false,
                    academicError = e.message ?: "学业概览获取失败"
                )
            }
        }
    }

    /**
     * 加载指定学期的成绩。
     *
     * 当前学期走 thisTermScores：它多带 examTime，点开课程才能查「平时/期中/期末」；
     * 历史学期走 allTermScores + 学期计划号过滤。
     */
    fun loadTermScores(term: TermItem, force: Boolean = false) {
        if (!force) {
            termScoreCache[term.termCode]?.let { cached ->
                _scoreQuery.value = _scoreQuery.value.copy(
                    records = cached,
                    totalCount = cached.size,
                    noMore = cached.size < SCORE_PAGE_SIZE,
                    loading = false,
                    error = ""
                )
                return
            }
        }
        _scoreQuery.value = _scoreQuery.value.copy(loading = true, error = "", records = emptyList())
        viewModelScope.launch {
            try {
                if (term.isCurrent) {
                    // 当前学期：一次性返回全部，且带 examTime
                    val list = repo.loadThisTermScores()
                    termScoreCache[term.termCode] = list
                    _scoreQuery.value = _scoreQuery.value.copy(
                        loading = false,
                        records = list,
                        totalCount = list.size,
                        noMore = true,
                        error = ""
                    )
                } else {
                    val page = repo.loadTermScores(term.termCode, 1)
                    termScoreCache[term.termCode] = page.records
                    _scoreQuery.value = _scoreQuery.value.copy(
                        loading = false,
                        records = page.records,
                        totalCount = page.totalCount,
                        noMore = page.records.size >= page.totalCount || page.records.isEmpty(),
                        error = ""
                    )
                }
            } catch (e: Exception) {
                _scoreQuery.value = _scoreQuery.value.copy(
                    loading = false,
                    error = friendlyError(e, "成绩")
                )
            }
        }
    }

    /** 切换到某个学期 */
    fun selectTerm(term: TermItem) {
        if (_scoreQuery.value.selectedTerm?.termCode == term.termCode) return
        _scoreQuery.value = _scoreQuery.value.copy(
            selectedTerm = term,
            records = emptyList(),
            detailRecord = null,
            detailItems = emptyList(),
            detailError = "",
            noMore = false
        )
        loadTermScores(term)
    }

    /**
     * 历史学期分页：教务 allTermScores 一页 100 条，通常一个学期不会超过，
     * 但学期跨度大 / 有重修记录时会超，这里按需补页。
     */
    fun loadMoreScoreQuery() {
        val s = _scoreQuery.value
        val term = s.selectedTerm ?: return
        if (term.isCurrent || s.loading || s.loadingMore || s.noMore) return
        _scoreQuery.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val nextPage = s.records.size / SCORE_PAGE_SIZE + 1
                val p = repo.loadTermScores(term.termCode, nextPage)
                val merged = s.records + p.records
                termScoreCache[term.termCode] = merged
                _scoreQuery.value = s.copy(
                    records = merged,
                    totalCount = p.totalCount,
                    loadingMore = false,
                    noMore = merged.size >= p.totalCount || p.records.isEmpty()
                )
            } catch (e: Exception) {
                _scoreQuery.value = s.copy(loadingMore = false)
            }
        }
    }

    /**
     * 一次性把全部成绩拉完（绩点统计必须基于全量）。
     * 已有记录会被保留，只往后补页；最多 20 页（2000 条）防止死循环。
     */
    fun loadAllScores(force: Boolean = false) {
        val s = _scoreQuery.value
        if (s.allLoading) return
        if (!force) {
            // 已经拉完，或上一次失败（等用户手动刷新，避免自动重试打转）
            if (s.allNoMore || s.allError.isNotBlank()) return
        }
        _scoreQuery.value = s.copy(allLoading = true, allError = "")
        viewModelScope.launch {
            var records = if (force) emptyList() else s.allRecords
            var page = records.size / SCORE_PAGE_SIZE + 1
            var reachedEnd = false
            var knownTotal = 0
            var failed = false
            try {
                while (page <= 20) {
                    val p = repo.loadScoreQueryPage(page)
                    if (p.records.isEmpty()) {
                        // 接口没给总数时，靠"空页"判断到底
                        reachedEnd = true
                        break
                    }
                    records = records + p.records
                    if (p.totalCount > 0) {
                        knownTotal = p.totalCount
                        if (records.size >= knownTotal) {
                            reachedEnd = true
                            break
                        }
                    }
                    page++
                }
            } catch (_: Exception) {
                failed = true
            }
            _scoreQuery.value = _scoreQuery.value.copy(
                allRecords = records,
                allLoading = false,
                allNoMore = reachedEnd || (knownTotal > 0 && records.size >= knownTotal),
                allError = if (failed && records.isEmpty()) "成绩加载失败，请重试" else ""
            )
        }
    }

    /** 打开某门课的成绩明细（仅本学期有效：教务只在当学期页面开放此入口） */
    fun loadScoreDetail(record: ScoreRecord) {
        _scoreQuery.value = _scoreQuery.value.copy(
            detailRecord = record,
            detailItems = emptyList(),
            detailLoading = true,
            detailError = ""
        )
        viewModelScope.launch {
            try {
                val items = repo.loadScoreDetail(record)
                _scoreQuery.value = _scoreQuery.value.copy(
                    detailLoading = false,
                    detailItems = items,
                    detailError = if (items.isEmpty()) "教务未提供这门课的成绩构成" else ""
                )
            } catch (e: Exception) {
                _scoreQuery.value = _scoreQuery.value.copy(
                    detailLoading = false,
                    detailError = e.message ?: "成绩明细获取失败"
                )
            }
        }
    }

    fun dismissScoreDetail() {
        _scoreQuery.value = _scoreQuery.value.copy(
            detailRecord = null,
            detailItems = emptyList(),
            detailLoading = false,
            detailError = ""
        )
    }

    // ---------- 培养方案 ----------
    data class PlanState(
        val loading: Boolean = false,
        val overview: PlanOverview? = null,
        val groups: List<PlanGroup> = emptyList(),
        val error: String = "",
        val loaded: Boolean = false
    )

    private val _plan = MutableStateFlow(PlanState())
    val plan = _plan.asStateFlow()

    fun loadPlan(force: Boolean = false) {
        if (_plan.value.loaded && !force) return
        _plan.value = _plan.value.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val (o, g) = repo.loadPlan()
                _plan.value = PlanState(loading = false, overview = o, groups = g, loaded = true)
            } catch (e: Exception) {
                _plan.value = _plan.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    // ---------- 宿舍电费 ----------
    data class ElectricState(
        val loading: Boolean = false,
        val buildings: List<Pair<String, String>> = emptyList(),   // 楼栋码 -> 楼栋名
        val floors: List<Pair<String, String>> = emptyList(),      // 楼层码 -> 楼层名
        val rooms: List<Pair<String, String>> = emptyList(),       // 房间码 -> 房间名
        val buildingIdx: Int = -1,
        val floorIdx: Int = -1,
        val roomIdx: Int = -1,
        val info: ElectricityInfo? = null,
        val infoLoading: Boolean = false,
        val error: String = ""
    )

    private val _electric = MutableStateFlow(ElectricState())
    val electric = _electric.asStateFlow()

    /** 上次选择的房间记忆（避免每次重选三级） */
    private fun electricPrefs() =
        getApplication<CampusApp>().getSharedPreferences("aufe_electric", android.content.Context.MODE_PRIVATE)

    fun loadElectricBuildings(force: Boolean = false) {
        if (_electric.value.buildings.isNotEmpty() && !force) return
        _electric.value = _electric.value.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val list = repo.loadElectricBuildings()
                _electric.value = _electric.value.copy(loading = false, buildings = list)
                // 记忆回填：自动定位到上次楼栋
                val saved = electricPrefs().getString("building_code", "") ?: ""
                if (saved.isNotBlank()) {
                    val idx = list.indexOfFirst { it.first == saved }
                    if (idx >= 0) selectBuilding(idx, restore = true)
                }
            } catch (ex: Exception) {
                _electric.value = _electric.value.copy(
                    loading = false, error = friendlyError(ex, "楼栋列表")
                )
            }
        }
    }

    fun selectBuilding(idx: Int, restore: Boolean = false) {
        val s = _electric.value
        if (idx !in s.buildings.indices) return
        val (code, name) = s.buildings[idx]
        electricPrefs().edit().putString("building_code", code).putString("building_name", name).apply()
        _electric.value = s.copy(
            buildingIdx = idx, floors = emptyList(), floorIdx = -1,
            rooms = emptyList(), roomIdx = -1, info = null, error = ""
        )
        viewModelScope.launch {
            try {
                val floors = repo.loadElectricFloors(code)
                _electric.value = _electric.value.copy(floors = floors)
                if (restore) {
                    val saved = electricPrefs().getString("floor_code", "") ?: ""
                    val fi = floors.indexOfFirst { it.first == saved }
                    if (fi >= 0) selectFloor(fi, restore = true)
                }
            } catch (ex: Exception) {
                _electric.value = _electric.value.copy(error = friendlyError(ex, "楼层列表"))
            }
        }
    }

    fun selectFloor(idx: Int, restore: Boolean = false) {
        val s = _electric.value
        if (idx !in s.floors.indices) return
        val (code, name) = s.floors[idx]
        electricPrefs().edit().putString("floor_code", code).putString("floor_name", name).apply()
        _electric.value = s.copy(
            floorIdx = idx, rooms = emptyList(), roomIdx = -1, info = null, error = ""
        )
        viewModelScope.launch {
            try {
                val rooms = repo.loadElectricRooms(code)
                _electric.value = _electric.value.copy(rooms = rooms)
                if (restore) {
                    val saved = electricPrefs().getString("room_code", "") ?: ""
                    val ri = rooms.indexOfFirst { it.first == saved }
                    if (ri >= 0) selectRoom(ri)
                }
            } catch (ex: Exception) {
                _electric.value = _electric.value.copy(error = friendlyError(ex, "房间列表"))
            }
        }
    }

    fun selectRoom(idx: Int) {
        val s = _electric.value
        if (idx !in s.rooms.indices) return
        val (code, name) = s.rooms[idx]
        electricPrefs().edit().putString("room_code", code).putString("room_name", name).apply()
        _electric.value = s.copy(roomIdx = idx, infoLoading = true, info = null, error = "")
        viewModelScope.launch {
            try {
                val info = repo.loadElectricInfo(code, name)
                _electric.value = _electric.value.copy(infoLoading = false, info = info)
            } catch (ex: Exception) {
                _electric.value = _electric.value.copy(
                    infoLoading = false, error = friendlyError(ex, "电费信息")
                )
            }
        }
    }

    // ---------- 电费充值（一卡通通道） ----------
    //
    // 与上面的 ElectricState 是两套东西：那边查 ISIM 的用电/缴费记录，
    // 这边走一卡通的 utility* 接口从校园卡扣款。房间编码不通用，所以各管各的级联。

    data class RechargeState(
        val account: PayAccountInfo? = null,
        val accountLoading: Boolean = false,
        val dorms: List<BillOption> = emptyList(),
        val buildings: List<BillOption> = emptyList(),
        val floors: List<BillOption> = emptyList(),
        val rooms: List<BillOption> = emptyList(),
        val dormIdx: Int = -1,
        val buildingIdx: Int = -1,
        val floorIdx: Int = -1,
        val roomIdx: Int = -1,
        val optionsLoading: Boolean = false,
        val submitting: Boolean = false,
        val result: UtilityPayResult? = null,
        val error: String = ""
    ) {
        val dorm: BillOption? get() = dorms.getOrNull(dormIdx)
        val building: BillOption? get() = buildings.getOrNull(buildingIdx)
        val floor: BillOption? get() = floors.getOrNull(floorIdx)
        val room: BillOption? get() = rooms.getOrNull(roomIdx)

        /** 四项都选齐才允许提交 */
        val ready: Boolean
            get() = account != null && dorm != null && building != null &&
                floor != null && room != null
    }

    private val _recharge = MutableStateFlow(RechargeState())
    val recharge = _recharge.asStateFlow()

    /** 打开充值弹层：账号信息只在没拿到时加载，避免每次开都多一次请求 */
    fun openRecharge() {
        _recharge.value = _recharge.value.copy(result = null, error = "")
        if (_recharge.value.account == null) loadPayAccount()
        if (_recharge.value.dorms.isEmpty()) loadPayDorms()
    }

    fun closeRecharge() {
        _recharge.value = _recharge.value.copy(result = null, error = "")
    }

    fun loadPayAccount(force: Boolean = false) {
        if (_recharge.value.account != null && !force) return
        _recharge.value = _recharge.value.copy(accountLoading = true, error = "")
        viewModelScope.launch {
            try {
                val info = repo.loadPayAccount()
                _recharge.value = _recharge.value.copy(accountLoading = false, account = info)
            } catch (ex: Exception) {
                _recharge.value = _recharge.value.copy(
                    accountLoading = false, error = friendlyError(ex, "充值账户信息")
                )
            }
        }
    }

    fun loadPayDorms() {
        _recharge.value = _recharge.value.copy(optionsLoading = true, error = "")
        viewModelScope.launch {
            try {
                val list = repo.loadPayDorms()
                _recharge.value = _recharge.value.copy(optionsLoading = false, dorms = list)
            } catch (ex: Exception) {
                _recharge.value = _recharge.value.copy(
                    optionsLoading = false, error = friendlyError(ex, "校区列表")
                )
            }
        }
    }

    fun selectPayDorm(idx: Int) {
        val s = _recharge.value
        if (idx !in s.dorms.indices) return
        _recharge.value = s.copy(
            dormIdx = idx, buildings = emptyList(), buildingIdx = -1,
            floors = emptyList(), floorIdx = -1, rooms = emptyList(), roomIdx = -1, error = ""
        )
        val dorm = s.dorms[idx]
        _recharge.value = _recharge.value.copy(optionsLoading = true)
        viewModelScope.launch {
            try {
                val list = repo.loadPayBuildings(dorm)
                _recharge.value = _recharge.value.copy(optionsLoading = false, buildings = list)
            } catch (ex: Exception) {
                _recharge.value = _recharge.value.copy(
                    optionsLoading = false, error = friendlyError(ex, "楼栋列表")
                )
            }
        }
    }

    fun selectPayBuilding(idx: Int) {
        val s = _recharge.value
        if (idx !in s.buildings.indices) return
        val dorm = s.dorm ?: return
        _recharge.value = s.copy(
            buildingIdx = idx, floors = emptyList(), floorIdx = -1,
            rooms = emptyList(), roomIdx = -1, error = ""
        )
        val building = s.buildings[idx]
        _recharge.value = _recharge.value.copy(optionsLoading = true)
        viewModelScope.launch {
            try {
                val list = repo.loadPayFloors(dorm, building)
                _recharge.value = _recharge.value.copy(optionsLoading = false, floors = list)
            } catch (ex: Exception) {
                _recharge.value = _recharge.value.copy(
                    optionsLoading = false, error = friendlyError(ex, "楼层列表")
                )
            }
        }
    }

    fun selectPayFloor(idx: Int) {
        val s = _recharge.value
        if (idx !in s.floors.indices) return
        val dorm = s.dorm ?: return
        val building = s.building ?: return
        _recharge.value = s.copy(
            floorIdx = idx, rooms = emptyList(), roomIdx = -1, error = ""
        )
        val floor = s.floors[idx]
        _recharge.value = _recharge.value.copy(optionsLoading = true)
        viewModelScope.launch {
            try {
                val list = repo.loadPayRooms(dorm, building, floor)
                _recharge.value = _recharge.value.copy(optionsLoading = false, rooms = list)
            } catch (ex: Exception) {
                _recharge.value = _recharge.value.copy(
                    optionsLoading = false, error = friendlyError(ex, "房间列表")
                )
            }
        }
    }

    fun selectPayRoom(idx: Int) {
        val s = _recharge.value
        if (idx !in s.rooms.indices) return
        _recharge.value = s.copy(roomIdx = idx, error = "")
    }

    /** 提交充值（真实扣校园卡余额）。成功后顺手刷新账户余额与当前房间电费 */
    fun submitRecharge(money: Int) {
        val s = _recharge.value
        val account = s.account ?: return
        val dorm = s.dorm ?: return
        val building = s.building ?: return
        val floor = s.floor ?: return
        val room = s.room ?: return
        if (money <= 0) {
            _recharge.value = s.copy(error = "充值金额必须是大于 0 的整数")
            return
        }
        if (money > account.balance) {
            _recharge.value = s.copy(
                error = "充值金额超过校园卡余额（¥%.2f）".format(account.balance)
            )
            return
        }
        _recharge.value = s.copy(submitting = true, result = null, error = "")
        viewModelScope.launch {
            try {
                val r = repo.payElectricity(room, dorm, building, floor, account, money)
                _recharge.value = _recharge.value.copy(submitting = false, result = r)
                if (r.success) {
                    loadPayAccount(force = true)
                    val roomIdx = _electric.value.roomIdx
                    if (roomIdx >= 0) selectRoom(roomIdx)
                }
            } catch (ex: Exception) {
                _recharge.value = _recharge.value.copy(
                    submitting = false, error = friendlyError(ex, "电费充值")
                )
            }
        }
    }

    // ---------- 一卡通 ----------
    data class YktState(
        val balanceLoading: Boolean = false,
        val balance: CardBalance? = null,
        val balanceError: String = "",
        /** 当前显示的是本地缓存的时间戳（0 表示是刚拉到的实时值） */
        val balanceCachedAt: Long = 0L,
        val recordsLoading: Boolean = false,
        val records: List<TransactionRecord> = emptyList(),
        val recordsError: String = "",
        val rangeText: String = "",
        val startDate: String = "",
        val endDate: String = ""
    )

    private val _ykt = MutableStateFlow(YktState())
    val ykt = _ykt.asStateFlow()

    /** 余额：进入页面立即加载（快接口） */
    fun loadYktBalance(force: Boolean = false) {
        if (_ykt.value.balance != null && !force) return
        val ctx = getApplication<CampusApp>().applicationContext

        // v1.11.3：先把本地缓存（桌面小卡片每 15 分钟会写一份）铺到界面上。
        // 之前进页面先显示"—""再等一次 SSO + 一次查询，体感就是"点了没反应"。
        // 现在数字立刻在，右侧标一句"更新中"，网络那一步回来再换成实时值。
        if (_ykt.value.balance == null) {
            val (cached, at) = runCatching { cn.aufe.campus.widget.WidgetDataStore.loadBalance(ctx) }
                .getOrDefault(null to 0L)
            if (cached != null && at > 0L) {
                _ykt.value = _ykt.value.copy(
                    balance = CardBalance(
                        balance = cached,
                        balanceText = String.format(java.util.Locale.CHINA, "%.2f", cached) + "元"
                    ),
                    balanceCachedAt = at
                )
            }
        }

        _ykt.value = _ykt.value.copy(balanceLoading = true, balanceError = "")
        viewModelScope.launch {
            try {
                val b = repo.loadCardBalance()
                val (s, e) = repo.defaultCardRange()
                _ykt.value = _ykt.value.copy(
                    balanceLoading = false, balance = b, balanceCachedAt = 0L,
                    startDate = s, endDate = e, rangeText = "$s ~ $e"
                )
                // 同步余额给桌面小卡片并刷新
                runCatching {
                    cn.aufe.campus.widget.WidgetDataStore.saveBalance(ctx, b.balance)
                    cn.aufe.campus.widget.WidgetDataStore.refreshYktWidgets(ctx)
                }
            } catch (ex: Exception) {
                _ykt.value = _ykt.value.copy(
                    balanceLoading = false,
                    balanceError = friendlyError(ex, "一卡通余额")
                )
            }
        }
    }

    /** 消费明细：独立加载（服务端较慢，用户点"查询"或进入页面后触发） */
    fun loadYktRecords(startDate: String = _ykt.value.startDate, endDate: String = _ykt.value.endDate) {
        if (startDate.isBlank() || endDate.isBlank()) return
        _ykt.value = _ykt.value.copy(
            recordsLoading = true, recordsError = "",
            startDate = startDate, endDate = endDate, rangeText = "$startDate ~ $endDate"
        )
        viewModelScope.launch {
            try {
                val list = repo.loadCardRecords(startDate, endDate)
                _ykt.value = _ykt.value.copy(recordsLoading = false, records = list)
            } catch (ex: Exception) {
                _ykt.value = _ykt.value.copy(
                    recordsLoading = false,
                    recordsError = friendlyError(ex, "消费明细")
                )
            }
        }
    }

    /** 把底层异常转成用户可读文案（会话过期单独提示） */
    private fun friendlyError(e: Exception, what: String): String = when (e) {
        is cn.aufe.campus.data.net.SessionExpiredException ->
            "登录会话已过期，请重新登录后再查看$what"
        is java.net.SocketTimeoutException -> "$what 查询超时（学校服务器响应较慢），请稍后重试"
        else -> e.message ?: "$what 加载失败"
    }

    fun logout() {
        auth.stopHeartbeat()
        auth.clearSession()
        val ctx = getApplication<CampusApp>().applicationContext
        runCatching {
            cn.aufe.campus.widget.WidgetDataStore.clear(ctx)
            cn.aufe.campus.widget.WidgetDataStore.refreshAll(ctx)
        }
    }
}
