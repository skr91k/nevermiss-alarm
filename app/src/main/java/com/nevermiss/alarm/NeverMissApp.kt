package com.nevermiss.alarm

import android.app.Application
import com.nevermiss.alarm.core.EventLog
import com.nevermiss.alarm.core.Notifications

class NeverMissApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            EventLog.log(this, "CRASH in ${t.name}: ${e.stackTraceToString().take(1500)}")
            previous?.uncaughtException(t, e)
        }
    }
}
