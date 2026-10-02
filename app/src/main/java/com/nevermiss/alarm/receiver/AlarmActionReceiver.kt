package com.nevermiss.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nevermiss.alarm.core.AlarmController

/** Snooze / Dismiss buttons in notifications. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        when (intent.action) {
            ACTION_SNOOZE -> AlarmController.snooze(context, id)
            ACTION_DISMISS -> AlarmController.dismissFully(context, id)
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.nevermiss.alarm.SNOOZE"
        const val ACTION_DISMISS = "com.nevermiss.alarm.DISMISS"
        const val EXTRA_ID = "id"
    }
}
