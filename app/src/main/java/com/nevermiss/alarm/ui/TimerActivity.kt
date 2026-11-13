package com.nevermiss.alarm.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevermiss.alarm.core.TimerController
import com.nevermiss.alarm.data.TimerStore
import com.nevermiss.alarm.service.TimerService
import kotlinx.coroutines.delay

/** "Time's up" screen. Shown over the lock screen; closes once no timer is ringing. */
class TimerActivity : ComponentActivity() {
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
        setContent { NeverMissTheme { TimesUpScreen(onFinished = { finish() }) } }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, TimerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
private fun TimesUpScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val ringing by TimerService.ringing.collectAsState()
    val version by TimerStore.version.collectAsState()
    val timers = remember(ringing, version) { ringing.mapNotNull { TimerStore.get(context, it) } }
    LaunchedEffect(ringing) {
        if (ringing.isEmpty()) {
            delay(1_500) // the service may still be starting
            if (TimerService.ringing.value.isEmpty()) onFinished()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Time's up", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(32.dp))
            timers.forEach { t ->
                Text(t.title(), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text(formatDuration(t.durationMs), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { TimerController.addMinute(context, t.id) },
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text("+1:00", fontSize = 20.sp) }
                    Button(
                        onClick = { TimerController.stop(context, t.id) },
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text("Stop", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
