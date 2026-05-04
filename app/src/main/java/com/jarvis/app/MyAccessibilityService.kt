package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

open class MyAccessibilityService : AccessibilityService() {

    private var lastCaptureTime = 0L
    private var lastLoggedCaptureLength = 0
    private var lastCaptureLogTime = 0L

    companion object {
        private const val TAG = "JARVIS_CMD"
        private const val DEBOUNCE_MS = 300L
        private const val MAX_CHARS = 5_000

        @Volatile
        var isServiceEnabled = false

        fun getCurrentScreenText(): String = ScreenContentRepository.currentText()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        Log.e(TAG, "MyAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < DEBOUNCE_MS) return
        lastCaptureTime = now

        try {
            val parts = linkedSetOf<String>()

            // Required direct event sources.
            event.text
                ?.mapNotNull { it?.toString()?.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { parts.add(it) }
            event.source?.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { parts.add(it) }

            // The active window tree gives the assistant useful surrounding context.
            event.source?.let { extractText(it, parts) }
            rootInActiveWindow?.let { extractText(it, parts) }

            val captured = parts.joinToString("\n").trim()
            if (captured.isNotEmpty()) {
                ScreenContentRepository.update(captured, event.packageName?.toString())
                if (kotlin.math.abs(captured.length - lastLoggedCaptureLength) >= 120 ||
                    now - lastCaptureLogTime >= 10_000L
                ) {
                    Log.e(TAG, "Accessibility captured ${captured.length} chars")
                    lastLoggedCaptureLength = captured.length
                    lastCaptureLogTime = now
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Accessibility capture failed: ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        isServiceEnabled = false
        Log.e(TAG, "MyAccessibilityService interrupted")
    }

    override fun onDestroy() {
        isServiceEnabled = false
        super.onDestroy()
    }

    private fun extractText(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null || out.sumOf { it.length } > MAX_CHARS) return

        node.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { out.add(it) }

        for (i in 0 until node.childCount) {
            extractText(node.getChild(i), out)
        }
    }
}
