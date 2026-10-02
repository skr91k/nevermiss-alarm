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
import com.nevermiss.alarm.receiver.AlarmActionReceiver
import com.nevermiss.alarm.ui.AlarmActivity
import com.nevermiss.alarm.ui.MainActivity
import java.util.Date

object Notifications {
    const val CH_RINGING = "ringing"
    const val CH_STATUS = "status"
    /** Used only if the foreground service can't start: the notification itself plays the alarm sound. */
    const val CH_FALLBACK = "fallback_alarm"

    const val RINGING_ID = 1
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

    fun buildRinging(context: Context, alarm: Alarm): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, alarm.id, AlarmActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CH_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm.label.ifBlank { "Alarm" })
            .setContentText(formatTime(context, System.currentTimeMillis()) + " - tap to open")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Snooze ${alarm.snoozeMinutes} min", action(context, AlarmActionReceiver.ACTION_SNOOZE, alarm.id))
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
            .putExtra(AlarmActionReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun formatTime(context: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))

}
