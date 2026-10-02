package com.nevermiss.alarm.core

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.nevermiss.alarm.data.AlarmStore

/** Everything on the phone that can stop an alarm from ringing, and how to fix it. */
object Reliability {

    data class Check(
        val title: String,
        val detail: String,
        val ok: Boolean,
        val fixLabel: String? = null,
        val fixIntent: Intent? = null,
        /** Manual checks can't be detected; the user confirms them. */
        val manualKey: String? = null,
    )

    private const val KEY_OEM_DONE = "oem_autostart_done"

    private val aggressiveOems = setOf(
        "xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus",
        "vivo", "iqoo", "samsung", "asus", "meizu", "tecno", "infinix", "itel", "lenovo", "motorola", "nothing",
    )

    @SuppressLint("BatteryLife")
    fun checks(context: Context): List<Check> {
        val pkg = context.packageName
        val pkgUri = Uri.parse("package:$pkg")
        val list = mutableListOf<Check>()

        list += Check(
            "Exact alarms allowed",
            "Lets the alarm fire at the exact minute.",
            AlarmScheduler.canScheduleExact(context),
            "Allow",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri) else null,
        )

        val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        list += Check(
            "Notifications allowed",
            "Needed for the ringing screen and snooze buttons.",
            notificationsOn,
            "Allow",
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        )

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(Notifications.CH_RINGING)
        list += Check(
            "Alarm notifications set to urgent",
            "The 'Ringing alarm' category must not be silenced.",
            channel != null && channel.importance >= NotificationManager.IMPORTANCE_HIGH,
            "Fix",
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CH_RINGING),
        )

        if (Build.VERSION.SDK_INT >= 34) {
            list += Check(
                "Full-screen alarm allowed",
                "Shows the alarm over the lock screen.",
                nm.canUseFullScreenIntent(),
                "Allow",
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri),
            )
        }

        val pm = context.getSystemService(PowerManager::class.java)
        list += Check(
            "Battery optimization off",
            "Stops Android from putting the app to sleep.",
            pm.isIgnoringBatteryOptimizations(pkg),
            "Turn off",
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
        )

        val audio = context.getSystemService(AudioManager::class.java)
        val vol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        list += Check(
            "Alarm volume",
            if (vol == 0) "Alarm volume is 0 - NeverMiss will force it to max while ringing anyway."
            else "NeverMiss raises it to max while ringing.",
            true,
        )

        val maker = Build.MANUFACTURER.lowercase()
        if (maker in aggressiveOems) {
            val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val steps = if (maker == "samsung") {
                "Samsung puts apps to sleep. 1) App info > Battery > Unrestricted. " +
                    "2) Settings > Battery > Background usage limits > Never sleeping apps > add NeverMiss, " +
                    "and turn off 'Put unused apps to sleep'."
            } else {
                "$brand phones kill apps in the background. In App info, allow 'Autostart' / " +
                    "'Run in background' and set Battery to 'Unrestricted' / 'No restrictions'. " +
                    "Also lock the app in the recent-apps screen. See dontkillmyapp.com for your model."
            }
            list += Check(
                "$brand background restrictions",
                steps,
                AlarmStore.prefs(context).getBoolean(KEY_OEM_DONE, false),
                "Open app info",
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
                manualKey = KEY_OEM_DONE,
            )
        }
        return list
    }

    fun markManualDone(context: Context, key: String) {
        AlarmStore.prefs(context).edit().putBoolean(key, true).apply()
    }
}
