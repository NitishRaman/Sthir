package com.nitish.still

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.e(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        Log.e(TAG, "!!! GEOFENCE BROADCAST RECEIVED !!!")
        Log.e(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null, cannot process.")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "GeofencingEvent has error: ${geofencingEvent.errorCode}")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        Log.i(TAG, "Geofence transition type: $geofenceTransition")

        val app = context.applicationContext as StillApplication

        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.i(TAG, "Transition is ENTER. Setting isInsideHome to TRUE.")
            app.updateInsideHomeStatus(true)

            val serviceIntent = Intent(context, TimerService::class.java)
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "Started TimerService.")

        } else if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.i(TAG, "Transition is EXIT. Setting isInsideHome to FALSE.")
            app.updateInsideHomeStatus(false)

            val serviceIntent = Intent(context, TimerService::class.java)
            context.stopService(serviceIntent)
            Log.i(TAG, "Stopped TimerService.")
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val ACTION_GEOFENCE_EVENT = "com.nitish.still.ACTION_GEOFENCE_EVENT"
        const val KEY_IS_INSIDE_HOME = "is_inside_home"
    }
}
