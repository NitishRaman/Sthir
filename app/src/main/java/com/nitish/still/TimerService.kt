package com.nitish.still

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TimerService", "Service started")
        trackUsage()
        return START_STICKY
    }

    private fun trackUsage() {
        serviceScope.launch {
            while (true) {
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
                if (foregroundApp != null) {
                    Log.d("TimerService", "Foreground app: $foregroundApp")
                }
                delay(1000)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}