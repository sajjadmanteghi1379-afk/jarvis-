package com.jarvis.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JarvisState {
    IDLE,
    AWAITING_CMD,
    LISTENING,
    THINKING,
    SPEAKING,
    RESEARCHING,
    ERROR,
    BACKGROUND_ACTIVE
}

object JarvisStateManager {
    private val _state = MutableStateFlow(JarvisState.IDLE)
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    fun setState(state: JarvisState) {
        if (_state.value == state) return
        val previous = _state.value
        _state.value = state
        Log.e("JARVIS_CMD", "Jarvis state changed: $previous -> $state")
    }
}
