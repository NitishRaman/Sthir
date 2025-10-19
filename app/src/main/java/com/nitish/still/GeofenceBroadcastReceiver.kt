package com.nitish.still

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_IS_INSIDE_HOME = "is_inside_home"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val geofencingEvent = GeofencingEvent.fromIntent(intent)
            if (geofencingEvent == null) {
                Log.e("GeofenceBR", "GeofencingEvent is null")
                return
            }

            if (geofencingEvent.hasError()) {
                val error = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
                Log.w("GeofenceBR", "Geofencing error: $error")
                return
            }

            val transition = geofencingEvent.geofenceTransition
            when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    Log.d("GeofenceBR", "Entered geofence, starting TimerService")
                    // start foreground service and mark Active
                    val svcIntent = Intent(context, TimerService::class.java).apply {
                        putExtra("state", "Active")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                }

                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    Log.d(
                        "GeofenceBR",
                        "Exited geofence, updating TimerService -> Inactive (and stopping)"
                    )
                    // update service to Inactive (so notification shows Inactive), then stop it
                    val updateIntent = Intent(context, TimerService::class.java).apply {
                        putExtra("state", "Inactive")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(updateIntent)
                    } else {
                        context.startService(updateIntent)
                    }

                    // optionally stop the service after a short delay (give it time to show Inactive)
                    // or stop immediately if you prefer no background work outside geofence:
                    val stopIntent = Intent(context, TimerService::class.java)
                    context.stopService(stopIntent)
                }

                else -> {
                    Log.d("GeofenceBR", "Unknown transition: $transition")
                }
            }
        } catch (t: Throwable) {
            Log.w("GeofenceBR", "onReceive error: ${t.message}")
        }
    }
}
