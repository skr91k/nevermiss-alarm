package com.nevermiss.alarm.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.nevermiss.alarm.core.EventLog
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays the alarm with a chain of fallbacks so that a deleted ringtone, an unreadable file
 * (e.g. before first unlock after reboot) or a media-player error never means silence:
 *   chosen sound -> system default alarm -> default ringtone -> built-in generated beep.
 * Also forces the alarm volume to maximum while ringing and restores it afterwards.
 */
class AlarmSound(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(ringtoneUri: String?, vibrate: Boolean) {
        forceVolumeUp()
        val candidates = listOfNotNull(
            ringtoneUri?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            Settings.System.DEFAULT_RINGTONE_URI,
        )
        val played = candidates.firstOrNull { tryPlay(it) }
        if (played == null) playBeep()
        EventLog.log(context, "sound: ${played ?: "built-in beep"}, alarm volume ${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
        // Watchdog: if nothing is audibly playing a few seconds in, switch to the beep.
        handler.postDelayed({
            if (track == null && player?.isPlaying != true) {
                EventLog.log(context, "watchdog: player silent - switching to built-in beep")
                releasePlayer()
                playBeep()
            }
        }, 3_000)
        if (vibrate) startVibration()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        vibrator?.cancel()
        vibrator = null
        restoreVolume()
    }

    private fun tryPlay(uri: Uri): Boolean = try {
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(context, uri)
            isLooping = true
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra - falling back to beep")
                handler.post { releasePlayer(); playBeep() }
                true
            }
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot play $uri", e)
        releasePlayer()
        false
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    /** Pure generated tone: needs no file, no storage, no media provider. */
    private fun playBeep() {
        if (track != null) return
        val rate = 44_100
        val samples = ShortArray(rate) // 1 second pattern: beep-beep-pause
        for (i in samples.indices) {
            val t = i.toDouble() / rate
            val on = t < 0.2 || (t >= 0.3 && t < 0.5)
            samples[i] = if (on) (sin(2 * PI * 1000 * t) * Short.MAX_VALUE * 0.9).toInt().toShort() else 0
        }
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build().apply {
                    write(samples, 0, samples.size)
                    setLoopPoints(0, samples.size, -1)
                    play()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Beep fallback failed", e)
        }
    }

    private fun forceVolumeUp() {
        try {
            savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not raise alarm volume", e)
        }
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        } catch (_: Exception) {
        }
        savedVolume = -1
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            v.vibrate(effect, attributes)
        }
        vibrator = v
    }

    private companion object {
        const val TAG = "NeverMiss"
    }
}
