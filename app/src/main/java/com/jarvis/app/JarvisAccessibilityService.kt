package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    private var lastCaptureTime = 0L
    private val DEBOUNCE_MS = 500L

    companion object {
        var lastScreenText = ""
        var isServiceEnabled = false
        fun getCurrentScreenText(): String = lastScreenText
    }

    override fun onServiceConnected() {
        isServiceEnabled = true
        Log.e("JARVIS_CMD", "AccessibilityService: connected")
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < DEBOUNCE_MS) return
        lastCaptureTime = now

        try {
            val rootNode = rootInActiveWindow ?: return
            val text = StringBuilder()
            extractText(rootNode, text)
            val captured = text.toString().trim()
            if (captured.isNotEmpty()) {
                lastScreenText = captured.take(3000)
                android.util.Log.e("JARVIS_CMD", "AccessibilityService: captured ${captured.length} chars")
            }
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_CMD", "AccessibilityService error: ${e.message}")
        }
    }

    override fun onInterrupt() {
        isServiceEnabled = false
        Log.e("JARVIS_CMD", "AccessibilityService: interrupted")
    }

    override fun onDestroy() {
        isServiceEnabled = false
        super.onDestroy()
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null || sb.length > 3000) return
        node.text?.let { if (it.isNotBlank()) sb.append(it).append(" ") }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            extractText(node.getChild(i), sb)
        }
    }
}
