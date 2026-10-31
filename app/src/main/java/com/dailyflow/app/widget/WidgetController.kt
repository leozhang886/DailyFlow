package com.dailyflow.app.widget

import com.dailyflow.app.domain.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Only transient display data. Room remains the sole source of task completion. */
data class WidgetSnapshot(val date: LocalDate, val tasks: List<TaskOccurrence> = emptyList(), val error: Boolean = false, val loading: Boolean = false) {
    val completedCount get() = tasks.count { it.completed }
}
data class WidgetCommand(val taskId: String, val date: LocalDate, val expected: Boolean, val revision: Long, val target: Boolean)
enum class WidgetActionResult { APPLIED, STALE, EXPIRED, MISSING, FAILED }
class WidgetController(private val repository: TaskRepository, private val clock: Clock = Clock.systemDefaultZone()) {
    suspend fun snapshot(): WidgetSnapshot {
        val date = LocalDate.now(clock)
        return try { WidgetSnapshot(date, repository.observeDay(date).first()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { WidgetSnapshot(date, error = true) }
    }
    suspend fun execute(command: WidgetCommand): WidgetActionResult {
        if (command.date != LocalDate.now(clock)) return WidgetActionResult.EXPIRED
        if (command.revision < 0 || command.taskId.isBlank()) return WidgetActionResult.STALE
        return try {
            when (repository.completeFromSnapshot(command.taskId, command.date, command.expected, command.revision, command.target) { LocalDate.now(clock) }) {
                CompletionResult.APPLIED -> WidgetActionResult.APPLIED
                CompletionResult.STALE -> WidgetActionResult.STALE
                CompletionResult.MISSING -> WidgetActionResult.MISSING
                CompletionResult.EXPIRED -> WidgetActionResult.EXPIRED
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { WidgetActionResult.FAILED }
    }
}
fun nextWidgetMidnight(clock: Clock = Clock.systemDefaultZone()): Long =
    LocalDate.now(clock).plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()

/** One unavailable instance must not prevent refreshing the other placed widgets. */
suspend fun <T> refreshWidgetInstances(ids: List<T>, update: suspend (T) -> Unit) {
    var failure: Exception? = null
    for (id in ids) try { update(id) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { failure = e }
    failure?.let { throw it }
}
