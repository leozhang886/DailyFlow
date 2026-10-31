package com.dailyflow.app.reminders

import com.dailyflow.app.data.*
import com.dailyflow.app.domain.*
import java.time.*
import org.junit.Test
import org.junit.Assert.*

class ReminderPlannerTest {
    private val day=LocalDate.of(2026,10,30)
    private fun data(date:LocalDate=day,minute:Int=480,offset:Int=0,rule:RecurrenceRule?=null,states:List<TaskDayStateEntity> = emptyList(),
        exceptions:List<TaskExceptionEntity> = emptyList(),overrides:List<ReminderOverrideEntity> = emptyList(),sent:List<ReminderDeliveryEntity> = emptyList()):ReminderSnapshot =
        ReminderSnapshot(listOf(TaskEntity("a","标题","备注",date,createdAt=1,updatedAt=1)),rule?.let{listOf(RecurrenceRuleEntity.from("a",it))}?:emptyList(),emptyList(),exceptions,states,
            listOf(ReminderRuleEntity("a",true,minute,offset)),overrides,sent)
    @Test fun dstGapShiftsForwardWithoutLosingOccurrence(){
        val date=LocalDate.of(2026,3,8);val plan=data(date,150).plan(ReminderKey("a",date),ZoneId.of("America/New_York"))!!
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(),plan.dueAt)
    }
    @Test fun dstOverlapUsesEarlierOffsetOnlyOnce(){
        val date=LocalDate.of(2026,11,1);val plan=data(date,90).plan(ReminderKey("a",date),ZoneId.of("America/New_York"))!!
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),plan.dueAt)
    }
    @Test fun offsetIsLocalCalendarDaysNotTwentyFourHours(){
        val date=LocalDate.of(2026,3,7);val due=data(date,480,1).plan(ReminderKey("a",date),ZoneId.of("America/New_York"))!!.dueAt
        assertEquals(Instant.parse("2026-03-08T12:00:00Z").toEpochMilli(),due)
    }
    @Test fun weeklyRuleUsesOnlyChosenWeekdays(){
        val s=data(rule=RecurrenceRule(Frequency.WEEKLY,weekdays=setOf(DayOfWeek.MONDAY)))
        assertEquals(LocalDate.of(2026,11,2),s.next(Instant.parse("2026-10-30T07:00:00Z"),ZoneOffset.UTC)!!.key.date)
    }
    @Test fun completedAndDeletedInstancesCannotNotify(){
        assertNull(data(states=listOf(TaskDayStateEntity("a",day,true,1))).plan(ReminderKey("a",day),ZoneOffset.UTC))
        assertNull(data(exceptions=listOf(TaskExceptionEntity("a",day,deleted=true,updatedAt=1))).plan(ReminderKey("a",day),ZoneOffset.UTC))
    }
    @Test fun singleOverrideCanHaveDifferentLocalReminderDate(){
        val s=data(rule=RecurrenceRule(Frequency.DAILY),overrides=listOf(ReminderOverrideEntity("a",day,true,600,2)))
        assertEquals(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli(),s.plan(ReminderKey("a",day),ZoneOffset.UTC)!!.dueAt)
        assertEquals(480,data().configurations.single().minute)
    }
    @Test fun singleDisabledOverridesEnabledBase(){
        assertNull(data(overrides=listOf(ReminderOverrideEntity("a",day,false,480,0))).plan(ReminderKey("a",day),ZoneOffset.UTC))
    }
    @Test fun deliveredOrClaimedKeysAreNotReplayedAfterClockGoesBack(){
        for(status in listOf("CLAIMED","DELIVERED","CANCELED")) {
            assertNull(data(sent=listOf(ReminderDeliveryEntity("a",day,1,status))).next(Instant.parse("2026-10-30T07:00:00Z"),ZoneOffset.UTC))
        }
    }
    @Test fun failedDeliveryCanRetryButPastReminderIsNotSelectedAsFuture(){
        val s=data(sent=listOf(ReminderDeliveryEntity("a",day,1,"FAILED")))
        assertNotNull(s.plan(ReminderKey("a",day),ZoneOffset.UTC))
        assertNull(s.next(Instant.parse("2026-10-30T09:00:00Z"),ZoneOffset.UTC))
    }
    @Test fun equalTimestampsAreStablyOrderedAndKeepDistinctKeys(){
        val s=ReminderSnapshot(listOf("b","a").map{TaskEntity(it,"same","",day,createdAt=1,updatedAt=1)},emptyList(),emptyList(),emptyList(),emptyList(),
            listOf("a","b").map{ReminderRuleEntity(it,true,480,0)},emptyList(),emptyList())
        assertEquals(listOf("a","b"),s.candidates(Instant.parse("2026-10-30T07:00:00Z"),ZoneOffset.UTC).map{it.key.taskId})
    }
}
