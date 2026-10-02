# NeverMiss – an Android alarm clock that doesn't miss

Normal alarm apps fail for boring reasons: Doze, battery killers from the phone maker,
a reboot during the night, a deleted ringtone, alarm volume at 0 – or you switching it
off half asleep and not remembering. NeverMiss defends against each of those separately.

## Features

- iOS-style scroll-wheel time picker
- Repeat: **Once**, **Weekly** (pick days), **Date** (one exact date, switches off after it
  rings) or **Monthly** (day 1–31; in shorter months it rings on the last day)
- **Dismiss puzzle**: 3 quick single-digit additions (e.g. `1 + 5 + 8`) – Snooze is the big
  easy button, Dismiss takes deliberate effort
- **Wake-up check**: 3 minutes after dismissing, it rings once more until you tap "I'm awake"
- **Auto-snooze**: if nobody touches it for 1/3/5/10 min it snoozes and rings again
- **Auto-stop** reminders: ring 5/15/30/60 s and stop by themselves (no puzzle)
- A ringing, snoozed or wake-check alarm is **locked** – it can't be switched off, edited
  or deleted from the list until it's properly dismissed
- Per-alarm sound, vibration, snooze length, label
- **Reliability check** card listing anything on the phone that could block an alarm
  (exact-alarm permission, notifications, full-screen permission, battery optimisation,
  manufacturer background limits), each with a one-tap fix
- Persistent **event log** of every schedule / fire / ring / dismiss, to explain any miss

## Reliability layers

| Failure | Defence |
|---|---|
| Doze / app standby delays the alarm | `AlarmManager.setAlarmClock()` – top-priority alarm, exempt from Doze |
| Main alarm broadcast never delivered | A second **backup alarm** 90 s later; each occurrence is de-duplicated by its trigger time |
| Phone rebooted overnight (e.g. system update), not unlocked yet | Direct-boot aware: alarms stored in device-protected storage, re-armed on `LOCKED_BOOT_COMPLETED` |
| Phone was off / app killed at alarm time | On boot or app open, an alarm missed < 2 h ago **rings immediately** |
| Time / time-zone change, app update, permission change | All alarms re-armed (`TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, …) |
| Alarm list wiped by the OS | Every alarm is re-armed each time the app opens and every time any alarm fires |
| Ringtone missing / unreadable / media error | Chosen sound → default alarm → default ringtone → **built-in generated beep** (no file needed), plus a 3 s "is it actually playing?" watchdog |
| Alarm volume at 0 | Alarm volume forced to max while ringing, restored afterwards |
| Process killed while ringing | Foreground service with `START_REDELIVER_INTENT` rings again |
| Ringing screen closed / swiped | Sound belongs to the service, not the screen; Back is disabled |
| Dismissed half asleep | Puzzle to dismiss + wake-up check 3 min later |
| Foreground service can't start | Fallback "insistent" notification that plays the alarm sound itself |

## Build & run

Requires the Android SDK (compileSdk 36). Min Android 8.0 (API 26).

```
./gradlew installDebug
```

In VS Code: **F5** (Run and Debug → "Run on phone") or **Cmd+Shift+B** builds, installs and
launches on the connected phone. Debug builds also show a "Test alarm (rings in 10 s)" button.

Read the on-device event log:

```
adb shell run-as com.nevermiss.alarm cat /data/user_de/0/com.nevermiss.alarm/files/events.log
```

## Code map

- `data/` – `Alarm` model (incl. next-trigger calculation), `AlarmStore` (JSON in device-protected SharedPreferences)
- `core/AlarmScheduler` – arming main + backup alarms, missed-alarm recovery
- `core/AlarmController` – fire / snooze / dismiss / wake-up check / auto-snooze state machine
- `core/EventLog` – persistent on-device log
- `service/AlarmService` – foreground service that owns the ringing alarm
- `service/AlarmSound` – playback with fallbacks, volume forcing, vibration
- `receiver/` – alarm broadcast, notification actions, boot/time-change re-arming
- `core/Reliability` – device health checks shown in the UI
- `ui/` – Compose screens: alarm list, editor, wheel time picker, full-screen ringing screen
