package com.quell.app.ui.timelocks

import android.app.Application
import androidx.lifecycle.*
import com.quell.app.data.model.BlockingSettings
import com.quell.app.data.repository.BlockingRepository
import com.quell.app.service.BlockingAccessibilityService
import kotlinx.coroutines.launch

class TimeLocksViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BlockingRepository.getInstance(application)
    val settings: LiveData<BlockingSettings?> = repository.observeSettings()

    fun updateSettings(update: BlockingSettings.() -> BlockingSettings) {
        viewModelScope.launch {
            val current = repository.getSettings()
            repository.saveSettings(current.update())
            BlockingAccessibilityService.instance?.invalidateSettingsCache()
        }
    }

    fun setTimeLockEnabled(value: Boolean) = updateSettings { copy(timeLockEnabled = value) }

    fun setTimeLockStart(hour: Int, minute: Int) = updateSettings {
        copy(timeLockStartHour = hour, timeLockStartMinute = minute)
    }

    fun setTimeLockEnd(hour: Int, minute: Int) = updateSettings {
        copy(timeLockEndHour = hour, timeLockEndMinute = minute)
    }

    fun setSessionLimitEnabled(value: Boolean) = updateSettings { copy(sessionLimitEnabled = value) }

    fun setSessionLimitMinutes(minutes: Int) = updateSettings { copy(sessionLimitMinutes = minutes) }

    fun setDailyLimitEnabled(value: Boolean) = updateSettings { copy(dailyLimitEnabled = value) }

    fun setDailyLimitInstagram(minutes: Int) = updateSettings { copy(dailyLimitMinutesInstagram = minutes) }

    fun setDailyLimitFacebook(minutes: Int) = updateSettings { copy(dailyLimitMinutesFacebook = minutes) }
}
