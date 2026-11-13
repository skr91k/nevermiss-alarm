package com.nevermiss.alarm.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.nevermiss.alarm.R
import com.nevermiss.alarm.data.Alarm
import com.nevermiss.alarm.data.CountdownTimer
import com.nevermiss.alarm.data.TimerStore
import com.nevermiss.alarm.receiver.TimerReceiver
import com.nevermiss.alarm.ui.TimerActivity
import com.nevermiss.alarm.receiver.AlarmActionReceiver
import com.nevermiss.alarm.ui.AlarmActivity
import com.nevermiss.alarm.ui.MainActivity
import java.util.Date

object Notifications {
    const val CH_RINGING = "ringing"
    const val CH_STATUS = "status"
    /** Used only if the foreground service can't start: the notification itself plays the alarm sound. */
    const val CH_FALLBACK = "fallback_alarm"

    /** Running timers: quiet, ongoing countdown. */
    const val CH_TIMER = "timer_running"

    const val RINGING_ID = 1
    const val TIMER_RINGING_ID = 2
    private const val TIMER_RUNNING_BASE = 30_000
    private const val TIMER_MISSED_BASE = 40_000
    private const val TIMER_FALLBACK_BASE = 50_000
    private const val SNOOZED_BASE = 10_000
    private const val MISSED_BASE = 20_000

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RINGING, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while an alarm is ringing"
                setSound(null, null) // the service plays the sound itself
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Snoozed & missed alarms", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, "Running timers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Countdown of timers that are running"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FALLBACK, "Backup alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Emergency alarm used if the normal alarm can't start"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
        )
    }

    fun buildRinging(context: Context, alarm: Alarm, preview: Boolean = false): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, alarm.id, AlarmActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CH_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle((if (preview) "Preview: " else "") + alarm.label.ifBlank { "Alarm" })
            .setContentText(formatTime(context, System.currentTimeMillis()) + " - tap to open")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (preview) {
            builder.addAction(0, "Stop preview", action(context, AlarmActionReceiver.ACTION_STOP_PREVIEW, alarm.id))
        } else {
            builder.addAction(0, "Snooze ${alarm.snoozeMinutes} min", action(context, AlarmActionReceiver.ACTION_SNOOZE, alarm.id))
        }
        // No Dismiss button here on purpose: dismissing happens only on the ringing screen.
        return builder.build()
    }

    fun buildPlaceholder(context: Context): Notification =
        NotificationCompat.Builder(context, CH_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("NeverMiss")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Last line of defence: a notification that loops the alarm sound on its own. */
    fun postFallback(context: Context, alarm: Alarm) {
        val fullScreen = PendingIntent.getActivity(
            context, alarm.id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_FALLBACK)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm.label.ifBlank { "Alarm" })
            .setContentText("Alarm! Tap to stop.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setAutoCancel(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        notify(context, MISSED_BASE + alarm.id, n)
    }

    fun showSnoozed(context: Context, alarm: Alarm, until: Long, wakeCheck: Boolean = false) {
        val builder = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(
                if (wakeCheck) "Wake-up check at ${formatTime(context, until)}"
                else "Snoozed until ${formatTime(context, until)}",
            )
            .setContentText(
                if (wakeCheck) "Make sure you're up - it will ring once more"
                else alarm.label.ifBlank { "Alarm" },
            )
            .setOngoing(true)
        // No Dismiss here: a snoozed alarm / wake check can only be ended on the ringing screen.
        val n = builder.build()
        notify(context, SNOOZED_BASE + alarm.id, n)
    }

    fun cancelSnoozed(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(SNOOZED_BASE + id)
    }

    fun showMissed(context: Context, alarm: Alarm) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Alarm rang without response")
            .setContentText("${alarm.label.ifBlank { "Alarm" }} rang ${MAX_RINGS} times and was stopped")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, MISSED_BASE + alarm.id, n)
    }

    private const val MAX_RINGS = AlarmController.MAX_AUTO_SNOOZES + 1

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // Notification permission denied; the alarm itself still rings.
        }
    }

    private fun action(context: Context, action: String, id: Int): PendingIntent {
        val intent = Intent(context, AlarmActionReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("nevermiss://action/$id/$action"))
            .putExtra(AlarmActionReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun formatTime(context: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))


    fun showTimerRunning(context: Context, timer: CountdownTimer) {
        val n = NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(timer.title())
            .setContentText("Ends at ${formatTime(context, timer.endAt)}")
            .setWhen(timer.endAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openTimers(context))
            .addAction(0, "Pause", timerAction(context, TimerReceiver.ACTION_PAUSE, timer.id))
            .addAction(0, "+1:00", timerAction(context, TimerReceiver.ACTION_ADD_MINUTE, timer.id))
            .build()
        notify(context, TIMER_RUNNING_BASE + timer.id, n)
    }

    fun cancelTimerRunning(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(TIMER_RUNNING_BASE + id)
    }

    fun buildTimerRinging(context: Context, ids: Set<Int>): Notification {
        val timers = ids.mapNotNull { TimerStore.get(context, it) }
        val fullScreen = PendingIntent.getActivity(
            context, 1, TimerActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val first = timers.firstOrNull()
        val builder = NotificationCompat.Builder(context, CH_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(if (timers.size > 1) "${timers.size} timers are done" else "Time's up")
            .setContentText(timers.joinToString(", ") { it.title() + " " + formatDurationShort(it.durationMs) })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (first != null) {
            builder.addAction(0, "Stop", timerAction(context, TimerReceiver.ACTION_STOP, first.id))
            builder.addAction(0, "+1:00", timerAction(context, TimerReceiver.ACTION_ADD_MINUTE, first.id))
        }
        return builder.build()
    }

    /** Foreground service couldn't start: an insistent notification plays the sound itself. */
    fun postTimerFallback(context: Context, timer: CountdownTimer) {
        val n = NotificationCompat.Builder(context, CH_FALLBACK)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Time's up")
            .setContentText(timer.title() + " " + formatDurationShort(timer.durationMs))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openTimers(context))
            .setDeleteIntent(timerAction(context, TimerReceiver.ACTION_STOP, timer.id))
            .addAction(0, "Stop", timerAction(context, TimerReceiver.ACTION_STOP, timer.id))
            .setAutoCancel(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        notify(context, TIMER_FALLBACK_BASE + timer.id, n)
    }

    fun cancelTimerFallback(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(TIMER_FALLBACK_BASE + id)
    }

    fun showTimerMissed(context: Context, timer: CountdownTimer) {
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("${timer.title()} finished")
            .setContentText("${formatDurationShort(timer.durationMs)} timer rang without response")
            .setContentIntent(openTimers(context))
            .setAutoCancel(true)
            .build()
        notify(context, TIMER_MISSED_BASE + timer.id, n)
    }

    private fun openTimers(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 2, MainActivity.timerIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun timerAction(context: Context, action: String, id: Int): PendingIntent {
        val intent = Intent(context, TimerReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("nevermiss://timer/$id/$action"))
            .putExtra(TimerReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatDurationShort(ms: Long): String = com.nevermiss.alarm.ui.formatDuration(ms)
}
