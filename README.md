package com.lifetrack.ui

import android.app.Application
import androidx.lifecycle.*
import androidx.room.Room
import com.lifetrack.data.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LifeViewModel(app: Application) : AndroidViewModel(app) {
    private val db =
        LifeStore.get(app)
    val repo = LocalLifeRepository(db)
    private val preferences = SettingsRepository(app)
    val data =
        repo.snapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Snapshot())
    val settings = preferences.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    var ready = MutableStateFlow(false)
        private set

    val loadError = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 10)

    init {
        load()
        viewModelScope.launch { repo.snapshots.collect { TodayWidget.render(app,it) } }
    }

    fun load() {
        viewModelScope.launch {
            loadError.value = null
            try {
                repo.initialize()
                ready.value = true
                refreshReminders()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadError.value = e.message ?: "Не удалось открыть базу данных"
            }
        }
    }

    fun run(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.emit(e.message ?: "Не удалось выполнить действие")
            }
        }
    }

    fun saveSettings(s: Settings) = run {
        preferences.save(s)
        refreshReminders()
    }

    fun start(demo: Boolean) = run {
        if (demo) repo.demo()
        preferences.save(settings.value.copy(onboarded = true))
        messages.emit("Добро пожаловать в LifeTrack")
    }

    fun saveHabit(h: HabitEntity) = run {
        repo.habit(h)
        refreshReminders()
    }

    fun toggle(h: HabitEntity, date: LocalDate = LocalDate.now()) = run { db.productDao().put(ProductPreference("habit_manual:${h.id}:${date.toEpochDay()}", "true")); repo.toggle(h, date) }

    suspend fun refreshReminders() {
        ReminderScheduler.sync(
            getApplication(),
            repo.snapshots.first().habits,
            preferences.flow.first().notifications,
        )
    }

    suspend fun import(text: String) {
        preferences.save(repo.import(text))
        refreshReminders()
    }

    fun export(): String = repo.export(data.value, settings.value)
}
