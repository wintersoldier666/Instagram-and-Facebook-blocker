package com.socialguard.blocker.ui.dashboard

import android.app.Application
import androidx.lifecycle.*
import com.socialguard.blocker.data.model.BlockingSettings
import com.socialguard.blocker.data.repository.BlockingRepository
import com.socialguard.blocker.util.UsageStatsHelper
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BlockingRepository.getInstance(application)

    val settings: LiveData<BlockingSettings?> = repository.observeSettings()

    private val _instagramMinutes = MutableLiveData<Long>(0L)
    val instagramMinutes: LiveData<Long> = _instagramMinutes

    private val _facebookMinutes = MutableLiveData<Long>(0L)
    val facebookMinutes: LiveData<Long> = _facebookMinutes

    fun refreshUsage() {
        viewModelScope.launch {
            _instagramMinutes.value = UsageStatsHelper.getTodayUsageMinutes(
                getApplication(), BlockingRepository.INSTAGRAM_PKG
            )
            _facebookMinutes.value = UsageStatsHelper.getTodayUsageMinutes(
                getApplication(), BlockingRepository.FACEBOOK_PKG
            )
        }
    }

    fun setBlockInstagram(block: Boolean) = viewModelScope.launch {
        val current = repository.getSettings()
        repository.saveSettings(current.copy(blockInstagram = block))
        invalidateAccessibilityCache()
    }

    fun setBlockFacebook(block: Boolean) = viewModelScope.launch {
        val current = repository.getSettings()
        repository.saveSettings(current.copy(blockFacebook = block))
        invalidateAccessibilityCache()
    }

    private fun invalidateAccessibilityCache() {
        com.socialguard.blocker.service.BlockingAccessibilityService.instance?.invalidateSettingsCache()
    }
}
