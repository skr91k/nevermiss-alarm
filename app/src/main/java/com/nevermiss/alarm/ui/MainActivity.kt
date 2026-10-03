package com.nevermiss.alarm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nevermiss.alarm.BuildConfig
import com.nevermiss.alarm.core.AlarmController
import com.nevermiss.alarm.core.AlarmScheduler
import com.nevermiss.alarm.core.Reliability
import com.nevermiss.alarm.data.Alarm
import com.nevermiss.alarm.data.AlarmStore
import com.nevermiss.alarm.service.AlarmService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { NeverMissTheme { MainScreen(resumeTick) } }
    }

    override fun onResume() {
        super.onResume()
        // Cheap insurance: every time the app is opened, re-arm all alarms.
        AlarmScheduler.rescheduleAll(this, "app opened")
        resumeTick++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(resumeTick: Int) {
    val context = LocalContext.current
    val version by AlarmStore.version.collectAsState()
    var localTick by remember { mutableIntStateOf(0) }
    val alarms = remember(version, resumeTick) {
        AlarmStore.all(context).filter { it.id != Alarm.TEST_ID }.sortedBy { it.hour * 60 + it.minute }
    }
    val checks = remember(version, resumeTick, localTick) { Reliability.checks(context) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    val ringing by AlarmService.ringing.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    fun toastScheduled(alarm: Alarm) {
        if (alarm.enabled) {
            Toast.makeText(context, "Alarm set ${ringsIn(alarm.nextTrigger())}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NeverMiss", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Alarm() }) { Icon(Icons.Default.Add, "Add alarm") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NextAlarmCard(alarms, now) }
            item {
                ReliabilityCard(
                    checks,
                    onFix = { check ->
                        val intent = check.fixIntent ?: return@ReliabilityCard
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                    onManualDone = { key ->
                        Reliability.markManualDone(context, key)
                        localTick++
                    },
                )
            }
            if (alarms.isEmpty()) {
                item {
                    Text(
                        "No alarms yet - tap + to add one.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(alarms, key = { it.id }) { alarm ->
                // An alarm that is ringing, snoozed or waiting for its wake-up check can only be
                // ended from the ringing screen - otherwise switching it off would skip the puzzle.
                val isRinging = ringing?.id == alarm.id
                val locked = isRinging || (alarm.enabled && alarm.snoozedUntil > System.currentTimeMillis())
                AlarmRow(
                    alarm,
                    locked = locked,
                    isRinging = isRinging,
                    onToggle = { enabled -> toastScheduled(AlarmController.save(context, alarm.copy(enabled = enabled))) },
                    onAction = { action ->
                        when (action) {
                            RowAction.EDIT -> editing = alarm
                            RowAction.DUPLICATE -> editing = alarm.duplicate()
                            RowAction.SKIP -> {
                                val saved = AlarmController.skipNext(context, alarm)
                                Toast.makeText(
                                    context,
                                    "Skipping ${formatMillis(context, saved.skippedTrigger)} - next ${formatMillis(context, saved.nextTrigger())}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            RowAction.UNDO_SKIP -> toastScheduled(AlarmController.undoSkip(context, alarm))
                            RowAction.DISMISS -> context.startActivity(
                                if (isRinging) AlarmActivity.intent(context) else AlarmActivity.dismissIntent(context, alarm.id),
                            )
                        }
                    },
                    onClick = {
                        when {
                            isRinging -> context.startActivity(AlarmActivity.intent(context))
                            locked -> Toast.makeText(
                                context,
                                "This alarm will ring again soon - you can change it after dismissing it.",
                                Toast.LENGTH_LONG,
                            ).show()
                            else -> editing = alarm
                        }
                    },
                )
            }
            if (BuildConfig.DEBUG) item {
                OutlinedButton(
                    onClick = {
                        AlarmController.scheduleTest(context)
                        Toast.makeText(context, "Test alarm in 10 seconds - lock your phone now", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test alarm (rings in 10 s)") }
            }
        }
    }

    editing?.let { alarm ->
        AlarmEditor(
            initial = alarm,
            onDismiss = { editing = null },
            onSave = {
                toastScheduled(AlarmController.save(context, it))
                editing = null
            },
            onDelete = if (alarm.id != 0) ({
                AlarmController.delete(context, alarm.id)
                editing = null
            }) else null,
        )
    }
}

@Composable
private fun NextAlarmCard(alarms: List<Alarm>, now: Long) {
    val context = LocalContext.current
    val next = alarms.filter { it.enabled }.minOfOrNull { it.nextTrigger(now) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Next alarm", color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (next == null) {
                Text("None set", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(formatMillis(context, next), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(ringsIn(next, now), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ReliabilityCard(
    checks: List<Reliability.Check>,
    onFix: (Reliability.Check) -> Unit,
    onManualDone: (String) -> Unit,
) {
    val problems = checks.filter { !it.ok }
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                val ok = problems.isEmpty()
                Icon(
                    if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (ok) Color(0xFF4CD964) else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ok) "All protections active" else "${problems.size} thing(s) could stop your alarm",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (expanded) "Tap to hide details" else "Tap for details",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val shown = if (expanded) checks else problems
            shown.forEach { check ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (check.ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (check.ok) Color(0xFF4CD964) else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(check.title, fontWeight = FontWeight.Medium)
                        Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!check.ok || (check.manualKey != null && expanded)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (check.manualKey != null && !check.ok) {
                            TextButton(onClick = { onManualDone(check.manualKey) }) { Text("I've done this") }
                        }
                        if (check.fixIntent != null) {
                            TextButton(onClick = { onFix(check) }) { Text(check.fixLabel ?: "Fix") }
                        }
                    }
                }
            }
        }
    }
}

private enum class RowAction { EDIT, DUPLICATE, SKIP, UNDO_SKIP, DISMISS }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmRow(
    alarm: Alarm,
    locked: Boolean,
    isRinging: Boolean,
    onToggle: (Boolean) -> Unit,
    onAction: (RowAction) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatHm(context, alarm.hour, alarm.minute),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                val subtitle = listOf(
                    alarm.label,
                    daysLabel(alarm),
                    if (alarm.autoStopSeconds > 0) "stops after ${alarm.autoStopSeconds} s" else "",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    isRinging -> Text("Ringing - tap to open", color = MaterialTheme.colorScheme.primary)
                    locked && alarm.wakeCheckPending -> Text(
                        "Wake-up check at ${formatMillis(context, alarm.snoozedUntil)}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    locked -> Text("Snoozed until ${formatMillis(context, alarm.snoozedUntil)}", color = MaterialTheme.colorScheme.primary)
                    alarm.enabled && alarm.isSkipping() -> Text(
                        "Skipping ${formatMillis(context, alarm.skippedTrigger)}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (locked) {
                Icon(Icons.Default.Lock, contentDescription = "Locked until dismissed", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle, enabled = !locked)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    fun item(text: String, action: RowAction) = @Composable {
                        DropdownMenuItem(text = { Text(text) }, onClick = { menuOpen = false; onAction(action) })
                    }
                    when {
                        // Ringing / snoozed / wake check: only its own dismiss rule can end it.
                        locked -> item(if (isRinging) "Open ringing alarm" else "Dismiss…", RowAction.DISMISS)()
                        else -> {
                            item("Edit", RowAction.EDIT)()
                            if (alarm.enabled && alarm.isRepeating) {
                                if (alarm.isSkipping()) item("Undo skip", RowAction.UNDO_SKIP)()
                                else item("Skip next (${formatMillis(context, alarm.regularTrigger())})", RowAction.SKIP)()
                            }
                        }
                    }
                    item("Duplicate", RowAction.DUPLICATE)()
                }
            }
        }
    }
}
