package com.jarvis.app

import android.util.Log
import java.util.Locale

object CommandNormalizer {
    private val phraseCorrections = listOf(
        "what's up" to "whatsapp",
        "whats up" to "whatsapp",
        "what sup" to "whatsapp",
        "whatsap" to "whatsapp",
        "what app" to "whatsapp",
        "whatsup" to "whatsapp",
        "a spotify" to "spotify",
        "spot if i" to "spotify",
        "spotty fly" to "spotify",
        "tele gram" to "telegram",
        "apex located" to "apex locator",
        "up next locator" to "apex locator",
        "our picks locator" to "apex locator",
        "our pick locator" to "apex locator",
        "epics locator" to "apex locator",
        "epics located" to "apex locator",
        "composite banding" to "composite bonding",
        "glass on armor" to "glass ionomer"
    )

    private val intentPhrases = listOf(
        "research for" to "research",
        "search for" to "research",
        "make report about" to "research",
        "create pdf about" to "research",
        "make pdf about" to "research",
        "read screen" to "screen vision",
        "what do you see" to "screen vision",
        "summarize screen" to "screen vision",
        "read notifications" to "read notifications",
        "read my notifications" to "read notifications",
        "notification summary" to "read notifications"
    )

    fun normalize(raw: String): NormalizedCommand {
        val original = raw.trim()
        if (original.isBlank()) return NormalizedCommand(raw, "", "Unknown")

        var text = original
            .lowercase(Locale.getDefault())
            .replace(Regex("[\\u2018\\u2019]"), "'")
            .replace(Regex("[,!?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        text = removeSafeFillers(text)

        (phraseCorrections + intentPhrases).forEach { (wrong, right) ->
            text = text.replace(Regex("\\b${Regex.escape(wrong)}\\b"), right)
        }

        text = text
            .replace(Regex("\\b(open|launch|start)\\s+(a|an|the)\\s+"), "$1 ")
            .replace(Regex("\\b(open|launch)\\s+whatsapp\\b"), "open whatsapp")
            .replace(Regex("\\b(open|launch)\\s+spotify\\b"), "open spotify")
            .replace(Regex("\\s+"), " ")
            .trim()

        val intent = inferIntentHint(text)
        Log.e("JARVIS_CMD", "COMMAND_NORMALIZED raw='$original' normalized='$text' intent='$intent'")
        JarvisDiagnostics.updateCommand(raw = original, normalized = text, intent = intent)
        return NormalizedCommand(original, text, intent)
    }

    fun normalizeText(raw: String): String = normalize(raw).text

    private fun removeSafeFillers(text: String): String {
        var cleaned = text
        listOf("please", "could you", "can you", "would you", "jarvis please").forEach { filler ->
            cleaned = cleaned.replace(Regex("\\b${Regex.escape(filler)}\\b"), " ")
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun inferIntentHint(text: String): String = when {
        text.startsWith("open ") || text.startsWith("launch ") || text.startsWith("start ") -> "OpenApp"
        text == "settings" || text == "open settings" -> "Settings"
        text == "screen vision" || text.contains("screen vision") -> "ScreenVision"
        text == "read notifications" || text.contains("notification summary") -> "ReadNotifications"
        text.startsWith("research ") || text.contains(" report ") || text.contains(" pdf ") -> "Research"
        text.contains("calendar") || text.contains("schedule") || text.contains("agenda") -> "CalendarBrief"
        text == "brief me" || text.contains("daily brief") || text.contains("morning brief") -> "DailyBrief"
        text.contains("goodbye") || text.contains("shutdown jarvis") || text.contains("shut down jarvis") -> "Goodbye"
        text.contains("stop listening") || text.contains("sleep jarvis") -> "StopListening"
        else -> "Unknown"
    }
}
