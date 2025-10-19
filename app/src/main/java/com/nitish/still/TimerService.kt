package com.nitish.still

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


data class TimerUsageState(val continuousUsageMs: Long)

class TimerService : Service() {

    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val binder = LocalBinder()

    private val _usageState = MutableStateFlow(TimerUsageState(0L))
    val usageState = _usageState.asStateFlow()

    private var continuousUsageMs = 0L
    private lateinit var prefs: SharedPreferences
    private var isInsideHome = false

    companion object {
        const val LABEL_UNLABELED = "Unlabeled"
        const val LABEL_IMPORTANT = "Important"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TimerService", "Service started")
        isInsideHome = intent?.getBooleanExtra(EXTRA_IS_INSIDE_HOME, false) ?: false

        createNotificationChannel()
        updateNotification(isInsideHome)

        serviceJob.cancel() // Cancel any previous job
        serviceJob = Job()
        trackUsage(isInsideHome)

        return START_STICKY
    }

    private fun updateNotification(isInsideHome: Boolean) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val title = if (isInsideHome) "You are at home" else "You are outside home"
        val text = if (isInsideHome) "Still is active and tracking screen time." else "Still is paused."

        val notification = NotificationCompat.Builder(this, "still_channel")
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

    private fun trackUsage(shouldTrackInitially: Boolean) {
        serviceScope.launch {
            val workIntervalMillis = TimeUnit.MINUTES.toMillis(prefs.getInt("work_interval", 60).toLong())

            // Keep local mutable state for stable counting
            var shouldTrack = shouldTrackInitially
            var lastEventTime = System.currentTimeMillis() - 1000L
            var lastForegroundPackage: String? = null

            while (true) {
                // If geofence status might change externally, read the latest flag each loop
                shouldTrack = isInsideHome

                if (shouldTrack) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (powerManager.isInteractive) {
                        try {
                            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                            val now = System.currentTimeMillis()

                            // Query only events since last loop to avoid duplicates
                            val events = usageStatsManager.queryEvents(lastEventTime, now)
                            val event = UsageEvents.Event()
                            var mostRecentForeground: String? = null
                            var mostRecentEventTime = lastEventTime

                            while (events.hasNextEvent()) {
                                events.getNextEvent(event)
                                // track the most recent MOVE_TO_FOREGROUND in this window
                                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                    mostRecentForeground = event.packageName
                                    mostRecentEventTime = maxOf(mostRecentEventTime, event.timeStamp)
                                } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                                    // if background happened, and it matches our lastForegroundPackage, we should clear it
                                    if (event.packageName == lastForegroundPackage) {
                                        lastForegroundPackage = null
                                    }
                                    mostRecentEventTime = maxOf(mostRecentEventTime, event.timeStamp)
                                }
                            }

                            // If there was a recent MOVE_TO_FOREGROUND use it; otherwise assume lastForegroundPackage persists
                            val foregroundApp = mostRecentForeground ?: lastForegroundPackage

                            // update lastEventTime to now so we don't re-process older events
                            lastEventTime = now

                            if (foregroundApp != null && foregroundApp != packageName) {
                                // decide leisure vs important
                                val pm = applicationContext.packageManager
                                val savedLabel = prefs.getString("app_label_$foregroundApp", null)

                                val isLeisure = try {
                                    when {
                                        savedLabel != null -> savedLabel == LABEL_LEISURE
                                        else -> {
                                            val info = pm.getApplicationInfo(foregroundApp, 0)
                                            isLeisureCategory(info, pm)
                                        }
                                    }
                                } catch (e: PackageManager.NameNotFoundException) {
                                    Log.w("TimerService", "getApplicationInfo failed for $foregroundApp: ${e.message}")
                                    false
                                } catch (t: Throwable) {
                                    Log.w("TimerService", "Error checking app info for $foregroundApp: ${t.message}")
                                    false
                                }

                                // If same app continues in foreground across ticks, we accumulate
                                if (isLeisure) {
                                    if (foregroundApp == lastForegroundPackage) {
                                        // add 1 second (1000ms) since our loop is ~1s
                                        continuousUsageMs += 1000
                                    } else {
                                        // new leisure app started; start counting from 1 second
                                        continuousUsageMs = 1000
                                    }
                                    lastForegroundPackage = foregroundApp

                                    Log.d("TimerService", "Counting: $foregroundApp continuousMs=$continuousUsageMs (isInside=$isInsideHome)")

                                    if (continuousUsageMs >= workIntervalMillis) {
                                        Log.d("TimerService", "Work interval reached for $foregroundApp")
                                        // TODO: trigger break workflow (camera prompt / teachable model)
                                        continuousUsageMs = 0
                                    }
                                } else {
                                    // not a leisure app: reset
                                    continuousUsageMs = 0
                                    lastForegroundPackage = foregroundApp // track it so we don't mis-count
                                    Log.v("TimerService", "Ignored app (not leisure): $foregroundApp")
                                }
                            } else {
                                // no foreground app or launcher — reset
                                continuousUsageMs = 0
                                lastForegroundPackage = null
                            }
                        } catch (t: Throwable) {
                            Log.w("TimerService", "trackUsage error: ${t.message}")
                            // on error reset safely
                            continuousUsageMs = 0
                            lastForegroundPackage = null
                            lastEventTime = System.currentTimeMillis()
                        }
                    } else {
                        // screen off: reset continuous usage count
                        continuousUsageMs = 0
                        lastForegroundPackage = null
                    }
                } else {
                    // not tracking (outside geofence)
                    continuousUsageMs = 0
                    lastForegroundPackage = null
                    lastEventTime = System.currentTimeMillis()
                }

                _usageState.value = TimerUsageState(continuousUsageMs)
                delay(1000L) // keep a steady 1s tick
            }
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "still_channel",
                "Still Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
