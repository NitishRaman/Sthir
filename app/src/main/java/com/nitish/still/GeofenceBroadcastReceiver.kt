package com.nitish.still

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_IS_INSIDE_HOME = "is_inside_home"
    }

    override fun onReceive(context: Context, intent: Intent) {
        GeofencingEvent.fromIntent(intent)?.let { geofencingEvent ->
            if (geofencingEvent.hasError()) {
                Log.e("GeofenceReceiver", "Geofencing event has error: ${geofencingEvent.errorCode}")
                return@let // Exit the let block
            }

            val geofenceTransition = geofencingEvent.geofenceTransition

            if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER || geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                val isInsideHome = geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
                Log.d("GeofenceReceiver", "User is ${if (isInsideHome) "inside" else "outside"} home zone.")

                // Update application state
                (context.applicationContext as StillApplication).updateInsideHomeStatus(isInsideHome)

                // Start the service to track usage
                val serviceIntent = Intent(context, TimerService::class.java).apply {
                    putExtra(EXTRA_IS_INSIDE_HOME, isInsideHome)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } ?: run {
            Log.e("GeofenceReceiver", "Geofencing event is null. Intent may not be a valid geofencing intent.")
        }
    }
}
