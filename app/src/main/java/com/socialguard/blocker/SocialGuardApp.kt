package com.quell.app

import android.app.Application
import com.quell.app.data.database.AppDatabase

class QuellApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize database eagerly so the default settings row exists
        AppDatabase.getInstance(this)
    }
}
