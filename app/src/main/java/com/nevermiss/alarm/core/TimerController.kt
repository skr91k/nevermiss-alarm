package com.nevermiss.alarm.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nevermiss.alarm.data.CountdownTimer
import com.nevermiss.alarm.data.TimerStore
import com.nevermiss.alarm.receiver.TimerReceiver
import com.nevermiss.alarm.service.TimerService
import com.nevermiss.alarm.ui.MainActivity

/**
 * Timers are armed in AlarmManager with setAlarmClock(), exactly like alarms, so they ring
 * even when the app is closed, swiped away or the process was killed. Nothing has to keep
 * running in the background while a timer counts down.
 */
object TimerController {
    /** A timer that ended while the phone was off less than this long ago still rings. */
    private const val MISSED_WINDOW_MS = 2 * 60 * 60 * 1000L

    fun create(context: Context, durationMs: Long, label: String = "", start: Boolean = true): CountdownTimer {
        val saved = TimerStore.upsert(context, CountdownTimer(label = label, durationMs = durationMs))
        EventLog.log(context, "timer ${saved.id} created ${durationMs / 1000}s")
        return if (start) start(context, saved.id) ?: saved else saved
    }

    fun duplicate(context: Context, timer: CountdownTimer): CountdownTimer =
        create(context, timer.durationMs, timer.label, start = false)

    fun rename(context: Context, id: Int, label: String) {
        val t = TimerStore.update(context, id) { it.copy(label = label.trim()) } ?: return
        if (t.isRunning) Notifications.showTimerRunning(context, t)
    }

    fun start(context: Context, id: Int): CountdownTimer? {
        val now = System.currentTimeMillis()
        val t = TimerStore.update(context, id) {
            val left = if (it.remainingMs > 0) it.remainingMs else it.durationMs
            it.copy(endAt = now + left, ringing = false)
        } ?: return null
        arm(context, t)
        return t
    }

    fun pause(context: Context, id: Int) {
        val now = System.currentTimeMillis()
        TimerStore.update(context, id) {
            if (!it.isRunning) it else it.copy(remainingMs = it.remaining(now), endAt = 0)
        }
        disarm(context, id)
    }

    fun reset(context: Context, id: Int) {
        TimerStore.update(context, id) { it.copy(endAt = 0, remainingMs = it.durationMs, ringing = false) }
        disarm(context, id)
        TimerService.remove(context, id)
    }

    /** +1:00 - on a running or paused timer adds a minute; on a ringing one restarts it for a minute. */
    fun addMinute(context: Context, id: Int) {
        val now = System.currentTimeMillis()
        val t = TimerStore.update(context, id) {
            when {
                it.ringing -> it.copy(ringing = false, endAt = now + 60_000)
                it.isRunning -> it.copy(endAt = it.endAt + 60_000)
                else -> it.copy(remainingMs = it.remainingMs + 60_000)
            }
        } ?: return
        TimerService.remove(context, id)
        if (t.isRunning) arm(context, t)
    }

    /** Stop the ringing: the timer goes back to its full length, ready to start again. */
    fun stop(context: Context, id: Int) {
        EventLog.log(context, "timer $id stopped")
        reset(context, id)
    }

    fun delete(context: Context, id: Int) {
        disarm(context, id)
        TimerService.remove(context, id)
        TimerStore.delete(context, id)
    }

    /** The AlarmManager broadcast for a timer arrived. */
    fun onFire(context: Context, id: Int, endAt: Long) {
        val t = TimerStore.get(context, id) ?: return
        // Paused, reset or given more time since this was armed - stale broadcast.
        if (t.endAt == 0L || t.endAt != endAt) return
        ring(context, t)
    }

    private fun ring(context: Context, t: CountdownTimer) {
        EventLog.log(context, "timer ${t.id} done - ringing")
        TimerStore.update(context, t.id) { it.copy(ringing = true) }
        Notifications.cancelTimerRunning(context, t.id)
        TimerService.start(context, t.id)
    }

    /** Re-arms running timers; rings ones that ended while nobody was listening (reboot, killed process). */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (t in TimerStore.all(context)) {
            when {
                t.ringing -> if (t.id !in TimerService.ringing.value) TimerService.start(context, t.id)
                t.endAt == 0L -> Notifications.cancelTimerRunning(context, t.id)
                t.endAt <= now && t.endAt > now - MISSED_WINDOW_MS -> ring(context, t)
                t.endAt <= now -> {
                    TimerStore.update(context, t.id) { it.copy(endAt = 0, remainingMs = it.durationMs) }
                    Notifications.cancelTimerRunning(context, t.id)
                }
                else -> arm(context, t)
            }
        }
    }

    private fun arm(context: Context, t: CountdownTimer) {
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = firePendingIntent(context, t.id, t.endAt)
        val show = PendingIntent.getActivity(
            context, 0, MainActivity.timerIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t.endAt, show), fire)
        } catch (e: SecurityException) {
            EventLog.log(context, "setAlarmClock REFUSED for timer ${t.id} - inexact fallback", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, fire)
        }
        Notifications.showTimerRunning(context, t)
    }

    private fun disarm(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(context, id, 0))
        Notifications.cancelTimerRunning(context, id)
    }

    private fun firePendingIntent(context: Context, id: Int, endAt: Long): PendingIntent {
        val intent = Intent(context, TimerReceiver::class.java)
            .setAction(TimerReceiver.ACTION_FIRE)
            .setData(Uri.parse("nevermiss://timer/$id"))
            .putExtra(TimerReceiver.EXTRA_ID, id)
            .putExtra(TimerReceiver.EXTRA_END_AT, endAt)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
