package com.dailyflow.app.domain

import java.time.DayOfWeek.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RecurrenceEngineTest {
    private fun d(value: String) = LocalDate.parse(value)
    private fun occurs(start: String, date: String, rule: RecurrenceRule?) =
        RecurrenceEngine.occursOn(d(start), rule, d(date))

    @Test fun oneTimeAppearsOnlyOnItsDate() {
        assertTrue(occurs("2026-10-27", "2026-10-27", null))
        assertFalse(occurs("2026-10-27", "2026-10-28", null))
        assertFalse(occurs("2026-10-27", "2026-10-26", null))
    }
    @Test fun dailyCrossesMonthEnd() {
        assertTrue(occurs("2026-10-31", "2026-11-01", RecurrenceRule(Frequency.DAILY)))
    }
    @Test fun dailyCrossesYearEnd() {
        assertTrue(occurs("2026-12-31", "2027-01-01", RecurrenceRule(Frequency.DAILY)))
    }
    @Test fun dailyIncludesLeapDay() {
        val rule = RecurrenceRule(Frequency.DAILY)
        assertTrue(occurs("2028-02-28", "2028-02-29", rule))
        assertTrue(occurs("2028-02-28", "2028-03-01", rule))
    }
    @Test fun dailyIntervalUsesActualDaysAcrossLeapDay() {
        val rule = RecurrenceRule(Frequency.DAILY, interval = 2)
        assertFalse(occurs("2028-02-28", "2028-02-29", rule))
        assertTrue(occurs("2028-02-28", "2028-03-01", rule))
    }
    @Test fun weeklyOnlyMatchesSelectedWeekdays() {
        val rule = RecurrenceRule(Frequency.WEEKLY, weekdays = setOf(TUESDAY, THURSDAY))
        assertTrue(occurs("2026-10-27", "2026-10-29", rule))
        assertFalse(occurs("2026-10-27", "2026-10-28", rule))
    }
    @Test fun weeklyNeverIncludesDatesBeforeStart() {
        val rule = RecurrenceRule(Frequency.WEEKLY, weekdays = setOf(MONDAY, FRIDAY))
        assertFalse(occurs("2026-10-30", "2026-10-26", rule))
        assertTrue(occurs("2026-10-30", "2026-11-02", rule))
    }
    @Test fun biweeklyUsesMondayAnchoredWeeksAcrossYear() {
        val rule = RecurrenceRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(THURSDAY))
        assertFalse(occurs("2026-12-31", "2027-01-07", rule))
        assertTrue(occurs("2026-12-31", "2027-01-14", rule))
    }
    @Test fun weeklySundayAndMondayAreDifferentWeeks() {
        val rule = RecurrenceRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(SUNDAY, MONDAY))
        assertTrue(occurs("2026-10-25", "2026-10-25", rule))
        assertFalse(occurs("2026-10-25", "2026-10-26", rule))
        assertTrue(occurs("2026-10-25", "2026-11-02", rule))
    }
    @Test fun untilDateIsInclusive() {
        val rule = RecurrenceRule(Frequency.DAILY, untilDate = d("2026-10-28"))
        assertTrue(occurs("2026-10-27", "2026-10-28", rule))
        assertFalse(occurs("2026-10-27", "2026-10-29", rule))
    }
    @Test fun distantHistoryDoesNotNeedPrecreatedInstances() {
        assertTrue(occurs("1999-01-01", "2040-02-29", RecurrenceRule(Frequency.DAILY)))
    }
    @Test(expected = IllegalArgumentException::class) fun zeroIntervalRejected() {
        RecurrenceRule(Frequency.DAILY, interval = 0)
    }
    @Test(expected = IllegalArgumentException::class) fun negativeIntervalRejected() {
        RecurrenceRule(Frequency.WEEKLY, interval = -1, weekdays = setOf(MONDAY))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyWeeklySelectionRejected() {
        RecurrenceRule(Frequency.WEEKLY)
    }
    @Test(expected = IllegalArgumentException::class) fun dailyWithWeekdaysRejected() {
        RecurrenceRule(Frequency.DAILY, weekdays = setOf(MONDAY))
    }
    @Test(expected = IllegalArgumentException::class) fun blankTitleRejected() { validateContent("  ", "") }
    @Test(expected = IllegalArgumentException::class) fun overlongTitleRejected() { validateContent("a".repeat(201), "") }
    @Test(expected = IllegalArgumentException::class) fun overlongNoteRejected() { validateContent("有效标题", "x".repeat(4001)) }
    @Test fun boundaryLengthContentAccepted() { validateContent("a".repeat(200), "x".repeat(4000)) }
}
