package com.jarvis.app

/**
 * Process-local store for the latest text visible to Jarvis' AccessibilityService.
 *
 * Accessibility services live outside the Activity lifecycle, so a small singleton keeps
 * command handlers, the agent, and UI code decoupled from the service implementation.
 */
object ScreenContentRepository {
    private const val MAX_CHARS = 5_000

    @Volatile
    private var latest: ScreenContentSnapshot = ScreenContentSnapshot()

    fun update(text: String, packageName: String? = null) {
        val normalized = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(MAX_CHARS)

        if (normalized.isNotBlank()) {
            latest = ScreenContentSnapshot(
                text = normalized,
                packageName = packageName.orEmpty(),
                capturedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun current(): ScreenContentSnapshot = latest

    fun currentText(maxAgeMs: Long = 30_000L): String {
        val snapshot = latest
        if (snapshot.text.isBlank()) return ""
        if (System.currentTimeMillis() - snapshot.capturedAtMs > maxAgeMs) return ""
        return snapshot.text
    }

    fun currentSummary(maxAgeMs: Long = 30_000L): String {
        val snapshot = latest
        if (snapshot.text.isBlank()) return ""
        if (System.currentTimeMillis() - snapshot.capturedAtMs > maxAgeMs) return ""

        val visibleItems = snapshot.text
            .lineSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { it.matches(Regex("\\d{1,2}:\\d{2}")) }
            .distinct()
            .take(5)
            .toList()

        val app = friendlyAppName(snapshot.packageName)
        val itemsText = if (visibleItems.isEmpty()) {
            "no readable items"
        } else {
            visibleItems.joinToString("; ")
        }
        return "You appear to be in $app. Important visible items: $itemsText."
    }

    private fun friendlyAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.instagram.android" -> "Instagram"
            "com.spotify.music" -> "Spotify"
            "com.google.android.youtube" -> "YouTube"
            "com.google.android.gm" -> "Gmail"
            "com.google.android.apps.maps" -> "Google Maps"
            "com.samsung.android.calendar" -> "Calendar"
            "com.sec.android.app.launcher" -> "the home screen"
            "com.android.settings" -> "Settings"
            else -> if (packageName.isBlank()) "the current screen" else packageName.substringAfterLast('.')
        }
    }
}

object ScreenContextStore {
    var latestScreenText: String
        get() = ScreenContentRepository.current().text
        set(value) = ScreenContentRepository.update(value)

    val latestUpdatedAt: Long
        get() = ScreenContentRepository.current().capturedAtMs
}

data class ScreenContentSnapshot(
    val text: String = "",
    val packageName: String = "",
    val capturedAtMs: Long = 0L
)
