package com.nitish.still

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class StillApplication : Application() {

    private val _isInsideHome = MutableStateFlow(false)
    val isInsideHome = _isInsideHome.asStateFlow()

    fun updateInsideHomeStatus(isInside: Boolean) {
        _isInsideHome.value = isInside
    }
}
