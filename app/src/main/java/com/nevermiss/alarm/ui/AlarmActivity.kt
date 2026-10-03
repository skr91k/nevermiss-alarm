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
    val silent = ringing == null && pending != null
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(ringing, pending) {
        if (pending != null && ringing == null) return@LaunchedEffect
        if (ringing != null) {
            seen = true
        } else {
            if (!seen) delay(3_000)
            if (AlarmService.ringing.value == null) onFinished()
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val r = ringing ?: pending ?: return@Box
        var showMath by remember(r.id) { mutableStateOf(false) }
        // In dismiss mode nothing is ringing, so close the screen once the action is done.
        fun done() {
            if (silent) onFinished()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                when {
                    silent && r.wakeCheck -> "Wake-up check (pending)"
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
                )
                // Dismiss mode for a snoozed alarm: go straight to its dismiss rule.
                silent && r.mathChallenge -> MathChallenge(
                    onSolved = { AlarmController.dismiss(context, r.id, wasWakeCheck = false); done() },
                    onCancel = onFinished,
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
                        onClick = { AlarmController.snooze(context, r.id) },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                    ) { Text("Snooze ${r.snoozeMinutes} min", fontSize = 24.sp) }
                    Spacer(Modifier.height(48.dp))
                    TextButton(onClick = {
                        if (r.mathChallenge) showMath = true
                        else AlarmController.dismiss(context, r.id, wasWakeCheck = false)
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

@Composable
private fun MathChallenge(onSolved: () -> Unit, onCancel: () -> Unit) {
    var solved by remember { mutableStateOf(0) }
    var seed by remember { mutableStateOf(0) }
    val (a, b, c) = remember(seed) { Triple(Random.nextInt(1, 10), Random.nextInt(1, 10), Random.nextInt(1, 10)) }
    var answer by remember(seed) { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (MATH_PROBLEMS > 1) Text("Problem ${solved + 1} of $MATH_PROBLEMS", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$a + $b + $c = ?", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it.filter(Char::isDigit).take(2) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrong) Text("Wrong - try this one", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (answer.toIntOrNull() == a + b + c) {
                    wrong = false
                    solved++
                    if (solved >= MATH_PROBLEMS) onSolved()
                } else {
                    wrong = true
                }
                seed++
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("Check", fontSize = 20.sp) }
        TextButton(onClick = onCancel) { Text("Back") }
    }
}
