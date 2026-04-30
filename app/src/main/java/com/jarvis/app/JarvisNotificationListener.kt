package com.jarvis.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.ArrayDeque

class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_NEW_NOTIFICATION = "com.jarvis.app.NEW_NOTIFICATION"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_APP = "app"

        // App package -> friendly name
        val WATCHED_PACKAGES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Messages",
            "com.instagram.android" to "Instagram",
            "com.google.android.gm" to "Gmail"
        )

        data class NotificationItem(
            val timestamp: Long,
            val sender: String,
            val message: String,
            val app: String,
            val packageName: String,
            val contentIntent: PendingIntent?
        )

        private val recent = ArrayDeque<NotificationItem>()
        private val lock = Any()

        fun snapshot(): List<NotificationItem> = synchronized(lock) { recent.toList() }

        fun lastMessage(): NotificationItem? = synchronized(lock) { recent.lastOrNull() }

        fun lastN(n: Int): List<NotificationItem> = synchronized(lock) {
            recent.toList().takeLast(n)
        }

        fun findBySender(name: String): NotificationItem? = synchronized(lock) {
            val q = name.lowercase().trim()
            if (q.isEmpty()) return null
            recent.toList().reversed().firstOrNull { it.sender.lowercase().contains(q) }
        }

        fun clear() = synchronized(lock) { recent.clear() }

        private fun add(item: NotificationItem) = synchronized(lock) {
            recent.addLast(item)
            while (recent.size > 20) recent.removeFirst()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val appName = WATCHED_PACKAGES[pkg] ?: return

        val extras = sbn.notification?.extras ?: return
        val sender = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: "Unknown"
        val message = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        if (message.isBlank()) {
            Log.e("JARVIS_CMD", "Notification skipped (empty body) from $appName / $sender")
            return
        }

        // Filter out non-message system notifications (e.g. WhatsApp "Checking for new messages")
        val lowerMsg = message.lowercase()
        if (lowerMsg.contains("checking for new messages") ||
            lowerMsg.contains("backup in progress") ||
            sender.equals("WhatsApp", ignoreCase = true) ||
            sender.contains("new messages", ignoreCase = true)) {
            Log.e("JARVIS_CMD", "Notification skipped (system) from $appName: $sender — $message")
            return
        }

        val item = NotificationItem(
            timestamp = System.currentTimeMillis(),
            sender = sender,
            message = message,
            app = appName,
            packageName = pkg,
            contentIntent = sbn.notification?.contentIntent
        )
        add(item)
        Log.e("JARVIS_CMD", "Notification captured: [$appName] $sender: $message")

        try {
            sendBroadcast(Intent(ACTION_NEW_NOTIFICATION).apply {
                `package` = packageName
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_APP, appName)
            })
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Notification broadcast failed: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.e("JARVIS_CMD", "JarvisNotificationListener connected")
    }
}
