package com.nevermiss.alarm.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import com.nevermiss.alarm.data.Alarm
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditor(
    initial: Alarm,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
) {
    val context = LocalContext.current
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var days by remember { mutableIntStateOf(initial.days) }
    var mode by remember { mutableStateOf(RepeatMode.of(initial)) }
    var date by remember { mutableStateOf(initial.exactDate ?: LocalDate.now().plusDays(1)) }
    var monthDay by remember { mutableIntStateOf(if (initial.monthDay != 0) initial.monthDay else LocalDate.now().dayOfMonth) }
    var showDatePicker by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(initial.label) }
    var vibrate by remember { mutableStateOf(initial.vibrate) }
    var math by remember { mutableStateOf(initial.mathChallenge) }
    var ensureAwake by remember { mutableStateOf(initial.ensureAwake) }
    var snooze by remember { mutableIntStateOf(initial.snoozeMinutes) }
    var ringtone by remember { mutableStateOf(initial.ringtoneUri) }
    var autoStop by remember { mutableIntStateOf(initial.autoStopSeconds) }
    var autoSnooze by remember { mutableIntStateOf(initial.autoSnoozeMinutes) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            ringtone = uri?.toString()
        }
    }
    val ringtoneTitle = remember(ringtone) {
        ringtone?.let {
            try {
                RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context)
            } catch (_: Exception) {
                null
            }
        } ?: "Default alarm sound"
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WheelTimePicker(
                    hour = hour,
                    minute = minute,
                    is24Hour = DateFormat.is24HourFormat(context),
                    onChange = { h, m -> hour = h; minute = m },
                )
                val preview = buildRepeat(Alarm(hour = hour, minute = minute), mode, days, date, monthDay)
                val trigger = preview.nextTrigger()
                val inPast = trigger <= System.currentTimeMillis()
                Text(
                    if (inPast) "That date and time has already passed" else "Rings " + ringsIn(trigger),
                    color = if (inPast) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )

                SectionTitle("Repeat")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RepeatMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.title) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                when (mode) {
                    RepeatMode.ONCE, RepeatMode.DAILY -> {}
                    RepeatMode.WEEKLY -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        DAY_LETTERS.forEachIndexed { i, letter ->
                            DayCircle(letter, days and (1 shl i) != 0) { days = days xor (1 shl i) }
                        }
                    }
                    RepeatMode.DATE -> TextButton(onClick = { showDatePicker = true }) {
                        Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")), fontSize = 18.sp)
                    }
                    RepeatMode.MONTHLY -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..31).chunked(7).forEach { week ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                week.forEach { d -> DayCircle(d.toString(), d == monthDay) { monthDay = d } }
                            }
                        }
                    }
                }
                Text(
                    daysLabel(preview) + if (mode == RepeatMode.DATE) " - switches off after it rings" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )

                SettingRow("Sound", ringtoneTitle) {
                    TextButton(onClick = {
                        picker.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                                .putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    ringtone?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                                ),
                        )
                    }) { Text("Change") }
                }
                SettingRow("Vibrate", null) { Switch(vibrate, { vibrate = it }) }
                SectionTitle("Auto-stop")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 5, 15, 30, 60).forEach { s ->
                        FilterChip(
                            selected = autoStop == s,
                            onClick = { autoStop = s },
                            label = { Text(if (s == 0) "Off" else "$s s") },
                        )
                    }
                }
                Text(
                    if (autoStop == 0) "Rings until you dismiss it (wake-up mode)"
                    else "Reminder: rings $autoStop s, then stops by itself",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp),
                )
                if (autoStop == 0) {
                    SettingRow("Solve math to dismiss", "Keeps you from turning it off half asleep") {
                        Switch(math, { math = it })
                    }
                    SettingRow("Ensure I'm awake", "Rings again 3 min after dismiss until you tap \"I'm awake\"") {
                        Switch(ensureAwake, { ensureAwake = it })
                    }
                }

                SectionTitle("Snooze length")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 15).forEach { m ->
                        FilterChip(selected = snooze == m, onClick = { snooze = m }, label = { Text("$m min") })
                    }
                }
                if (autoStop == 0) {
                    SectionTitle("Auto-snooze after")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 5, 10).forEach { m ->
                            FilterChip(selected = autoSnooze == m, onClick = { autoSnooze = m }, label = { Text("$m min") })
                        }
                    }
                    Text(
                        "If nobody touches it, it snoozes after ringing $autoSnooze min, then rings again",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = !inPast, onClick = {
                        onSave(
                            buildRepeat(initial, mode, days, date, monthDay).copy(
                                hour = hour,
                                minute = minute,
                                label = label.trim(),
                                vibrate = vibrate,
                                mathChallenge = math,
                                ensureAwake = ensureAwake,
                                snoozeMinutes = snooze,
                                ringtoneUri = ringtone,
                                autoStopSeconds = autoStop,
                                autoSnoozeMinutes = autoSnooze,
                                enabled = true,
                            ),
                        )
                    }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

private const val EVERY_DAY = 0b1111111

enum class RepeatMode(val title: String) {
    ONCE("Once"), DAILY("Daily"), WEEKLY("Weekly"), DATE("Date"), MONTHLY("Monthly");

    companion object {
        fun of(a: Alarm) = when {
            a.date != null -> DATE
            a.monthDay != 0 -> MONTHLY
            a.days == EVERY_DAY -> DAILY
            a.days != 0 -> WEEKLY
            else -> ONCE
        }
    }
}

/** Sets exactly one repeat kind on [base] and clears the others. */
private fun buildRepeat(base: Alarm, mode: RepeatMode, days: Int, date: LocalDate, monthDay: Int): Alarm = when (mode) {
    RepeatMode.ONCE -> base.copy(days = 0, date = null, monthDay = 0)
    RepeatMode.DAILY -> base.copy(days = EVERY_DAY, date = null, monthDay = 0)
    RepeatMode.WEEKLY -> base.copy(days = days, date = null, monthDay = 0)
    RepeatMode.DATE -> base.copy(days = 0, date = date.toString(), monthDay = 0)
    RepeatMode.MONTHLY -> base.copy(days = 0, date = null, monthDay = monthDay)
}

@Composable
private fun DayCircle(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
