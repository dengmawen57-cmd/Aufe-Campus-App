package cn.aufe.campus.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import cn.aufe.campus.ui.CampusViewModel
import cn.aufe.campus.ui.profile.ProfileScreen
import cn.aufe.campus.ui.schedule.ScheduleScreen
import cn.aufe.campus.ui.score.ScoreScreen
import cn.aufe.campus.ui.select.CourseSelectScreen

private data class TabItem(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("课表", Icons.Filled.CalendarMonth),
    TabItem("选课", Icons.Filled.PlaylistAdd),
    TabItem("成绩单", Icons.Filled.EmojiEvents),
    TabItem("我的", Icons.Filled.Person)
)

@Composable
fun MainScreen(
    vm: CampusViewModel,
    onLogout: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenScoreQuery: () -> Unit,
    onOpenGpa: () -> Unit = {},
    onOpenYkt: () -> Unit,
    onOpenElectric: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TABS.forEachIndexed { idx, item ->
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            when (tab) {
                0 -> ScheduleScreen(vm = vm, onOpenYkt = onOpenYkt)
                1 -> CourseSelectScreen(vm = vm)
                2 -> ScoreScreen(vm = vm)
                else -> ProfileScreen(
                    vm = vm,
                    onLogout = onLogout,
                    onOpenPlan = onOpenPlan,
                    onOpenScoreQuery = onOpenScoreQuery,
                    onOpenGpa = onOpenGpa,
                    onOpenYkt = onOpenYkt,
                    onOpenElectric = onOpenElectric
                )
            }
        }
    }
}
