package com.nevermiss.alarm.core

import android.content.Context
import com.nevermiss.alarm.data.Alarm
import com.nevermiss.alarm.data.AlarmStore
import com.nevermiss.alarm.service.AlarmService

/** All state transitions of an alarm: save, fire, snooze, dismiss. */
object AlarmController {
    /** How many times an unattended alarm re-rings before giving up. */
    const val MAX_AUTO_SNOOZES = 10
    /** After a dismiss, ring once more this much later and require "I'm awake". */
    const val WAKE_CHECK_DELAY_MS = 3 * 60 * 1000L

    fun save(context: Context, alarm: Alarm): Alarm {
        val saved = AlarmStore.upsert(context, alarm.copy(snoozedUntil = 0, autoSnoozes = 0, wakeCheckPending = false))
        EventLog.log(context, "saved alarm ${saved.id} %02d:%02d days=${saved.days} enabled=${saved.enabled}".format(saved.hour, saved.minute))
        if (saved.enabled) AlarmScheduler.schedule(context, saved) else AlarmScheduler.cancel(context, saved.id)
        return AlarmStore.get(context, saved.id) ?: saved
    }

    fun delete(context: Context, id: Int) {
        AlarmScheduler.cancel(context, id)
        AlarmStore.delete(context, id)
        Notifications.cancelSnoozed(context, id)
    }

    fun scheduleTest(context: Context, delayMs: Long = 10_000) {
        val alarm = Alarm(
            id = Alarm.TEST_ID,
            label = "Test alarm",
            mathChallenge = false,
            snoozedUntil = System.currentTimeMillis() + delayMs,
        )
        AlarmStore.upsert(context, alarm)
        AlarmScheduler.schedule(context, alarm)
    }

    /**
     * Called when an alarm broadcast arrives. Returns the alarm to ring, or null if this
     * occurrence was already handled (e.g. the backup firing after the main one rang).
     */
    fun onFire(context: Context, id: Int, trigger: Long, redelivered: Boolean): Pair<Alarm, Boolean>? {
        val alarm = AlarmStore.get(context, id) ?: return null
        if (!redelivered && (alarm.lastFiredAt == trigger || !alarm.enabled)) return null
        val isWakeCheck = alarm.wakeCheckPending && alarm.snoozedUntil == trigger
        AlarmScheduler.cancelBackup(context, id)
        Notifications.cancelSnoozed(context, id)
        val updated = AlarmStore.update(context, id) {
            it.copy(
                lastFiredAt = trigger,
                snoozedUntil = 0,
                // One-time alarms switch off as soon as they ring; snooze / wake check turn them back on.
                enabled = it.isRepeating,
                wakeCheckPending = false,
            )
        } ?: return null
        // Re-arm everything now, so tomorrow's alarm exists even if this process dies while ringing.
        AlarmScheduler.rescheduleAll(context, "alarm $id fired")
        return updated to isWakeCheck
    }

    fun snooze(context: Context, id: Int, auto: Boolean = false) {
        EventLog.log(context, "snooze alarm $id auto=$auto")
        val alarm = AlarmStore.get(context, id)
        if (alarm != null) {
            val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
            val updated = AlarmStore.update(context, id) {
                it.copy(
                    enabled = true,
                    snoozedUntil = until,
                    wakeCheckPending = false,
                    autoSnoozes = if (auto) it.autoSnoozes + 1 else it.autoSnoozes,
                )
            }!!
            AlarmScheduler.schedule(context, updated)
            Notifications.showSnoozed(context, updated, until)
        }
        AlarmService.stop(context)
    }

    /**
     * Dismiss from the ringing screen. Unless this ring *was* the wake-up check, it doesn't end
     * the alarm yet: it schedules a wake-up check a few minutes later.
     */
    fun dismiss(context: Context, id: Int, wasWakeCheck: Boolean) {
        if (wasWakeCheck) return dismissFully(context, id)
        EventLog.log(context, "dismiss alarm $id -> wake-up check in ${WAKE_CHECK_DELAY_MS / 60_000} min")
        val until = System.currentTimeMillis() + WAKE_CHECK_DELAY_MS
        val updated = AlarmStore.update(context, id) {
            it.copy(enabled = true, snoozedUntil = until, wakeCheckPending = true, autoSnoozes = 0)
        }
        if (updated != null) {
            AlarmScheduler.schedule(context, updated)
            Notifications.showSnoozed(context, updated, until, wakeCheck = true)
        }
        AlarmService.stop(context)
    }

    fun dismissFully(context: Context, id: Int) {
        EventLog.log(context, "dismiss alarm $id (done)")
        val updated = AlarmStore.update(context, id) {
            it.copy(snoozedUntil = 0, autoSnoozes = 0, wakeCheckPending = false, enabled = it.isRepeating && it.enabled)
        }
        if (updated != null) {
            if (updated.enabled) AlarmScheduler.schedule(context, updated) else AlarmScheduler.cancel(context, id)
        }
        if (id == Alarm.TEST_ID) AlarmStore.delete(context, id)
        Notifications.cancelSnoozed(context, id)
        AlarmService.stop(context)
    }

    /** Nobody reacted within the alarm's auto-snooze time: ring again later rather than silently stopping. */
    fun onRingTimeout(context: Context, id: Int) {
        val alarm = AlarmStore.get(context, id)
        if (alarm != null && alarm.autoSnoozes < MAX_AUTO_SNOOZES) {
            snooze(context, id, auto = true)
        } else {
            dismissFully(context, id)
            if (alarm != null) Notifications.showMissed(context, alarm)
        }
    }
}
