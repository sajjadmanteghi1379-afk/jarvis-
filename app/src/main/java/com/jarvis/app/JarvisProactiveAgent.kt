package com.jarvis.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Background worker that runs every 15 minutes (WorkManager) to:
 *   - Alert about upcoming calendar events (next hour) with weather + traffic context
 *   - Warn on low battery + no charger detected for 30+ minutes
 *   - Surface forgotten reminders from memory
 *   - Fire morning briefing at 7am and evening summary at 9pm
 *   - Detect simple usage patterns (placeholder hook — extend later)
 *
 * Output is delivered via a heads-up notification (the foreground listener
 * service may also speak it if the app is in foreground).
 */
class JarvisProactiveAgent(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "jarvis_proactive_agent"
        const val CHANNEL_ID = "jarvis_proactive_channel"
        private const val NOTIFICATION_BASE_ID = 4000
        private const val PREFS = "jarvis_proactive_state"
        private const val KEY_LAST_LOW_BATTERY_TS = "last_low_batt_ts"
        private const val KEY_LAST_MORNING_DATE = "last_morning_date"
        private const val KEY_LAST_EVENING_DATE = "last_evening_date"
        private const val KEY_LAST_EVENT_ALERTED = "last_event_alerted_ids"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<JarvisProactiveAgent>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
            Log.e("JARVIS_CMD", "ProactiveAgent: scheduled (15min)")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            ensureChannel()
            val ctx = applicationContext
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val notes = mutableListOf<String>()

            // Calendar — events within next 60 minutes
            val upcoming = calendarUpcoming(ctx, now, now + 60 * 60_000L)
            val lastAlerted = prefs.getString(KEY_LAST_EVENT_ALERTED, "")?.split(",")?.toMutableSet() ?: mutableSetOf()
            for (ev in upcoming) {
                if (lastAlerted.contains(ev.id.toString())) continue
                val mins = ((ev.startMs - now) / 60_000).toInt().coerceAtLeast(0)
                val weather = safeWeather()
                notes.add("Reminder, sir: ${ev.title} in $mins minutes. $weather")
                lastAlerted.add(ev.id.toString())
            }
            // Trim memory of alerted IDs (keep last 50)
            val trimmed = lastAlerted.toList().takeLast(50)
            prefs.edit().putString(KEY_LAST_EVENT_ALERTED, trimmed.joinToString(",")).apply()

            // Battery — flag if low and no charger for 30+ minutes
            val batt = batteryInfo(ctx)
            if (batt.pct in 1..20 && !batt.charging) {
                val lastLow = prefs.getLong(KEY_LAST_LOW_BATTERY_TS, 0L)
                if (lastLow == 0L) {
                    prefs.edit().putLong(KEY_LAST_LOW_BATTERY_TS, now).apply()
                } else if (now - lastLow > 30 * 60_000L) {
                    notes.add("Battery is at ${batt.pct} percent and you haven't plugged in for 30+ minutes, sir.")
                    prefs.edit().putLong(KEY_LAST_LOW_BATTERY_TS, now).apply()
                }
            } else if (batt.charging || batt.pct > 30) {
                prefs.edit().remove(KEY_LAST_LOW_BATTERY_TS).apply()
            }

            // Forgotten reminders — memories tagged 'reminder'
            try {
                if (JarvisMemory.cacheSize() > 0) {
                    val rem = JarvisMemory.searchByKeywords("reminder", 3)
                        .filter { it.category == "reminder" || it.value.lowercase().contains("remind") }
                    if (rem.isNotEmpty()) {
                        val r = rem.first()
                        val ageH = ((now - r.timestamp) / 3_600_000L).toInt()
                        if (ageH in 1..72 && r.accessCount == 0) {
                            notes.add("You asked me to remind you, sir: ${r.value.take(100)}")
                        }
                    }
                }
            } catch (_: Exception) {}

            // Morning briefing 7am
            if (hour == 7 && prefs.getString(KEY_LAST_MORNING_DATE, "") != today) {
                val br = morningBriefing(ctx)
                if (br.isNotEmpty()) notes.add(br)
                prefs.edit().putString(KEY_LAST_MORNING_DATE, today).apply()
            }

            // Evening summary 9pm
            if (hour == 21 && prefs.getString(KEY_LAST_EVENING_DATE, "") != today) {
                val es = eveningSummary(ctx)
                if (es.isNotEmpty()) notes.add(es)
                prefs.edit().putString(KEY_LAST_EVENING_DATE, today).apply()
            }

            // Emit notifications
            notes.forEachIndexed { i, msg ->
                showNotification(ctx, "J.A.R.V.I.S Proactive", msg, NOTIFICATION_BASE_ID + i)
                logProactive(msg)
            }

            Log.e("JARVIS_CMD", "ProactiveAgent: tick ok — emitted ${notes.size} notes")
            Result.success()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "ProactiveAgent: error ${e.message}")
            Result.retry()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────
    private data class Event(val id: Long, val title: String, val startMs: Long)

    private fun calendarUpcoming(ctx: Context, fromMs: Long, toMs: Long): List<Event> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<Event>()
        try {
            val proj = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART
            )
            val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} != 1"
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, proj, sel,
                arrayOf(fromMs.toString(), toMs.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use {
                while (it.moveToNext()) {
                    out.add(Event(it.getLong(0), it.getString(1) ?: "event", it.getLong(2)))
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "ProactiveAgent: calendar error ${e.message}")
        }
        return out
    }

    private data class Battery(val pct: Int, val charging: Boolean)

    private fun batteryInfo(ctx: Context): Battery {
        return try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            Battery(pct, charging)
        } catch (e: Exception) { Battery(-1, false) }
    }

    private fun safeWeather(): String {
        if (OPENWEATHER_API_KEY == "YOUR_OPENWEATHER_KEY") return ""
        return try {
            val url = "https://api.openweathermap.org/data/2.5/weather?q=${Uri.encode(USER_CITY)}&appid=$OPENWEATHER_API_KEY&units=metric"
            val r = client.newCall(Request.Builder().url(url).build()).execute()
            if (!r.isSuccessful) return ""
            val j = JSONObject(r.body?.string() ?: "")
            val temp = j.getJSONObject("main").getDouble("temp").toInt()
            val desc = j.getJSONArray("weather").getJSONObject(0).getString("description")
            "Currently $temp°C and $desc."
        } catch (_: Exception) { "" }
    }

    private fun morningBriefing(ctx: Context): String {
        val sb = StringBuilder("Good morning, sir. ")
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = today.timeInMillis
        today.add(Calendar.DAY_OF_YEAR, 1)
        val end = today.timeInMillis
        val events = calendarUpcoming(ctx, start, end)
        sb.append(if (events.isEmpty()) "No events on the calendar today. "
                  else "${events.size} event(s) today, starting with ${events.first().title}. ")
        val w = safeWeather(); if (w.isNotEmpty()) sb.append(w).append(" ")
        val news = topHeadline()
        if (news.isNotEmpty()) sb.append("Top headline: ").append(news)
        return sb.toString().trim()
    }

    private fun eveningSummary(ctx: Context): String {
        val sb = StringBuilder("Evening, sir. ")
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = tomorrow.timeInMillis
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        val end = tomorrow.timeInMillis
        val events = calendarUpcoming(ctx, start, end)
        sb.append(if (events.isEmpty()) "Tomorrow looks clear. "
                  else "Tomorrow you have ${events.size} event(s), starting with ${events.first().title}. ")
        val w = safeWeather(); if (w.isNotEmpty()) sb.append("Weather: ").append(w)
        return sb.toString().trim()
    }

    private fun topHeadline(): String {
        if (NEWSAPI_KEY == "YOUR_NEWSAPI_KEY") return ""
        return try {
            val url = "https://newsapi.org/v2/top-headlines?apiKey=$NEWSAPI_KEY&pageSize=1&language=en&sources=the-guardian-uk,bbc-news"
            val r = client.newCall(
                Request.Builder().url(url).addHeader("User-Agent", "JarvisProactive/1.0").build()
            ).execute()
            if (!r.isSuccessful) return ""
            val arts = JSONObject(r.body?.string() ?: "").getJSONArray("articles")
            if (arts.length() == 0) "" else arts.getJSONObject(0).optString("title", "").take(140)
        } catch (_: Exception) { "" }
    }

    private fun ensureChannel() {
        val mgr = applicationContext.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Jarvis Proactive", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Proactive alerts from Jarvis (calendar, battery, briefings)."
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun showNotification(ctx: Context, title: String, body: String, id: Int) {
        try {
            val openIntent = PendingIntent.getActivity(
                ctx, id, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "ProactiveAgent: notify failed ${e.message}")
        }
    }

    private fun logProactive(msg: String) {
        try {
            FirebaseDatabase.getInstance().getReference("$FIREBASE_PATH/agent_log").push().setValue(
                mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "kind" to "proactive",
                    "message" to msg.take(300)
                )
            )
        } catch (_: Exception) {}
    }
}
