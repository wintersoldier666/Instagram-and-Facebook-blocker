package com.socialguard.blocker.ui.timelocks

import android.app.Application
import androidx.lifecycle.*
import com.socialguard.blocker.data.model.BlockingSettings
import com.socialguard.blocker.data.repository.BlockingRepository
import com.socialguard.blocker.service.BlockingAccessibilityService
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
}
