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
    private val timeout = Runnable {
        if (_ringing.value?.preview == true) stopSelf() else AlarmController.onRingTimeout(this, currentId)
    }
    private val autoStop = Runnable {
        EventLog.log(this, "auto-stop alarm $currentId")
        if (_ringing.value?.preview == true) stopSelf() else AlarmController.dismissFully(this, currentId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        val trigger = intent?.getLongExtra(EXTRA_TRIGGER, 0L) ?: 0L
        val redelivered = flags and START_FLAG_REDELIVERY != 0
        if (intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true) return startPreview(id)
        val previewing = _ringing.value?.preview == true

        // Decide first so the very first notification already carries the full-screen intent.
        val fired = if (id == currentId && _ringing.value != null && !previewing) null
        else AlarmController.onFire(this, id, trigger, redelivered)
        val alarm = fired?.first

        EventLog.log(this, "service start id=$id redelivered=$redelivered -> ${if (alarm != null) "RING" else "ignored (already handled / disabled / missing)"}")
        val notification = (alarm ?: currentAlarm?.takeIf { _ringing.value != null })
            ?.let { Notifications.buildRinging(this, it, preview = alarm == null && previewing) }
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
        // A real alarm simply replaces a preview.
        if (previous != null && previous.id != alarm.id && !previous.preview) {
            AlarmStore.update(this, previous.id) {
                it.copy(enabled = true, snoozedUntil = System.currentTimeMillis() + it.snoozeMinutes * 60_000L)
            }?.let { AlarmScheduler.schedule(this, it) }
        }
        ring(alarm, wakeCheck = fired.second)
        // If the process gets killed while ringing, Android restarts us with the same intent.
        return START_REDELIVER_INTENT
    }

    /** Rings [id] now with all its settings, without touching its schedule or state. */
    private fun startPreview(id: Int): Int {
        val alarm = AlarmStore.get(this, id)
        val busy = _ringing.value?.let { !it.preview } == true
        if (alarm == null || busy) {
            if (_ringing.value == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.RINGING_ID, Notifications.buildRinging(this, alarm, preview = true),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            EventLog.log(this, "preview startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        EventLog.log(this, "preview alarm $id")
        ring(alarm, wakeCheck = false, preview = true)
        // A killed preview should not come back.
        return START_NOT_STICKY
    }

    private fun ring(alarm: Alarm, wakeCheck: Boolean, preview: Boolean = false) {
        currentId = alarm.id
        currentAlarm = alarm
        val ringTimeoutMs = alarm.autoSnoozeSeconds * 1_000L
        WakeLocks.acquire(this, ringTimeoutMs + 60_000)
        sound?.stop()
        sound = AlarmSound(this).also { it.start(alarm.ringtoneUri, alarm.vibrate) }
        EventLog.log(this, "ringing alarm ${alarm.id}${if (wakeCheck) " (wake-up check)" else ""}")
        _ringing.value = Ringing(
            alarm.id, alarm.label, alarm.snoozeMinutes, alarm.mathChallenge, wakeCheck, alarm.autoStopSeconds,
            preview = preview, ensureAwake = alarm.ensureAwake,
        )
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.RINGING_ID, Notifications.buildRinging(this, alarm, preview))
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
        /** Started from the menu's "Preview": snooze / dismiss / Back only end the preview. */
        val preview: Boolean = false,
        val ensureAwake: Boolean = false,
    )

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TRIGGER = "trigger"
        private const val EXTRA_PREVIEW = "preview"

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

        /** Rings this alarm now so you can hear and try it; its real schedule is untouched. */
        fun preview(context: Context, id: Int): Boolean {
            if (_ringing.value?.preview == false) return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_PREVIEW, true),
            )
            context.startActivity(AlarmActivity.intent(context))
            return true
        }

        /** Ends a preview; does nothing to a real ringing alarm. */
        fun stopPreview(context: Context) {
            if (_ringing.value?.preview == true) stop(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }
}
