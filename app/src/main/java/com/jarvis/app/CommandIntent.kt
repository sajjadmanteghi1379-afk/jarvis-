package com.jarvis.app

sealed class CommandIntent {
    data class OpenApp(val appName: String) : CommandIntent()
    data class Research(val topic: String, val mode: String = "pdf") : CommandIntent()
    object ReadNotifications : CommandIntent()
    object ScreenVision : CommandIntent()
    object CalendarBrief : CommandIntent()
    object DailyBrief : CommandIntent()
    object StopListening : CommandIntent()
    object Goodbye : CommandIntent()
    object Settings : CommandIntent()
    data class Unknown(val cleanText: String) : CommandIntent()

    fun label(): String = when (this) {
        is OpenApp -> "OpenApp($appName)"
        is Research -> "Research(topic='$topic', mode='$mode')"
        ReadNotifications -> "ReadNotifications"
        ScreenVision -> "ScreenVision"
        CalendarBrief -> "CalendarBrief"
        DailyBrief -> "DailyBrief"
        StopListening -> "StopListening"
        Goodbye -> "Goodbye"
        Settings -> "Settings"
        is Unknown -> "Unknown($cleanText)"
    }
}

data class NormalizedCommand(
    val raw: String,
    val text: String,
    val intentHint: String
)
