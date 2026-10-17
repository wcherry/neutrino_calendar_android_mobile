package com.neutrino.calendar.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.neutrino.calendar.MainActivity
import com.neutrino.calendar.R
import com.neutrino.calendar.appScope
import com.neutrino.calendar.core.logic.NotificationPlan
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.Reminder
import com.neutrino.calendar.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

/**
 * The device is the alarm: the server can't push yet (its reminder engine only logs), so every
 * open reminder due in the future is scheduled here with [AlarmManager], event alerts included — an
 * event's alert is a reminder linked to it. The iOS app's `ReminderNotifications`.
 *
 * The plan is made again whenever the reminder list changes, when the app comes forward, and from
 * background refresh. AlarmManager can't list what it holds, so the plan scheduled is kept in a file;
 * a reboot clears every alarm and [BootReceiver] schedules that file again.
 *
 * Actions: **Mark as Done** completes the reminder on the server (a repeating one moves on);
 * **Snooze** fires again in ten minutes, on this device only; a tap opens the reminder.
 */
class ReminderAlarms(private val context: Context) {

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val store = context.getSharedPreferences("ncal.alarms", Context.MODE_PRIVATE)

    /** Whether a reminder belongs to an event in a hidden calendar, whose alerts are hidden with it. */
    var isHidden: (Reminder) -> Boolean = { false }

    val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Brings the scheduled alarms in line with `reminders`; with alerts off, removes them all. */
    fun apply(reminders: List<Reminder>, enabled: Boolean, now: Instant = Instant.now()) {
        if (!enabled) {
            removeAll()
            return
        }
        val plan = NotificationPlan.plan(reminders.filter { !isHidden(it) }, now)
        val scheduled = load()
        val (add, remove) = NotificationPlan.changes(scheduled.map { it.identifier }, plan)
        remove.forEach(::cancel)
        add.forEach(::schedule)
        // Alarms already scheduled keep their place; a moved reminder is a new identifier.
        save(plan + scheduled.filter { it.identifier.startsWith(NotificationPlan.SNOOZE_PREFIX) && it.fireAt > now })
    }

    /** After a reboot, or once exact alarms are allowed: everything in the stored plan, again. */
    fun rescheduleStored(now: Instant = Instant.now()) {
        val kept = load().filter { it.fireAt > now }
        kept.forEach(::schedule)
        save(kept)
    }

    /** Every alarm and every delivered notification this app made, for sign-out and the switch turned off. */
    fun removeAll() {
        load().forEach { cancel(it.identifier) }
        save(emptyList())
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun snooze(reminderId: String, title: String, now: Instant = Instant.now()) {
        val alert = NotificationPlan.Alert(NotificationPlan.SNOOZE_PREFIX + reminderId, reminderId, now.plusSeconds(SNOOZE_SECONDS).toEpochMilli(), title)
        schedule(alert)
        save(load().filter { it.identifier != alert.identifier } + alert)
    }

    private fun schedule(alert: NotificationPlan.Alert) {
        val pending = pendingIntent(alert.identifier, alert) ?: return
        // An instant, not a wall-clock time: a reminder fires at its moment whatever zone the phone is in.
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alert.fireAtEpochMillis, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alert.fireAtEpochMillis, pending)
    }

    private fun cancel(identifier: String) {
        pendingIntent(identifier, null)?.let { alarms.cancel(it) }
    }

    private fun pendingIntent(identifier: String, alert: NotificationPlan.Alert?): PendingIntent? {
        // The data URI makes each alarm distinct; the extras ride along for the receiver.
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setData(Uri.parse("ncal-alarm://$identifier"))
            .apply {
                alert?.let {
                    putExtra(EXTRA_REMINDER_ID, it.reminderId)
                    putExtra(EXTRA_TITLE, it.title)
                }
            }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun load(): List<NotificationPlan.Alert> =
        store.getString(KEY, null)?.let { runCatching { CalendarJson.decodeFromString(ListSerializer(NotificationPlan.Alert.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun save(list: List<NotificationPlan.Alert>) {
        store.edit().putString(KEY, CalendarJson.encodeToString(ListSerializer(NotificationPlan.Alert.serializer()), list)).apply()
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_ARRIVALS = "arrivals"
        const val CHANNEL_NEXT_EVENT = "next_event"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        const val ACTION_DONE = "com.neutrino.calendar.DONE"
        const val ACTION_SNOOZE = "com.neutrino.calendar.SNOOZE"
        const val SNOOZE_SECONDS = 10 * 60L
        private const val KEY = "plan"

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ARRIVALS, context.getString(R.string.channel_arrivals), NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_NEXT_EVENT, context.getString(R.string.channel_next_event), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }

        fun notificationId(key: String) = key.hashCode()

        /** Opens the app on `uri`: `neutrinocalendar://reminder/<id>`, `…/task/<id>`, `…/event/<link>`. */
        fun openIntent(context: Context, uri: String): PendingIntent =
            PendingIntent.getActivity(
                context, uri.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun actionIntent(context: Context, action: String, extras: Intent.() -> Unit, key: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, (action + key).hashCode(),
                Intent(context, NotificationActionReceiver::class.java).setAction(action).apply(extras),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

/** A reminder's alarm went off: show it, unless alerts were turned off since. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SURFACES) {
            val result = goAsync()
            context.appScope.launch(Dispatchers.Main) {
                runCatching { context.graph.surfaces.redraw() }
                result.finish()
            }
            return
        }
        val reminderId = intent.getStringExtra(ReminderAlarms.EXTRA_REMINDER_ID) ?: return
        val title = intent.getStringExtra(ReminderAlarms.EXTRA_TITLE) ?: "Reminder"
        if (!context.graph.prefs.alertsEnabled.value) return
        val notification = NotificationCompat.Builder(context, ReminderAlarms.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_shortcut_reminder)
            .setContentTitle(title)
            .setContentText("Reminder")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup("reminders")
            .setContentIntent(ReminderAlarms.openIntent(context, "neutrinocalendar://reminder/$reminderId"))
            .addAction(0, "Mark as Done", ReminderAlarms.actionIntent(context, ReminderAlarms.ACTION_DONE, { putExtra(ReminderAlarms.EXTRA_REMINDER_ID, reminderId) }, reminderId))
            .addAction(0, "Snooze 10 Minutes", ReminderAlarms.actionIntent(context, ReminderAlarms.ACTION_SNOOZE, {
                putExtra(ReminderAlarms.EXTRA_REMINDER_ID, reminderId)
                putExtra(ReminderAlarms.EXTRA_TITLE, title)
            }, reminderId))
            .build()
        if (context.graph.alarms.hasPermission) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(ReminderAlarms.notificationId(reminderId), notification)
        }
    }

    companion object {
        /** Sent at the next event's start and end, to move the next-event notification on. */
        const val ACTION_SURFACES = "com.neutrino.calendar.SURFACES"
    }
}

/**
 * Mark as Done and Snooze, on a reminder or an arrival alert. The list may not be loaded when the
 * process was started for this, so it is loaded first.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val reminderId = intent.getStringExtra(ReminderAlarms.EXTRA_REMINDER_ID)
        val taskId = intent.getStringExtra(ReminderAlarms.EXTRA_TASK_ID)
        NotificationManagerCompat.from(context).cancel(ReminderAlarms.notificationId(reminderId ?: taskId ?: return))
        val result = goAsync()
        context.appScope.launch(Dispatchers.Main) {
            try {
                when {
                    intent.action == ReminderAlarms.ACTION_SNOOZE && reminderId != null ->
                        graph.alarms.snooze(reminderId, intent.getStringExtra(ReminderAlarms.EXTRA_TITLE) ?: "Reminder")
                    intent.action == ReminderAlarms.ACTION_DONE && reminderId != null && graph.account.auth.isSignedIn -> {
                        val reminders = graph.reminders
                        if (reminders.reminders.value.none { it.id == reminderId }) reminders.reload()
                        reminders.reminders.value.firstOrNull { it.id == reminderId }?.takeIf { !it.completed }
                            ?.let { reminders.setCompleted(it, true) }
                    }
                    intent.action == ReminderAlarms.ACTION_DONE && taskId != null && graph.account.auth.isSignedIn -> {
                        val tasks = graph.tasks
                        if (tasks.task(taskId) == null) tasks.reload()
                        tasks.task(taskId)?.takeIf { !it.done }?.let { tasks.setDone(it, true) }
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}

/** A reboot clears every alarm and geofence; a zone change moves nothing (alarms are instants) but is a cheap moment to check. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        if (!graph.account.auth.isSignedIn) return
        graph.alarms.rescheduleStored()
        val result = goAsync()
        context.appScope.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.Main) { graph.geofences.reapply() }
            } finally {
                result.finish()
            }
        }
    }
}
