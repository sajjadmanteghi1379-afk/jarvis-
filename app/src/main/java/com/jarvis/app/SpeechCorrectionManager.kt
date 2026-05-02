package com.jarvis.app

import android.util.Log

object SpeechCorrectionManager {
    private val phraseCorrections = listOf(
        "up next locator" to "apex locator",
        "epics located" to "apex locator",
        "epics locator" to "apex locator",
        "our picks locator" to "apex locator",
        "our pick locator" to "apex locator",
        "a spotify" to "spotify",
        "what's up" to "whatsapp",
        "whats up" to "whatsapp",
        "what app" to "whatsapp"
    )

    private val dentalCorrections = listOf(
        "a pics locator" to "apex locator",
        "apex located" to "apex locator",
        "composite reason" to "composite resin",
        "composite rising" to "composite resin",
        "dental in plant" to "dental implant",
        "dental implant" to "dental implant",
        "ginger vitus" to "gingivitis",
        "ginger bite us" to "gingivitis",
        "period on titus" to "periodontitis",
        "period and titus" to "periodontitis",
        "endo dontics" to "endodontics",
        "end of dontics" to "endodontics",
        "oral sergery" to "oral surgery",
        "inferior alveola nerve" to "inferior alveolar nerve",
        "inferior al viola nerve" to "inferior alveolar nerve"
    )

    fun normalizeCommand(raw: String): String {
        val original = raw.trim()
        if (original.isBlank()) return original

        var normalized = original
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

        (phraseCorrections + dentalCorrections).forEach { (wrong, right) ->
            normalized = normalized.replace(Regex("\\b${Regex.escape(wrong)}\\b"), right)
        }

        normalized = normalized
            .replace(Regex("\\bopen a spotify\\b"), "open spotify")
            .replace(Regex("\\bopen the spotify\\b"), "open spotify")
            .replace(Regex("\\bopen what's up\\b"), "open whatsapp")
            .replace(Regex("\\bopen whats up\\b"), "open whatsapp")
            .replace(Regex("\\bopen what app\\b"), "open whatsapp")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized != original) {
            Log.e("JARVIS_CMD", "Speech correction: '$original' -> '$normalized'")
        }
        return normalized
    }
}
