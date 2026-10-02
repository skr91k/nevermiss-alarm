package com.nevermiss.alarm.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent log of everything the alarm pipeline does, kept in device-protected storage so it
 * survives reboots and works before unlock. Lets us see exactly why an alarm didn't ring.
 */
object EventLog {
    private const val FILE = "events.log"
    private const val MAX_BYTES = 256 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun file(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, FILE)

    @Synchronized
    fun log(context: Context, message: String, error: Throwable? = null) {
        val line = "${fmt.format(Date())}  $message" + (error?.let { "  !! ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        Log.i("NeverMiss", line, error)
        try {
            val f = file(context)
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep)
            }
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    fun time(millis: Long): String = if (millis <= 0) "-" else fmt.format(Date(millis))

    @Synchronized
    fun read(context: Context): String = try {
        file(context).takeIf { it.exists() }?.readText().orEmpty()
    } catch (_: Exception) {
        ""
    }
}
