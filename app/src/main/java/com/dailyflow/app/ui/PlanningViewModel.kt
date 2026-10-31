package com.dailyflow.app.ui

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import com.dailyflow.app.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningViewModel(private val repository:TaskRepository,private val clock:Clock=Clock.systemDefaultZone(),private val saved:SavedStateHandle=SavedStateHandle()):ViewModel(){
    private val restored=DraftStateCodec.decodeRule(saved["ruleForm"])
    private val mutable=MutableStateFlow(if(restored==null)PlanningState() else PlanningState(page=PlanningPage.RULE,form=restored,message="已恢复未保存的规则草稿"))
    val state=mutable.asStateFlow()
    private val reload=MutableStateFlow(0)
    private val month=MutableStateFlow(runCatching { YearMonth.parse(saved.get<String>("historyMonth")) }.getOrElse { YearMonth.now(clock) });val historyMonth=month.asStateFlow()
    val series=reload.flatMapLatest{repository.observeSeries().map{PlanningList(it,false)}.onStart{emit(PlanningList())}.catch{emit(PlanningList(loading=false,error=true))}}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),PlanningList())
    val exceptions=combine(mutable.map{it.exceptionSeries?.taskId}.distinctUntilChanged(),reload){id,_->id}.flatMapLatest{id->
        if(id==null)flowOf(PlanningList<TaskException>(loading=false)) else repository.observeExceptions(id).map{PlanningList(it,false)}.onStart{emit(PlanningList())}.catch{emit(PlanningList(loading=false,error=true))}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),PlanningList())
    val history=combine(month,reload){m,_->m}.flatMapLatest{m->
        val end=minOf(m.atEndOfMonth(),LocalDate.now(clock).minusDays(1))
        if(end<m.atDay(1))flowOf(PlanningList<TaskOccurrence>(loading=false)) else repository.observeHistory(m.atDay(1),end).map{PlanningList(it,false)}.onStart{emit(PlanningList())}.catch{emit(PlanningList(loading=false,error=true))}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),PlanningList())
    init{viewModelScope.launch(start=CoroutineStart.UNDISPATCHED){state.collect{saved["ruleForm"]=DraftStateCodec.encodeRule(it.form)}}}
    var returnsToHistory=false;private set
    fun openHistoryDetails(){returnsToHistory=true;mutable.value=PlanningState()}
    fun resumeHistory(){returnsToHistory=false;mutable.value=PlanningState(page=PlanningPage.HISTORY)}
    fun clearMessage(){mutable.value=state.value.copy(message=null)}
    fun openManage(){mutable.value=PlanningState(page=PlanningPage.MANAGE)}
    fun openHistory(){selectHistoryMonth(YearMonth.now(clock));mutable.value=PlanningState(page=PlanningPage.HISTORY)}
    fun selectHistoryMonth(value:YearMonth){if(value<=YearMonth.now(clock)&&value.year in 1..9999){month.value=value;saved["historyMonth"]=value.toString()}}
    fun moveMonth(offset:Long){val next=month.value.plusMonths(offset);if(next<=YearMonth.now(clock)&&next.year in 1..9999){month.value=next;saved["historyMonth"]=next.toString()}}
    fun retry(){reload.value++}
    fun openRule(series:SeriesSummary){
        val date=maxOf(LocalDate.now(clock),series.startDate)
        val rule=series.changes.lastOrNull{it.effectiveFrom<=date}?.rule?:series.rule
        val settings=RuleSettings(date,rule.frequency,rule.interval.toString(),if(rule.frequency==Frequency.WEEKLY)rule.weekdays else setOf(date.dayOfWeek),rule.untilDate?.takeIf{it>=date})
        mutable.value=PlanningState(page=PlanningPage.RULE,form=RuleForm(series,settings))
    }
    fun openExceptions(series:SeriesSummary){mutable.value=PlanningState(page=PlanningPage.EXCEPTIONS,exceptionSeries=series)}
    private fun change(edit:(RuleSettings)->RuleSettings){val s=state.value;if(s.busy)return;val f=s.form?:return;mutable.value=s.copy(form=f.copy(settings=edit(f.settings)),error=null,confirmRule=false)}
    fun frequency(f:Frequency)=change{it.copy(frequency=f)}
    fun interval(s:String)=change{it.copy(intervalText=s)}
    fun effectiveDate(date:LocalDate)=change{it.copy(effectiveFrom=date)}
    fun until(date:LocalDate?)=change{it.copy(untilDate=date)}
    fun weekday(day:DayOfWeek)=change{val days=if(day in it.weekdays)it.weekdays-day else it.weekdays+day;it.copy(weekdays=days)}
    fun confirmRule(){val s=state.value;val f=s.form?:return;if(s.busy)return
        if(!f.dirty){mutable.value=s.copy(error="尚未修改规则，无需保存");return}
        try{f.settings.validatedRule();require(f.settings.effectiveFrom>=LocalDate.now(clock)&&f.settings.effectiveFrom>=f.series.startDate){"生效日不得早于今天或系列开始日"};mutable.value=s.copy(confirmRule=true,error=null)}
        catch(e:IllegalArgumentException){mutable.value=s.copy(error=e.message)}
    }
    fun dismissConfirm(){if(!state.value.busy)mutable.value=state.value.copy(confirmRule=false,confirmRecovery=null)}
    fun saveRule(){val s=state.value;val f=s.form?:return;if(!s.confirmRule||s.busy)return
        mutable.value=s.copy(busy=true,error=null)
        viewModelScope.launch{try{repository.changeRecurrence(f.series.taskId,f.settings.effectiveFrom,f.settings.validatedRule(),f.series.revision){LocalDate.now(clock)};mutable.value=PlanningState(page=PlanningPage.MANAGE,message="已保存规则，生效日前记录保留")}
            catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=state.value.copy(busy=false,confirmRule=false,error=e.message?:"保存失败，草稿已保留")}}
    }
    fun requestRecovery(e:TaskException){if(!state.value.busy)mutable.value=state.value.copy(confirmRecovery=e,error=null)}
    fun recover(){val s=state.value;val e=s.confirmRecovery?:return;if(s.busy)return;mutable.value=s.copy(busy=true)
        viewModelScope.launch{try{repository.recoverException(e.taskId,e.date,e.deleted);mutable.value=state.value.copy(busy=false,confirmRecovery=null,message="已恢复该日例外")}
            catch(ex:CancellationException){throw ex}catch(ex:Exception){mutable.value=state.value.copy(busy=false,confirmRecovery=null,error=ex.message?:"恢复失败，请重试")}}
    }
    fun back(){val s=state.value;if(s.busy)return
        when{ s.confirmRule||s.confirmRecovery!=null->dismissConfirm();s.form?.dirty==true->mutable.value=s.copy(discard=true)
            s.page==PlanningPage.RULE||s.page==PlanningPage.EXCEPTIONS->openManage();else->closeAll()}
    }
    fun keepDraft(){mutable.value=state.value.copy(discard=false)}
    fun discard(){openManage()}
    fun canNavigateAway():Boolean{if(state.value.busy||state.value.form?.dirty==true){mutable.value=state.value.copy(error="请先保存或取消当前规则草稿");return false};closeAll();return true}
    fun closeAll(){returnsToHistory=false;mutable.value=PlanningState()}
    class Factory(private val repo:TaskRepository):ViewModelProvider.Factory{
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=PlanningViewModel(repo) as T
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>,extras:CreationExtras):T=PlanningViewModel(repo,saved=runCatching{extras.createSavedStateHandle()}.getOrElse{SavedStateHandle()}) as T
    }
}
