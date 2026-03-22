package com.socialguard.blocker.ui.settings

import android.app.Application
import androidx.lifecycle.*
import com.socialguard.blocker.data.model.BlockingSettings
import com.socialguard.blocker.data.repository.BlockingRepository
import com.socialguard.blocker.service.BlockingAccessibilityService
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BlockingRepository.getInstance(application)
    val settings: LiveData<BlockingSettings?> = repository.observeSettings()

    fun updateSettings(update: BlockingSettings.() -> BlockingSettings) {
        viewModelScope.launch {
            val current = repository.getSettings()
            repository.saveSettings(current.update())
            BlockingAccessibilityService.instance?.invalidateSettingsCache()
        }
    }

    fun setBlockInstagramReels(value: Boolean) = updateSettings { copy(blockInstagramReels = value) }
    fun setBlockInstagramExplore(value: Boolean) = updateSettings { copy(blockInstagramExplore = value) }
    fun setBlockInstagramDMs(value: Boolean) = updateSettings { copy(blockInstagramDMs = value) }
    fun setBlockFacebookMarketplace(value: Boolean) = updateSettings { copy(blockFacebookMarketplace = value) }
    fun setBlockFacebookWatch(value: Boolean) = updateSettings { copy(blockFacebookWatch = value) }
    fun setBlockFacebookGaming(value: Boolean) = updateSettings { copy(blockFacebookGaming = value) }
    fun setShowUsagePopup(value: Boolean) = updateSettings { copy(showUsagePopup = value) }
}
