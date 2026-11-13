package com.nevermiss.alarm.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stopwatch state lives in prefs, measured on the elapsed-realtime clock, so it keeps
 * counting while the app is closed (it's just a start time - nothing runs in the background).
 */
private data class Stopwatch(
    /** elapsedRealtime when started; 0 when paused. */
    val startedAt: Long = 0L,
    /** Time counted before the current run. */
    val accumulated: Long = 0L,
    /** Total time at each lap, oldest first. */
    val laps: List<Long> = emptyList(),
) {
    val running get() = startedAt > 0
    fun elapsed(now: Long = SystemClock.elapsedRealtime()) =
        accumulated + if (running) (now - startedAt).coerceAtLeast(0) else 0

    companion object {
        private const val PREFS = "stopwatch"

        fun load(context: Context): Stopwatch {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var startedAt = p.getLong("startedAt", 0)
            var accumulated = p.getLong("accumulated", 0)
            // Phone rebooted while running: the old start time is meaningless now; keep what we had.
            if (startedAt > SystemClock.elapsedRealtime()) {
                startedAt = 0
                accumulated = p.getLong("accumulated", 0)
            }
            val laps = JSONArray(p.getString("laps", "[]")).let { a -> (0 until a.length()).map { a.getLong(it) } }
            return Stopwatch(startedAt, accumulated, laps)
        }
    }

    fun save(context: Context): Stopwatch {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("startedAt", startedAt)
            .putLong("accumulated", accumulated)
            .putString("laps", JSONArray(laps).toString())
            .apply()
        return this
    }
}

@Composable
fun StopwatchTab(padding: PaddingValues) {
    val context = LocalContext.current
    var sw by remember { mutableStateOf(Stopwatch.load(context)) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sw.running) {
        now = SystemClock.elapsedRealtime()
        while (sw.running) {
            delay(16)
            now = SystemClock.elapsedRealtime()
        }
    }
    val elapsed = sw.elapsed(now)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val stroke = 5.dp.toPx()
                val radius = (size.minDimension - stroke) / 2
                val topLeft = Offset(center.x - radius, center.y - radius)
                drawCircle(track, radius = radius, style = Stroke(stroke))
                if (elapsed > 0) {
                    // One lap of the ring = one minute.
                    val sweep = (elapsed % 60_000) / 60_000f * 360f
                    drawArc(primary, -90f, sweep, false, topLeft, Size(radius * 2, radius * 2), style = Stroke(stroke))
                    val angle = Math.toRadians((sweep - 90f).toDouble())
                    drawCircle(
                        primary,
                        radius = stroke * 1.8f,
                        center = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat()),
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(mainPart(elapsed), fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Text(
                    "%02d".format((elapsed / 10) % 100),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val laps = sw.laps
            // Newest lap first; the lap currently running isn't listed until "Lap" is tapped.
            itemsIndexed(laps.reversed()) { i, total ->
                val n = laps.size - i
                val lap = total - (laps.getOrNull(n - 2) ?: 0L)
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text("#%02d".format(n), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp, modifier = Modifier.width(64.dp))
                    Text(lapFormat(lap), fontSize = 18.sp, modifier = Modifier.width(110.dp))
                    Text(lapFormat(total), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.width(100.dp)) {
                if (elapsed > 0) TextButton(onClick = { sw = Stopwatch().save(context) }) {
                    Text("RESET", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(80.dp).clip(CircleShape).background(primary).clickable {
                    val t = SystemClock.elapsedRealtime()
                    sw = if (sw.running) sw.copy(startedAt = 0, accumulated = sw.elapsed(t)).save(context)
                    else sw.copy(startedAt = t).save(context)
                },
            ) {
                Icon(
                    if (sw.running) AppIcons.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (sw.running) "Pause" else "Start",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
            Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) {
                when {
                    sw.running -> TextButton(onClick = {
                        sw = sw.copy(laps = sw.laps + sw.elapsed()).save(context)
                    }) { Text("LAP", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    elapsed > 0 -> TextButton(onClick = { share(context, sw) }) {
                        Text("SHARE", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 8 / 1:05 / 1:02:03 - the part before the hundredths. */
private fun mainPart(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        s >= 60 -> "%d:%02d".format(s / 60, s % 60)
        else -> s.toString()
    }
}

/** 0:05.94 */
private fun lapFormat(ms: Long): String {
    val s = ms / 1000
    val base = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    return base + ".%02d".format((ms / 10) % 100)
}

private fun share(context: Context, sw: Stopwatch) {
    val text = buildString {
        append("Stopwatch: ${lapFormat(sw.elapsed())}")
        sw.laps.forEachIndexed { i, total ->
            val lap = total - (sw.laps.getOrNull(i - 1) ?: 0L)
            append("\n#%02d  %s  %s".format(i + 1, lapFormat(lap), lapFormat(total)))
        }
    }
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"),
    )
}
