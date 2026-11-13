package com.nevermiss.alarm.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A countdown timer. Times are wall-clock millis so a running timer survives the app being
 * closed, the process being killed and (via re-arming on boot) a reboot.
 * @param endAt > 0 while running (or ringing): when it reaches zero.
 * @param remainingMs time left while paused / stopped.
 */
data class CountdownTimer(
    val id: Int = 0,
    val label: String = "",
    val durationMs: Long,
    val endAt: Long = 0L,
    val remainingMs: Long = durationMs,
    val ringing: Boolean = false,
) {
    val isRunning: Boolean get() = endAt > 0 && !ringing

    fun remaining(now: Long = System.currentTimeMillis()): Long =
        if (endAt > 0) (endAt - now).coerceAtLeast(0) else remainingMs

    /** Paused part-way, or added time: the reset button makes sense. */
    val isTouched: Boolean get() = endAt == 0L && remainingMs != durationMs

    fun title(): String = label.ifBlank { "Timer" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("durationMs", durationMs)
        put("endAt", endAt)
        put("remainingMs", remainingMs)
        put("ringing", ringing)
    }

    companion object {
        fun fromJson(o: JSONObject) = CountdownTimer(
            id = o.getInt("id"),
            label = o.optString("label"),
            durationMs = o.getLong("durationMs"),
            endAt = o.optLong("endAt"),
            remainingMs = o.optLong("remainingMs", o.getLong("durationMs")),
            ringing = o.optBoolean("ringing"),
        )
    }
}

/** Same storage approach as [AlarmStore]: device-protected, so timers ring before first unlock. */
object TimerStore {
    private const val PREFS = "timers"
    private const val KEY_TIMERS = "timers_json"
    private const val KEY_NEXT_ID = "next_id"

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<CountdownTimer> {
        val raw = prefs(context).getString(KEY_TIMERS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { CountdownTimer.fromJson(arr.getJSONObject(it)) }
    }

    fun get(context: Context, id: Int): CountdownTimer? = all(context).firstOrNull { it.id == id }

    /** Adds a new timer (id 0) at the end of the list, or replaces one in place. */
    @Synchronized
    fun upsert(context: Context, timer: CountdownTimer): CountdownTimer {
        val p = prefs(context)
        if (timer.id == 0) {
            val next = p.getInt(KEY_NEXT_ID, 1)
            p.edit().putInt(KEY_NEXT_ID, next + 1).commit()
            val saved = timer.copy(id = next)
            write(context, all(context) + saved)
            return saved
        }
        val list = all(context)
        write(context, if (list.any { it.id == timer.id }) list.map { if (it.id == timer.id) timer else it } else list + timer)
        return timer
    }

    @Synchronized
    fun update(context: Context, id: Int, transform: (CountdownTimer) -> CountdownTimer): CountdownTimer? {
        val current = get(context, id) ?: return null
        val updated = transform(current)
        if (updated != current) write(context, all(context).map { if (it.id == id) updated else it })
        return updated
    }

    @Synchronized
    fun delete(context: Context, id: Int) {
        write(context, all(context).filter { it.id != id })
    }

    private fun write(context: Context, timers: List<CountdownTimer>) {
        val arr = JSONArray()
        timers.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_TIMERS, arr.toString()).commit()
        _version.value++
    }
}
