package com.jarvis.app

import android.util.Log

data class JarvisNotification(
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

object NotificationRepository {
    private const val MAX_RECENT = 50
    private val lock = Any()
    private val recent = mutableListOf<JarvisNotification>()

    fun add(notification: JarvisNotification) = synchronized(lock) {
        recent.add(notification)
        while (recent.size > MAX_RECENT) recent.removeAt(0)
        if (recent.size == 1 || recent.size % 5 == 0) {
            Log.e("JARVIS_CMD", "Notifications stored: ${recent.size}")
        }
    }

    fun latest(limit: Int): List<JarvisNotification> = synchronized(lock) {
        recent.takeLast(limit.coerceAtLeast(1)).asReversed()
    }

    fun latest(): List<JarvisNotification> = latest(5)

    fun count(): Int = synchronized(lock) { recent.size }

    fun clear() = synchronized(lock) { recent.clear() }

    fun last(): JarvisNotification? = synchronized(lock) { recent.lastOrNull() }

    fun findByTitle(title: String): JarvisNotification? = synchronized(lock) {
        val query = title.lowercase().trim()
        if (query.isEmpty()) return null
        recent.asReversed().firstOrNull { it.title.lowercase().contains(query) }
    }

    fun isNotificationReadCommand(lower: String): Boolean {
        val normalized = lower.trim().trimEnd('?', '.', '!')
        return normalized == "read my notifications" ||
            normalized == "read notifications" ||
            normalized == "read my notification" ||
            normalized == "show my notifications" ||
            normalized == "check notifications" ||
            normalized == "what are my notifications" ||
            normalized == "summarize notifications" ||
            normalized == "summarise notifications"
    }

    fun summarizeLatest(count: Int = 5): String {
        val items = latest(count)
        if (items.isEmpty()) return "You have no recent notifications."
        val parts = items.mapIndexed { index, item ->
            val title = item.title.ifBlank { item.appName }
            val text = item.text.ifBlank { "No message text" }.take(120)
            "${index + 1}. ${item.appName}: $title - $text"
        }
        return "Here are your latest ${items.size} notification${if (items.size == 1) "" else "s"}. ${parts.joinToString(". ")}."
    }
}
