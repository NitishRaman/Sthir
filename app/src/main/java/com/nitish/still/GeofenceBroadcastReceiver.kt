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
        private const val TAG = "GeofenceBR"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent == null) {
                Log.e(TAG, "onReceive: intent is null")
                return
            }

            // Log intent basics & extras (safe)
            val extrasMap = try {
                intent.extras?.keySet()?.associateWith { k -> intent.extras?.get(k) }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read intent extras: ${t.message}")
                null
            }
            Log.d(TAG, "onReceive action=${intent.action} package=${intent.`package`} extras=$extrasMap")

            // Try to parse a real GeofencingEvent (what Play Services sends)
            val geofencingEvent = GeofencingEvent.fromIntent(intent)
            if (geofencingEvent != null) {
                if (geofencingEvent.hasError()) {
                    val error = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
                    Log.w(TAG, "Geofencing error: $error (${geofencingEvent.errorCode})")
                    return
                }

                val transition = geofencingEvent.geofenceTransition
                val triggeringIds = geofencingEvent.triggeringGeofences?.map { it.requestId } ?: emptyList()
                Log.d(TAG, "GeofencingEvent parsed: transition=$transition triggeringIds=$triggeringIds")

                val isInside = when (transition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> {
                        Log.d(TAG, "ENTER geofence -> starting TimerService (isInside=true)")
                        startTimerService(context, true)
                        true
                    }
                    Geofence.GEOFENCE_TRANSITION_EXIT -> {
                        Log.d(TAG, "EXIT geofence -> updating TimerService (isInside=false)")
                        startTimerService(context, false)
                        false
                    }
                    else -> {
                        Log.d(TAG, "Unknown geofence transition: $transition")
                        return
                    }
                }

                // Update global app state immediately (StillApplication)
                val app = context.applicationContext as? StillApplication
                if (app != null) {
                    app.updateInsideHomeStatus(isInside)
                    Log.d(TAG, "Updated StillApplication.isInsideHome = $isInside")
                } else {
                    Log.w(TAG, "StillApplication instance not found; cannot update inside state")
                }

                return
            }

            // Robust fallback: tolerate boolean, String("true"/"false"), or Int (1/0)
            if (intent.hasExtra(EXTRA_IS_INSIDE_HOME)) {
                val raw = intent.extras?.get(EXTRA_IS_INSIDE_HOME)
                val isInsideManual = when (raw) {
                    is Boolean -> raw
                    is String -> raw.equals("true", ignoreCase = true) || raw == "1"
                    is Int -> raw != 0
                    is Long -> raw != 0L
                    else -> {
                        Log.w(TAG, "Fallback: EXTRA_IS_INSIDE_HOME present but unrecognized type=${raw?.javaClass?.simpleName}")
                        false
                    }
                }
                Log.d(TAG, "Fallback: received manual EXTRA_IS_INSIDE_HOME=$isInsideManual (testing path)")
                startTimerService(context, isInsideManual)

                val app = context.applicationContext as? StillApplication
                if (app != null) {
                    app.updateInsideHomeStatus(isInsideManual)
                    Log.d(TAG, "Updated StillApplication.isInsideHome (fallback) = $isInsideManual")
                } else {
                    Log.w(TAG, "StillApplication instance not found; cannot update inside state (fallback)")
                }
                return
            }


            // If we reach here, the intent wasn't a geofence event and had no manual test extra
            Log.w(TAG, "onReceive: Not a GeofencingEvent and no $EXTRA_IS_INSIDE_HOME extra found - ignoring intent")

        } catch (t: Throwable) {
            Log.w(TAG, "onReceive error: ${t.message}", t)
        }
    }

    private fun startTimerService(context: Context, isInside: Boolean) {
        try {
            val svcIntent = Intent(context, TimerService::class.java).apply {
                putExtra(EXTRA_IS_INSIDE_HOME, isInside)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
            Log.d(TAG, "Requested TimerService start/update with isInside=$isInside")
        } catch (t: Throwable) {
            Log.w(TAG, "startTimerService failed: ${t.message}", t)
        }
    }
}
