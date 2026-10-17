package com.neutrino.calendar

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neutrino.calendar.notifications.ReminderAlarms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CalendarApplication : Application() {

    lateinit var graph: AppGraph
        private set

    /** Work that should outlive any one screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        ReminderAlarms.createChannels(this)
        graph = AppGraph(this)
        RefreshWorker.schedule(this)

        // Foreground: reconnect the live signal and catch up on what changed elsewhere, sending
        // edits queued offline first; background: drop the socket. The iOS app's scenePhase handling.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.geofences.refreshAccess()
                if (!graph.account.auth.isSignedIn) return
                graph.sync.start()
                // Retired keys from the account's key file, as every Neutrino app does on foreground.
                graph.scope.launch {
                    if (graph.account.keys.hasKey) runCatching { graph.account.keys.pullKeyFile() }.onSuccess { graph.account.keyringChanged() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                graph.sync.suspend()
            }
        })
    }
}

val Context.graph: AppGraph get() = (applicationContext as CalendarApplication).graph
val Context.appScope: CoroutineScope get() = (applicationContext as CalendarApplication).appScope

/**
 * Re-syncs while the app is closed, so a reminder set on the web is planned before it is due, edits
 * queued offline are sent, and the widgets stay current. The system decides when it runs; this asks
 * for about every half hour. The iOS `BackgroundRefresh`.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (!graph.account.auth.isSignedIn) return Result.success()
        return withContext(Dispatchers.Main) {
            // Reloads the reminders among the rest; the alarm plan follows the list.
            graph.sync.catchUp()
            if (graph.reminders.error.value == null) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val NAME = "calendar-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
