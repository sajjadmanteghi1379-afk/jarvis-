package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.*

class JarvisProactive(private val context: Context, private val speak: (String) -> Unit) {

    private var monitorJob: Job? = null
    private var monitorStarted = false

    fun startMonitoring() {
        if (monitorStarted) {
            Log.e("JARVIS_CMD", "ProactiveMonitor: already running, ignoring duplicate start")
            return
        }
        monitorStarted = true
        monitorJob?.cancel()
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            delay(5 * 60 * 1000L)
            while (isActive) {
                try { checkAll() } catch (e: Exception) {
                    Log.e("JARVIS_CMD", "ProactiveMonitor: error ${e.message}")
                }
                delay(5 * 60 * 1000L)
            }
        }
        Log.e("JARVIS_CMD", "ProactiveMonitor: started 5-minute monitoring loop")
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorStarted = false
        Log.e("JARVIS_CMD", "ProactiveMonitor: stopped")
    }

    private suspend fun checkAll() {
        val alerts = mutableListOf<String>()
        checkCalendarReminders(alerts)
        checkBattery(alerts)
        if (alerts.isNotEmpty()) {
            val message = alerts.joinToString(" ")
            withContext(Dispatchers.Main) {
                Log.e("JARVIS_CMD", "ProactiveMonitor: alert -> $message")
                speak(message)
            }
        }
    }

    private fun checkCalendarReminders(alerts: MutableList<String>) {
        try {
            val now = System.currentTimeMillis()
            val in30Min = now + 30 * 60 * 1000L
            val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART)
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
                    "${CalendarContract.Events.DTSTART} <= ? AND " +
                    "${CalendarContract.Events.DELETED} != 1"
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection,
                arrayOf(now.toString(), in30Min.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "event"
                    val start = it.getLong(1)
                    val minsLeft = ((start - now) / 60000).toInt()
                    alerts.add("Reminder sir, $title in $minsLeft minutes.")
                    Log.e("JARVIS_CMD", "ProactiveMonitor: calendar alert '$title' in ${minsLeft}min")
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "ProactiveMonitor: calendar check error ${e.message}")
        }
    }

    private fun checkBattery(alerts: MutableList<String>) {
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            if (!isCharging && level in 1..15 && scale > 0) {
                val pct = level * 100 / scale
                alerts.add("Battery critical at $pct percent sir, please charge now.")
                Log.e("JARVIS_CMD", "ProactiveMonitor: battery critical at $pct%")
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "ProactiveMonitor: battery check error ${e.message}")
        }
    }
}
