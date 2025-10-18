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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TimerService : Service() {

    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val binder = LocalBinder()

    private val _usageState = MutableSharedFlow<UsageState>(replay = 1)
    val usageState = _usageState.asSharedFlow()

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

        // Cancel any previous tracking job to avoid multiple instances
        serviceJob.cancel()
        serviceJob = Job()
        trackUsage(isInsideHome) // Start or resume tracking based on current status

        return START_STICKY
    }

    private fun updateNotification(isInsideHome: Boolean) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val title = if (isInsideHome) "You are at home" else "You are outside home"
        val text = if (isInsideHome) "Still is active" else "Still is deactivated"

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

    private fun trackUsage(shouldTrack: Boolean) {
        serviceScope.launch {
            val workIntervalMillis = TimeUnit.MINUTES.toMillis(prefs.getInt("work_interval", 60).toLong())

            while (true) {
                if (shouldTrack) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (powerManager.isInteractive) {
                        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                        val time = System.currentTimeMillis()
                        val events = usageStatsManager.queryEvents(time - 1000 * 60, time)
                        val event = UsageEvents.Event()
                        var foregroundApp: String? = null
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                foregroundApp = event.packageName
                            }
                        }

                        if (foregroundApp != null && foregroundApp != packageName) {
                            // This line will now work correctly
                            val appLabel = prefs.getString("app_label_$foregroundApp", LABEL_UNLABELED)
                            
                            // This line will also work correctly
                            if (appLabel != LABEL_IMPORTANT) {
                                continuousUsageMs += 1000
                                Log.d("TimerService", "Foreground app: $foregroundApp, Usage: $continuousUsageMs")

                                if (continuousUsageMs >= workIntervalMillis) {
                                    Log.d("TimerService", "Work interval reached!")
                                    continuousUsageMs = 0
                                }
                            } else {
                                continuousUsageMs = 0
                            }
                        } else {
                            continuousUsageMs = 0
                        }
                    } else {
                        continuousUsageMs = 0
                    }
                } else {
                    continuousUsageMs = 0 // Reset usage if not tracking
                }
                _usageState.tryEmit(UsageState(continuousUsageMs = continuousUsageMs))
                delay(1000)
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