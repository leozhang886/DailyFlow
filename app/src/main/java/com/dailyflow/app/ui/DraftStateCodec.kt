package com.dailyflow.app.ui

import com.dailyflow.app.domain.*
import java.io.*
import java.time.*
import java.util.Base64

/** Small, versioned SavedState strings. Not a second task database. */
object DraftStateCodec {
    private fun DataOutputStream.optional(value: String?){writeBoolean(value!=null);if(value!=null)writeUTF(value)}
    private fun DataInputStream.optional():String?=if(readBoolean())readUTF() else null
    private fun DataOutputStream.rule(rule:RecurrenceRule?){writeBoolean(rule!=null);if(rule!=null){writeUTF(rule.frequency.name);writeInt(rule.interval);writeInt(rule.weekdays.fold(0){m,d->m or (1 shl (d.value-1))});optional(rule.untilDate?.toString())}}
    private fun DataInputStream.rule():RecurrenceRule?=if(readBoolean()){val f=Frequency.valueOf(readUTF());val n=readInt();val mask=readInt();RecurrenceRule(f,n,DayOfWeek.entries.filter{mask and(1 shl(it.value-1))!=0}.toSet(),optional()?.let(LocalDate::parse))}else null
    private fun DataOutputStream.draft(d:TaskDraft){writeUTF(d.title);writeUTF(d.note);writeUTF(d.date.toString());writeInt(d.scheduledMinute?:-1);writeUTF(d.priority.name);rule(d.rule)}
    private fun DataInputStream.draft():TaskDraft {val title=readUTF();val note=readUTF();val date=LocalDate.parse(readUTF());val minute=readInt();return TaskDraft(title,note,date,minute.takeIf{it>=0},Priority.valueOf(readUTF()),rule())}
    private fun encode(writer:DataOutputStream.()->Unit):String?=try{val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{it.writeInt(1);writer(it)};val raw=bytes.toByteArray();if(raw.size>32000)null else Base64.getEncoder().encodeToString(raw)}catch(_:Exception){null}
    private fun <T> decode(value:String?,reader:DataInputStream.()->T):T?=try{if(value==null || value.length>44000)null else DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use{require(it.readInt()==1);val result=reader(it);require(it.available()==0);result}}catch(_:Exception){null}
    fun encodeEditor(e:EditorState?):String?=e?.let{encode{draft(e.draft);draft(e.original);optional(e.taskId);optional(e.occurrenceDate?.toString());writeUTF(e.scope.name)}}
    fun decodeEditor(s:String?):EditorState?=decode(s){EditorState(draft(),draft(),optional(),optional()?.let(LocalDate::parse),EditScope.valueOf(readUTF()))}
    private fun DataOutputStream.settings(s:RuleSettings){writeUTF(s.effectiveFrom.toString());writeUTF(s.frequency.name);writeUTF(s.intervalText);writeInt(s.weekdays.fold(0){m,d->m or(1 shl(d.value-1))});optional(s.untilDate?.toString())}
    private fun DataInputStream.settings():RuleSettings{val date=LocalDate.parse(readUTF());val f=Frequency.valueOf(readUTF());val interval=readUTF();val mask=readInt();return RuleSettings(date,f,interval,DayOfWeek.entries.filter{mask and(1 shl(it.value-1))!=0}.toSet(),optional()?.let(LocalDate::parse))}
    fun encodeRule(f:RuleForm?):String?=f?.let{encode{writeUTF(f.series.taskId);writeUTF(f.series.title);writeUTF(f.series.startDate.toString());rule(f.series.rule);writeLong(f.series.revision);settings(f.settings);settings(f.original)}}
    fun decodeRule(s:String?):RuleForm?=decode(s){val id=readUTF();val title=readUTF();val start=LocalDate.parse(readUTF());val rule=requireNotNull(rule());val revision=readLong();RuleForm(SeriesSummary(id,title,start,rule,revision),settings(),settings())}
}
