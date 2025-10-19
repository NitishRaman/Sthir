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
                            // Use applicationContext.packageManager (safe from a Service / coroutine)
                            val pm = applicationContext.packageManager
                            val savedLabel = prefs.getString("app_label_$foregroundApp", null)

                            val isLeisure = try {
                                when {
                                    // If user explicitly labeled, respect that
                                    savedLabel != null -> savedLabel == LABEL_LEISURE
                                    // else infer using classifier (falls back to heuristics)
                                    else -> {
                                        val info = pm.getApplicationInfo(foregroundApp, 0)
                                        isLeisureCategory(info, pm)
                                    }
                                }
                            } catch (e: PackageManager.NameNotFoundException) {
                                Log.w("TimerService", "getApplicationInfo failed for $foregroundApp: ${e.message}")
                                false
                            } catch (e: Exception) {
                                Log.w("TimerService", "Unexpected error checking app info for $foregroundApp: ${e.message}")
                                false
                            }

                            if (isInsideHome && isLeisure) {
                                continuousUsageMs += 1000
                                Log.d("TimerService", "Foreground app (counted): $foregroundApp, Usage: $continuousUsageMs")

                                if (continuousUsageMs >= workIntervalMillis) {
                                    Log.d("TimerService", "Work interval reached!")
                                    continuousUsageMs = 0
                                }
                            } else {
                                // Not counted: either not leisure or not inside home
                                continuousUsageMs = 0
                                Log.v("TimerService", "Ignored app (inside=$isInsideHome leisure=$isLeisure): $foregroundApp")
                            }
                        } else {
                            continuousUsageMs = 0 // Reset if no app in foreground or if it's the launcher
                        }
                        if (foregroundApp != null && foregroundApp != packageName) {
                            // Use applicationContext.packageManager (safe from a Service / coroutine)
                            val pm = applicationContext.packageManager
                            val savedLabel = prefs.getString("app_label_$foregroundApp", null)

                            val isLeisure = try {
                                when {
                                    // If user explicitly labeled, respect that
                                    savedLabel != null -> savedLabel == LABEL_LEISURE
                                    // else infer using classifier (falls back to heuristics)
                                    else -> {
                                        val info = pm.getApplicationInfo(foregroundApp, 0)
                                        isLeisureCategory(info, pm)
                                    }
                                }
                            } catch (e: PackageManager.NameNotFoundException) {
                                Log.w("TimerService", "getApplicationInfo failed for $foregroundApp: ${e.message}")
                                false
                            } catch (e: Exception) {
                                Log.w("TimerService", "Unexpected error checking app info for $foregroundApp: ${e.message}")
                                false
                            }

                            if (isInsideHome && isLeisure) {
                                continuousUsageMs += 1000
                                Log.d("TimerService", "Foreground app (counted): $foregroundApp, Usage: $continuousUsageMs")

                                if (continuousUsageMs >= workIntervalMillis) {
                                    Log.d("TimerService", "Work interval reached!")
                                    continuousUsageMs = 0
                                }
                            } else {
                                // Not counted: either not leisure or not inside home
                                continuousUsageMs = 0
                                Log.v("TimerService", "Ignored app (inside=$isInsideHome leisure=$isLeisure): $foregroundApp")
                            }
                        } else {
                            continuousUsageMs = 0 // Reset if no app in foreground or if it's the launcher
                        }


                    } else {
                        continuousUsageMs = 0 // Reset if screen is off
                    }
                } else {
                    continuousUsageMs = 0 // Reset usage if not tracking
                }
                _usageState.value = TimerUsageState(continuousUsageMs)
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
