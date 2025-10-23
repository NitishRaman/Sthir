package com.nitish.still

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nitish.still.GeofenceBroadcastReceiver.Companion.EXTRA_IS_INSIDE_HOME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence

class TimerService : Service() {

    // preserve last counted leisure session so UI can show it while Still is foreground
    private var lastLeisureSnapshotMs: Long = 0L

    private var serviceJob = Job()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val binder = LocalBinder()

    private val _usageState = MutableStateFlow(UsageState(0L))
    val usageState = _usageState.asStateFlow()

    private var continuousUsageMs = 0L
    private lateinit var prefs: SharedPreferences
    private var isInsideHome = false

    companion object {
        const val LABEL_UNLABELED = "Unlabeled"
        const val LABEL_IMPORTANT = "Important"
        const val LABEL_LEISURE = "Leisure"
        private const val NOTIF_CHANNEL = "still_channel"
        private const val BREAK_NOTIF_ID = 1001
    }
    private var lastBreakTriggerAt = 0L
    private val breakDebounceMs = 30_000L

    private var receiverRegistered = false

    // helper for correct pending intent flags depending on SDK
    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
        Log.d("TimerService", "onCreate() - PID=${android.os.Process.myPid()} UID=${android.os.Process.myUid()}")
        try {
            val workInterval = prefs.getInt("work_interval", 60)
            val breakInterval = prefs.getInt("break_interval", 300)
            Log.d("TimerService", "Prefs loaded: work_interval=${workInterval} min, break_interval=${breakInterval} sec")
        } catch (t: Throwable) {
            Log.w("TimerService", "Failed to read prefs onCreate: ${t.message}")
        }

        if (!receiverRegistered) {
            val filter = android.content.IntentFilter("com.nitish.still.ACTION_BREAK_COMPLETED")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(breakReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(breakReceiver, filter)
                }
                receiverRegistered = true
                Log.d("TimerService", "breakReceiver registered")
            } catch (t: Throwable) {
                Log.w("TimerService", "Failed to register breakReceiver: ${t.message}", t)
                receiverRegistered = false
            }
        } else {
            Log.d("TimerService", "breakReceiver already registered (skipping)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TimerService", "Service started")
        Log.d("TimerService", "onStartCommand: intent=$intent flags=$flags startId=$startId")

        intent?.let {
            if (it.hasExtra(EXTRA_IS_INSIDE_HOME)) {
                isInsideHome = it.getBooleanExtra(EXTRA_IS_INSIDE_HOME, false)
                checkInitialHomeStatus()
                Log.d("TimerService", "onStartCommand: isInsideHome = $isInsideHome")
            }
        }

        createNotificationChannel()
        updateNotification(isInsideHome)


        serviceJob.cancel()
        serviceJob = Job()
        // recreate serviceScope so new launched coroutines are parented to the fresh Job
        serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

        trackUsage(isInsideHome)

        return START_STICKY
    }

    private fun updateNotification(isInsideHome: Boolean) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (isInsideHome) "You are at home" else "You are outside home"
        val text = if (isInsideHome) "Still is active and tracking screen time." else "Still is paused."

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun trackUsage(shouldTrackInitially: Boolean) {
        serviceScope.launch {
            val workIntervalMillis = TimeUnit.MINUTES.toMillis(prefs.getInt("work_interval", 60).toLong())

            var shouldTrack = shouldTrackInitially
            var lastEventTime = System.currentTimeMillis() - 5000L // start a few seconds earlier
            var lastForegroundPackage: String? = null

            while (true) {
                Log.v("TimerService", "tick: shouldTrack=$shouldTrack isInsideHome=$isInsideHome continuousMs=$continuousUsageMs lastBreakAt=$lastBreakTriggerAt lastFg=$lastForegroundPackage")
                shouldTrack = isInsideHome

                // default emit value (will be overridden inside try when resolvedForeground is known)
                var emitMs = continuousUsageMs

                if (shouldTrack) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (powerManager.isInteractive) {
                        try {
                            if (!hasUsageStatsPermission()) {
                                Log.w("TimerService", "Missing Usage Access permission; skipping usage-events query.")
                                continuousUsageMs = 0L
                                lastForegroundPackage = null
                                lastEventTime = System.currentTimeMillis()
                            } else {
                                val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                                val now = System.currentTimeMillis()
                                val event = UsageEvents.Event()
                                var mostRecentForeground: String? = null
                                var mostRecentEventTime = lastEventTime

                                val usageEvents = usageStatsManager.queryEvents(lastEventTime, now)
                                var processedAnyEvent = false
                                while (usageEvents.hasNextEvent()) {
                                    usageEvents.getNextEvent(event)
                                    processedAnyEvent = true
                                    when (event.eventType) {
                                        UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                                            mostRecentForeground = event.packageName
                                            mostRecentEventTime = maxOf(mostRecentEventTime, event.timeStamp)
                                            Log.v("TimerService", "event: MOVE_TO_FOREGROUND ${event.packageName} @ ${event.timeStamp}")
                                        }
                                        UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                                            if (event.packageName == lastForegroundPackage) {
                                                lastForegroundPackage = null
                                            }
                                            mostRecentEventTime = maxOf(mostRecentEventTime, event.timeStamp)
                                            Log.v("TimerService", "event: MOVE_TO_BACKGROUND ${event.packageName} @ ${event.timeStamp}")
                                        }
                                        else -> {
                                            mostRecentEventTime = maxOf(mostRecentEventTime, event.timeStamp)
                                        }
                                    }
                                }

                                val foregroundApp = mostRecentForeground ?: lastForegroundPackage

                                // FALLBACK: infer foreground via queryUsageStats if needed
                                val resolvedForeground: String? = if (foregroundApp == null) {
                                    try {
                                        val fallbackStart = now - 10_000L
                                        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, fallbackStart, now)
                                        val top = stats.maxByOrNull { it.lastTimeUsed }
                                        val candidate = top?.packageName
                                        if (candidate != null && candidate != packageName) {
                                            Log.v("TimerService", "fallback foreground inferred via usageStats: $candidate (lastTimeUsed=${top.lastTimeUsed})")
                                            candidate
                                        } else {
                                            null
                                        }
                                    } catch (t: Throwable) {
                                        Log.w("TimerService", "fallback queryUsageStats failed: ${t.message}")
                                        null
                                    }
                                } else {
                                    foregroundApp
                                }

                                // move lastEventTime forward to avoid reprocessing; prefer event time if available
                                lastEventTime = if (mostRecentEventTime > 0) (mostRecentEventTime + 1) else now

                                if (resolvedForeground != null && resolvedForeground != packageName) {
                                    val pm = applicationContext.packageManager
                                    val savedLabel = prefs.getString("app_label_$resolvedForeground", null)

                                    val isLeisure = try {
                                        when {
                                            savedLabel != null -> savedLabel == LABEL_LEISURE
                                            else -> {
                                                val info = pm.getApplicationInfo(resolvedForeground, 0)
                                                isLeisureCategory(info, pm)
                                            }
                                        }
                                    } catch (e: PackageManager.NameNotFoundException) {
                                        Log.w("TimerService", "getApplicationInfo failed for $resolvedForeground: ${e.message}")
                                        false
                                    } catch (t: Throwable) {
                                        Log.w("TimerService", "Error checking app info for $resolvedForeground: ${t.message}")
                                        false
                                    }

                                    if (isLeisure) {
                                        if (resolvedForeground == lastForegroundPackage) {
                                            continuousUsageMs += 1000L
                                        } else {
                                            continuousUsageMs = 1000L
                                        }
                                        lastForegroundPackage = resolvedForeground

                                        // update last snapshot (used when Still is opened)
                                        lastLeisureSnapshotMs = continuousUsageMs
                                        Log.d("TimerService", "lastLeisureSnapshotMs updated = $lastLeisureSnapshotMs")

                                        Log.d("TimerService", "Counting: $resolvedForeground continuousMs=$continuousUsageMs (isInside=$isInsideHome)")

                                        if (continuousUsageMs >= workIntervalMillis) {
                                            Log.d("TimerService", "Work interval reached for $resolvedForeground")
                                            val nowTs = System.currentTimeMillis()
                                            if (nowTs - lastBreakTriggerAt >= breakDebounceMs) {
                                                lastBreakTriggerAt = nowTs
                                                val breakSeconds = prefs.getInt("break_interval", 300)

                                                // Try launching the activity directly (may fail on some OEMs / background restrictions)
                                                try {
                                                    val promptIntent = Intent(applicationContext, BreakPromptActivity::class.java).apply {
                                                        putExtra("break_seconds", breakSeconds)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                                    }
                                                    startActivity(promptIntent)
                                                    Log.d("TimerService", "Launched BreakPromptActivity (direct).")
                                                } catch (t: Throwable) {
                                                    Log.w("TimerService", "Direct start failed, sending fullscreen notification: ${t.message}")

                                                    val promptIntent = Intent(applicationContext, BreakPromptActivity::class.java).apply {
                                                        putExtra("break_seconds", breakSeconds)
                                                    }

                                                    val pendingIntent = TaskStackBuilder.create(applicationContext).run {
                                                        addNextIntentWithParentStack(promptIntent)
                                                        getPendingIntent(0, pendingIntentFlags())
                                                    }

                                                    val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
                                                        .setSmallIcon(R.mipmap.ic_launcher)
                                                        .setContentTitle("Time for a short break")
                                                        .setContentText("You completed your work interval. Tap to start your break.")
                                                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                                                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                                                        .setAutoCancel(true)
                                                        .setContentIntent(pendingIntent)
                                                        .setFullScreenIntent(pendingIntent, true)
                                                        .build()

                                                    NotificationManagerCompat.from(applicationContext).notify(BREAK_NOTIF_ID, notif)
                                                }
                                            } else {
                                                Log.d("TimerService", "Break recently triggered; skipping retrigger.")
                                            }

                                            // reset continuous counter after firing a break prompt (or skipping)
                                            continuousUsageMs = 0L
                                        }

                                    } else {
                                        continuousUsageMs = 0L
                                        lastForegroundPackage = resolvedForeground
                                        Log.v("TimerService", "Ignored app (not leisure): $resolvedForeground")
                                    }
                                } else {
                                    // no foreground app found in the window (or Still is foreground)
                                    if (mostRecentForeground == null) {
                                        Log.v("TimerService", "No foreground event found in window; resolvedForeground=null")
                                    } else {
                                        continuousUsageMs = 0L
                                        lastForegroundPackage = null
                                    }
                                }

                                // --- IMPORTANT: compute emitMs while resolvedForeground is in scope ---
                                emitMs = if (resolvedForeground == packageName) {
                                    // User opened your app: show the last leisure snapshot
                                    lastLeisureSnapshotMs
                                } else {
                                    // Otherwise show live continuous counter
                                    continuousUsageMs
                                }
                            }
                        } catch (se: SecurityException) {
                            Log.w("TimerService", "queryEvents SecurityException: ${se.message}")
                            continuousUsageMs = 0L
                            lastForegroundPackage = null
                            lastEventTime = System.currentTimeMillis()
                            emitMs = continuousUsageMs
                        } catch (t: Throwable) {
                            Log.w("TimerService", "queryEvents unexpected error: ${t.message}")
                            continuousUsageMs = 0L
                            lastForegroundPackage = null
                            lastEventTime = System.currentTimeMillis()
                            emitMs = continuousUsageMs
                        }
                    } else {
                        // screen off
                        continuousUsageMs = 0L
                        lastForegroundPackage = null
                        emitMs = continuousUsageMs
                    }
                } else {
                    // outside geofence
                    continuousUsageMs = 0L
                    lastForegroundPackage = null
                    lastEventTime = System.currentTimeMillis()
                    emitMs = continuousUsageMs
                }

                // Single emission per tick (emitMs was set above inside try/catch or via defaults)
                Log.v("TimerService", "emit usageState: emitMs=$emitMs continuousUsageMs=$continuousUsageMs lastLeisureSnapshotMs=$lastLeisureSnapshotMs")
                _usageState.value = UsageState(emitMs)
                delay(1000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkInitialHomeStatus() {
        try {
            val homeLat = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
            val homeLon = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0
            if (homeLat == 0.0 && homeLon == 0.0) {
                Log.d("TimerService", "Home location not set, skipping initial location check")
                return
            }
            val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(applicationContext)
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val homeLoc = android.location.Location("home").apply {
                        latitude = homeLat; longitude = homeLon
                    }
                    val dist = loc.distanceTo(homeLoc)
                    val nowInside = dist < GeofenceConstants.GEOFENCE_RADIUS_METERS
                    Log.d("TimerService", "Initial lastLocation distance=$dist isInside=$nowInside")
                    isInsideHome = nowInside
                    _usageState.value = UsageState(continuousUsageMs)
                    updateNotification(isInsideHome)
                } else {
                    Log.d("TimerService", "Initial lastLocation is null")
                }
            }.addOnFailureListener {
                Log.w("TimerService", "Initial location fetch failed: ${it.message}")
            }
        } catch (t: Throwable) {
            Log.w("TimerService", "checkInitialHomeStatus failed: ${t.message}")
        }
    }






    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIF_CHANNEL,
                "Still Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Timer and break notifications"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private val breakReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            try {
                val action = intent.action
                if (action == "com.nitish.still.ACTION_BREAK_COMPLETED") {
                    Log.d("TimerService", "Received ACTION_BREAK_COMPLETED broadcast, resetting counters.")
                    continuousUsageMs = 0L
                    lastBreakTriggerAt = System.currentTimeMillis()
                    _usageState.value = UsageState(lastLeisureSnapshotMs)
                } else {
                    Log.w("TimerService", "breakReceiver received unexpected action: $action")
                }
            } catch (t: Throwable) {
                Log.w("TimerService", "breakReceiver onReceive error: ${t.message}", t)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    // expose snapshot so UI can read it immediately after bind
    fun getLastLeisureSnapshotMs(): Long = lastLeisureSnapshotMs

    fun setInsideHome(inside: Boolean) {
        if (isInsideHome == inside) return
        isInsideHome = inside
        Log.d("TimerService", "setInsideHome: isInsideHome=$isInsideHome")
        if (!isInsideHome) {
            continuousUsageMs = 0L
        }
        _usageState.value = UsageState(continuousUsageMs)
        updateNotification(isInsideHome)
    }

    override fun onDestroy() {
        try {
            if (receiverRegistered) {
                unregisterReceiver(breakReceiver)
                receiverRegistered = false
                Log.d("TimerService", "breakReceiver unregistered")
            }
        } catch (t: Throwable) {
            Log.w("TimerService", "Failed to unregister breakReceiver: ${t.message}")
        }

        super.onDestroy()
        serviceJob.cancel()
        serviceJob = Job()
    }
}
