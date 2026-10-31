package com.dailyflow.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object RecurrenceEngine {
    fun occursOn(startDate: LocalDate, rule: RecurrenceRule?, date: LocalDate): Boolean {
        if (date < startDate) return false
        if (rule == null) return date == startDate
        if (rule.untilDate != null && date > rule.untilDate) return false
        return when (rule.frequency) {
            Frequency.DAILY -> ChronoUnit.DAYS.between(startDate, date) % rule.interval == 0L
            Frequency.WEEKLY -> {
                val startWeek = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val queryWeek = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                ChronoUnit.WEEKS.between(startWeek, queryWeek) % rule.interval == 0L && date.dayOfWeek in rule.weekdays
            }
        }
    }
}
