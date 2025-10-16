package com.nitish.still

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "GeofencingEvent has error: ${geofencingEvent.errorCode}")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        val prefs = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)

        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.i(TAG, "User has entered the home geofence.")
            prefs.edit().putBoolean(KEY_IS_INSIDE_HOME, true).apply()
            sendGeofenceUpdate(context, true)

            val serviceIntent = Intent(context, TimerService::class.java)
            context.startForegroundService(serviceIntent)

        } else if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.i(TAG, "User has exited the home geofence.")
            prefs.edit().putBoolean(KEY_IS_INSIDE_HOME, false).apply()
            sendGeofenceUpdate(context, false)

            val serviceIntent = Intent(context, TimerService::class.java)
            context.stopService(serviceIntent)
        }
    }

    private fun sendGeofenceUpdate(context: Context, isInsideHome: Boolean) {
        val intent = Intent(ACTION_GEOFENCE_UPDATE).apply {
            putExtra(EXTRA_IS_INSIDE_HOME, isInsideHome)
            `package` = context.packageName
        }
        context.sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val ACTION_GEOFENCE_UPDATE = "com.nitish.still.ACTION_GEOFENCE_UPDATE"
        const val EXTRA_IS_INSIDE_HOME = "is_inside_home"
        const val KEY_IS_INSIDE_HOME = "is_inside_home"
    }
}
