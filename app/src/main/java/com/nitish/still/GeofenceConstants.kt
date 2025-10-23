package com.nitish.still

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object GeofenceConstants {
    const val GEOFENCE_ID = "home_geofence"
    const val GEOFENCE_INTENT_ACTION = "com.nitish.still.GEOFENCE_EVENT"
    const val GEOFENCE_RADIUS_METERS = 500f

    fun createGeofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = GEOFENCE_INTENT_ACTION
            `package` = context.packageName
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
