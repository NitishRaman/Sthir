package com.nitish.still

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class StillApplication : Application() {

    private val _isInsideHome = MutableStateFlow(false)
    val isInsideHome = _isInsideHome.asStateFlow()

    fun updateInsideHomeStatus(isInside: Boolean) {
        Log.d("StillApp", "updateInsideHomeStatus => $isInside (old=${_isInsideHome.value})")
        _isInsideHome.value = isInside
    }
}
