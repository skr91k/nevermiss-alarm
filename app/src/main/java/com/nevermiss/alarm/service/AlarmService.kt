package com.nevermiss.alarm.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nevermiss.alarm.core.AlarmController
import com.nevermiss.alarm.core.AlarmScheduler
import com.nevermiss.alarm.core.EventLog
import com.nevermiss.alarm.core.Notifications
import com.nevermiss.alarm.core.WakeLocks
import com.nevermiss.alarm.data.Alarm
import com.nevermiss.alarm.data.AlarmStore
import com.nevermiss.alarm.ui.AlarmActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that owns the ringing alarm. Sound is independent of the UI:
 * closing or swiping away the ringing screen never silences the alarm.
 */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var sound: AlarmSound? = null
    private var currentId = -1
    private var currentAlarm: Alarm? = null
    private val timeout = Runnable { AlarmController.onRingTimeout(this, currentId) }
    private val autoStop = Runnable {
        EventLog.log(this, "auto-stop alarm $currentId")
        AlarmController.dismissFully(this, currentId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        val trigger = intent?.getLongExtra(EXTRA_TRIGGER, 0L) ?: 0L
        val redelivered = flags and START_FLAG_REDELIVERY != 0

        // Decide first so the very first notification already carries the full-screen intent.
        val fired = if (id == currentId && _ringing.value != null) null
        else AlarmController.onFire(this, id, trigger, redelivered)
        val alarm = fired?.first

        EventLog.log(this, "service start id=$id redelivered=$redelivered -> ${if (alarm != null) "RING" else "ignored (already handled / disabled / missing)"}")
        val notification = (alarm ?: currentAlarm?.takeIf { _ringing.value != null })
            ?.let { Notifications.buildRinging(this, it) }
            ?: Notifications.buildPlaceholder(this)
        try {
            ServiceCompat.startForeground(
                this, Notifications.RINGING_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            EventLog.log(this, "startForeground FAILED - fallback notification", e)
            alarm?.let { Notifications.postFallback(this, it) }
            stopSelf()
            return START_NOT_STICKY
        }

        if (alarm == null) {
            if (_ringing.value == null) stopSelf()
            return START_NOT_STICKY
        }

        // A second alarm while one is ringing: snooze the old one so it isn't lost.
        val previous = _ringing.value
        if (previous != null && previous.id != alarm.id) {
            AlarmStore.update(this, previous.id) {
                it.copy(enabled = true, snoozedUntil = System.currentTimeMillis() + it.snoozeMinutes * 60_000L)
            }?.let { AlarmScheduler.schedule(this, it) }
        }
        ring(alarm, wakeCheck = fired.second)
        // If the process gets killed while ringing, Android restarts us with the same intent.
        return START_REDELIVER_INTENT
    }

    private fun ring(alarm: Alarm, wakeCheck: Boolean) {
        currentId = alarm.id
        currentAlarm = alarm
        val ringTimeoutMs = alarm.autoSnoozeMinutes * 60_000L
        WakeLocks.acquire(this, ringTimeoutMs + 60_000)
        sound?.stop()
        sound = AlarmSound(this).also { it.start(alarm.ringtoneUri, alarm.vibrate) }
        EventLog.log(this, "ringing alarm ${alarm.id}${if (wakeCheck) " (wake-up check)" else ""}")
        _ringing.value = Ringing(alarm.id, alarm.label, alarm.snoozeMinutes, alarm.mathChallenge, wakeCheck, alarm.autoStopSeconds)
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.RINGING_ID, Notifications.buildRinging(this, alarm))
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(autoStop)
        if (alarm.autoStopSeconds > 0) {
            handler.postDelayed(autoStop, alarm.autoStopSeconds * 1000L)
        } else {
            handler.postDelayed(timeout, ringTimeoutMs)
        }
        // Works when allowed (app visible / screen on); otherwise the full-screen intent opens it.
        try {
            startActivity(AlarmActivity.intent(this))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        EventLog.log(this, "service stopped (alarm $currentId)")
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(autoStop)
        sound?.stop()
        sound = null
        _ringing.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        WakeLocks.release()
        super.onDestroy()
    }

    data class Ringing(
        val id: Int,
        val label: String,
        val snoozeMinutes: Int,
        val mathChallenge: Boolean,
        val wakeCheck: Boolean,
        val autoStopSeconds: Int,
    )

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TRIGGER = "trigger"

        private val _ringing = MutableStateFlow<Ringing?>(null)
        val ringing: StateFlow<Ringing?> = _ringing

        fun start(context: Context, id: Int, trigger: Long) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TRIGGER, trigger)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Should not happen for setAlarmClock() broadcasts, but never fail silently.
                EventLog.log(context, "startForegroundService FAILED - fallback notification", e)
                AlarmController.onFire(context, id, trigger, redelivered = false)
                    ?.let { Notifications.postFallback(context, it.first) }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }
}
