package com.nevermiss.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nevermiss.alarm.core.AlarmScheduler

/**
 * Reboot, app update, clock / time-zone change and exact-alarm permission changes can all
 * wipe or invalidate AlarmManager entries - re-arm everything each time.
 * LOCKED_BOOT_COMPLETED arrives before the first unlock, so alarms work even if the phone
 * restarted overnight (e.g. an automatic system update) and nobody has entered the PIN yet.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context, intent.action ?: "system event")
    }
}
