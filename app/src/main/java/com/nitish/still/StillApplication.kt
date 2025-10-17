package com.nitish.still

import android.app.Application
import android.content.Context
import com.nitish.still.GeofenceBroadcastReceiver.Companion.KEY_IS_INSIDE_HOME
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class StillApplication : Application() {

    private val prefs by lazy {
        getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
    }

    private val _isInsideHome = MutableStateFlow(false)
    val isInsideHome = _isInsideHome.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        _isInsideHome.value = prefs.getBoolean(KEY_IS_INSIDE_HOME, false)
    }

    fun updateInsideHomeStatus(isInside: Boolean) {
        // Persist the state for when the app restarts
        prefs.edit().putBoolean(KEY_IS_INSIDE_HOME, isInside).apply()
        // Update the flow to notify observers
        _isInsideHome.value = isInside
    }
}
