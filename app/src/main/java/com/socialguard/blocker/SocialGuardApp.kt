package com.quell.app

import android.app.Application
import com.quell.app.data.database.AppDatabase
import com.quell.app.util.CrashLogger

class QuellApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // Initialize database eagerly so the default settings row exists
        AppDatabase.getInstance(this)
    }
}
