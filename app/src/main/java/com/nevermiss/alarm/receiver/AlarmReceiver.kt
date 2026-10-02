package com.nevermiss.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nevermiss.alarm.core.AlarmScheduler
import com.nevermiss.alarm.core.EventLog
import com.nevermiss.alarm.core.WakeLocks
import com.nevermiss.alarm.service.AlarmService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        // Keep the CPU awake until the service has taken over.
        WakeLocks.acquire(context, 60_000)
        EventLog.log(
            context,
            "FIRE received alarm ${intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)} " +
                "slot ${EventLog.time(intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER, 0L))} " +
                "backup=${intent.getBooleanExtra(AlarmScheduler.EXTRA_BACKUP, false)}",
        )
        AlarmService.start(
            context,
            id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1),
            trigger = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER, 0L),
        )
    }
}
