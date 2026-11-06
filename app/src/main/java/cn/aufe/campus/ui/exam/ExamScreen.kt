package cn.aufe.campus.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aufe.campus.data.model.ExamItem
import cn.aufe.campus.ui.FeatureViewModel
import cn.aufe.campus.ui.components.ErrorBox
import cn.aufe.campus.ui.components.FeatureScaffold
import cn.aufe.campus.ui.components.LoadingBox
import cn.aufe.campus.ui.components.TagChip

/**
 * 考试安排（移植自 LoveACE）。
 *
 * 把「校统考」和「其他考试」合成一个按时间排序的列表 ——
 * 学生关心的是"我哪天有考试、在哪个教室、几号座位"，
 * 不关心这场考试在学校内部归哪个系统管。
 */
@Composable
fun ExamScreen(vm: FeatureViewModel, onBack: () -> Unit) {
    val state by vm.exam.collectAsState()
    LaunchedEffect(Unit) { vm.loadExams() }

    FeatureScaffold(
        title = "考试安排",
        subtitle = if (state.items.isEmpty()) "本学期还没有排考信息" else "共 ${state.items.size} 场考试",
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.loadExams(force = true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
    ) { padding ->
        when {
            state.loading && state.items.isEmpty() ->
                LoadingBox("正在查询考试安排…", 260.dp)
            state.error.isNotBlank() && state.items.isEmpty() ->
                ErrorBox(state.error, onRetry = { vm.loadExams(force = true) }, height = 260.dp)
            state.items.isEmpty() ->
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "本学期暂时没有查到考试安排\n排考一般集中在期末前一个月发布",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items) { exam -> ExamCard(exam) }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "座位号来自考试计划页，个别科目要等考前才公布。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun ExamCard(exam: ExamItem) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exam.courseName.ifBlank { "未知课程" },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (exam.type.isNotBlank()) TagChip(exam.type)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Event, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    listOf(exam.date, exam.time).filter { it.isNotBlank() }.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (exam.location.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Place, null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(exam.location, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (exam.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    exam.note,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
