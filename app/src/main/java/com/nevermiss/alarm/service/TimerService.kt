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
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nevermiss.alarm.core.EventLog
import com.nevermiss.alarm.core.Notifications
import com.nevermiss.alarm.core.TimerController
import com.nevermiss.alarm.data.TimerStore
import com.nevermiss.alarm.ui.TimerActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Rings finished timers. One service and one sound for all of them; it stops when the last one is stopped. */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var sound: AlarmSound? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** Nobody stopped it: give up after a while and leave a notification instead. */
    private val giveUp = Runnable {
        val ids = _ringing.value
        EventLog.log(this, "timers $ids rang ${RING_LIMIT_MS / 60_000} min unanswered - stopping")
        ids.forEach { id ->
            TimerStore.get(this, id)?.let { Notifications.showTimerMissed(this, it) }
            TimerController.reset(this, id)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarted after the process was killed: pick up whatever is still marked as ringing.
        val ids = intent?.getIntExtra(EXTRA_ID, -1)?.takeIf { it > 0 }?.let { setOf(it) }
            ?: TimerStore.all(this).filter { it.ringing }.map { it.id }.toSet()
        val valid = ids.filter { TimerStore.get(this, it)?.ringing == true }
        _ringing.value = _ringing.value + valid
        try {
            ServiceCompat.startForeground(
                this, Notifications.TIMER_RINGING_ID, Notifications.buildTimerRinging(this, _ringing.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            EventLog.log(this, "timer startForeground FAILED - fallback notification", e)
            valid.forEach { id -> TimerStore.get(this, id)?.let { Notifications.postTimerFallback(this, it) } }
            stopSelf()
            return START_NOT_STICKY
        }
        if (_ringing.value.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (sound == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeverMiss:timer")
                .apply { acquire(RING_LIMIT_MS + 60_000) }
            sound = AlarmSound(this).also { it.start(null, vibrate = true) }
            handler.postDelayed(giveUp, RING_LIMIT_MS)
        }
        try {
            startActivity(TimerActivity.intent(this))
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    private fun removeId(id: Int) {
        _ringing.value = _ringing.value - id
        if (_ringing.value.isEmpty()) {
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.TIMER_RINGING_ID, Notifications.buildTimerRinging(this, _ringing.value))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        sound?.stop()
        sound = null
        wakeLock?.let { if (it.isHeld) it.release() }
        _ringing.value = emptySet()
        instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val RING_LIMIT_MS = 5 * 60_000L

        private var instance: TimerService? = null
        private val _ringing = MutableStateFlow<Set<Int>>(emptySet())
        /** Ids of the timers ringing right now. */
        val ringing: StateFlow<Set<Int>> = _ringing

        fun start(context: Context, id: Int) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java).putExtra(EXTRA_ID, id))
            } catch (e: Exception) {
                EventLog.log(context, "timer startForegroundService FAILED - fallback notification", e)
                TimerStore.get(context, id)?.let { Notifications.postTimerFallback(context, it) }
            }
        }

        /** This timer no longer rings (stopped, +1 min, deleted). */
        fun remove(context: Context, id: Int) {
            instance?.removeId(id)
            Notifications.cancelTimerFallback(context, id)
        }
    }
}
