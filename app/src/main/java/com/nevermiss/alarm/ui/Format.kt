package com.nevermiss.alarm.ui

import android.content.Context
import android.text.format.DateFormat
import com.nevermiss.alarm.data.Alarm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatHm(context: Context, hour: Int, minute: Int): String =
    if (DateFormat.is24HourFormat(context)) "%02d:%02d".format(hour, minute)
    else "%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")

fun formatMillis(context: Context, millis: Long): String {
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " +
        formatHm(context, t.hour, t.minute)
}

fun daysLabel(alarm: Alarm): String {
    alarm.exactDate?.let {
        return if (alarm.yearly) "Yearly on " + it.format(DateTimeFormatter.ofPattern("d MMMM"))
        else "On " + it.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))
    }
    if (alarm.monthDay != 0) {
        return "Monthly on the ${ordinal(alarm.monthDay)}" + if (alarm.monthDay > 28) " (or last day)" else ""
    }
    return weeklyLabel(alarm)
}

fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

private fun weeklyLabel(alarm: Alarm): String = when (alarm.days) {
    0 -> "Once"
    0b1111111 -> "Every day"
    0b0011111 -> "Weekdays"
    0b1100000 -> "Weekends"
    else -> (0..6).filter { alarm.hasDay(it) }.joinToString(", ") { DAY_NAMES[it] }
}

/** "Fri 12 Dec, 7:00 AM" - with the date, for reminders that may be weeks away. */
fun formatDateTime(context: Context, millis: Long): String {
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return t.format(DateTimeFormatter.ofPattern("EEE d MMM")) + ", " + formatHm(context, t.hour, t.minute)
}

/** 9:51, 1:00:00 - timer display. */
fun formatDuration(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun ringsIn(millis: Long, now: Long = System.currentTimeMillis()): String {
    val totalMin = ((millis - now + 59_999) / 60_000).coerceAtLeast(0)
    val d = totalMin / (60 * 24)
    val h = (totalMin / 60) % 24
    val m = totalMin % 60
    return buildString {
        append("in ")
        if (d > 0) append("${d}d ")
        if (d > 0 || h > 0) append("${h}h ")
        append("${m}min")
    }
}
