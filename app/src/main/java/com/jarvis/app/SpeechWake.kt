package com.jarvis.app

import java.util.Locale

data class WakeMatch(
    val detected: Boolean,
    val command: String = "",
    val phrase: String = ""
)

data class WakeParse(
    val detected: Boolean,
    val phrase: String,
    val command: String,
    val wakeOnly: Boolean
)

object SpeechWake {
    private val baseWakePhrases = listOf(
        "start listening jarvis",
        "jarvis are you there",
        "jarvis wake up",
        "wake up jarvis",
        "jarvis listen",
        "listen jarvis",
        "hello jarvis",
        "okay jarvis",
        "hey jarvis",
        "a jarvis",
        "hey jervis",
        "hey jar this",
        "hey jar vise",
        "hey jarviss",
        "hey service",
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
        "jar this",
        "her this",
        "jarvis",
        "s a m",
        "sam"
    ).map { normalizeSpeech(it) }.distinct().sortedByDescending { it.length }

    private val wakePhrases = baseWakePhrases

    fun normalizeSpeech(input: String): String {
        val lower = input.lowercase(Locale.getDefault()).trim()
        val samCollapsed = lower
            .replace(Regex("\\bs\\s*[.\\-]?\\s*a\\s*[.\\-]?\\s*m\\b"), "sam")
        return samCollapsed
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isWakePhrase(text: String): Boolean = parse(text).detected

    fun parse(text: String): WakeParse {
        val normalized = normalizeSpeech(text)
        if (normalized.isBlank()) return WakeParse(false, "", "", false)

        for (phrase in wakePhrases) {
            if (!isAllowedAliasUse(normalized, phrase)) continue
            if (normalized == phrase) return WakeParse(true, phrase, "", true)
            if (normalized.startsWith("$phrase ")) {
                val command = normalized.removePrefix(phrase).trim()
                if (phrase == "hey service" && hasAppOrServiceCommandContext(command)) continue
                return WakeParse(true, phrase, command, command.isBlank())
            }
        }

        return WakeParse(false, "", normalized, false)
    }

    fun extractCommand(text: String): WakeMatch {
        val parse = parse(text)
        return WakeMatch(parse.detected, parse.command, parse.phrase)
    }

    private fun isAllowedAliasUse(normalized: String, phrase: String): Boolean {
        return when (phrase) {
            "jar this" -> normalized == "jar this" ||
                normalized == "hey jar this" ||
                normalized == "hi jar this" ||
                normalized.startsWith("hey jar this ") ||
                normalized.startsWith("hi jar this ")
            "her this" -> normalized == "her this" && fuzzyWakeScore(normalized) >= 0.80
            "hey service" -> normalized == "hey service" ||
                (normalized.startsWith("hey service ") &&
                    !hasAppOrServiceCommandContext(normalized.removePrefix("hey service").trim()))
            else -> true
        }
    }

    private fun hasAppOrServiceCommandContext(command: String): Boolean {
        if (command.isBlank()) return false
        val contextWords = listOf(
            "open", "launch", "start", "app", "application", "service",
            "services", "settings", "accessibility", "notification"
        )
        return contextWords.any { command == it || command.startsWith("$it ") || command.contains(" $it ") }
    }

    private fun fuzzyWakeScore(normalized: String): Double {
        val compact = normalized.replace(" ", "")
        val canonical = when (compact) {
            "jarthis", "jerviss", "jarvise", "herthis" -> "jarvis"
            else -> compact
        }
        val distance = levenshtein(canonical, "jarvis")
        val maxLen = maxOf(canonical.length, "jarvis".length).coerceAtLeast(1)
        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }

    private fun levenshtein(a: String, b: String): Int {
        val costs = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = costs[0]
            costs[0] = i
            for (j in 1..b.length) {
                val current = costs[j]
                costs[j] = minOf(
                    costs[j] + 1,
                    costs[j - 1] + 1,
                    previous + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                previous = current
            }
        }
        return costs[b.length]
    }
}

fun normalizeSpeech(input: String): String = SpeechWake.normalizeSpeech(input)

fun isWakePhrase(text: String): Boolean = SpeechWake.isWakePhrase(text)
