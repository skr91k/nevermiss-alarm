package com.nevermiss.alarm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.nevermiss.alarm.core.EventLog
import com.nevermiss.alarm.core.Notifications
import com.nevermiss.alarm.ui.MainActivity

class NeverMissApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            EventLog.log(this, "CRASH in ${t.name}: ${e.stackTraceToString().take(1500)}")
            previous?.uncaughtException(t, e)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // Going from no visible screen to the main list = the user opened the app.
                // Moving between our own screens never drops the count to 0.
                if (started++ == 0) openedFresh = activity is MainActivity
            }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        private var started = 0
        private var openedFresh = false

        /** True once per app open (from launcher, recents...), not when coming back from our own screens. */
        fun consumeOpened(): Boolean = openedFresh.also { openedFresh = false }
    }
}
