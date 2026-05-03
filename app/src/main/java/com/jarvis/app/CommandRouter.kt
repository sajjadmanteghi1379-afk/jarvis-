package com.jarvis.app

import android.util.Log
import java.util.Locale

object CommandRouter {
    data class AppRoute(val displayName: String, val packages: List<String>)

    val appAliases: Map<String, AppRoute> = mapOf(
        "whatsapp" to AppRoute("WhatsApp", listOf("com.whatsapp")),
        "telegram" to AppRoute("Telegram", listOf("org.telegram.messenger")),
        "spotify" to AppRoute("Spotify", listOf("com.spotify.music")),
        "chrome" to AppRoute("Chrome", listOf("com.android.chrome")),
        "youtube" to AppRoute("YouTube", listOf("com.google.android.youtube")),
        "maps" to AppRoute("Google Maps", listOf("com.google.android.apps.maps")),
        "google maps" to AppRoute("Google Maps", listOf("com.google.android.apps.maps")),
        "gmail" to AppRoute("Gmail", listOf("com.google.android.gm")),
        "calendar" to AppRoute("Calendar", listOf("com.samsung.android.calendar", "com.google.android.calendar")),
        "clock" to AppRoute("Clock", listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock")),
        "settings" to AppRoute("Settings", listOf("com.android.settings"))
    )

    fun route(command: NormalizedCommand): CommandIntent {
        val text = command.text
        val intent = when {
            text.isBlank() -> CommandIntent.Unknown(text)
            text == "read notifications" || text == "read my notifications" ||
                text == "notification summary" || text == "check notifications" -> CommandIntent.ReadNotifications
            text == "screen vision" || text == "read my screen" || text == "read screen" ||
                text.contains("screen vision") || text.contains("what do you see") -> CommandIntent.ScreenVision
            text == "brief me" || text.contains("daily brief") || text.contains("morning brief") -> CommandIntent.DailyBrief
            text.contains("what's on my calendar") || text.contains("whats on my calendar") ||
                text.contains("calendar today") || text.contains("my agenda") ||
                text.contains("today's schedule") -> CommandIntent.CalendarBrief
            text == "settings" || text == "open settings" || text == "jarvis settings" -> CommandIntent.Settings
            text.contains("goodbye jarvis") || text.contains("shutdown jarvis") ||
                text.contains("shut down jarvis") || text.contains("close jarvis") -> CommandIntent.Goodbye
            text.contains("stop listening") || text.contains("sleep jarvis") -> CommandIntent.StopListening
            isResearch(text) -> CommandIntent.Research(cleanResearchTopic(text), researchMode(text))
            else -> routeApp(text) ?: CommandIntent.Unknown(text)
        }
        Log.e("JARVIS_CMD", "COMMAND_ROUTED normalized='$text' intent='${intent.label()}'")
        JarvisDiagnostics.updateIntent(intent.label())
        return intent
    }

    fun route(raw: String): CommandIntent = route(CommandNormalizer.normalize(raw))

    fun appRoute(appName: String): AppRoute? = appAliases[appName.lowercase(Locale.getDefault()).trim()]

    fun isVagueResearchTopic(topic: String): Boolean {
        val lower = topic.lowercase(Locale.getDefault()).trim()
        val usefulWords = lower.split(Regex("\\s+")).filter { it.length > 2 }
        return lower.isBlank() ||
            lower in setOf("it", "this", "that", "stuff", "something", "topic", "report", "pdf") ||
            usefulWords.isEmpty()
    }

    private fun routeApp(text: String): CommandIntent? {
        val appText = text
            .removePrefix("open ")
            .removePrefix("launch ")
            .removePrefix("start ")
            .trim()
        val route = appAliases.entries.firstOrNull { (alias, _) ->
            appText == alias || appText.contains(alias)
        } ?: return null
        return CommandIntent.OpenApp(route.value.displayName)
    }

    private fun isResearch(text: String): Boolean =
        text.startsWith("research ") ||
            text.startsWith("search ") ||
            text.contains(" report ") ||
            text.contains(" pdf ")

    private fun researchMode(text: String): String = when {
        text.contains("send to pc") || text.contains("save to pc") -> "pc"
        text.contains("read it to me") || text.contains("read to me") -> "read"
        text.contains("tell me") -> "tell"
        else -> "pdf"
    }

    private fun cleanResearchTopic(text: String): String {
        return text
            .replace(Regex("\\b(research|search)\\b"), " ")
            .replace(Regex("\\b(make|create)\\b"), " ")
            .replace(Regex("\\b(pdf|report|about|for|on|topic|please|tell|me|read|it|to|pc|save|send)\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s/-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
