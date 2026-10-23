package com.nevermiss.alarm.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevermiss.alarm.core.AlarmController
import com.nevermiss.alarm.data.AlarmStore
import com.nevermiss.alarm.service.AlarmService
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** Full-screen ringing UI. Shown over the lock screen; closing it does NOT stop the sound. */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back button must not hide the alarm by accident.
        onBackPressedDispatcher.addCallback(this) {
            // Nothing is ringing in dismiss mode, so Back may close it.
            if (dismissId != null && AlarmService.ringing.value == null) finish()
        }
        dismissId = intent.getIntExtra(EXTRA_DISMISS_ID, -1).takeIf { it > 0 }
        setContent { NeverMissTheme { RingingScreen(dismissId, onFinished = { finish() }) } }
    }

    /** Set when opened from the list's "Dismiss" menu for an alarm that isn't ringing right now. */
    private var dismissId by mutableStateOf<Int?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dismissId = intent.getIntExtra(EXTRA_DISMISS_ID, -1).takeIf { it > 0 }
    }

    companion object {
        private const val EXTRA_DISMISS_ID = "dismiss_id"

        /** Opens the dismiss screen (with the alarm's own rule) for a snoozed alarm. */
        fun dismissIntent(context: Context, id: Int): Intent =
            Intent(context, AlarmActivity::class.java).putExtra(EXTRA_DISMISS_ID, id)

        fun intent(context: Context): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
private fun RingingScreen(dismissId: Int?, onFinished: () -> Unit) {
    val context = LocalContext.current
    val ringing by AlarmService.ringing.collectAsState()
    // Dismiss mode: the alarm is snoozed / waiting for its wake check, not ringing.
    val pending = remember(dismissId) {
        dismissId?.let { AlarmStore.get(context, it) }?.let {
            AlarmService.Ringing(it.id, it.label, it.snoozeMinutes, it.mathChallenge, it.wakeCheckPending, it.autoStopSeconds)
        }
    }
    // Just snoozed (by tap or by itself): stay open a little longer so it can still be dismissed.
    var snoozed by remember { mutableStateOf<AlarmService.Ringing?>(null) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    val waiting = pending ?: snoozed
    val silent = ringing == null && waiting != null
    // Stopped by itself (reminder auto-stop, or out of auto-snoozes): show that it rang for a bit.
    var stopped by remember { mutableStateOf<AlarmService.Ringing?>(null) }
    var rangAt by remember { mutableLongStateOf(0L) }
    // The user stopped it from this screen, so it can close right away.
    var handled by remember { mutableStateOf(false) }
    var lastRinging by remember { mutableStateOf<AlarmService.Ringing?>(null) }
    LaunchedEffect(ringing, waiting) {
        if (waiting != null && ringing == null) return@LaunchedEffect
        if (ringing != null) {
            if (ringing != lastRinging) {
                rangAt = System.currentTimeMillis()
                handled = false
            }
            lastRinging = ringing
            stopped = null
        } else {
            if (lastRinging == null) delay(3_000)
            if (AlarmService.ringing.value != null) return@LaunchedEffect
            val last = lastRinging
            if (last == null || handled) return@LaunchedEffect onFinished()
            // Nobody touched it: keep the screen up instead of going black.
            lastTouch = System.currentTimeMillis()
            val alarm = AlarmStore.get(context, last.id)
            if (alarm != null && alarm.snoozedUntil > System.currentTimeMillis() && !alarm.wakeCheckPending) {
                snoozed = last.copy(wakeCheck = false)
            } else {
                stopped = last
            }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Close the snoozed / stopped screen once it has gone untouched for SNOOZE_GRACE_MS.
    LaunchedEffect(snoozed, stopped) {
        if (snoozed == null && stopped == null) return@LaunchedEffect
        while (true) {
            val left = lastTouch + SNOOZE_GRACE_MS - System.currentTimeMillis()
            if (left <= 0) break
            delay(left)
        }
        // Another alarm started ringing meanwhile: drop back to the normal ringing screen.
        if (AlarmService.ringing.value == null) {
            onFinished()
        } else {
            snoozed = null
            stopped = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // Watch every touch (without consuming it) to keep the snoozed screen open.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = System.currentTimeMillis()
                    }
                }
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val s = stopped
        if (ringing == null && s != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(s.label.ifBlank { "Alarm" }, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                val t = Instant.ofEpochMilli(rangAt).atZone(ZoneId.systemDefault())
                Text(
                    formatHm(context, t.hour, t.minute),
                    fontSize = 88.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text("Rang and stopped by itself", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
                ClosingIn(lastTouch, now)
                Spacer(Modifier.height(40.dp))
                Button(onClick = onFinished, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Text("OK", fontSize = 22.sp)
                }
            }
            return@Box
        }
        val r = ringing ?: waiting ?: return@Box
        var showMath by remember(r.id) { mutableStateOf(false) }
        // In dismiss mode nothing is ringing, so close the screen once the action is done.
        fun done() {
            handled = true
            if (silent) onFinished()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                when {
                    silent && r.wakeCheck -> "Wake-up check (pending)"
                    silent && pending == null -> "Snoozed for ${r.snoozeMinutes} min"
                    silent -> "Dismiss snoozed alarm"
                    r.wakeCheck -> "Wake-up check"
                    else -> r.label.ifBlank { "Alarm" }
                },
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            Text(
                formatHm(context, t.hour, t.minute),
                fontSize = 88.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (silent && pending == null) ClosingIn(lastTouch, now)
            Spacer(Modifier.height(40.dp))
            when {
                // Reminder-style alarm: stops by itself, so one tap is enough.
                r.autoStopSeconds > 0 -> {
                    if (!silent) {
                        Text(
                            "Stops by itself after ${r.autoStopSeconds} s",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { AlarmController.dismissFully(context, r.id); done() },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) { Text("Stop", fontSize = 22.sp) }
                }
                // Wake check: one tap is enough - you already proved you were awake once.
                r.wakeCheck -> {
                    Text("Are you up?", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { AlarmController.dismiss(context, r.id, wasWakeCheck = true); done() },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) { Text("I'm awake", fontSize = 22.sp) }
                }
                showMath -> MathChallenge(
                    onSolved = { AlarmController.dismiss(context, r.id, wasWakeCheck = false); done() },
                    onCancel = { if (silent) onFinished() else showMath = false },
                    onInput = { lastTouch = System.currentTimeMillis() },
                )
                // Dismiss mode for a snoozed alarm: go straight to its dismiss rule.
                silent && r.mathChallenge -> MathChallenge(
                    onSolved = { AlarmController.dismiss(context, r.id, wasWakeCheck = false); done() },
                    onCancel = onFinished,
                    onInput = { lastTouch = System.currentTimeMillis() },
                )
                silent -> {
                    Button(
                        onClick = { AlarmController.dismiss(context, r.id, wasWakeCheck = false); done() },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) { Text("Dismiss", fontSize = 22.sp) }
                    TextButton(onClick = onFinished) { Text("Back") }
                }
                else -> {
                    // Snooze is the big, easy target; dismissing takes deliberate effort.
                    Button(
                        onClick = {
                            lastTouch = System.currentTimeMillis()
                            snoozed = r
                            AlarmController.snooze(context, r.id)
                        },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                    ) { Text("Snooze ${r.snoozeMinutes} min", fontSize = 24.sp) }
                    Spacer(Modifier.height(48.dp))
                    TextButton(onClick = {
                        if (r.mathChallenge) showMath = true
                        else { AlarmController.dismiss(context, r.id, wasWakeCheck = false); done() }
                    }) {
                        Text(
                            if (r.mathChallenge) "Dismiss (solve a quick sum)" else "Dismiss",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val MATH_PROBLEMS = 1

/** After Snooze, the screen stays open this long since the last touch so it can still be dismissed. */
private const val SNOOZE_GRACE_MS = 10_000L

@Composable
private fun ClosingIn(lastTouch: Long, now: Long) {
    val secs = ((lastTouch + SNOOZE_GRACE_MS - now + 999) / 1_000).coerceIn(1, SNOOZE_GRACE_MS / 1_000)
    Text("Closing in $secs s - tap to keep it open", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MathChallenge(onSolved: () -> Unit, onCancel: () -> Unit, onInput: () -> Unit = {}) {
    var solved by remember { mutableStateOf(0) }
    var seed by remember { mutableStateOf(0) }
    val (a, b, c) = remember(seed) { Triple(Random.nextInt(1, 10), Random.nextInt(1, 10), Random.nextInt(1, 10)) }
    var answer by remember(seed) { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun check() {
        if (answer.toIntOrNull() == a + b + c) {
            wrong = false
            solved++
            if (solved >= MATH_PROBLEMS) onSolved()
        } else {
            wrong = true
        }
        seed++
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (MATH_PROBLEMS > 1) Text("Problem ${solved + 1} of $MATH_PROBLEMS", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$a + $b + $c = ?", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = {
                // Keyboard taps don't reach the screen's touch watcher, so report them here.
                onInput()
                answer = it.filter(Char::isDigit).take(2)
                // A correct answer goes through on its own; "Check" is only needed for a wrong one.
                if (answer.toIntOrNull() == a + b + c) check()
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrong) Text("Wrong - try this one", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = ::check,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("Check", fontSize = 20.sp) }
        TextButton(onClick = onCancel) { Text("Back") }
    }
}
