package app.brokeometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.mail.FoundItem
import app.brokeometer.tasks.TaskEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Tasks: what's still to do, what's done, and where they come from. */
class TasksViewModel(application: Application) : AndroidViewModel(application) {

    private val agent = (application as BrokeApp).container.tasks

    val open: StateFlow<List<TaskEntity>> = agent.open.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val done: StateFlow<List<TaskEntity>> = agent.done.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fromEmail = agent.fromEmail
    val fromCalendar = agent.fromCalendar
    val fromNotes = agent.fromNotes
    val summaryAt = agent.summaryAt
    val ownReminders = agent.ownReminders
    val lookBack = agent.lookBack
    val lookingBack = agent.lookingBack
    val calendarProblem = agent.calendarProblem

    fun add(title: String, day: LocalDate?, time: Int?) = agent.add(title, day, time)
    fun change(task: TaskEntity, title: String, day: LocalDate?, time: Int?) = agent.change(task, title, day, time)
    fun setDone(task: TaskEntity, done: Boolean) = agent.setDone(task, done)
    fun delete(task: TaskEntity) = agent.delete(task)
    fun setDone(tasks: List<TaskEntity>, done: Boolean) = agent.setDone(tasks, done)
    fun delete(tasks: List<TaskEntity>) = agent.delete(tasks)

    fun setFromEmail(on: Boolean) = agent.setFromEmail(on)
    fun setFromCalendar(on: Boolean) = agent.setFromCalendar(on)
    fun setFromNotes(on: Boolean) = agent.setFromNotes(on)
    fun setSummaryAt(minutes: Int) = agent.setSummaryAt(minutes)
    fun setOwnReminders(on: Boolean) = agent.setOwnReminders(on)
    fun canReadCalendar(): Boolean = agent.canReadCalendar()
    fun lookBackAgain() = agent.lookBackOnce(again = true)

    /** The calendar read again and anything due sent, as on opening Tasks. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { agent.syncCalendar() }
            runCatching { agent.check() }
        }
    }

    /** An email task's email, to open in the app: its list entry, or one made from what the task knows. */
    fun emailFor(task: TaskEntity): Long? {
        task.ref?.let { return it }
        val id = task.messageId ?: return null
        val account = task.accountId ?: return null
        val from = task.detail.substringBefore(": ", "")
        val subject = task.detail.substringAfter(": ", task.title)
        val entry = FoundMail.entry(account, FoundItem(0, id, from, "", subject, task.createdAt))
        FoundMail.keep(listOf(entry))
        return entry.id
    }
}
