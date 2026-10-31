package com.dailyflow.app.domain

/** Preserve legacy ordinal 2 in Room and saved drafts; new UI has three choices. */
fun Priority.organizedPriority(): Priority = if (this == Priority.MEDIUM) Priority.NORMAL else this

val selectablePriorities = listOf(Priority.HIGH, Priority.NORMAL, Priority.LOW)
enum class TaskSort { MANUAL, PRIORITY, CREATED }
enum class CompletionFilter { ALL, UNFINISHED, COMPLETED }

data class TaskListOptions(
    val query: String = "",
    val completion: CompletionFilter = CompletionFilter.ALL,
    val priority: Priority? = null,
    val sort: TaskSort = TaskSort.MANUAL,
) {
    val hasCriteria: Boolean get() = query.isNotBlank() || completion != CompletionFilter.ALL || priority != null
}

/** Input has already been resolved for a single date by the existing recurrence/exception engine. */
fun organizeTasks(tasks: List<TaskOccurrence>, options: TaskListOptions): List<TaskOccurrence> {
    val query = options.query.trim()
    val matches = tasks.filter { task ->
        (options.completion == CompletionFilter.ALL || task.completed == (options.completion == CompletionFilter.COMPLETED)) &&
            (options.priority == null || task.priority.organizedPriority() == options.priority.organizedPriority()) &&
            (query.isEmpty() || task.title.contains(query, ignoreCase = true) || task.note.contains(query, ignoreCase = true))
    }
    val manual = compareBy<TaskOccurrence> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.taskId }
    val comparator = when (options.sort) {
        TaskSort.MANUAL -> manual
        TaskSort.PRIORITY -> compareBy<TaskOccurrence> {
            when (it.priority.organizedPriority()) { Priority.HIGH -> 0; Priority.LOW -> 2; else -> 1 }
        }.then(manual)
        TaskSort.CREATED -> compareBy<TaskOccurrence> { it.createdAt }.thenBy { it.taskId }
    }
    return matches.sortedWith(comparator)
}
