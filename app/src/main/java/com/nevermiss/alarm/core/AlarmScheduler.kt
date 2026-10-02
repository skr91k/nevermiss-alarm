package com.nevermiss.alarm.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.nevermiss.alarm.data.Alarm
import com.nevermiss.alarm.data.AlarmStore
import com.nevermiss.alarm.receiver.AlarmReceiver
import com.nevermiss.alarm.ui.MainActivity

/**
 * Every enabled alarm is armed twice:
 *  1. setAlarmClock() - the highest-priority alarm Android has. It is exempt from Doze and
 *     app-standby, and the system even leaves idle mode early so it fires on time.
 *  2. a backup setExactAndAllowWhileIdle() shortly after, which only rings if the main one
 *     never got delivered (the occurrence is de-duplicated by its trigger time).
 */
object AlarmScheduler {
    const val ACTION_FIRE = "com.nevermiss.alarm.FIRE"
    const val EXTRA_ID = "id"
    const val EXTRA_TRIGGER = "trigger"
    const val EXTRA_BACKUP = "backup"

    private const val BACKUP_DELAY_MS = 90_000L
    /** An occurrence missed less than this long ago (phone off, app killed...) rings immediately. */
    private const val MISSED_WINDOW_MS = 2 * 60 * 60 * 1000L

    fun rescheduleAll(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        EventLog.log(context, "rescheduleAll ($reason), exact=${canScheduleExact(context)}")
        for (alarm in AlarmStore.all(context)) {
            if (!alarm.enabled) {
                cancel(context, alarm.id)
                continue
            }
            val missed = alarm.scheduledAt in (now - MISSED_WINDOW_MS) until now &&
                alarm.lastFiredAt != alarm.scheduledAt
            if (missed) {
                EventLog.log(context, "MISSED alarm ${alarm.id} slot ${EventLog.time(alarm.scheduledAt)} (lastFired ${EventLog.time(alarm.lastFiredAt)}) - ringing now")
                arm(context, alarm.id, trigger = alarm.scheduledAt, fireAt = now + 3_000)
            } else {
                schedule(context, alarm)
            }
        }
    }

    /** Arms the next occurrence of [alarm] and returns its trigger time. */
    fun schedule(context: Context, alarm: Alarm): Long {
        val trigger = alarm.nextTrigger()
        if (trigger <= System.currentTimeMillis()) {
            EventLog.log(context, "alarm ${alarm.id} date ${alarm.date} has passed - switching off")
            cancel(context, alarm.id)
            AlarmStore.update(context, alarm.id) { it.copy(enabled = false) }
            return trigger
        }
        arm(context, alarm.id, trigger, trigger)
        if (trigger != alarm.scheduledAt) EventLog.log(context, "armed alarm ${alarm.id} for ${EventLog.time(trigger)}")
        AlarmStore.update(context, alarm.id) { it.copy(scheduledAt = trigger) }
        return trigger
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(firePendingIntent(context, id, 0, backup = false))
        am.cancel(firePendingIntent(context, id, 0, backup = true))
    }

    fun cancelBackup(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(firePendingIntent(context, id, 0, backup = true))
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun arm(context: Context, id: Int, trigger: Long, fireAt: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val main = firePendingIntent(context, id, trigger, backup = false)
        val backup = firePendingIntent(context, id, trigger, backup = true)
        val show = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, show), main)
        } catch (e: SecurityException) {
            EventLog.log(context, "setAlarmClock REFUSED for alarm $id - inexact fallback", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, main)
        }
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt + BACKUP_DELAY_MS, backup)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt + BACKUP_DELAY_MS, backup)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt + BACKUP_DELAY_MS, backup)
        }
    }

    private fun firePendingIntent(context: Context, id: Int, trigger: Long, backup: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            // Unique data per (alarm, main/backup) so the two PendingIntents never collide.
            .setData(Uri.parse("nevermiss://alarm/$id/${if (backup) "backup" else "main"}"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TRIGGER, trigger)
            .putExtra(EXTRA_BACKUP, backup)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
