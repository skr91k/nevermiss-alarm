package com.nevermiss.alarm.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Alarms live in device-protected storage so they can be read (and ring) right after
 * a reboot, before the user unlocks the phone for the first time.
 */
object AlarmStore {
    private const val PREFS = "alarms"
    private const val KEY_ALARMS = "alarms_json"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_MATH_MIGRATED = "math_migrated_v1"

    private val _version = MutableStateFlow(0)
    /** Bumped on every write so the UI can refresh. */
    val version: StateFlow<Int> = _version

    fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Alarm> {
        val raw = prefs(context).getString(KEY_ALARMS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val alarms = (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
        if (!prefs(context).getBoolean(KEY_MATH_MIGRATED, false)) {
            prefs(context).edit().putBoolean(KEY_MATH_MIGRATED, true).commit()
            val migrated = alarms.map { it.copy(mathChallenge = true) }
            write(context, migrated)
            return migrated
        }
        return alarms
    }

    fun get(context: Context, id: Int): Alarm? = all(context).firstOrNull { it.id == id }

    /** Inserts or replaces. An alarm with id 0 gets a fresh id. */
    @Synchronized
    fun upsert(context: Context, alarm: Alarm): Alarm {
        val p = prefs(context)
        val saved = if (alarm.id == 0) {
            val next = p.getInt(KEY_NEXT_ID, 1)
            p.edit().putInt(KEY_NEXT_ID, next + 1).commit()
            alarm.copy(id = next)
        } else alarm
        write(context, all(context).filter { it.id != saved.id } + saved)
        return saved
    }

    @Synchronized
    fun update(context: Context, id: Int, transform: (Alarm) -> Alarm): Alarm? {
        val current = get(context, id) ?: return null
        val updated = transform(current)
        if (updated != current) write(context, all(context).map { if (it.id == id) updated else it })
        return updated
    }

    @Synchronized
    fun delete(context: Context, id: Int) {
        write(context, all(context).filter { it.id != id })
    }

    private fun write(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray()
        alarms.forEach { arr.put(it.toJson()) }
        // commit() (synchronous) on purpose: the process may be killed right after an alarm fires.
        prefs(context).edit().putString(KEY_ALARMS, arr.toString()).commit()
        _version.value++
    }
}
