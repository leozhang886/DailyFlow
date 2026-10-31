package com.dailyflow.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dailyflow.app.domain.*

fun sortLabel(value: TaskSort) = when (value) {
    TaskSort.MANUAL -> "手动顺序"
    TaskSort.PRIORITY -> "优先级"
    TaskSort.CREATED -> "创建时间（旧到新）"
}
fun completionLabel(value: CompletionFilter) = when (value) {
    CompletionFilter.ALL -> "全部"
    CompletionFilter.UNFINISHED -> "未完成"
    CompletionFilter.COMPLETED -> "已完成"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskOrganizationControls(day: DayUiState, model: DailyViewModel, reordering: Boolean, setReordering: (Boolean) -> Unit) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.heightIn(min = 48.dp).testTag("openSearch")) { Text("搜索") }
            TextButton(onClick = { filtersOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("openFilters")) { Text("筛选") }
            TextButton(onClick = { sortOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("openSort")) { Text("排序") }
        }
        if (searchOpen || day.options.query.isNotEmpty()) {
            OutlinedTextField(value = day.options.query, onValueChange = model::setSearchQuery,
                label = { Text("搜索当前日期的标题或备注") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("taskSearch"),
                trailingIcon = { if (day.options.query.isNotEmpty()) TextButton(onClick = { model.setSearchQuery("") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("clearSearch").semantics { contentDescription = "清空搜索" }) { Text("清空") } })
        }
        if (day.options.hasCriteria || day.options.sort != TaskSort.MANUAL) {
            Text(completionLabel(day.options.completion) + " · " + (day.options.priority?.let(::priorityLabel) ?: "全部优先级") +
                " · " + sortLabel(day.options.sort), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (day.options.hasCriteria) TextButton(onClick = model::clearListCriteria,
                modifier = Modifier.heightIn(min = 48.dp).testTag("clearOrganization")) { Text("清除搜索和筛选") }
        }
        if (reordering && (day.options.hasCriteria || day.options.sort != TaskSort.MANUAL)) {
            Text("调整顺序已暂停；清除搜索和筛选并选择手动顺序后可继续。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (filtersOpen) AlertDialog(onDismissRequest = { filtersOpen = false }, title = { Text("筛选当前日期") },
        text = { ScrollableDialogText {
            Text("完成状态", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompletionFilter.entries.forEach { value -> FilterChip(selected = day.options.completion == value,
                    onClick = { model.setCompletionFilter(value) }, label = { Text(completionLabel(value)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("completionFilter_" + value.name)) }
            }
            Text("优先级", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<Priority?>(null) + selectablePriorities).forEach { value -> FilterChip(selected = day.options.priority == value,
                    onClick = { model.setPriorityFilter(value) }, label = { Text(value?.let(::priorityLabel) ?: "全部优先级") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("priorityFilter_" + (value?.name ?: "ALL"))) }
            }
            Text("筛选不修改任务；切换日期后沿用当前条件。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { filtersOpen = false }, modifier = Modifier.testTag("closeFilters")) { Text("完成筛选") } },
        dismissButton = { TextButton(onClick = model::clearListCriteria) { Text("清除条件") } })
    if (sortOpen) AlertDialog(onDismissRequest = { sortOpen = false }, title = { Text("任务排序") },
        text = { ScrollableDialogText {
            TaskSort.entries.forEach { value ->
                TextButton(onClick = { model.setSort(value) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("taskSort_" + value.name)) {
                    RadioButton(selected = day.options.sort == value, onClick = null)
                    Text(sortLabel(value), Modifier.weight(1f).padding(start = 8.dp))
                }
            }
            Text("保留未完成、已完成分组，各组内部按所选方式排序。同值使用持久化字段和任务标识保持稳定。", style = MaterialTheme.typography.bodySmall)
            Text("手动调整是全局任务顺序，会影响其他日期的同一任务。搜索或筛选期间不能调整，避免移动未显示的任务。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { sortOpen = false }) { Text("完成排序设置") } },
        dismissButton = { TextButton(onClick = { model.setSort(TaskSort.MANUAL); model.clearListCriteria(); setReordering(true); sortOpen = false },
            modifier = Modifier.testTag("startReordering")) { Text("调整手动顺序") } })
}

@Composable
internal fun OrganizedTaskCard(task: TaskOccurrence, group: List<TaskOccurrence>, reordering: Boolean, busy: Boolean,
    pending: Set<String>, model: DailyViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TaskCard(task, task.taskId + ":" + task.date in pending, { model.openDetails(task) }) { model.setCompleted(task, it) }
        if (reordering) {
            val index = group.indexOfFirst { it.taskId == task.taskId }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { group.getOrNull(index - 1)?.let { model.moveTask(task, it) } }, enabled = !busy && index > 0,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("moveUp_" + task.taskId).semantics { contentDescription = "上移：" + task.title }) { Text("上移") }
                TextButton(onClick = { group.getOrNull(index + 1)?.let { model.moveTask(task, it) } }, enabled = !busy && index >= 0 && index < group.lastIndex,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("moveDown_" + task.taskId).semantics { contentDescription = "下移：" + task.title }) { Text("下移") }
            }
        }
    }
}
