package com.dailyflow.app.reminders

import com.dailyflow.app.domain.*
import com.dailyflow.app.ui.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.*
import java.util.Base64
import org.junit.Test
import org.junit.Assert.*

class ReminderDraftTest {
    private val day=LocalDate.of(2026,10,30)
    @Test fun newDraftFormatRoundTripsReminderAndDirtyState(){
        val draft=TaskDraft("draft",date=day,reminder=ReminderSetting(true,600,2),reminderEdited=true)
        val editor=EditorState(draft,original=draft.copy(reminder=null,reminderEdited=false))
        assertEquals(editor,DraftStateCodec.decodeEditor(DraftStateCodec.encodeEditor(editor)))
        assertTrue(DraftStateCodec.decodeEditor(DraftStateCodec.encodeEditor(editor))!!.dirty)
    }
    @Test fun genuineV1SavedDraftDecodesWithoutInventingReminder(){
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{out->out.writeInt(1)
            repeat(2){out.writeUTF("old");out.writeUTF("note");out.writeUTF(day.toString());out.writeInt(-1);out.writeUTF("NORMAL");out.writeBoolean(false)}
            out.writeBoolean(false);out.writeBoolean(false);out.writeUTF("SINGLE")}
        val old=DraftStateCodec.decodeEditor(Base64.getEncoder().encodeToString(bytes.toByteArray()))!!
        assertEquals("old",old.draft.title);assertNull(old.draft.reminder);assertFalse(old.draft.reminderEdited)
    }
    @Test fun summaryDistinguishesFutureExpiredCompletedAndFailed(){
        val now=Instant.parse("2026-10-30T07:00:00Z")
        val task=TaskOccurrence("a",day,"a","",false,false,false,reminder=ReminderSetting(true,480))
        assertTrue(reminderSummary(task,now,ZoneOffset.UTC).contains("08:00"))
        assertEquals("提醒已过期",reminderSummary(task,now.plusSeconds(7200),ZoneOffset.UTC))
        assertTrue(reminderSummary(task.copy(completed=true),now,ZoneOffset.UTC).contains("已结束"))
        assertTrue(reminderSummary(task.copy(reminderDeliveryStatus="FAILED"),now,ZoneOffset.UTC).contains("失败"))
    }
    @Test fun changingTaskDateKeepsLocalReminderOffset(){
        val a=TaskDraft("a",date=day,reminder=ReminderSetting(true,480,1));val b=a.copy(date=day.plusDays(2))
        assertEquals(2*86400000L,reminderTime(b.date,b.reminder!!,ZoneOffset.UTC).toEpochMilli()-reminderTime(a.date,a.reminder!!,ZoneOffset.UTC).toEpochMilli())
    }
}
