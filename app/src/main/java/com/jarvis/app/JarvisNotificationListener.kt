package com.jarvis.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_NEW_NOTIFICATION = "com.jarvis.app.NEW_NOTIFICATION"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_APP = "app"

        val WATCHED_PACKAGES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages",
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

        fun snapshot(): List<NotificationItem> = NotificationRepository.latest(50)
            .asReversed()
            .map { it.toNotificationItem() }

        fun lastMessage(): NotificationItem? = NotificationRepository.last()?.toNotificationItem()

        fun lastN(n: Int): List<NotificationItem> = NotificationRepository.latest(n)
            .asReversed()
            .map { it.toNotificationItem() }

        fun findBySender(name: String): NotificationItem? = NotificationRepository.findByTitle(name)?.toNotificationItem()

        fun clear() = NotificationRepository.clear()

        private fun JarvisNotification.toNotificationItem(): NotificationItem {
            return NotificationItem(
                timestamp = timestamp,
                sender = title,
                message = text,
                app = appName,
                packageName = packageName,
                contentIntent = null
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val appName = WATCHED_PACKAGES[pkg] ?: resolveAppName(pkg)
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && text.isBlank()) {
            Log.e("JARVIS_CMD", "Notification skipped (empty content) from $appName")
            return
        }

        val lowerText = text.lowercase()
        if (lowerText.contains("checking for new messages") ||
            lowerText.contains("backup in progress") ||
            title.equals("WhatsApp", ignoreCase = true) ||
            title.contains("new messages", ignoreCase = true)
        ) {
            Log.e("JARVIS_CMD", "Notification skipped (system) from $appName: $title - $text")
            return
        }

        NotificationRepository.add(
            JarvisNotification(
                appName = appName,
                packageName = pkg,
                title = title.ifBlank { appName },
                text = text,
                timestamp = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        )
        Log.e("JARVIS_CMD", "Notification captured: [$appName] $title: $text")

        if (!WATCHED_PACKAGES.containsKey(pkg) || text.isBlank()) return

        try {
            sendBroadcast(Intent(ACTION_NEW_NOTIFICATION).apply {
                `package` = packageName
                putExtra(EXTRA_SENDER, title.ifBlank { appName })
                putExtra(EXTRA_MESSAGE, text)
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

    private fun resolveAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Exception) {
            packageName
        }
    }
}
