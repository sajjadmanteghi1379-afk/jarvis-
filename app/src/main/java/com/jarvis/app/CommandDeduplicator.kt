package com.jarvis.app

import kotlin.math.max

enum class RecognitionSource {
    VOSK,
    ANDROID_STT
}

object CommandDeduplicator {
    private const val WINDOW_MS = 2_500L

    private var lastCanonical = ""
    private var lastRaw = ""
    private var lastTimestamp = 0L

    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun shouldIgnore(source: RecognitionSource, normalizedText: String, now: Long = System.currentTimeMillis()): Boolean {
        val canonical = canonicalize(normalizedText)
        if (canonical.isBlank()) return false

        val withinWindow = now - lastTimestamp <= WINDOW_MS
        val duplicate = withinWindow && (
            canonical == lastCanonical ||
                normalizedText == lastRaw ||
                similarity(canonical, lastCanonical) >= 0.86
            )

        if (duplicate) return true

        lastCanonical = canonical
        lastRaw = normalizedText
        lastTimestamp = now
        return false
    }

    private fun canonicalize(text: String): String {
        return text
            .replace(Regex("\\bwhat\\s+you\\s+the\\b"), "what is the")
            .replace(Regex("\\bwhat\\s+the\\b"), "what is the")
            .replace(Regex("\\bwhats\\s+the\\b"), "what is the")
            .replace(Regex("\\bwhat\\s+s\\s+up\\b"), "whatsapp")
            .replace(Regex("\\bwhat\\s+is\\s+up\\b"), "whatsapp")
            .replace(Regex("\\bwhats\\s+up\\b"), "whatsapp")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / max(a.length, b.length).toDouble()
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
