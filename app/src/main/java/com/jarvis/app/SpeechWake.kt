package com.jarvis.app

import java.util.Locale

data class WakeMatch(
    val detected: Boolean,
    val command: String = "",
    val phrase: String = ""
)

object SpeechWake {
    private val wakePhrases = listOf(
        "start listening jarvis",
        "jarvis are you there",
        "jarvis wake up",
        "wake up jarvis",
        "jarvis listen",
        "listen jarvis",
        "hello jarvis",
        "okay jarvis",
        "hey jarvis",
        "hey jervis",
        "hi jarvis",
        "hi jervis",
        "yo jarvis",
        "ok jarvis",
        "h jarvis",
        "hey sam",
        "hi sam",
        "jarviss",
        "jarves",
        "jarviz",
        "jervis",
        "jarvis",
        "s a m",
        "sam"
    ).map { normalizeSpeech(it) }.distinct().sortedByDescending { it.length }

    fun normalizeSpeech(input: String): String {
        val lower = input.lowercase(Locale.getDefault()).trim()
        val samCollapsed = lower
            .replace(Regex("\\bs\\s*[.\\-]?\\s*a\\s*[.\\-]?\\s*m\\b"), "sam")
        return samCollapsed
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isWakePhrase(text: String): Boolean = extractCommand(text).detected

    fun extractCommand(text: String): WakeMatch {
        val normalized = normalizeSpeech(text)
        if (normalized.isBlank()) return WakeMatch(false)

        for (phrase in wakePhrases) {
            val match = Regex("(^|\\s)${Regex.escape(phrase)}(\\s|$)").find(normalized) ?: continue
            val command = (normalized.substring(0, match.range.first) + " " +
                normalized.substring(match.range.last + 1))
                .replace(Regex("\\s+"), " ")
                .trim()
            return WakeMatch(true, command, phrase)
        }
        return WakeMatch(false, normalized)
    }
}

fun normalizeSpeech(input: String): String = SpeechWake.normalizeSpeech(input)

fun isWakePhrase(text: String): Boolean = SpeechWake.isWakePhrase(text)
