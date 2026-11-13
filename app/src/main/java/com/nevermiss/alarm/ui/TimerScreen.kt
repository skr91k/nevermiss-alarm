package com.nevermiss.alarm.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevermiss.alarm.core.TimerController
import com.nevermiss.alarm.data.CountdownTimer
import com.nevermiss.alarm.data.TimerStore
import com.nevermiss.alarm.service.TimerService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerTab(padding: PaddingValues, showKeypad: Boolean, onKeypadClosed: () -> Unit) {
    val context = LocalContext.current
    val version by TimerStore.version.collectAsState()
    val ringing by TimerService.ringing.collectAsState()
    val timers = remember(version) { TimerStore.all(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = timers.any { it.endAt > 0 }
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    var renaming by remember { mutableStateOf<CountdownTimer?>(null) }

    if (timers.isEmpty()) {
        // Nothing yet: the keypad *is* the screen.
        Column(Modifier.padding(padding).fillMaxSize()) {
            TimerKeypad(onStart = { TimerController.create(context, it) })
        }
    } else {
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val active = timers.count { it.endAt > 0 }
                Text(
                    "$active active timer${if (active == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            items(timers, key = { it.id }) { t ->
                TimerRow(
                    timer = t,
                    now = now,
                    isRinging = t.ringing || t.id in ringing,
                    onRename = { renaming = t },
                )
            }
        }
    }

    if (showKeypad) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onKeypadClosed, sheetState = sheet) {
            TimerKeypad(
                onStart = {
                    TimerController.create(context, it)
                    onKeypadClosed()
                },
                onClose = onKeypadClosed,
            )
        }
    }

    renaming?.let { t ->
        var text by remember(t.id) { mutableStateOf(t.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename timer") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Label") }) },
            confirmButton = {
                TextButton(onClick = { TimerController.rename(context, t.id, text); renaming = null }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TimerRow(timer: CountdownTimer, now: Long, isRinging: Boolean, onRename: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val remaining = timer.remaining(now)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isRinging -> RoundButton(Icons.Default.Close, "Stop", MaterialTheme.colorScheme.error) {
                        TimerController.stop(context, timer.id)
                    }
                    timer.isRunning -> RoundButton(AppIcons.Pause, "Pause", MaterialTheme.colorScheme.surfaceContainerHigh) {
                        TimerController.pause(context, timer.id)
                    }
                    else -> RoundButton(Icons.Default.PlayArrow, "Start", MaterialTheme.colorScheme.primary) {
                        TimerController.start(context, timer.id)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isRinging) "Time's up" else formatDuration(remaining),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRinging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        timer.title().uppercase() + if (timer.label.isBlank()) " · " + formatDuration(timer.durationMs) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    isRinging || timer.isRunning -> TextButton(onClick = { TimerController.addMinute(context, timer.id) }) {
                        Text("+1:00", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    timer.isTouched -> IconButton(onClick = { TimerController.reset(context, timer.id) }) {
                        Icon(Icons.Default.Refresh, "Reset")
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { menuOpen = false; TimerController.duplicate(context, timer) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; TimerController.delete(context, timer.id) },
                        )
                    }
                }
            }
            if (timer.endAt > 0 && !isRinging) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (remaining.toFloat() / maxOf(timer.durationMs, remaining)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(52.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (color == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Type digits like a microwave: 1, 3, 0 -> 00h 01m 30s. */
@Composable
private fun TimerKeypad(onStart: (Long) -> Unit, onClose: (() -> Unit)? = null) {
    var digits by rememberSaveable { mutableStateOf("") }
    val padded = digits.padStart(6, '0')
    val h = padded.substring(0, 2).toInt()
    val m = padded.substring(2, 4).toInt()
    val s = padded.substring(4, 6).toInt()
    val totalMs = ((h * 3600L) + (m * 60L) + s) * 1000L
    fun type(d: String) {
        digits = (digits + d).trimStart('0').take(6)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (onClose != null) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val active = MaterialTheme.colorScheme.onSurface
            val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            listOf("%02d".format(h) to "h", "%02d".format(m) to "m", "%02d".format(s) to "s").forEach { (v, unit) ->
                Text(v, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = if (digits.isEmpty()) idle else active)
                Text(unit, fontSize = 22.sp, color = idle, modifier = Modifier.padding(start = 2.dp, end = 8.dp, bottom = 10.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫"))
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { k ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).clickable {
                            if (k == "⌫") digits = digits.dropLast(1) else type(k)
                        },
                    ) {
                        if (k == "⌫") Icon(AppIcons.Backspace, "Delete digit", modifier = Modifier.size(28.dp))
                        else Text(k, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("1m" to "100", "5m" to "500", "10m" to "1000", "30m" to "3000").forEach { (title, d) ->
                FilledTonalButton(onClick = { digits = d }, modifier = Modifier.weight(1f)) { Text(title) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onStart(totalMs); digits = "" },
            enabled = totalMs > 0,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("START TIMER", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
    }
}
