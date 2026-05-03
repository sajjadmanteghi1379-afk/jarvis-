package com.jarvis.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class DiagnosticsSnapshot(
    val lastRawCommand: String = "",
    val lastNormalizedCommand: String = "",
    val lastIntent: String = "",
    val lastActionResult: String = ""
)

object JarvisDiagnostics {
    var snapshot by mutableStateOf(DiagnosticsSnapshot())
        private set

    fun updateCommand(raw: String, normalized: String, intent: String) {
        snapshot = snapshot.copy(
            lastRawCommand = raw,
            lastNormalizedCommand = normalized,
            lastIntent = intent
        )
    }

    fun updateIntent(intent: String) {
        snapshot = snapshot.copy(lastIntent = intent)
    }

    fun actionResult(result: String) {
        snapshot = snapshot.copy(lastActionResult = result)
        android.util.Log.e("JARVIS_CMD", "ACTION_RESULT $result")
    }
}
