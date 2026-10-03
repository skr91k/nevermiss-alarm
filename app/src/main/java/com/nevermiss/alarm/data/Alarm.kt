package com.nevermiss.alarm.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Repeat is one of:
 *  - [date] set: rings once on that exact date (ISO yyyy-MM-dd), then switches off.
 *  - [monthDay] 1..31: every month on that day; months without that day use their last day.
 *  - [days] bitmask, Monday = bit 0 ... Sunday = bit 6: weekly.
 *  - none of these: once, at the next occurrence of the time.
 * @param scheduledAt the trigger time currently armed in AlarmManager.
 * @param lastFiredAt the trigger time of the most recent occurrence that actually rang.
 *                    If scheduledAt is in the past and != lastFiredAt, the occurrence was missed.
 */
data class Alarm(
    val id: Int = 0,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val ringtoneUri: String? = null,
    val snoozeMinutes: Int = 5,
    val mathChallenge: Boolean = true,
    val snoozedUntil: Long = 0L,
    val scheduledAt: Long = 0L,
    val lastFiredAt: Long = 0L,
    val autoSnoozes: Int = 0,
    /** The pending snooze is a wake-up check: rings again after dismiss to make sure you're up. */
    val wakeCheckPending: Boolean = false,
    /** > 0: reminder-style alarm that rings this many seconds and then stops by itself. */
    val autoStopSeconds: Int = 0,
    /** Rings untouched this long, then snoozes by itself. */
    val autoSnoozeMinutes: Int = 1,
    val date: String? = null,
    val monthDay: Int = 0,
    /** A repeating occurrence the user chose to skip ("Skip next"). */
    val skippedTrigger: Long = 0L,
    /** After dismiss, ring again a few minutes later until "I'm awake" is tapped. */
    val ensureAwake: Boolean = true,
) {
    /** True while the next regular occurrence is being skipped. */
    fun isSkipping(now: Long = System.currentTimeMillis()): Boolean =
        isRepeating && skippedTrigger > now && regularTrigger(now) == skippedTrigger

    /** A fresh copy for "Duplicate": same settings, no runtime state. */
    fun duplicate(): Alarm = Alarm(
        hour = hour, minute = minute, days = days, label = label, vibrate = vibrate,
        ringtoneUri = ringtoneUri, snoozeMinutes = snoozeMinutes, mathChallenge = mathChallenge,
        autoStopSeconds = autoStopSeconds, autoSnoozeMinutes = autoSnoozeMinutes,
        date = date, monthDay = monthDay, ensureAwake = ensureAwake,
    )

    val isRepeating: Boolean get() = date == null && (days != 0 || monthDay != 0)

    val exactDate: LocalDate? get() = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun hasDay(dayIndex: Int): Boolean = days and (1 shl dayIndex) != 0

    fun nextTrigger(now: Long = System.currentTimeMillis()): Long {
        if (snoozedUntil > now) return snoozedUntil
        val next = regularTrigger(now)
        return if (isRepeating && skippedTrigger != 0L && next == skippedTrigger) regularTrigger(next) else next
    }

    /** Next occurrence from the repeat rule alone - ignores snooze and skip. */
    fun regularTrigger(now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        exactDate?.let { d ->
            // May be in the past; the scheduler refuses to arm it then.
            return d.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        }
        if (monthDay != 0) {
            var month = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
            repeat(13) {
                val day = minOf(monthDay, month.lengthOfMonth())
                val candidate = month.atDay(day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                if (candidate > now) return candidate
                month = month.plusMonths(1)
            }
        }
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(8) {
            val candidate = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (candidate > now && (days == 0 || hasDay(date.dayOfWeek.value - 1))) return candidate
            date = date.plusDays(1)
        }
        error("No trigger found for alarm $id")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("days", days)
        put("label", label)
        put("enabled", enabled)
        put("vibrate", vibrate)
        put("ringtoneUri", ringtoneUri ?: "")
        put("snoozeMinutes", snoozeMinutes)
        put("mathChallenge", mathChallenge)
        put("snoozedUntil", snoozedUntil)
        put("scheduledAt", scheduledAt)
        put("lastFiredAt", lastFiredAt)
        put("autoSnoozes", autoSnoozes)
        put("wakeCheckPending", wakeCheckPending)
        put("autoStopSeconds", autoStopSeconds)
        put("autoSnoozeMinutes", autoSnoozeMinutes)
        put("date", date ?: "")
        put("monthDay", monthDay)
        put("skippedTrigger", skippedTrigger)
        put("ensureAwake", ensureAwake)
    }

    companion object {
        /** Hidden alarm used by the "Test alarm" button. */
        const val TEST_ID = 999_999

        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            days = o.optInt("days"),
            label = o.optString("label"),
            enabled = o.optBoolean("enabled", true),
            vibrate = o.optBoolean("vibrate", true),
            ringtoneUri = o.optString("ringtoneUri").ifEmpty { null },
            snoozeMinutes = o.optInt("snoozeMinutes", 5),
            mathChallenge = o.optBoolean("mathChallenge", true),
            snoozedUntil = o.optLong("snoozedUntil"),
            scheduledAt = o.optLong("scheduledAt"),
            lastFiredAt = o.optLong("lastFiredAt"),
            autoSnoozes = o.optInt("autoSnoozes"),
            wakeCheckPending = o.optBoolean("wakeCheckPending"),
            autoStopSeconds = o.optInt("autoStopSeconds"),
            autoSnoozeMinutes = o.optInt("autoSnoozeMinutes", 1),
            date = o.optString("date").ifEmpty { null },
            monthDay = o.optInt("monthDay"),
            skippedTrigger = o.optLong("skippedTrigger"),
            ensureAwake = o.optBoolean("ensureAwake", true),
        )
    }
}
