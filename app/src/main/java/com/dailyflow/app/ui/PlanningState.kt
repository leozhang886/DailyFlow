package com.dailyflow.app.ui

import com.dailyflow.app.domain.*
import java.time.*

enum class PlanningPage { MANAGE, RULE, EXCEPTIONS, HISTORY }
data class RuleSettings(val effectiveFrom: LocalDate,val frequency: Frequency,val intervalText: String,val weekdays: Set<DayOfWeek>,val untilDate: LocalDate?) {
    fun validatedRule(): RecurrenceRule {
        val interval=intervalText.toIntOrNull();require(interval!=null && interval in 1..365) { "间隔须为 1—365 的整数" }
        require(untilDate==null || untilDate>=effectiveFrom) { "结束日不得早于生效日" }
        return RecurrenceRule(frequency,interval,if(frequency==Frequency.WEEKLY)weekdays else emptySet(),untilDate)
    }
}
data class RuleForm(val series: SeriesSummary,val settings: RuleSettings,val original: RuleSettings=settings) {val dirty get()=settings!=original}
data class PlanningState(val page: PlanningPage?=null,val form: RuleForm?=null,val exceptionSeries: SeriesSummary?=null,
    val confirmRule: Boolean=false,val confirmRecovery: TaskException?=null,val discard: Boolean=false,val busy: Boolean=false,
    val error: String?=null,val message: String?=null)
data class PlanningList<T>(val items: List<T> = emptyList(),val loading: Boolean=true,val error: Boolean=false)
