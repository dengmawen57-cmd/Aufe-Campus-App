package cn.aufe.campus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.aufe.campus.CampusApp
import cn.aufe.campus.data.CampusRepository
import cn.aufe.campus.data.api.JwApi
import cn.aufe.campus.data.model.CardBalance
import cn.aufe.campus.data.model.ElectricityInfo
import cn.aufe.campus.data.model.PlanGroup
import cn.aufe.campus.data.model.PlanOverview
import cn.aufe.campus.data.model.ScoreRecord
import cn.aufe.campus.data.model.ScoreReport
import cn.aufe.campus.data.model.SelectableCourse
import cn.aufe.campus.data.model.SelectedCourse
import cn.aufe.campus.data.model.TransactionRecord
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
                // 顺便和教务对一次教学周次（拿到就以此为准，拿不到就用本地推算）
                runCatching { repo.syncTeachingWeek() }
                _schedule.value = ScheduleState(
                    loading = false,
                    courses = r.selected,
                    error = "",
                    loaded = true
                )
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
                val plan = repo.loadPlanCourses()
                val free = try {
                    repo.searchFreeCourses()
                } catch (_: Exception) {
                    JwApi.CourseListResult()
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
    data class ScoreQueryState(
        val loading: Boolean = false,
        val records: List<ScoreRecord> = emptyList(),
        val totalCount: Int = 0,
        val loadingMore: Boolean = false,
        val noMore: Boolean = false,
        val error: String = "",
        val loaded: Boolean = false
    )

    private val _scoreQuery = MutableStateFlow(ScoreQueryState())
    val scoreQuery = _scoreQuery.asStateFlow()

    fun loadScoreQuery(force: Boolean = false) {
        val s = _scoreQuery.value
        if (s.loading || s.loadingMore) return
        if (s.loaded && !force && s.error.isBlank()) return
        _scoreQuery.value = s.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val p = repo.loadScoreQueryPage(1)
                _scoreQuery.value = ScoreQueryState(
                    loading = false,
                    records = p.records,
                    totalCount = p.totalCount,
                    noMore = p.records.size >= p.totalCount,
                    loaded = true
                )
            } catch (e: Exception) {
                _scoreQuery.value = s.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun loadMoreScoreQuery() {
        val s = _scoreQuery.value
        if (s.loading || s.loadingMore || s.noMore) return
        _scoreQuery.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val nextPage = s.records.size / 100 + 1
                val p = repo.loadScoreQueryPage(nextPage)
                val merged = s.records + p.records
                _scoreQuery.value = s.copy(
                    records = merged,
                    totalCount = p.totalCount,
                    loadingMore = false,
                    noMore = merged.size >= p.totalCount
                )
            } catch (e: Exception) {
                _scoreQuery.value = s.copy(loadingMore = false)
            }
        }
    }

    /**
     * 一次性把全部成绩拉完（绩点统计必须基于全量，分页只拿到前 100 条会算错）。
     * 已有记录会被保留，只往后补页；最多补 20 页（2000 条）防止死循环。
     */
    fun loadAllScores() {
        val s = _scoreQuery.value
        if (s.loading || s.loadingMore || s.noMore) return
        _scoreQuery.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            var records = s.records
            // 接口没给总数时乐观往后补一页，靠"空页"判断结束
            var total = if (s.totalCount > 0) s.totalCount else records.size + 100
            var page = records.size / 100 + 1
            var failed = false
            try {
                while (records.size < total && page <= 20) {
                    val p = repo.loadScoreQueryPage(page)
                    if (p.records.isEmpty()) break
                    records = records + p.records
                    total = p.totalCount
                    page++
                }
            } catch (_: Exception) {
                failed = true
            }
            val cur = _scoreQuery.value
            _scoreQuery.value = cur.copy(
                records = records,
                totalCount = total,
                loadingMore = false,
                loaded = true,
                noMore = records.size >= total,
                error = if (failed && records.size < total) "部分成绩加载失败，绩点可能不完整" else cur.error
            )
        }
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

    // ---------- 一卡通 ----------
    data class YktState(
        val balanceLoading: Boolean = false,
        val balance: CardBalance? = null,
        val balanceError: String = "",
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
        _ykt.value = _ykt.value.copy(balanceLoading = true, balanceError = "")
        viewModelScope.launch {
            try {
                val b = repo.loadCardBalance()
                val (s, e) = repo.defaultCardRange()
                _ykt.value = _ykt.value.copy(
                    balanceLoading = false, balance = b,
                    startDate = s, endDate = e, rangeText = "$s ~ $e"
                )
                // 同步余额给桌面小卡片并刷新
                runCatching {
                    val ctx = getApplication<CampusApp>().applicationContext
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
