package com.dailyflow.app.ui

import com.dailyflow.app.domain.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset

// Pickers represent calendar dates as UTC midnight; never apply a device time zone.
fun pickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
fun pickerDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

enum class TaskPage { LIST, DETAILS, EDITOR }
data class EditorState(
    val draft: TaskDraft,
    val original: TaskDraft = draft,
    val taskId: String? = null,
    val occurrenceDate: LocalDate? = null,
    val scope: EditScope = EditScope.SINGLE,
    val validationError: String? = null,
) {
    val dirty get() = draft != original
    val dateLocked get() = taskId != null && draft.rule != null
}
data class ScopePrompt(val task: TaskOccurrence, val scope: EditScope = EditScope.SINGLE)
data class TaskInteractionState(
    val page: TaskPage = TaskPage.LIST,
    val detail: TaskOccurrence? = null,
    val editor: EditorState? = null,
    val editPrompt: ScopePrompt? = null,
    val deletePrompt: ScopePrompt? = null,
    val discardPrompt: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)
