package com.socialguard.blocker.ui.stats

import android.app.Application
import androidx.lifecycle.*
import com.socialguard.blocker.data.repository.BlockingRepository
import com.socialguard.blocker.util.UsageStatsHelper
import kotlinx.coroutines.launch

data class AppStats(
    val appName: String,
    val packageName: String,
    val dailyMinutes: Map<String, Long>,  // date -> minutes
    val todayMinutes: Long,
    val weeklyTotal: Long
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val _instagramStats = MutableLiveData<AppStats>()
    val instagramStats: LiveData<AppStats> = _instagramStats

    private val _facebookStats = MutableLiveData<AppStats>()
    val facebookStats: LiveData<AppStats> = _facebookStats

    fun loadStats() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()

            val instaDailyMap = UsageStatsHelper.getUsageForLastDays(ctx, BlockingRepository.INSTAGRAM_PKG, 7)
            val instaToday = UsageStatsHelper.getTodayUsageMinutes(ctx, BlockingRepository.INSTAGRAM_PKG)
            _instagramStats.value = AppStats(
                appName = "Instagram",
                packageName = BlockingRepository.INSTAGRAM_PKG,
                dailyMinutes = instaDailyMap,
                todayMinutes = instaToday,
                weeklyTotal = instaDailyMap.values.sum()
            )

            val fbDailyMap = UsageStatsHelper.getUsageForLastDays(ctx, BlockingRepository.FACEBOOK_PKG, 7)
            val fbToday = UsageStatsHelper.getTodayUsageMinutes(ctx, BlockingRepository.FACEBOOK_PKG)
            _facebookStats.value = AppStats(
                appName = "Facebook",
                packageName = BlockingRepository.FACEBOOK_PKG,
                dailyMinutes = fbDailyMap,
                todayMinutes = fbToday,
                weeklyTotal = fbDailyMap.values.sum()
            )
        }
    }
}
