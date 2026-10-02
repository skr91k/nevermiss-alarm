package com.nevermiss.alarm.core

import android.content.Context
import android.os.PowerManager

/** One shared partial wake lock, held from the alarm broadcast until ringing stops. */
object WakeLocks {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context, timeoutMs: Long) {
        val l = lock ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeverMiss:alarm")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        l.acquire(timeoutMs)
    }

    @Synchronized
    fun release() {
        lock?.let { if (it.isHeld) it.release() }
    }
}
