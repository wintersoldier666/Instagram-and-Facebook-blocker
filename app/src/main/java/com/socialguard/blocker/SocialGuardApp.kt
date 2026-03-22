package com.socialguard.blocker

import android.app.Application
import com.socialguard.blocker.data.database.AppDatabase

class SocialGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize database eagerly so the default settings row exists
        AppDatabase.getInstance(this)
    }
}
