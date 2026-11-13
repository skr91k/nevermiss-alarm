package com.nevermiss.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nevermiss.alarm.core.TimerController

/** Timer finished (from AlarmManager), and the Stop / +1 min buttons of its notifications. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        when (intent.action) {
            ACTION_FIRE -> TimerController.onFire(context, id, intent.getLongExtra(EXTRA_END_AT, 0L))
            ACTION_STOP -> TimerController.stop(context, id)
            ACTION_ADD_MINUTE -> TimerController.addMinute(context, id)
            ACTION_PAUSE -> TimerController.pause(context, id)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.nevermiss.alarm.TIMER_FIRE"
        const val ACTION_STOP = "com.nevermiss.alarm.TIMER_STOP"
        const val ACTION_ADD_MINUTE = "com.nevermiss.alarm.TIMER_ADD_MINUTE"
        const val ACTION_PAUSE = "com.nevermiss.alarm.TIMER_PAUSE"
        const val EXTRA_ID = "id"
        const val EXTRA_END_AT = "end_at"
    }
}
