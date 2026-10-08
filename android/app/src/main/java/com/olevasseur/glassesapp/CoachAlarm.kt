package com.olevasseur.glassesapp

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Durable alarms, independent of activity, camera, backend, and Gemini lifetimes. */
class CoachAlarm(private val context: Context) {
    companion object {
        private val lock = Any()
        const val CHANNEL = "coach_timers"
        const val SPEECH_CHANNEL = "coach_timer_speech"
        const val FIRE = "com.olevasseur.glassesapp.COACH_TIMER"
        fun openAlarms(context: Context) {
            if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    private val prefs = context.getSharedPreferences("coach_timers", Context.MODE_PRIVATE)
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private fun book() = CoachTimers(prefs.getString("timers", "[]") ?: "[]")
    private fun save(book: CoachTimers) { check(prefs.edit().putString("timers", book.serialized()).commit()) { "Cannot save timers" } }
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private fun exactAllowed() = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
    private fun remaining(timer: CoachTimer) = timer.remaining(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
    private fun pending(timer: CoachTimer) = PendingIntent.getBroadcast(context, 0,
        Intent(context, CoachAlarmReceiver::class.java).setAction(FIRE).setData(Uri.parse("coach-timer:${timer.id}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun openApp() = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(timer: CoachTimer) {
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + remaining(timer).coerceAtLeast(1), openApp()), pending(timer))
    }
    fun channels() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Cooking and DIY timers", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        })
        notifications.createNotificationChannel(NotificationChannel(SPEECH_CHANNEL, "Timer announcements", NotificationManager.IMPORTANCE_LOW))
    }
    fun notification(text: String, speech: Boolean = false): Notification = Notification.Builder(context, if (speech) SPEECH_CHANNEL else CHANNEL)
        .setSmallIcon(R.drawable.ic_glasses).setContentTitle("the glasses app").setContentText(text)
        .setContentIntent(openApp()).setCategory(Notification.CATEGORY_ALARM).setAutoCancel(!speech).build()
    fun tool(call: FunctionCall): ToolResult = synchronized(lock) {
        try {
            val book = book()
            val result = when (call.name) {
                "set_timer" -> {
                    channels()
                    require(exactAllowed()) { "On the phone, tap Allow precise timers and grant Alarms & reminders, then ask again" }
                    require((Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        && notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
                        "Enable phone notifications for the glasses app and its Cooking and DIY timers channel, then ask again"
                    }
                    val name = call.args.opt("name") as? String ?: error("set_timer requires a name")
                    val duration = (call.args.opt("duration") as? Number)?.toDouble() ?: error("duration must be seconds")
                    val timer = book.add(name, duration, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
                    save(book)
                    try { schedule(timer) } catch (e: Exception) { book.cancel(name); save(book); throw e }
                    JSONObject().put("status", "ok").put("name", timer.name).put("duration", duration)
                }
                "list_timers" -> JSONObject().put("status", "ok").put("timers", JSONArray(book.all().map {
                    JSONObject().put("name", it.name).put("remaining_seconds", (remaining(it) + 999) / 1000)
                        .put("alarm_permission", exactAllowed())
                }))
                "cancel_timer" -> {
                    val name = call.args.opt("name") as? String ?: error("cancel_timer requires a name")
                    val timer = book.cancel(name)
                    save(book) // A queued broadcast checks storage too.
                    if (timer != null) { alarm.cancel(pending(timer)); pending(timer).cancel() }
                    JSONObject().put("status", if (timer == null) "not_found" else "ok").put("name", name)
                }
                else -> toolError("Unknown timer tool")
            }
            ToolResult(result)
        } catch (e: Exception) { ToolResult(toolError(e.message ?: "Timer operation failed")) }
    }
    fun restore() = synchronized(lock) {
        if (exactAllowed()) {
            val book = book()
            book.rebase(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
            save(book)
            book.all().forEach(::schedule)
        }
    }
    fun due(id: String): CoachTimer? = synchronized(lock) {
        val book = book()
        val timer = book.due(id, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
        if (timer == null) book.all().firstOrNull { it.id == id }?.let { if (exactAllowed()) schedule(it) }
        if (timer != null) {
            channels()
            notifications.notify(timer.id, 1, notification("The ${timer.name} timer is done"))
            save(book)
        }
        timer
    }
}

class CoachAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CoachAlarm.FIRE) {
            val id = intent.data?.schemeSpecificPart ?: return
            context.startForegroundService(Intent(context, CoachAlarmService::class.java).putExtra("id", id))
        } else CoachAlarm(context).restore()
    }
}
