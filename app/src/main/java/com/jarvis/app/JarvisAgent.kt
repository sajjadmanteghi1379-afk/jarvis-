package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Bridge to MainActivity capabilities the agent needs (Activity context,
 * voice I/O, screen capture, etc.). Agent calls back into the host so it
 * can stay decoupled from the Compose layer.
 */
interface AgentHost {
    val activity: Activity
    fun speakInterim(text: String)
    fun speakAndListenForYesNo(prompt: String, onResult: (Boolean) -> Unit)
    fun launchScreenCapture(onResult: (String) -> Unit)
}

/**
 * Anthropic tool-use agentic loop. Up to MAX_ITERATIONS round-trips with
 * Claude. Each iteration: send messages+tools, parse content blocks,
 * execute tool_use blocks in parallel, append tool_result, repeat until
 * Claude returns plain text.
 */
class JarvisAgent(
    private val context: Context,
    private val host: AgentHost
) {

    companion object {
        private const val MODEL = "claude-opus-4-7"
        private const val MAX_ITERATIONS = 25
        private const val CACHE_TTL_MS = 60_000L
        private const val AGENT_LOG_PATH = "/jarvis/agent_log"

        private val SYSTEM_PROMPT = """
You are J.A.R.V.I.S., Tony Stark's AI assistant — sophisticated, proactive, witty, and resourceful.
You have access to powerful tools that let you control the phone, access the internet, manage information,
and execute multi-step plans. Always think 3 steps ahead. Anticipate the user's needs. Be concise but charming.
Address the user as 'sir'.

DECISION RULES:
- For irreversible actions (sending messages, calls, purchases, deleting memories, calendar events) you MUST
  first call confirm_action(description) and only proceed if it returns "yes".
- For reversible actions (opening apps, searching, fetching info, navigating, alarms, settings) act decisively.
- Use multiple tools in sequence — and in parallel where possible — to fully solve complex requests.
- If a request is ambiguous, make reasonable assumptions. Only call ask_user as a last resort.
- Final reply: 1-3 short voice-friendly sentences. No markdown, no asterisks, no bullet points.
- When you are about to call several independent read-only tools, return them all in the SAME turn so
  they execute in parallel.
""".trimIndent()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Tool result cache: key = "tool|json", value = (result, expiryMs)
    private val cache = mutableMapOf<String, Pair<String, Long>>()

    /**
     * Run the agent loop. `onUpdate` is called with short interim status
     * messages between iterations; `onFinal` is called once with the
     * final user-facing response.
     */
    suspend fun run(
        userInput: String,
        history: List<ChatMessage>,
        onUpdate: (String) -> Unit,
        onFinal: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val toolsUsed = mutableListOf<String>()

        try {
            // Build initial messages — last 10 exchanges for context
            val messages = JSONArray()
            history.takeLast(10).forEach { m ->
                if (m.role == "YOU" || m.role == "JARVIS") {
                    messages.put(JSONObject().apply {
                        put("role", if (m.role == "JARVIS") "assistant" else "user")
                        put("content", m.content)
                    })
                }
            }
            messages.put(JSONObject().apply {
                put("role", "user"); put("content", userInput)
            })

            var finalText = "I encountered a difficulty completing that, sir."
            for (iter in 1..MAX_ITERATIONS) {
                Log.e("JARVIS_CMD", "AGENT iter=$iter messages=${messages.length()}")
                val response = callClaude(messages)
                if (response == null) {
                    finalText = "Connection to my reasoning core failed, sir."
                    break
                }
                val contentArr = response.optJSONArray("content") ?: JSONArray()
                val toolUseBlocks = mutableListOf<JSONObject>()
                val textBlocks = mutableListOf<String>()
                for (i in 0 until contentArr.length()) {
                    val block = contentArr.getJSONObject(i)
                    when (block.optString("type")) {
                        "tool_use" -> toolUseBlocks.add(block)
                        "text" -> textBlocks.add(block.optString("text"))
                    }
                }

                // Append assistant turn (must include the FULL content array)
                messages.put(JSONObject().apply {
                    put("role", "assistant"); put("content", contentArr)
                })

                if (toolUseBlocks.isEmpty()) {
                    finalText = textBlocks.joinToString(" ").trim()
                        .replace("**", "").replace("*", "")
                        .ifEmpty { "Done, sir." }
                    Log.e("JARVIS_CMD", "AGENT done: '$finalText'")
                    break
                }

                // Stream interim "thinking out loud"
                if (textBlocks.isNotEmpty()) {
                    val interim = textBlocks.joinToString(" ").trim()
                        .replace("**", "").replace("*", "")
                    if (interim.isNotEmpty()) {
                        Log.e("JARVIS_CMD", "AGENT interim: $interim")
                        withContext(Dispatchers.Main) { onUpdate(interim) }
                    }
                }

                // Execute all tool calls in parallel
                val results: List<Pair<String, String>> = coroutineScope {
                    val deferreds: List<Deferred<Pair<String, String>>> = toolUseBlocks.map { block ->
                        async {
                            val name = block.optString("name")
                            val input = block.optJSONObject("input") ?: JSONObject()
                            val id = block.optString("id")
                            toolsUsed.add(name)
                            Log.e("JARVIS_CMD", "AGENT tool=$name input=$input")
                            val result = try {
                                executeTool(name, input)
                            } catch (e: Exception) {
                                Log.e("JARVIS_CMD", "AGENT tool error $name: ${e.message}")
                                "Tool $name failed: ${e.message?.take(80)}"
                            }
                            id to result
                        }
                    }
                    deferreds.awaitAll()
                }

                // Append tool_result user turn
                val toolResultContent = JSONArray()
                results.forEach { (id, result) ->
                    toolResultContent.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", result)
                    })
                }
                messages.put(JSONObject().apply {
                    put("role", "user"); put("content", toolResultContent)
                })
            }

            withContext(Dispatchers.Main) { onFinal(finalText) }
            logAgentRun(userInput, toolsUsed, finalText, System.currentTimeMillis() - startedAt)
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "AGENT loop fatal: ${e.message}")
            withContext(Dispatchers.Main) { onFinal("Agent loop error, sir: ${e.message?.take(60)}") }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Claude API call (no streaming — interim updates are between turns)
    // ──────────────────────────────────────────────────────────────────
    private fun callClaude(messages: JSONArray): JSONObject? {
        return try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 2048)
                put("system", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text"); put("text", SYSTEM_PROMPT)
                        put("cache_control", JSONObject().apply { put("type", "ephemeral") })
                    })
                })
                put("tools", buildToolDefinitions())
                put("messages", messages)
            }
            val resp = client.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val raw = resp.body?.string() ?: return null
            if (!resp.isSuccessful) {
                Log.e("JARVIS_CMD", "AGENT API ${resp.code}: ${raw.take(300)}")
                return null
            }
            JSONObject(raw)
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "AGENT call exception: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tool dispatch
    // ──────────────────────────────────────────────────────────────────
    private suspend fun executeTool(name: String, input: JSONObject): String {
        // Check cache for read-only tools
        val cacheable = name in CACHEABLE_TOOLS
        if (cacheable) {
            val key = "$name|${input}"
            val hit = cache[key]
            if (hit != null && hit.second > System.currentTimeMillis()) {
                Log.e("JARVIS_CMD", "AGENT cache hit: $name")
                return hit.first
            }
        }

        val result: String = when (name) {
            // Information gathering
            "get_calendar_events" -> getCalendarEvents(input.optInt("days_ahead", 1))
            "get_weather" -> getWeather(input.optString("location", USER_CITY))
            "get_location" -> getLocation()
            "search_web" -> searchWeb(input.getString("query"))
            "deep_research" -> deepResearch(input.getString("topic"))
            "get_news" -> getNews(input.optString("category", "").ifEmpty { null })
            "read_memory" -> readMemory(input.optString("query", ""))
            "read_notifications" -> readNotifications(input.optInt("count", 5))
            "capture_screen" -> captureScreen()
            "get_battery_status" -> getBatteryStatus()
            "get_phone_state" -> getPhoneState()

            // Communication
            "send_whatsapp" -> sendWhatsApp(input.getString("contact"), input.getString("message"))
            "send_telegram" -> sendTelegram(input.getString("contact"), input.getString("message"))
            "send_sms" -> sendSms(input.getString("phone"), input.getString("message"))
            "make_call" -> makeCall(input.getString("contact"))
            "send_email" -> sendEmail(
                input.getString("to"),
                input.optString("subject", ""),
                input.optString("body", "")
            )

            // Phone control
            "open_app" -> openAppByName(input.getString("name"))
            "set_alarm" -> setAlarm(input.getString("time"), input.optString("label", "Jarvis Alarm"))
            "set_timer" -> setTimer(input.getInt("duration_seconds"))
            "set_volume" -> setVolume(
                input.optString("stream", "music"), input.getInt("percent")
            )
            "toggle_dnd" -> toggleDnd(input.getBoolean("enabled"))
            "toggle_wifi" -> toggleWifi(input.getBoolean("enabled"))
            "toggle_bluetooth" -> toggleBluetooth(input.getBoolean("enabled"))
            "set_brightness" -> setBrightness(input.getInt("percent"))
            "play_spotify" -> playSpotify(input.getString("query"))

            // Memory
            "save_memory" -> saveMemory(
                input.getString("key"),
                input.getString("value"),
                input.optString("category", "other")
            )
            "recall_context" -> recallContext(input.getString("topic"))

            // Location & travel
            "navigate_to" -> navigateTo(input.getString("destination"))
            "find_nearby" -> findNearby(input.getString("category"))
            "book_uber" -> bookUber(input.getString("destination"))
            "get_traffic" -> getTraffic(input.getString("destination"))

            // Productivity
            "create_note" -> createNote(input.getString("title"), input.getString("content"))
            "add_calendar_event" -> addCalendarEvent(
                input.getString("title"),
                input.getString("time"),
                input.optInt("duration", 60),
                input.optString("location", "")
            )
            "set_reminder" -> setReminder(input.getString("text"), input.getString("when"))

            // Self-reflection
            "ask_user" -> askUser(input.getString("question"))
            "confirm_action" -> confirmAction(input.getString("description"))

            else -> "Unknown tool: $name"
        }

        if (cacheable) cache["$name|${input}"] = result to (System.currentTimeMillis() + CACHE_TTL_MS)
        return result
    }

    // ──────────────────────────────────────────────────────────────────
    // Tool implementations
    // ──────────────────────────────────────────────────────────────────

    private fun getCalendarEvents(daysAhead: Int): String {
        if (!hasPerm(Manifest.permission.READ_CALENDAR)) return "Calendar permission not granted."
        val now = System.currentTimeMillis()
        val end = now + daysAhead * 24L * 60L * 60L * 1000L
        val proj = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY
        )
        val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DELETED} != 1"
        val out = mutableListOf<String>()
        try {
            val c = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, proj, sel,
                arrayOf(now.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )
            c?.use {
                val sdf = SimpleDateFormat("EEE MMM d HH:mm", Locale.getDefault())
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "Untitled"
                    val start = it.getLong(1)
                    val allDay = it.getInt(2) == 1
                    out.add(if (allDay) "$title (all day ${SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(Date(start))})"
                            else "$title at ${sdf.format(Date(start))}")
                }
            }
        } catch (e: Exception) { return "Calendar read failed: ${e.message}" }
        return if (out.isEmpty()) "No events in next $daysAhead day(s)." else out.joinToString("; ")
    }

    private suspend fun getWeather(location: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.openweathermap.org/data/2.5/weather?q=${Uri.encode(location)}&appid=$OPENWEATHER_API_KEY&units=metric"
            val r = client.newCall(Request.Builder().url(url).build()).execute()
            if (!r.isSuccessful) return@withContext "Weather API ${r.code}"
            val j = JSONObject(r.body?.string() ?: "")
            val temp = j.getJSONObject("main").getDouble("temp")
            val feels = j.getJSONObject("main").getDouble("feels_like")
            val desc = j.getJSONArray("weather").getJSONObject(0).getString("description")
            val hum = j.getJSONObject("main").getInt("humidity")
            val wind = j.getJSONObject("wind").getDouble("speed")
            // 24h forecast in same call would require /forecast — short summary fine here
            "Current in $location: ${temp.toInt()}°C, $desc. Feels like ${feels.toInt()}°C. Humidity $hum%. Wind ${wind.toInt()} m/s."
        } catch (e: Exception) { "Weather failed: ${e.message?.take(60)}" }
    }

    private fun getLocation(): String {
        if (!hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return "Location permission not granted."
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            var best: android.location.Location? = null
            for (p in providers) {
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best!!.accuracy) best = loc
            }
            if (best == null) return "No recent fix available."
            val lat = best.latitude; val lon = best.longitude
            val addr = try {
                Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
                    ?.firstOrNull()?.getAddressLine(0)
            } catch (_: Exception) { null }
            "Coordinates ${"%.4f".format(lat)}, ${"%.4f".format(lon)}. ${addr ?: "Address unavailable."}"
        } catch (e: Exception) { "Location failed: ${e.message?.take(60)}" }
    }

    private suspend fun searchWeb(query: String): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        try {
            val ddgUrl = "https://api.duckduckgo.com/?q=${Uri.encode(query)}&format=json&no_redirect=1&no_html=1"
            val r = client.newCall(
                Request.Builder().url(ddgUrl).addHeader("User-Agent", "JarvisAgent/1.0").build()
            ).execute()
            if (r.isSuccessful) {
                val j = JSONObject(r.body?.string() ?: "")
                val abs = j.optString("Abstract", ""); val ans = j.optString("Answer", "")
                if (abs.isNotEmpty()) out.append("DDG: ").append(abs).append(" ")
                if (ans.isNotEmpty()) out.append("DDG ans: ").append(ans).append(" ")
            }
        } catch (_: Exception) {}
        try {
            val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(query)}"
            val r = client.newCall(
                Request.Builder().url(wikiUrl).addHeader("User-Agent", "JarvisAgent/1.0").build()
            ).execute()
            if (r.isSuccessful) {
                val extract = JSONObject(r.body?.string() ?: "").optString("extract", "")
                if (extract.isNotEmpty()) out.append("Wiki: ").append(extract.take(800))
            }
        } catch (_: Exception) {}
        return@withContext out.toString().ifEmpty { "No web results found for '$query'." }.take(1500)
    }

    private suspend fun deepResearch(topic: String): String = withContext(Dispatchers.IO) {
        // Sync to PC research queue (existing pipeline) and return acknowledgement
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val safe = topic.replace(" ", "_").replace(Regex("[^A-Za-z0-9_]"), "")
            FirebaseDatabase.getInstance().getReference("$FIREBASE_PATH/pc_research_queue").push().setValue(
                mapOf(
                    "topic" to topic, "content" to "(deferred — agent-initiated)",
                    "timestamp" to ts, "filename" to "Jarvis_Research_${safe}_$ts.txt",
                    "source" to "Android-Agent"
                )
            )
            "Research request for '$topic' queued to PC. Will produce a PDF in your Documents folder."
        } catch (e: Exception) { "Research queue failed: ${e.message?.take(60)}" }
    }

    private suspend fun getNews(category: String?): String = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder("https://newsapi.org/v2/top-headlines?apiKey=$NEWSAPI_KEY&pageSize=5&language=en")
            if (category != null) sb.append("&category=$category&country=us")
            else sb.append("&sources=the-guardian-uk,bbc-news,reuters")
            val r = client.newCall(
                Request.Builder().url(sb.toString()).addHeader("User-Agent", "JarvisAgent/1.0").build()
            ).execute()
            if (!r.isSuccessful) return@withContext "News API ${r.code}"
            val arts = JSONObject(r.body?.string() ?: "").getJSONArray("articles")
            val list = mutableListOf<String>()
            for (i in 0 until minOf(arts.length(), 5)) {
                val t = arts.getJSONObject(i).optString("title", "").trim()
                if (t.isNotEmpty() && t != "[Removed]") list.add(t)
            }
            if (list.isEmpty()) "No headlines." else list.joinToString(" | ")
        } catch (e: Exception) { "News failed: ${e.message?.take(60)}" }
    }

    private fun readMemory(query: String): String {
        val hits = if (query.isBlank()) JarvisMemory.listGroupedByCategory(10).flatMap { it.value }
        else JarvisMemory.searchByKeywords(query, 8)
        if (hits.isEmpty()) return "No memories found for '$query'."
        return hits.joinToString("; ") { "[${it.category}] ${it.value.take(120)}" }
    }

    private fun readNotifications(count: Int): String {
        val items = JarvisNotificationListener.lastN(count.coerceIn(1, 20))
        if (items.isEmpty()) return "No recent notifications captured."
        return items.joinToString("; ") { "[${it.app}] ${it.sender}: ${it.message.take(120)}" }
    }

    private suspend fun captureScreen(): String = withContext(Dispatchers.Main) {
        val screenSummary = ScreenContentRepository.currentSummary()
        if (screenSummary.isNotBlank()) {
            Log.e("JARVIS_CMD", "Raw screen text: ${ScreenContentRepository.currentText().take(1000)}")
            return@withContext "$screenSummary\nRaw screen text is available in debug logs."
        }
        val deferred = CompletableDeferred<String>()
        host.launchScreenCapture { result ->
            if (!deferred.isCompleted) deferred.complete(result)
        }
        withTimeoutOrNull(15_000L) { deferred.await() } ?: "Screen capture timed out."
    }

    private fun getBatteryStatus(): String {
        return try {
            val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (scale > 0) level * 100 / scale else -1
            "Battery: $pct%, ${if (charging) "charging" else "on battery"}."
        } catch (e: Exception) { "Battery read failed: ${e.message?.take(60)}" }
    }

    private fun getPhoneState(): String {
        val sb = StringBuilder()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_SILENT -> "silent"
                else -> "unknown"
            }
            sb.append("Ringer: $ringer. ")
            val music = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            sb.append("Media volume: ${music * 100 / musicMax}%. ")
        } catch (_: Exception) {}
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val dnd = when (nm.currentInterruptionFilter) {
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority only"
                android.app.NotificationManager.INTERRUPTION_FILTER_NONE -> "total silence"
                android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms only"
                else -> "unknown"
            }
            sb.append("Do Not Disturb: $dnd. ")
        } catch (_: Exception) {}
        return sb.toString().trim().ifEmpty { "Phone state unavailable." }
    }

    private fun sendWhatsApp(contact: String, message: String): String {
        return try {
            val phone = resolveContactNumber(contact) ?: contact
            val cleanedPhone = phone.replace(Regex("[^+0-9]"), "")
            val uri = Uri.parse("https://wa.me/$cleanedPhone?text=${Uri.encode(message)}")
            host.activity.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "WhatsApp opened with prefilled message to $contact. User must tap send."
        } catch (e: Exception) { "WhatsApp send failed: ${e.message?.take(60)}" }
    }

    private fun sendTelegram(contact: String, message: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://msg?text=${Uri.encode(message)}")).apply {
                setPackage("org.telegram.messenger")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            host.activity.startActivity(intent)
            "Telegram opened with message draft for $contact."
        } catch (e: Exception) { "Telegram failed: ${e.message?.take(60)}" }
    }

    private fun sendSms(phone: String, message: String): String {
        if (!hasPerm(Manifest.permission.SEND_SMS)) {
            // Fall back to compose intent if permission denied
            return try {
                host.activity.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                        .putExtra("sms_body", message)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "SMS draft opened (SEND_SMS permission not granted)."
            } catch (e: Exception) { "SMS fallback failed: ${e.message?.take(60)}" }
        }
        return try {
            val sms = android.telephony.SmsManager.getDefault()
            sms.sendTextMessage(phone, null, message, null, null)
            "SMS sent to $phone."
        } catch (e: Exception) { "SMS send failed: ${e.message?.take(60)}" }
    }

    private fun makeCall(contact: String): String {
        return try {
            val phone = resolveContactNumber(contact) ?: contact
            val intent = Intent(
                if (hasPerm(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:$phone")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            host.activity.startActivity(intent)
            "Calling $contact at $phone."
        } catch (e: Exception) { "Call failed: ${e.message?.take(60)}" }
    }

    private fun sendEmail(to: String, subject: String, body: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).apply {
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            host.activity.startActivity(intent)
            "Email draft opened to $to."
        } catch (e: Exception) { "Email failed: ${e.message?.take(60)}" }
    }

    private fun openAppByName(name: String): String {
        val pkg = APP_NAME_TO_PACKAGE[name.lowercase()]
            ?: APP_NAME_TO_PACKAGE.entries.firstOrNull { it.key in name.lowercase() || name.lowercase() in it.key }?.value
            ?: return "No package mapping for '$name'."
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "App $pkg not installed."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            host.activity.startActivity(intent)
            "Opened $name."
        } catch (e: Exception) { "Open $name failed: ${e.message?.take(60)}" }
    }

    private fun setAlarm(timeStr: String, label: String): String {
        val (h, m) = parseTime(timeStr) ?: return "Could not parse time '$timeStr'. Use HH:MM."
        return try {
            host.activity.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Alarm set for ${"%02d:%02d".format(h, m)} ($label)."
        } catch (e: Exception) { "Alarm failed: ${e.message?.take(60)}" }
    }

    private fun setTimer(seconds: Int): String {
        return try {
            host.activity.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Timer set for $seconds seconds."
        } catch (e: Exception) { "Timer failed: ${e.message?.take(60)}" }
    }

    private fun setVolume(stream: String, percent: Int): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val s = when (stream.lowercase()) {
                "music", "media" -> AudioManager.STREAM_MUSIC
                "ring", "ringer" -> AudioManager.STREAM_RING
                "notification" -> AudioManager.STREAM_NOTIFICATION
                "alarm" -> AudioManager.STREAM_ALARM
                "voice", "call" -> AudioManager.STREAM_VOICE_CALL
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(s)
            val target = (percent.coerceIn(0, 100) * max / 100)
            am.setStreamVolume(s, target, 0)
            "$stream volume set to $percent%."
        } catch (e: Exception) { "Volume failed: ${e.message?.take(60)}" }
    }

    private fun toggleDnd(enabled: Boolean): String {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                host.activity.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return "DND access not granted — opened settings. Re-issue command after granting."
            }
            nm.setInterruptionFilter(
                if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
            "Do Not Disturb ${if (enabled) "enabled" else "disabled"}."
        } catch (e: Exception) { "DND failed: ${e.message?.take(60)}" }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(enabled: Boolean): String {
        // Android 10+ blocks programmatic WiFi toggle — open settings panel instead
        return try {
            host.activity.startActivity(
                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "WiFi panel opened — Android restricts direct toggle. Tap to ${if (enabled) "enable" else "disable"}."
        } catch (e: Exception) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.isWifiEnabled = enabled
                "WiFi set via legacy API: ${if (enabled) "on" else "off"}."
            } catch (e2: Exception) { "WiFi toggle failed: ${e2.message?.take(60)}" }
        }
    }

    private fun toggleBluetooth(enabled: Boolean): String {
        return try {
            host.activity.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Bluetooth settings opened. Tap to ${if (enabled) "enable" else "disable"}."
        } catch (e: Exception) { "Bluetooth toggle failed: ${e.message?.take(60)}" }
    }

    private fun setBrightness(percent: Int): String {
        return try {
            if (!Settings.System.canWrite(context)) {
                host.activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return "WRITE_SETTINGS not granted — opened settings. Re-issue after granting."
            }
            val v = (percent.coerceIn(0, 100) * 255 / 100)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            "Brightness set to $percent%."
        } catch (e: Exception) { "Brightness failed: ${e.message?.take(60)}" }
    }

    private fun playSpotify(query: String): String {
        return try {
            host.activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")).apply {
                    setPackage("com.spotify.music")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "Spotify opened searching for '$query'."
        } catch (e: Exception) { "Spotify failed: ${e.message?.take(60)}" }
    }

    private suspend fun saveMemory(key: String, value: String, category: String): String =
        withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<String>()
            JarvisMemory.remember("$key: $value") { entry ->
                if (entry != null) deferred.complete("Saved memory '$key' under [${entry.category}].")
                else deferred.complete("Memory save failed.")
            }
            withTimeoutOrNull(8_000L) { deferred.await() } ?: "Memory save timed out."
        }

    private suspend fun recallContext(topic: String): String = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<String>()
        JarvisMemory.semanticSearch(topic) { result -> deferred.complete(result) }
        withTimeoutOrNull(15_000L) { deferred.await() } ?: "Recall timed out."
    }

    private fun navigateTo(destination: String): String {
        return try {
            host.activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
                    .apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            )
            "Navigation started to $destination."
        } catch (e: Exception) {
            try {
                host.activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Maps opened for $destination."
            } catch (e2: Exception) { "Navigate failed: ${e2.message?.take(60)}" }
        }
    }

    private fun findNearby(category: String): String {
        return try {
            host.activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(category)}"))
                    .apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            )
            "Maps opened searching for nearby $category."
        } catch (e: Exception) { "Nearby search failed: ${e.message?.take(60)}" }
    }

    private fun bookUber(destination: String): String {
        return try {
            val uri = Uri.parse("uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=${Uri.encode(destination)}")
            host.activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Uber deep-link opened for $destination."
        } catch (e: Exception) {
            try {
                host.activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[formatted_address]=${Uri.encode(destination)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Uber web fallback opened for $destination."
            } catch (e2: Exception) { "Uber failed: ${e2.message?.take(60)}" }
        }
    }

    private fun getTraffic(destination: String): String {
        return try {
            host.activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=driving"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Traffic check opened to $destination — see ETA in Maps."
        } catch (e: Exception) { "Traffic failed: ${e.message?.take(60)}" }
    }

    private fun createNote(title: String, content: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, content)
                setPackage("com.samsung.android.app.notes")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            host.activity.startActivity(intent)
            "Samsung Notes opened with note '$title'."
        } catch (e: Exception) {
            try {
                host.activity.startActivity(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, content)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                "Note share sheet opened (Samsung Notes not found)."
            } catch (e2: Exception) { "Note failed: ${e2.message?.take(60)}" }
        }
    }

    private fun addCalendarEvent(title: String, timeStr: String, durationMin: Int, location: String): String {
        if (!hasPerm(Manifest.permission.WRITE_CALENDAR)) return "Calendar write permission not granted."
        val (h, m) = parseTime(timeStr) ?: return "Bad time '$timeStr'."
        return try {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val start = cal.timeInMillis
            val end = start + durationMin * 60_000L
            val result = CalendarActionHandler(context).addCalendarEvent(title, start, end, location)
            if (result.success) "Calendar event '$title' added at ${"%02d:%02d".format(h, m)}."
            else result.message
        } catch (e: Exception) { "Calendar add failed: ${e.message?.take(60)}" }
    }

    private suspend fun setReminder(text: String, whenStr: String): String {
        // Reminder = save to memory + try to set alarm if a time can be extracted
        val saveResult = saveMemory("reminder_${System.currentTimeMillis()}", "$text — $whenStr", "reminder")
        val timePair = parseTime(whenStr)
        return if (timePair != null) {
            val alarm = setAlarm(whenStr, "Reminder: ${text.take(40)}")
            "$saveResult $alarm"
        } else {
            saveResult
        }
    }

    private suspend fun askUser(question: String): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        host.speakAndListenForYesNo(question) { yes ->
            if (!deferred.isCompleted) deferred.complete(if (yes) "yes" else "no")
        }
        withTimeoutOrNull(20_000L) { deferred.await() } ?: "user did not respond"
    }

    private suspend fun confirmAction(description: String): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        host.speakAndListenForYesNo("Confirm: $description. Say yes or no, sir.") { yes ->
            if (!deferred.isCompleted) deferred.complete(if (yes) "yes" else "no")
        }
        withTimeoutOrNull(20_000L) { deferred.await() } ?: "no"
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────
    private fun hasPerm(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun resolveContactNumber(name: String): String? {
        if (name.matches(Regex("[+0-9 ()-]+"))) return name
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name)
            )
            val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            context.contentResolver.query(uri, proj, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun parseTime(s: String): Pair<Int, Int>? {
        val cleaned = s.lowercase().trim()
            .replace("am", "").replace("pm", "").trim()
        val m = Regex("(\\d{1,2})[:.](\\d{2})").find(cleaned)
        if (m != null) {
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val mi = m.groupValues[2].toIntOrNull() ?: return null
            val isPm = s.lowercase().contains("pm") && h < 12
            return Pair(if (isPm) h + 12 else h, mi)
        }
        val singleHour = Regex("^(\\d{1,2})$").find(cleaned)
        if (singleHour != null) {
            val h = singleHour.groupValues[1].toIntOrNull() ?: return null
            val isPm = s.lowercase().contains("pm") && h < 12
            return Pair(if (isPm) h + 12 else h, 0)
        }
        return null
    }

    private fun logAgentRun(input: String, tools: List<String>, output: String, durationMs: Long) {
        try {
            FirebaseDatabase.getInstance().getReference(AGENT_LOG_PATH).push().setValue(
                mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "input" to input,
                    "tools" to tools,
                    "output" to output.take(500),
                    "duration_ms" to durationMs
                )
            )
        } catch (e: Exception) { Log.e("JARVIS_CMD", "Agent log failed: ${e.message}") }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tool definitions for the API
    // ──────────────────────────────────────────────────────────────────
    private fun buildToolDefinitions(): JSONArray {
        val arr = JSONArray()
        // Helper to register a tool
        fun add(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) {
            arr.put(JSONObject().apply {
                put("name", name); put("description", desc)
                put("input_schema", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    if (required.isNotEmpty()) put("required", JSONArray(required))
                })
            })
        }
        fun strProp(desc: String) = JSONObject().apply { put("type", "string"); put("description", desc) }
        fun intProp(desc: String) = JSONObject().apply { put("type", "integer"); put("description", desc) }
        fun boolProp(desc: String) = JSONObject().apply { put("type", "boolean"); put("description", desc) }

        // Information gathering
        add("get_calendar_events", "Read calendar events for the next N days.",
            JSONObject().put("days_ahead", intProp("How many days ahead to read.")), listOf("days_ahead"))
        add("get_weather", "Get current weather (and brief context) for a location.",
            JSONObject().put("location", strProp("City or location. Optional — defaults to user's city.")))
        add("get_location", "Get user's current GPS coordinates and street address.", JSONObject())
        add("search_web", "Search the web (DuckDuckGo + Wikipedia) for a query.",
            JSONObject().put("query", strProp("Search query.")), listOf("query"))
        add("deep_research", "Queue a deep multi-source research task (returns PDF on PC).",
            JSONObject().put("topic", strProp("Topic to research.")), listOf("topic"))
        add("get_news", "Get top news headlines, optionally filtered by category.",
            JSONObject().put("category", strProp("Optional: business, entertainment, health, science, sports, technology.")))
        add("read_memory", "Search the global memory (Firebase) for entries matching a query.",
            JSONObject().put("query", strProp("Keyword to search; empty returns recent grouped memories.")))
        add("read_notifications", "Read the last N captured notifications from messaging apps.",
            JSONObject().put("count", intProp("How many recent notifications to return (1-20).")), listOf("count"))
        add("capture_screen", "Take a screenshot and have Claude Vision describe it.", JSONObject())
        add("get_battery_status", "Get current battery level and charging state.", JSONObject())
        add("get_phone_state", "Get ringer mode, volume, DND state.", JSONObject())

        // Communication (irreversible — agent must confirm_action first)
        add("send_whatsapp", "Open WhatsApp with a prefilled message to a contact.",
            JSONObject().apply {
                put("contact", strProp("Contact name or phone number."))
                put("message", strProp("Message body."))
            }, listOf("contact", "message"))
        add("send_telegram", "Open Telegram with a prefilled message.",
            JSONObject().apply {
                put("contact", strProp("Contact name (Telegram opens to recipient picker)."))
                put("message", strProp("Message body."))
            }, listOf("contact", "message"))
        add("send_sms", "Send an SMS to a phone number.",
            JSONObject().apply {
                put("phone", strProp("Recipient phone number with country code."))
                put("message", strProp("Message body."))
            }, listOf("phone", "message"))
        add("make_call", "Make a phone call to a contact or number.",
            JSONObject().put("contact", strProp("Contact name or phone number.")), listOf("contact"))
        add("send_email", "Open email composer with a prefilled draft.",
            JSONObject().apply {
                put("to", strProp("Recipient email address."))
                put("subject", strProp("Email subject."))
                put("body", strProp("Email body."))
            }, listOf("to"))

        // Phone control
        add("open_app", "Open an installed app by friendly name (whatsapp, instagram, spotify, calendar, etc.).",
            JSONObject().put("name", strProp("App name.")), listOf("name"))
        add("set_alarm", "Set an alarm at HH:MM.",
            JSONObject().apply {
                put("time", strProp("Time in HH:MM 24-hour format, or with am/pm."))
                put("label", strProp("Optional alarm label."))
            }, listOf("time"))
        add("set_timer", "Set a countdown timer in seconds.",
            JSONObject().put("duration_seconds", intProp("Total seconds.")), listOf("duration_seconds"))
        add("set_volume", "Set audio volume on a stream (music/ring/notification/alarm) to 0-100%.",
            JSONObject().apply {
                put("stream", strProp("music | ring | notification | alarm | voice"))
                put("percent", intProp("0-100"))
            }, listOf("stream", "percent"))
        add("toggle_dnd", "Enable or disable Do Not Disturb.",
            JSONObject().put("enabled", boolProp("true to enable DND.")), listOf("enabled"))
        add("toggle_wifi", "Open WiFi panel (Android restricts direct toggle).",
            JSONObject().put("enabled", boolProp("intended state")), listOf("enabled"))
        add("toggle_bluetooth", "Open Bluetooth settings.",
            JSONObject().put("enabled", boolProp("intended state")), listOf("enabled"))
        add("set_brightness", "Set screen brightness 0-100%.",
            JSONObject().put("percent", intProp("0-100")), listOf("percent"))
        add("play_spotify", "Search and play a song/artist/playlist on Spotify.",
            JSONObject().put("query", strProp("Search query.")), listOf("query"))

        // Memory
        add("save_memory", "Save a fact to global Firebase memory.",
            JSONObject().apply {
                put("key", strProp("Short identifier."))
                put("value", strProp("Full content of the memory."))
                put("category", strProp("location | preference | person | event | task | fact | relationship | project | reminder | other"))
            }, listOf("key", "value"))
        add("recall_context", "Semantic recall of memories matching a topic (uses Claude).",
            JSONObject().put("topic", strProp("Topic to recall.")), listOf("topic"))

        // Location & travel
        add("navigate_to", "Start Google Maps navigation to a destination.",
            JSONObject().put("destination", strProp("Address or place name.")), listOf("destination"))
        add("find_nearby", "Find nearby places of a category in Maps.",
            JSONObject().put("category", strProp("e.g. 'restaurant', 'pharmacy'.")), listOf("category"))
        add("book_uber", "Open Uber with prefilled destination.",
            JSONObject().put("destination", strProp("Destination address.")), listOf("destination"))
        add("get_traffic", "Get driving ETA to a destination.",
            JSONObject().put("destination", strProp("Destination address.")), listOf("destination"))

        // Productivity
        add("create_note", "Create a note in Samsung Notes (or share sheet fallback).",
            JSONObject().apply {
                put("title", strProp("Note title."))
                put("content", strProp("Note content."))
            }, listOf("title", "content"))
        add("add_calendar_event", "Create a calendar event.",
            JSONObject().apply {
                put("title", strProp("Event title."))
                put("time", strProp("Start time HH:MM (today or tomorrow if past)."))
                put("duration", intProp("Duration in minutes (default 60)."))
                put("location", strProp("Optional location."))
            }, listOf("title", "time"))
        add("set_reminder", "Save a reminder to memory; if 'when' is a time today, also set an alarm.",
            JSONObject().apply {
                put("text", strProp("What to remind."))
                put("when", strProp("When (HH:MM or descriptive)."))
            }, listOf("text", "when"))

        // Self-reflection
        add("ask_user", "Ask the user a clarifying yes/no question via voice (returns 'yes' or 'no').",
            JSONObject().put("question", strProp("Question to ask.")), listOf("question"))
        add("confirm_action", "REQUIRED before any irreversible action — speak the description and listen for yes/no.",
            JSONObject().put("description", strProp("Plain-English description of the action being confirmed.")), listOf("description"))

        return arr
    }
}

// Static lookup for app names
private val APP_NAME_TO_PACKAGE = mapOf(
    "whatsapp" to "com.whatsapp",
    "instagram" to "com.instagram.android",
    "telegram" to "org.telegram.messenger",
    "snapchat" to "com.snapchat.android",
    "spotify" to "com.spotify.music",
    "samsung notes" to "com.samsung.android.app.notes",
    "notes" to "com.samsung.android.app.notes",
    "browser" to "com.sec.android.app.sbrowser",
    "chrome" to "com.android.chrome",
    "gallery" to "com.sec.android.gallery3d",
    "camera" to "com.sec.android.app.camera",
    "files" to "com.sec.android.app.myfiles",
    "messages" to "com.samsung.android.messaging",
    "calculator" to "com.sec.android.app.popupcalculator",
    "youtube" to "com.google.android.youtube",
    "gmail" to "com.google.android.gm",
    "maps" to "com.google.android.apps.maps",
    "google maps" to "com.google.android.apps.maps",
    "play store" to "com.android.vending",
    "contacts" to "com.samsung.android.contacts",
    "phone" to "com.samsung.android.dialer",
    "dialer" to "com.samsung.android.dialer",
    "clock" to "com.sec.android.app.clockpackage",
    "calendar" to "com.samsung.android.calendar",
    "settings" to "com.android.settings",
    "samsung health" to "com.sec.android.app.shealth",
    "health" to "com.sec.android.app.shealth",
    "outlook" to "com.microsoft.office.outlook",
    "translate" to "com.google.android.apps.translate",
    "revolut" to "com.revolut.revolut",
    "voovo" to "com.voovo.app",
    "claude" to "com.anthropic.claude",
    "chatgpt" to "com.openai.chatgpt",
    "netflix" to "com.netflix.mediaclient",
    "twitter" to "com.twitter.android",
    "x" to "com.x.android",
    "tiktok" to "com.zhiliaoapp.musically",
    "linkedin" to "com.linkedin.android",
    "reddit" to "com.reddit.frontpage",
    "discord" to "com.discord",
    "notion" to "notion.id"
)

// Tools whose output is safe to cache for 60s
private val CACHEABLE_TOOLS = setOf(
    "get_weather", "get_location", "search_web", "get_news",
    "get_battery_status", "get_phone_state", "get_calendar_events"
)
