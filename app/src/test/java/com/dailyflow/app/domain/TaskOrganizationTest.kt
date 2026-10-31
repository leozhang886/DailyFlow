package com.dailyflow.app.domain
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*
class TaskOrganizationTest {
 private val date=LocalDate.of(2026,10,29)
 private fun task(id:String,priority:Priority=Priority.NORMAL,done:Boolean=false,title:String=id,note:String="",order:Long=0,created:Long=0)=TaskOccurrence(id,date,title,note,done,false,false,priority=priority,sortOrder=order,createdAt=created)
 private fun ids(items:List<TaskOccurrence>,o:TaskListOptions=TaskListOptions())=organizeTasks(items,o).map{it.taskId}
 @Test fun defaultAndLegacyPriorityRemainCompatible(){assertEquals(Priority.NORMAL,task("a").priority);assertEquals(listOf(Priority.HIGH,Priority.NORMAL,Priority.LOW),selectablePriorities);assertEquals(Priority.NORMAL,Priority.MEDIUM.organizedPriority());assertEquals(2,Priority.MEDIUM.ordinal)}
 @Test fun prioritySortHasExplicitRanks(){assertEquals(listOf("h","m","n","l"),ids(listOf(task("l",Priority.LOW),task("n"),task("h",Priority.HIGH),task("m",Priority.MEDIUM)),TaskListOptions(sort=TaskSort.PRIORITY)))}
 @Test fun manualSortUsesOrderThenCreationThenId(){assertEquals(listOf("z","a","b"),ids(listOf(task("b",created=2),task("a",created=2),task("z",order=-1))))}
 @Test fun creationSortUsesRealTimestampThenId(){assertEquals(listOf("b","a","z"),ids(listOf(task("z",created=5),task("a",created=5),task("b",created=1)),TaskListOptions(sort=TaskSort.CREATED)))}
 @Test fun completedFilter(){assertEquals(listOf("b"),ids(listOf(task("a"),task("b",done=true)),TaskListOptions(completion=CompletionFilter.COMPLETED)))}
 @Test fun unfinishedFilter(){assertEquals(listOf("a"),ids(listOf(task("a"),task("b",done=true)),TaskListOptions(completion=CompletionFilter.UNFINISHED)))}
 @Test fun normalFilterIncludesLegacyMedium(){assertEquals(listOf("m","n"),ids(listOf(task("h",Priority.HIGH),task("n"),task("m",Priority.MEDIUM)),TaskListOptions(priority=Priority.NORMAL)))}
 @Test fun combinedFilters(){assertEquals(listOf("h"),ids(listOf(task("h",Priority.HIGH),task("d",Priority.HIGH,true),task("l",Priority.LOW)),TaskListOptions(completion=CompletionFilter.UNFINISHED,priority=Priority.HIGH)))}
 @Test fun chineseTitleSearch(){assertEquals(listOf("a"),ids(listOf(task("a",title="学习中文"),task("b")),TaskListOptions(query="中文")))}
 @Test fun englishCaseAndWhitespace(){assertEquals(listOf("a"),ids(listOf(task("a",title="Read Kotlin"),task("b")),TaskListOptions(query="  kOtLiN  ")))}
 @Test fun noteSearch(){assertEquals(listOf("a"),ids(listOf(task("a",note="银行账单"),task("b")),TaskListOptions(query="账单")))}
 @Test fun searchCombinesWithFilters(){assertEquals(listOf("a"),ids(listOf(task("a",Priority.HIGH,title="Read"),task("b",Priority.LOW,title="READ")),TaskListOptions(query="read",priority=Priority.HIGH)))}
 @Test fun noMatchAndClear(){val list=listOf(task("a"));assertTrue(ids(list,TaskListOptions(query="none")).isEmpty());assertEquals(listOf("a"),ids(list,TaskListOptions(query="   ")))}
 @Test fun readOnlyAndLargeList(){val list=(0..1999).map{task(it.toString(),title="task "+it)};val before=list.toList();assertEquals(11,organizeTasks(list,TaskListOptions(query="task 199")).size);assertEquals(before,list)}
}
