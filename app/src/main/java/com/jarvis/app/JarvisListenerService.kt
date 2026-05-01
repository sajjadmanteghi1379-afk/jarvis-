package com.jarvis.app

import android.app.*
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager

class JarvisListenerService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "jarvis_listener_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_COMMAND = "com.jarvis.app.COMMAND"
        const val ACTION_WAKE = "com.jarvis.app.WAKE"
        const val ACTION_SLEEP = "com.jarvis.app.SLEEP"
        const val EXTRA_COMMAND = "command"
        var isRunning = false
        var isAwake = false
        @Volatile var pendingVisionResult: String? = null
        @Volatile var visionResultReady: Boolean = false
        // MediaProjection storage — shared across service instances
        var storedResultCode: Int = 0
        var storedProjectionData: android.content.Intent? = null
        @Volatile var mediaProjectionInstance: android.media.projection.MediaProjection? = null
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listenIntent: Intent? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false
    private var isListening = false
    private var isStartingListening = false
    private var isMuted = false
    private var earlyExecuted = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var originalSystemVolume = 0
    private var originalNotificationVolume = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceRunning = false

    // ─── Self-voice / dedup / error-recovery bookkeeping ───────────────────
    private var lastCommandTimestamp: Long = 0L
    private var lastCommandText: String = ""
    private var lastErrorCode: Int = -1
    private var errorRepetitionCount: Int = 0
    private var errorWindowStart: Long = 0L
    private val commandHistory = ArrayDeque<Pair<Long, String>>()

    // ─── Self-voice echo suppression ─────────────────────────────────────────
    private val recentTtsOutputs = mutableListOf<String>()
    private var ttsEndTime = 0L
    private var lastSpokenText = ""

    // ─── Consecutive recognition error counter ────────────────────────────────
    private var consecutiveErrors = 0
    private var lastConsecutiveErrorTime = 0L
    private var lastRecognizerResumeLogMs = 0L

    // ─── Notification announcement bookkeeping ─────────────────────────────
    private var lastNotificationAnnounceMs: Long = 0L
    private var notificationReceiver: BroadcastReceiver? = null

    // ─── Conversation mode ──────────────────────────────────────────────────
    private var conversationActive = false
    private var conversationTimeout: Job? = null

    // ─── Recipe creation mode bookkeeping ─────────────────────────────────
    private var recipeCreationMode: Boolean = false
    private var pendingRecipeName: String = ""
    private val pendingRecipeSteps: MutableList<String> = mutableListOf()
    private var pendingCalendarDraft: CalendarEventDraft? = null

    // ─── App entry table (mirrors MainActivity) ────────────────────────────
    private data class AppEntry(val keywords: List<String>, val packages: List<String>, val name: String)

    private val apps = listOf(
        AppEntry(listOf("whatsapp", "whats app", "what's app"), listOf("com.whatsapp"), "WhatsApp"),
        AppEntry(listOf("instagram", "insta"), listOf("com.instagram.android"), "Instagram"),
        AppEntry(listOf("telegram"), listOf("org.telegram.messenger"), "Telegram"),
        AppEntry(listOf("snapchat", "snap chat"), listOf("com.snapchat.android"), "Snapchat"),
        AppEntry(listOf("samsung notes", "open notes", "my notes", "notes app",
            "create note", "new note", "write note", "open note"),
            listOf("com.samsung.android.app.notes"), "Samsung Notes"),
        AppEntry(listOf("samsung browser", "internet browser", "samsung internet"),
            listOf("com.sec.android.app.sbrowser"), "Samsung Browser"),
        AppEntry(listOf("open chrome", "google chrome", "launch chrome", "chrome browser"),
            listOf("com.android.chrome"), "Chrome"),
        AppEntry(listOf("gallery", "my photos", "photo app", "my pictures", "open gallery"),
            listOf("com.sec.android.gallery3d", "com.samsung.android.gallery3d"), "Gallery"),
        AppEntry(listOf("open camera", "camera app"), listOf("com.sec.android.app.camera"), "Camera"),
        AppEntry(listOf("my files", "file manager", "files app", "open files"),
            listOf("com.sec.android.app.myfiles", "com.samsung.android.myfiles"), "My Files"),
        AppEntry(listOf("open messages", "sms app", "samsung messages", "messaging app"),
            listOf("com.samsung.android.messaging"), "Messages"),
        AppEntry(listOf("open spotify", "spotify app", "launch spotify"),
            listOf("com.spotify.music"), "Spotify"),
        AppEntry(listOf("calculator", "calc app", "open calculator"),
            listOf("com.sec.android.app.popupcalculator"), "Calculator"),
        AppEntry(listOf("nytimes", "new york times", "nyt", "ny times"),
            listOf("com.nytimes.android"), "New York Times"),
        AppEntry(listOf("guardian", "the guardian"), listOf("com.guardian"), "The Guardian"),
        AppEntry(listOf("open youtube", "youtube app", "launch youtube"),
            listOf("com.google.android.youtube"), "YouTube"),
        AppEntry(listOf("gmail", "google mail", "open gmail"),
            listOf("com.google.android.gm"), "Gmail"),
        AppEntry(listOf("google maps", "maps app", "open maps"),
            listOf("com.google.android.apps.maps"), "Google Maps"),
        AppEntry(listOf("play store", "app store", "google play"),
            listOf("com.android.vending"), "Play Store"),
        AppEntry(listOf("contacts app", "my contacts", "open contacts"),
            listOf("com.samsung.android.contacts"), "Contacts"),
        AppEntry(listOf("phone app", "dialer app", "open dialer", "open phone"),
            listOf("com.samsung.android.dialer"), "Phone"),
        AppEntry(listOf("open clock", "clock app", "open alarm app", "alarm app"),
            listOf("com.sec.android.app.clockpackage"), "Clock"),
        AppEntry(listOf("open calendar", "calendar app", "samsung calendar"),
            listOf("com.samsung.android.calendar"), "Calendar"),
        AppEntry(listOf("open settings", "launch settings", "go to settings"),
            listOf("com.android.settings"), "Settings"),
        AppEntry(listOf("samsung health", "health app", "open health"),
            listOf("com.sec.android.app.shealth"), "Samsung Health"),
        AppEntry(listOf("outlook", "microsoft outlook", "open outlook"),
            listOf("com.microsoft.office.outlook"), "Outlook"),
        AppEntry(listOf("translate", "google translate", "open translate"),
            listOf("com.google.android.apps.translate"), "Google Translate"),
        AppEntry(listOf("revolut", "open revolut"), listOf("com.revolut.revolut"), "Revolut"),
        AppEntry(listOf("voovo", "open voovo"), listOf("com.voovo.app"), "Voovo"),
        AppEntry(listOf("netflix", "open netflix", "launch netflix"), listOf("com.netflix.mediaclient"), "Netflix"),
        AppEntry(listOf("twitter", "open twitter", "x app", "open x", "launch twitter"), listOf("com.twitter.android", "com.x.android"), "Twitter"),
        AppEntry(listOf("tiktok", "tik tok", "open tiktok", "open tik tok"), listOf("com.zhiliaoapp.musically"), "TikTok"),
        AppEntry(listOf("linkedin", "open linkedin", "linked in"), listOf("com.linkedin.android"), "LinkedIn"),
        AppEntry(listOf("reddit", "open reddit"), listOf("com.reddit.frontpage"), "Reddit"),
        AppEntry(listOf("discord", "open discord"), listOf("com.discord"), "Discord"),
        AppEntry(listOf("notion", "open notion", "launch notion"), listOf("notion.id", "so.notion.id"), "Notion")
    )

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening for 'Hey Jarvis'...", false))
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jarvis::ListenerWakeLock"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.e("JARVIS_CMD", "PARTIAL_WAKE_LOCK acquired (10 min)")
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "WakeLock acquire failed: ${e.message}")
        }
        Log.e("JARVIS_CMD", "JarvisListenerService started")
        tts = TextToSpeech(this, this)   // will call onInit when ready
        registerNotificationReceiver()
        try { RecipeManager.init(applicationContext) } catch (e: Exception) {
            Log.e("JARVIS_CMD", "RecipeManager init failed: ${e.message}")
        }
        try { JarvisMemory.init(applicationContext) } catch (e: Exception) {
            Log.e("JARVIS_CMD", "JarvisMemory init failed: ${e.message}")
        }
    }

    private fun registerNotificationReceiver() {
        val filter = IntentFilter(JarvisNotificationListener.ACTION_NEW_NOTIFICATION)
        notificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != JarvisNotificationListener.ACTION_NEW_NOTIFICATION) return
                val sender = intent.getStringExtra(JarvisNotificationListener.EXTRA_SENDER) ?: return
                val message = intent.getStringExtra(JarvisNotificationListener.EXTRA_MESSAGE) ?: return
                val app = intent.getStringExtra(JarvisNotificationListener.EXTRA_APP) ?: return
                handleIncomingNotification(sender, message, app)
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(notificationReceiver, filter)
            }
            Log.e("JARVIS_CMD", "Notification receiver registered")
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Notification receiver register failed: ${e.message}")
        }
    }

    private fun handleIncomingNotification(sender: String, message: String, app: String) {
        // Throttle: at most one announcement per 30s
        val now = System.currentTimeMillis()
        if (now - lastNotificationAnnounceMs < 30_000L) {
            Log.e("JARVIS_CMD", "Notification announce throttled (${now - lastNotificationAnnounceMs}ms): [$app] $sender")
            return
        }

        // Do-not-disturb: skip if ringer is silent
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.e("JARVIS_CMD", "Notification suppressed (ringer silent): [$app] $sender")
            return
        }

        // Only announce if screen is locked OR device is non-interactive (idle)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val locked = km.isKeyguardLocked
        val idle = !pm.isInteractive
        if (!locked && !idle) {
            Log.e("JARVIS_CMD", "Notification not announced (screen on & unlocked): [$app] $sender")
            return
        }

        // Don't speak over an active command/TTS session
        if (isSpeaking || isAwake) {
            Log.e("JARVIS_CMD", "Notification skipped (busy): [$app] $sender")
            return
        }

        val truncated = if (message.length > 140) message.take(140) + "…" else message
        lastNotificationAnnounceMs = now
        Log.e("JARVIS_CMD", "Announcing notification: [$app] $sender — $truncated")
        speakResponse("Sir, new message from $sender on $app: $truncated")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.85f)
            tts?.setPitch(0.75f)
            isTtsReady = true
            Log.e("JARVIS_CMD", "Service TTS ready")
        } else {
            Log.e("JARVIS_CMD", "Service TTS init failed")
        }
        muteRecognitionBeep()
        // Start listening after short delay regardless of TTS status
        scope.launch {
            delay(500)
            try { initAndStartListening() } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Startup crash: initAndStartListening: ${e.message}", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                "STOP" -> { isRunning = false; isServiceRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                ACTION_SLEEP -> {
                    isAwake = false
                    updateNotification("Listening for 'Hey Jarvis'...", false)
                    Log.e("JARVIS_CMD", "Service going to sleep mode")
                }
                "ACTION_STORE_PROJECTION_TOKEN" -> {
                    val code = intent.getIntExtra("projection_result_code", 0)
                    val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("projection_data", Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>("projection_data")
                    }
                    if (code != 0 && data != null) {
                        storedResultCode = code
                        storedProjectionData = data
                        Log.e("JARVIS_CMD", "Projection token stored in service")
                    }
                }
                else -> {
                    if (isServiceRunning) return START_STICKY
                    isServiceRunning = true
                    if (intent?.action == null) muteRecognitionBeep()
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Startup crash: ${e.message}", e)
        }
        return START_STICKY
    }

    private fun muteRecognitionBeep() {
        if (isMuted) return
        try {
            originalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
            originalNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
            isMuted = true
            Log.e("JARVIS_CMD", "Recognition beep muted")
        } catch (e: Exception) { Log.e("JARVIS_CMD", "Mute failed: ${e.message}") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isAwake = false
        isServiceRunning = false
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "WakeLock release failed: ${e.message}")
        }
        if (isMuted) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVolume, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotificationVolume, 0)
            } catch (e: Exception) {}
        }
        // Destroy recognizer only here — never inside the listening loop
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        try { notificationReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        notificationReceiver = null
        scope.cancel()
        Log.e("JARVIS_CMD", "Service destroyed — restarting")
        sendBroadcast(Intent("com.jarvis.app.RESTART_SERVICE").apply { `package` = packageName })
    }

    // ─── Speech recognizer (created ONCE, never recreated in loop) ──────────

    private fun initAndStartListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("JARVIS_CMD", "Speech recognition not available")
            return
        }
        if (speechRecognizer != null) {
            startListening()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        Log.e("JARVIS_CMD", "SpeechRecognizer created once — will reuse across cycles")

        listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                isStartingListening = false
                if (isSpeaking) {
                    Log.e("JARVIS_CMD", "Ignoring recognition result while speaking")
                    return
                }
                if (System.currentTimeMillis() - ttsEndTime < 2000) { Log.e("JARVIS_CMD", "Too soon after TTS, ignoring"); if (isRunning) scope.launch { delay(1000); startListening() }; return }
                if (earlyExecuted) { earlyExecuted = false; if (isRunning && !isSpeaking) scope.launch { delay(200); startListening() }; return }
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.lowercase() ?: ""
                Log.e("JARVIS_CMD", "Service heard: '$text'")
                processResult(text)
                if (isRunning && !isSpeaking) scope.launch { delay(200); startListening() }
            }
            override fun onError(error: Int) {
                isListening = false
                isStartingListening = false
                if (isSpeaking) {
                    Log.e("JARVIS_CMD", "Ignoring recognition error while speaking: $error")
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastConsecutiveErrorTime > 15000) consecutiveErrors = 0
                consecutiveErrors++
                lastConsecutiveErrorTime = now
                Log.e("JARVIS_CMD", "Service recognition error: $error (consecutive: $consecutiveErrors)")
                if (!isRunning || isSpeaking) return

                if (consecutiveErrors >= 5) {
                    Log.e("JARVIS_CMD", "Too many errors — rebuilding SpeechRecognizer")
                    consecutiveErrors = 0
                    rebuildRecognizer()
                    return
                }

                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                    Log.e("JARVIS_CMD", "ERROR_SERVER_DISCONNECTED — recreating recognizer")
                    consecutiveErrors = 0
                    rebuildRecognizer()
                    return
                }

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        scope.launch { delay(600); if (!isSpeaking && !isListening) startListening() }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        speechRecognizer?.cancel()
                        scope.launch { delay(1200); if (!isSpeaking && !isListening) startListening() }
                    }
                    else -> scope.launch { delay(800); if (!isSpeaking && !isListening) startListening() }
                }
            }
            override fun onEndOfSpeech() { isListening = false; isStartingListening = false }
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                isListening = true
                isStartingListening = false
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                if (isSpeaking) {
                    Log.e("JARVIS_CMD", "Ignoring partial recognition while speaking")
                    return
                }
                if (earlyExecuted) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase()?.trim() ?: return
                if (partial.length < 4) return
                val wakePatterns = listOf("hey jarvis", "ok jarvis", "okay jarvis", "hi jarvis", "jarvis")
                for (pattern in wakePatterns) {
                    if (partial.contains(pattern)) {
                        val cmd = partial.replace(pattern, "").trim()
                        if (cmd.length > 3) {
                            Log.e("JARVIS_CMD", "Partial early execute: '$cmd'")
                            earlyExecuted = true
                            isListening = false
                            speechRecognizer?.stopListening()
                            processResult(partial)
                        }
                        break
                    }
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        if (!isRunning || isSpeaking) return
        if (isListening || isStartingListening) return
        val recognizer = speechRecognizer ?: run {
            initAndStartListening()
            return
        }
        try {
            isStartingListening = true
            recognizer.startListening(listenIntent)
            val now = System.currentTimeMillis()
            if (now - lastRecognizerResumeLogMs > 1500L) {
                Log.e("JARVIS_CMD", "Recognizer resumed")
                lastRecognizerResumeLogMs = now
            }
        } catch (e: Exception) {
            isStartingListening = false
            Log.e("JARVIS_CMD", "startListening error: ${e.message}")
            if (isRunning) scope.launch { delay(1000); startListening() }
        }
    }

    // ─── Destroy + recreate the recognizer after a fatal session error ─────
    // Called on ERROR_SERVER_DISCONNECTED (11) and whenever a single error
    // has repeated >5 times inside a 10s window (e.g. after a long-running
    // Claude/research request). This is the ONLY place we recreate it
    // outside of onCreate.
    private fun rebuildRecognizer() {
        scope.launch {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
            isListening = false
            isStartingListening = false
            delay(500)
            if (!isRunning) return@launch
            initAndStartListening()
            Log.e("JARVIS_CMD", "Recognizer rebuilt successfully")
        }
    }

    // ─── TTS echo fingerprint helpers ────────────────────────────────────────

    private fun trackTts(text: String) {
        lastSpokenText = text.lowercase()
        val words = text.lowercase().split(" ").filter { it.length > 2 }
        for (i in 0..maxOf(0, words.size - 3)) {
            val phrase = words.subList(i, minOf(i + 3, words.size)).joinToString(" ")
            recentTtsOutputs.add(phrase)
        }
        if (recentTtsOutputs.size > 200) recentTtsOutputs.subList(0, 100).clear()
    }

    private fun isSelfVoice(text: String): Boolean {
        val lower = text.lowercase().trim()
        val jarvisWords = listOf(
            "sir", "opening", "playing", "done sir", "added to your calendar",
            "let me take a look", "all systems", "nominal", "neural net",
            "firebase synced", "memory core", "comm channel", "shall i", "understood",
            "cancelled sir", "standing by", "calendar event", "appointment for",
            "would you like me", "permissions granted", "to create calendar"
        )
        if (jarvisWords.any { lower.contains(it) }) return true
        if (lastSpokenText.isNotEmpty() && lastSpokenText.length > 10) {
            val firstWords = lower.take(20)
            if (lastSpokenText.contains(firstWords)) return true
        }
        if (System.currentTimeMillis() - ttsEndTime < 2500) return true
        val words = lower.split(" ").filter { it.isNotBlank() }
        if (words.size >= 3) {
            val firstThree = words.take(3).joinToString(" ")
            if (recentTtsOutputs.contains(firstThree)) return true
        }
        return false
    }

    // ─── TTS: stop mic → speak → 800ms cooldown → resume mic ────────────────

    private fun speakResponse(text: String, onDone: () -> Unit = {}) {
        if (!isTtsReady || tts == null) { onDone(); return }
        isSpeaking = true
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        isListening = false
        isStartingListening = false
        Log.e("JARVIS_CMD", "Recognizer paused")
        Log.e("JARVIS_CMD", "Service TTS: '$text'")
        trackTts(text)

        val uttId = "svc_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String?) {
                Log.e("JARVIS_CMD", "TTS started")
            }
            override fun onDone(uid: String?) {
                ttsEndTime = System.currentTimeMillis()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isSpeaking = false
                    Log.e("JARVIS_CMD", "TTS stopped")
                    onDone()
                    startConversationMode()
                    if (!isListening && isRunning) startListening()
                }, 1000)
            }
            override fun onError(uid: String?) {
                ttsEndTime = System.currentTimeMillis()
                scope.launch {
                    isSpeaking = false
                    Log.e("JARVIS_CMD", "TTS stopped")
                    onDone()
                    delay(1000)
                    if (isRunning) startListening()
                }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    // ─── sendViaPendingIntent — bypasses Background Activity Launch restrictions ──────────
    // PendingIntent.send() is exempt from Android 10+ BAL restrictions, so use this
    // for every activity launch from the service. Falls back to direct startActivity on failure.
    private fun sendViaPendingIntent(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send()
            true
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "PendingIntent.send failed: ${e.message} — falling back to startActivity")
            try {
                applicationContext.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e("JARVIS_CMD", "startActivity fallback failed: ${e2.message}")
                false
            }
        }
    }

    // ─── launchAppFromService — primary launcher using PendingIntent to bypass BAL ───

    private fun launchAppFromService(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    System.currentTimeMillis().toInt(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pendingIntent.send()
                Log.e("JARVIS_CMD", "Service launched via PendingIntent: $packageName")
                true
            } else {
                Log.e("JARVIS_CMD", "No launch intent for: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "PendingIntent launch failed for $packageName: ${e.message}")
            // Fallback to direct launch
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent != null) { applicationContext.startActivity(intent); true } else false
            } catch (e2: Exception) {
                Log.e("JARVIS_CMD", "Fallback startActivity failed for $packageName: ${e2.message}")
                false
            }
        }
    }

    // ─── openApp — delegates to launchAppFromService, falls back to ACTION_MAIN ─

    private fun openApp(packageName: String): Boolean {
        Log.e("JARVIS_CMD", "Service opening: $packageName")
        if (launchAppFromService(packageName)) return true
        return try {
            val ok = sendViaPendingIntent(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = packageName
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            if (ok) Log.e("JARVIS_CMD", "Service launched via ACTION_MAIN fallback: $packageName")
            ok
        } catch (ex: Exception) {
            Log.e("JARVIS_CMD", "Service openApp fallback failed for $packageName: ${ex.message}")
            false
        }
    }

    // ─── Media keys ─────────────────────────────────────────────────────────

    private fun sendMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Service media key failed: ${e.message}")
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun extractNumber(text: String): Int {
        val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
            "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty five" to 45,
            "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90)
        for ((w, n) in words) if (text.contains(w)) return n
        return Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 0
    }

    private fun extractTime(text: String): Pair<Int, Int>? {
        val normalized = text.replace(".", "").replace("o'clock", "").trim()
        val r1 = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", RegexOption.IGNORE_CASE).find(normalized)
        if (r1 != null) {
            var h = r1.groupValues[1].toIntOrNull() ?: return null
            val m = r1.groupValues[2].toIntOrNull() ?: 0
            val ap = r1.groupValues[3].lowercase()
            if (ap == "pm" && h != 12) h += 12
            if (ap == "am" && h == 12) h = 0
            return Pair(h, m)
        }
        val r2 = Regex("(\\d{1,2}):(\\d{2})").find(normalized)
        if (r2 != null) return Pair(r2.groupValues[1].toIntOrNull() ?: return null, r2.groupValues[2].toIntOrNull() ?: 0)
        return null
    }

    // ─── Shared memory brain (Firebase /jarvis/memory/global) ────────────────
    private fun handleMemoryCommandInService(lower: String): Boolean {
        // "what do you remember about me"
        if (lower == "what do you remember about me" ||
            lower == "what do you know about me" ||
            lower == "tell me what you remember about me" ||
            lower == "what do you remember") {
            val grouped = JarvisMemory.listGroupedByCategory(10)
            if (grouped.isEmpty()) {
                speakResponse("I have no memories of you yet, sir.")
            } else {
                val parts = grouped.entries.joinToString(". ") { (cat, entries) ->
                    val snippets = entries.joinToString("; ") { it.value.take(80) }
                    "$cat: $snippets"
                }
                speakResponse("Here is what I recall, sir. $parts.")
            }
            return true
        }

        // "sync memory"
        if (lower == "sync memory" || lower == "synchronize memory" ||
            lower == "refresh memory" || lower == "reload memory") {
            speakResponse("Synchronising memory, sir.")
            JarvisMemory.forceSync { count ->
                scope.launch {
                    delay(800)
                    speakResponse("Memory synchronised, sir. $count entries on board.")
                }
            }
            return true
        }

        // "forget [topic]"
        if (lower.startsWith("forget ")) {
            val topic = lower.removePrefix("forget ").trim()
            if (topic.isEmpty() || topic == "it" || topic == "that") {
                speakResponse("Forget what, sir?"); return true
            }
            speakResponse("Working on it, sir.")
            JarvisMemory.forget(topic) { deleted ->
                scope.launch {
                    delay(600)
                    val msg = if (deleted == 0) "I had nothing on $topic, sir."
                    else "Forgotten $deleted memor${if (deleted == 1) "y" else "ies"} about $topic, sir."
                    speakResponse(msg)
                }
            }
            return true
        }

        // "where did I [verb]" / "what did I say about" — semantic recall
        val semanticTriggers = listOf(
            "where did i ", "where is my ", "where are my ",
            "what did i say about ", "what did i tell you about ",
            "do you recall ", "do you remember when ", "remind me about ",
            "remind me where ", "remind me what "
        )
        if (semanticTriggers.any { lower.startsWith(it) || lower.contains(it) }) {
            speakResponse("Checking my memory, sir.")
            JarvisMemory.semanticSearch(lower) { ans ->
                scope.launch { delay(500); speakResponse(ans) }
            }
            return true
        }

        // "what do you remember about [topic]" — keyword search
        val recallPrefixes = listOf(
            "what do you remember about ", "what do you know about ",
            "tell me about ", "do you have anything on "
        )
        for (p in recallPrefixes) {
            if (lower.startsWith(p)) {
                val topic = lower.removePrefix(p).trim().trimEnd('?', '.', ',')
                if (topic.isEmpty()) continue
                val hits = JarvisMemory.searchByKeywords(topic, 5)
                if (hits.isEmpty()) {
                    speakResponse("I have no memories on $topic, sir.")
                } else {
                    val joined = hits.joinToString("; ") { it.value.take(120) }
                    speakResponse("Here is what I remember about $topic, sir. $joined.")
                }
                return true
            }
        }

        // "remember [anything]" — write
        // Skip phrases that map to other commands (voice enrollment, etc.)
        val nonMemoryRemember = listOf(
            "my voice", "the voice", "voice id", "voice enrollment", "my face"
        )
        if (nonMemoryRemember.none { lower.contains(it) }) {
            val rememberPrefixes = listOf(
                "remember that ", "remember this ", "remember ",
                "note that ", "make a note that ", "make a note ",
                "save this ", "save that ", "store this ", "store that "
            )
            for (p in rememberPrefixes) {
                if (lower.startsWith(p)) {
                    val payload = lower.removePrefix(p).trim().trimEnd('?', '.', ',')
                    if (payload.length < 3) {
                        speakResponse("Remember what, sir?"); return true
                    }
                    speakResponse("Noted, sir. Saving to the brain.")
                    JarvisMemory.remember(payload) { entry ->
                        if (entry == null) {
                            scope.launch {
                                delay(500)
                                speakResponse("I could not save that to Firebase, sir.")
                            }
                        } else {
                            Log.e("JARVIS_CMD", "Memory saved: ${entry.key} → ${entry.category}")
                        }
                    }
                    return true
                }
            }
        }

        return false
    }

    // ─── Full self-sufficient command handler (mirrors MainActivity.handleVoiceCommand) ───

    private fun executeCommandInService(input: String): Boolean {
        Log.e("JARVIS_CMD", "Service executing: $input")
        val lower = input.lowercase().trim()
        Log.e("JARVIS_CMD", "Service handling: '$lower'")

        // ── PRIORITY: Screen vision (NEVER goes to agent) ──────────────────────
        if (lower.contains("what do you see") || lower.contains("describe my screen") ||
            lower.contains("on my screen") || lower.contains("read my screen") ||
            lower.contains("read the screen") || lower.contains("analyze screen") ||
            lower.contains("summarize screen") || lower.contains("home screen")) {
            Log.e("JARVIS_CMD", "Matched: SCREEN VISION (priority)")
            handleScreenVision()
            return true
        }

        // ── SHARED MEMORY BRAIN ──────────────────────────────────────────────
        if (handleMemoryCommandInService(lower)) return true

        // ── RECIPE CREATION MODE — capture steps ─────────────────────────────
        if (recipeCreationMode) {
            if (handleRecipeCreationInput(lower)) return true
            // If not a recipe-creation phrase, fall through to normal handlers
        }

        // ── RECIPES — LIST ────────────────────────────────────────────────────
        if (lower == "list my recipes" || lower == "list recipes" ||
            lower == "what recipes do i have" || lower == "what are my recipes" ||
            lower == "show my recipes" || lower == "show recipes") {
            Log.e("JARVIS_CMD", "Matched: LIST RECIPES")
            val all = RecipeManager.all()
            if (all.isEmpty()) {
                speakResponse("You have no saved recipes, sir.")
            } else {
                val names = all.joinToString(", ") { it.name }
                speakResponse("You have ${all.size} recipe${if (all.size == 1) "" else "s"}, sir. $names.")
            }
            return true
        }

        // ── RECIPES — DELETE ──────────────────────────────────────────────────
        if (lower.startsWith("delete recipe ") || lower.startsWith("remove recipe ")) {
            Log.e("JARVIS_CMD", "Matched: DELETE RECIPE")
            val name = lower.removePrefix("delete recipe ").removePrefix("remove recipe ").trim()
            if (name.isEmpty()) {
                speakResponse("Which recipe should I delete, sir?")
            } else if (RecipeManager.delete(name)) {
                speakResponse("Recipe $name deleted, sir.")
            } else {
                speakResponse("I could not find a recipe called $name, sir.")
            }
            return true
        }

        // ── RECIPES — CREATE ──────────────────────────────────────────────────
        if (!recipeCreationMode && (
                lower.startsWith("create recipe ") ||
                lower.startsWith("new recipe ") ||
                lower.startsWith("make recipe "))) {
            Log.e("JARVIS_CMD", "Matched: CREATE RECIPE")
            val name = lower
                .removePrefix("create recipe ")
                .removePrefix("new recipe ")
                .removePrefix("make recipe ")
                .trim()
            if (name.isEmpty()) {
                speakResponse("What should I call this recipe, sir?")
                return true
            }
            recipeCreationMode = true
            pendingRecipeName = name
            pendingRecipeSteps.clear()
            speakResponse("Creating recipe $name, sir. Say step one followed by your command, then say save recipe when done.")
            return true
        }

        // ── RECIPES — RUN BY EXPLICIT NAME ────────────────────────────────────
        if (lower.startsWith("run ")) {
            val name = lower.removePrefix("run ").trim()
            val recipe = RecipeManager.findByTrigger(name) ?: RecipeManager.findByName(name)
            if (recipe != null) {
                Log.e("JARVIS_CMD", "Matched: RUN RECIPE '${recipe.name}'")
                runRecipeSteps(recipe)
                return true
            }
        }

        // ── RECIPES — RUN BY TRIGGER NAME ─────────────────────────────────────
        run {
            val recipe = RecipeManager.findByTrigger(lower)
            if (recipe != null) {
                Log.e("JARVIS_CMD", "Matched: RECIPE TRIGGER '${recipe.name}'")
                runRecipeSteps(recipe)
                return true
            }
        }

        // ── NOTIFICATIONS — READ LAST MESSAGE ─────────────────────────────────
        if (lower == "read my last message" || lower == "read last message" ||
            lower == "what's my last message" || lower == "whats my last message" ||
            lower == "read my latest message" || lower == "read latest message") {
            Log.e("JARVIS_CMD", "Matched: READ LAST MESSAGE")
            val item = JarvisNotificationListener.lastMessage()
            if (item == null) {
                speakResponse("You have no messages, sir.")
            } else {
                speakResponse("Last message from ${item.sender} on ${item.app}: ${item.message}")
            }
            return true
        }

        // ── NOTIFICATIONS — READ RECENT (last 5) ──────────────────────────────
        if (lower == "read my notifications" || lower == "read notifications" ||
            lower == "read my messages" || lower == "read recent messages" ||
            lower == "read my unread messages" || lower == "what are my notifications") {
            Log.e("JARVIS_CMD", "Matched: READ NOTIFICATIONS")
            val items = JarvisNotificationListener.lastN(5)
            if (items.isEmpty()) {
                speakResponse("You have no notifications, sir.")
            } else {
                val parts = items.map { "${it.sender} on ${it.app} said: ${it.message}" }
                speakResponse("Sir, your last ${items.size} message${if (items.size == 1) "" else "s"}. ${parts.joinToString(". ")}.")
            }
            return true
        }

        // ── NOTIFICATIONS — CLEAR ─────────────────────────────────────────────
        if (lower == "clear notifications" || lower == "clear my notifications" ||
            lower == "clear messages" || lower == "clear my messages" ||
            lower == "reset notifications") {
            Log.e("JARVIS_CMD", "Matched: CLEAR NOTIFICATIONS")
            JarvisNotificationListener.clear()
            speakResponse("Notifications cleared, sir.")
            return true
        }

        // ── NOTIFICATIONS — REPLY TO [sender]: [message] ──────────────────────
        if (lower.startsWith("reply to ") || lower.startsWith("respond to ")) {
            Log.e("JARVIS_CMD", "Matched: REPLY TO")
            val body = lower.removePrefix("reply to ").removePrefix("respond to ").trim()
            // Split on first ":" — fall back to " saying " or " with "
            val sep = listOf(":", " saying ", " with ", " that ").firstOrNull { body.contains(it) }
            if (sep == null) {
                speakResponse("Please say: reply to name, colon, your message, sir.")
                return true
            }
            val parts = body.split(sep, limit = 2)
            val target = parts[0].trim()
            val reply = if (parts.size > 1) parts[1].trim() else ""
            if (target.isEmpty() || reply.isEmpty()) {
                speakResponse("I need both a name and a message, sir.")
                return true
            }
            val item = JarvisNotificationListener.findBySender(target)
            val pkg = item?.packageName ?: "com.whatsapp"
            val appLabel = item?.app ?: "WhatsApp"
            var sent = false

            // Strategy A — if we know the source app, fire ACTION_SEND with EXTRA_TEXT
            // packaged to that app. Many messengers (WhatsApp, Telegram, Gmail) accept this.
            try {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, reply)
                    `package` = pkg
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (sendIntent.resolveActivity(packageManager) != null) {
                    sent = sendViaPendingIntent(sendIntent)
                }
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Reply ACTION_SEND failed: ${e.message}")
            }

            // Strategy B — WhatsApp deep link (no phone needed → opens chooser/contact picker)
            if (!sent && pkg == "com.whatsapp") {
                try {
                    val uri = Uri.parse("https://wa.me/?text=${Uri.encode(reply)}")
                    sent = sendViaPendingIntent(Intent(Intent.ACTION_VIEW, uri).apply {
                        `package` = "com.whatsapp"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    Log.e("JARVIS_CMD", "Reply wa.me failed: ${e.message}")
                }
            }

            // Strategy C — open the original notification's contentIntent (jumps into the chat)
            if (!sent && item?.contentIntent != null) {
                try { item.contentIntent.send(); sent = true } catch (e: Exception) {
                    Log.e("JARVIS_CMD", "Reply contentIntent.send failed: ${e.message}")
                }
            }

            // Strategy D — last resort: open the app
            if (!sent) sent = openApp(pkg)

            if (sent) {
                speakResponse("Opening $appLabel to reply to $target, sir.")
            } else {
                speakResponse("Could not open $appLabel, sir.")
            }
            return true
        }

        // ── GOODBYE/SHUTDOWN ──────────────────────────────────────────────────
        if (lower.contains("goodbye jarvis") || lower.contains("good bye jarvis") ||
            lower.contains("shutdown jarvis") || lower.contains("shut down jarvis") ||
            lower.contains("turn off jarvis") || lower.contains("close jarvis") ||
            lower.contains("exit jarvis") || lower.contains("quit jarvis")) {
            Log.e("JARVIS_CMD", "Matched: GOODBYE/SHUTDOWN")
            isAwake = false
            speakResponse("Goodbye sir.") { scope.launch { delay(1000); stopSelf() } }
            return true
        }

        // ── SPOTIFY PLAY ──────────────────────────────────────────────────────
        if (lower.startsWith("play ") || (lower.contains(" play ") && !lower.contains("play store"))) {
            Log.e("JARVIS_CMD", "Matched: SPOTIFY PLAY")
            var query = lower
                .replace("please", "").replace("can you", "")
                .replace("jarvis", "").replace("play me some", "")
                .replace("play me", "").replace("play some", "")
                .replace("play a song called", "").replace("play the song", "")
                .replace("play a song", "").replace("play song", "")
                .replace("on spotify", "").replace("in spotify", "")
                .replace("spotify", "").replace("play", "").trim()
            if (query.length > 2) {
                try {
                    sendViaPendingIntent(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")).apply {
                            `package` = "com.spotify.music"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    scope.launch {
                        delay(5000)
                        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                        delay(1500)
                        sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                        delay(300)
                        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                } catch (e: Exception) { openApp("com.spotify.music") }
                speakResponse("Playing $query on Spotify, sir.")
            } else {
                openApp("com.spotify.music")
                speakResponse("Opening Spotify, sir.")
            }
            return true
        }

        // ── MEDIA CONTROLS ────────────────────────────────────────────────────
        if (lower == "resume" || lower == "resume music" || lower == "resume song" ||
            lower == "play music" || lower == "unpause" || lower == "start music" ||
            lower == "continue music" || lower == "continue song") {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            speakResponse("Resuming music, sir."); return true
        }
        if (lower == "pause" || lower == "pause music" || lower == "pause song" ||
            lower == "stop music" || lower == "stop song") {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            speakResponse("Music paused, sir."); return true
        }
        if (lower == "next" || lower == "next song" || lower == "next track" ||
            lower == "skip" || lower == "skip song" || lower == "skip track") {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            speakResponse("Next track, sir."); return true
        }
        if (lower == "previous" || lower == "previous song" || lower == "previous track" ||
            lower == "back" || lower == "go back") {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            speakResponse("Previous track, sir."); return true
        }
        // ── VOLUME — SET BY PERCENT ─────────────────────────────────────────
        if ((lower.contains("volume") || lower.contains("set volume")) &&
            (lower.contains("%") || Regex("\\b\\d{1,3}\\b").containsMatchIn(lower)) &&
            (lower.startsWith("set volume") || lower.startsWith("volume to ") ||
             lower.startsWith("volume ") || lower.contains("volume to ") ||
             lower.contains("set the volume"))) {
            Log.e("JARVIS_CMD", "Matched: SET VOLUME PERCENT")
            val pct = parseVolumePercent(lower)
            if (pct != null) {
                setMusicVolumePercent(pct)
                speakResponse("Volume set to $pct percent, sir.")
                return true
            }
        }

        if (lower == "volume up" || lower == "louder" || lower == "increase volume") {
            sendMediaKey(KeyEvent.KEYCODE_VOLUME_UP)
            speakResponse("Volume up, sir."); return true
        }
        if (lower == "volume down" || lower == "quieter" || lower == "decrease volume") {
            sendMediaKey(KeyEvent.KEYCODE_VOLUME_DOWN)
            speakResponse("Volume down, sir."); return true
        }
        if (lower == "mute" || lower == "silence") {
            sendMediaKey(KeyEvent.KEYCODE_VOLUME_MUTE)
            speakResponse("Muted, sir."); return true
        }

        // ── DO NOT DISTURB ────────────────────────────────────────────────────
        if (lower.contains("do not disturb") || lower == "dnd on" || lower == "dnd off" ||
            lower.contains("turn on dnd") || lower.contains("turn off dnd") ||
            lower.contains("enable dnd") || lower.contains("disable dnd") ||
            lower.contains("silent mode") || lower == "focus mode on" || lower == "focus mode off") {
            Log.e("JARVIS_CMD", "Matched: DND")
            val turnOff = lower.contains(" off") || lower.contains("disable") ||
                lower.contains("turn off") || lower == "dnd off" ||
                lower == "focus mode off"
            val ok = setDoNotDisturb(!turnOff)
            if (!ok) {
                speakResponse("I need notification policy access, sir. Opening settings.")
                try {
                    sendViaPendingIntent(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) { Log.e("JARVIS_CMD", "DND settings failed: ${e.message}") }
            } else {
                speakResponse(if (turnOff) "Do not disturb off, sir." else "Do not disturb on, sir.")
            }
            return true
        }

        // ── CLOSE ALL APPS — best-effort ──────────────────────────────────────
        if (lower == "close all apps" || lower == "close all" || lower == "kill all apps") {
            Log.e("JARVIS_CMD", "Matched: CLOSE ALL APPS")
            try {
                sendViaPendingIntent(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speakResponse("Returning to home, sir.")
            } catch (e: Exception) {
                speakResponse("Could not close apps, sir.")
            }
            return true
        }

        // ── CALENDAR — short phrasings ────────────────────────────────────────
        if (lower == "read calendar" || lower == "read my calendar" ||
            lower == "read my schedule" || lower == "read schedule") {
            if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                speakResponse("I need calendar permission, sir."); return true
            }
            speakResponse("Checking your schedule, sir.")
            scope.launch(Dispatchers.IO) {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
                val startMs = cal.timeInMillis
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val endMs = cal.timeInMillis
                val projection = arrayOf(
                    android.provider.CalendarContract.Events.TITLE,
                    android.provider.CalendarContract.Events.DTSTART,
                    android.provider.CalendarContract.Events.ALL_DAY)
                val selection = "${android.provider.CalendarContract.Events.DTSTART} >= ? AND " +
                    "${android.provider.CalendarContract.Events.DTSTART} < ? AND " +
                    "${android.provider.CalendarContract.Events.DELETED} != 1"
                val events = mutableListOf<String>()
                try {
                    val cursor = contentResolver.query(
                        android.provider.CalendarContract.Events.CONTENT_URI, projection, selection,
                        arrayOf(startMs.toString(), endMs.toString()),
                        "${android.provider.CalendarContract.Events.DTSTART} ASC")
                    cursor?.use {
                        while (it.moveToNext()) {
                            val title = it.getString(0) ?: "Untitled"
                            if (it.getInt(2) == 1) events.add("$title all day")
                            else {
                                val sdf = java.text.SimpleDateFormat("h:mma", Locale.getDefault())
                                events.add("at ${sdf.format(java.util.Date(it.getLong(1))).lowercase().replace(":00", "")} $title")
                            }
                        }
                    }
                } catch (e: Exception) { Log.e("JARVIS_CMD", "Service short calendar read error: ${e.message}") }
                withContext(Dispatchers.Main) {
                    val msg = if (events.isEmpty()) "You have no events today, sir."
                    else "You have ${events.size} event${if (events.size == 1) "" else "s"} today, sir. ${events.take(3).joinToString(", ").replaceFirstChar { it.uppercase() }}."
                    speakResponse(msg)
                }
            }
            return true
        }

        // ── ALARM ─────────────────────────────────────────────────────────────
        if (lower.contains("alarm") || lower.contains("wake me") || lower.contains("wake up at")) {
            Log.e("JARVIS_CMD", "Matched: ALARM")
            val time = extractTime(lower)
            if (time != null) {
                try {
                    sendViaPendingIntent(
                        Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, time.first)
                            putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm")
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    val amPm = if (time.first >= 12) "PM" else "AM"
                    val h = if (time.first > 12) time.first - 12 else if (time.first == 0) 12 else time.first
                    speakResponse("Alarm set for $h:${time.second.toString().padStart(2, '0')} $amPm, sir.")
                } catch (e: Exception) {
                    openApp("com.sec.android.app.clockpackage")
                    speakResponse("Opening clock to set alarm, sir.")
                }
            } else {
                openApp("com.sec.android.app.clockpackage")
                speakResponse("Opening clock, sir.")
            }
            return true
        }

        // ── TIMER ─────────────────────────────────────────────────────────────
        if (lower.contains("timer") || lower.contains("countdown")) {
            Log.e("JARVIS_CMD", "Matched: TIMER")
            val mins = extractNumber(lower)
            if (mins > 0) {
                try {
                    sendViaPendingIntent(
                        Intent(AlarmClock.ACTION_SET_TIMER).apply {
                            putExtra(AlarmClock.EXTRA_LENGTH, mins * 60)
                            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Timer")
                            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    speakResponse("$mins minute timer set, sir.")
                } catch (e: Exception) {
                    openApp("com.sec.android.app.clockpackage")
                    speakResponse("Opening clock for timer, sir.")
                }
            } else {
                openApp("com.sec.android.app.clockpackage")
                speakResponse("Opening clock, sir.")
            }
            return true
        }

        // ── STOPWATCH ─────────────────────────────────────────────────────────
        if (lower.contains("stopwatch")) {
            openApp("com.sec.android.app.clockpackage")
            speakResponse("Opening stopwatch, sir."); return true
        }

        // ── CAMERA — FOLD/INNER SELFIE ────────────────────────────────────────
        if (lower.contains("fold selfie") || lower.contains("inner selfie") ||
            lower.contains("tablet selfie") || lower.contains("inner camera") ||
            lower.contains("fold camera")) {
            var opened = false
            try {
                sendViaPendingIntent(Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("camerafacing", "front"); putExtra("selfie", true)
                    putExtra("lensFacingType", 2); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }); opened = true
            } catch (e: Exception) {}
            if (!opened) try {
                sendViaPendingIntent(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                    putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("lensFacingType", 2); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }); opened = true
            } catch (e: Exception) {}
            if (!opened) openApp("com.sec.android.app.camera")
            speakResponse("Opening inner fold camera, sir."); return true
        }

        // ── CAMERA — SELFIE ───────────────────────────────────────────────────
        if (lower.contains("take a selfie") || lower.contains("selfie") ||
            lower.contains("front camera") || lower.contains("selfie mode")) {
            var opened = false
            try {
                sendViaPendingIntent(Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("camerafacing", "front"); putExtra("selfie", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }); opened = true
            } catch (e: Exception) {}
            if (!opened) try {
                sendViaPendingIntent(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                    putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }); opened = true
            } catch (e: Exception) {}
            if (!opened) openApp("com.sec.android.app.camera")
            speakResponse("Opening front camera, sir."); return true
        }

        // ── CAMERA — VIDEO ────────────────────────────────────────────────────
        if (lower.contains("record video") || lower.contains("video camera") ||
            lower.contains("video mode") || lower.contains("start recording")) {
            var opened = false
            try {
                sendViaPendingIntent(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }); opened = true
            } catch (e: Exception) {}
            if (!opened) openApp("com.sec.android.app.camera")
            speakResponse("Opening video camera, sir."); return true
        }

        // ── CAMERA — PHOTO / OPEN CAMERA ─────────────────────────────────────
        if (lower.contains("take photo") || lower.contains("take a photo") ||
            lower.contains("take picture") || lower.contains("take a picture") ||
            lower.contains("capture photo") || lower.contains("photo mode") ||
            lower.contains("back camera") || lower.contains("rear camera")) {
            var opened = false
            try {
                sendViaPendingIntent(Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 0)
                    putExtra("camerafacing", "back"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }); opened = true
            } catch (e: Exception) {}
            if (!opened) openApp("com.sec.android.app.camera")
            speakResponse("Opening camera, sir."); return true
        }

        // ── FLASHLIGHT ────────────────────────────────────────────────────────
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light")) {
            try {
                val cam = applicationContext.getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraId = cam.cameraIdList.first()
                val turnOn = !lower.contains("off") && !lower.contains("turn off")
                cam.setTorchMode(cameraId, turnOn)
                speakResponse(if (turnOn) "Flashlight on, sir." else "Flashlight off, sir.")
            } catch (e: Exception) { speakResponse("Could not control flashlight, sir.") }
            return true
        }

        // ── SCREENSHOT HINT ───────────────────────────────────────────────────
        if (lower.contains("screenshot") || lower.contains("screen shot") || lower.contains("capture screen")) {
            speakResponse("Press power and volume down together for screenshot, sir."); return true
        }

        // ── CLAUDE / CHATGPT ──────────────────────────────────────────────────
        if (lower.contains("open claude") || lower.contains("launch claude")) {
            Log.e("JARVIS_CMD", "Matched: OPEN CLAUDE")
            if (!openApp("com.anthropic.claude")) {
                try { sendViaPendingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            }
            speakResponse("Opening Claude AI, sir."); return true
        }
        if (lower.contains("open chatgpt") || lower.contains("open chat gpt") || lower.contains("launch gpt")) {
            Log.e("JARVIS_CMD", "Matched: OPEN CHATGPT")
            if (!openApp("com.openai.chatgpt")) {
                try { sendViaPendingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.openai.com")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            }
            speakResponse("Opening ChatGPT, sir."); return true
        }

        // ── APP LIST ──────────────────────────────────────────────────────────
        for (app in apps) {
            if (app.keywords.any { lower.contains(it) }) {
                Log.e("JARVIS_CMD", "Matched: APP '${app.name}'")
                if (app.name == "Samsung Notes" &&
                    (lower.contains("new") || lower.contains("create") || lower.contains("write"))) {
                    try {
                        sendViaPendingIntent(Intent(Intent.ACTION_INSERT).apply {
                            `package` = app.packages[0]; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) { openApp(app.packages[0]) }
                    speakResponse("Creating a new note, sir."); return true
                }
                var opened = false
                for (pkg in app.packages) { if (openApp(pkg)) { opened = true; break } }
                if (!opened && app.name == "New York Times") {
                    try { sendViaPendingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.nytimes.com")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); opened = true } catch (e: Exception) {}
                }
                if (!opened && app.name == "The Guardian") {
                    try { sendViaPendingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.theguardian.com")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); opened = true } catch (e: Exception) {}
                }
                speakResponse(if (opened) "Opening ${app.name}, sir." else "Could not find ${app.name} on your device, sir.")
                return true
            }
        }

        // ── CALENDAR READ ─────────────────────────────────────────────────────
        if (lower.contains("what's on my calendar") || lower.contains("what's on my schedule") ||
            lower.contains("show my calendar") || lower.contains("my agenda") ||
            lower.contains("what do i have today") || lower.contains("what do i have tomorrow") ||
            lower.contains("today's schedule") || lower.contains("today's events") ||
            lower.contains("tomorrow's events")) {
            if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                speakResponse("I need calendar permission, sir."); return true
            }
            val isTomorrow = lower.contains("tomorrow")
            val period = if (isTomorrow) "tomorrow" else "today"
            speakResponse("Checking your $period schedule, sir.")
            scope.launch(Dispatchers.IO) {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
                if (isTomorrow) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val startMs = cal.timeInMillis
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val endMs = cal.timeInMillis
                val projection = arrayOf(android.provider.CalendarContract.Events.TITLE,
                    android.provider.CalendarContract.Events.DTSTART,
                    android.provider.CalendarContract.Events.ALL_DAY)
                val selection = "${android.provider.CalendarContract.Events.DTSTART} >= ? AND " +
                    "${android.provider.CalendarContract.Events.DTSTART} < ? AND " +
                    "${android.provider.CalendarContract.Events.DELETED} != 1"
                val events = mutableListOf<String>()
                try {
                    val cursor = contentResolver.query(
                        android.provider.CalendarContract.Events.CONTENT_URI, projection, selection,
                        arrayOf(startMs.toString(), endMs.toString()),
                        "${android.provider.CalendarContract.Events.DTSTART} ASC")
                    cursor?.use {
                        while (it.moveToNext()) {
                            val title = it.getString(0) ?: "Untitled"
                            if (it.getInt(2) == 1) events.add("$title all day")
                            else {
                                val sdf = java.text.SimpleDateFormat("h:mma", Locale.getDefault())
                                events.add("at ${sdf.format(java.util.Date(it.getLong(1))).lowercase().replace(":00", "")} $title")
                            }
                        }
                    }
                } catch (e: Exception) { Log.e("JARVIS_CMD", "Service calendar read error: ${e.message}") }
                withContext(Dispatchers.Main) {
                    val msg = if (events.isEmpty()) "You have no events for $period, sir."
                    else "You have ${events.size} event${if (events.size == 1) "" else "s"} $period, sir. ${events.take(3).joinToString(", ").replaceFirstChar { it.uppercase() }}."
                    speakResponse(msg)
                }
            }
            return true
        }

        // ── CALENDAR CREATE — hands-free, NEVER goes to agent ─────────────────
        if (lower.contains("calendar") || lower.contains("appointment") ||
            lower.contains("add event") || lower.contains("dental") ||
            lower.contains("meeting") || lower.contains("remind me") ||
            (lower.contains("schedule") && !lower.contains("my schedule") &&
             !lower.contains("what's on") && !lower.startsWith("read"))) {
            Log.e("JARVIS_CMD", "Command detected: calendar")
            Log.e("JARVIS_CMD", "Calendar command detected")
            if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                speakResponse("I need calendar permission, sir."); return true
            }
            handleCalendarInService(input, lower)
            return true
        }

        // ── CALENDAR DELETE ───────────────────────────────────────────────────
        if ((lower.contains("delete") || lower.contains("remove") || lower.contains("cancel")) &&
            (lower.contains("appointment") || lower.contains("meeting") ||
             (lower.contains("event") && (lower.contains("my") || lower.contains("the"))))) {
            openApp("com.samsung.android.calendar")
            speakResponse("Opening calendar to manage events, sir."); return true
        }

        // ── CALL ──────────────────────────────────────────────────────────────
        if ((lower.startsWith("call ") || lower.contains("phone call") ||
                lower.contains("dial ") || lower.contains("make a call")) &&
            !lower.contains("whatsapp") && !lower.contains("telegram") &&
            !lower.contains("video call")) {
            try {
                sendViaPendingIntent(Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                speakResponse("Opening dialer, sir.")
            } catch (e: Exception) {
                openApp("com.samsung.android.dialer"); speakResponse("Opening phone, sir.")
            }
            return true
        }

        // ── NAVIGATE ──────────────────────────────────────────────────────────
        if (lower.contains("navigate to ") || lower.contains("directions to ") || lower.contains("take me to ")) {
            Log.e("JARVIS_CMD", "Matched: NAVIGATE")
            val dest = lower.replace("navigate to ", "").replace("directions to ", "").replace("take me to ", "").trim()
            if (dest.isNotEmpty()) {
                try {
                    sendViaPendingIntent(
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")).apply {
                            `package` = "com.google.android.apps.maps"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    speakResponse("Navigating to $dest, sir.")
                } catch (e: Exception) {
                    openApp("com.google.android.apps.maps"); speakResponse("Opening Maps, sir.")
                }
                return true
            }
        }

        // ── WEB SEARCH ────────────────────────────────────────────────────────
        if (lower.contains("search for ") || lower.contains("look up ") ||
            (lower.startsWith("search ") && !lower.contains("research"))) {
            Log.e("JARVIS_CMD", "Matched: WEB SEARCH")
            val raw = lower.replace("search online for ", "").replace("search for ", "")
                .replace("look up ", "").replace("google search ", "").replace("search ", "").trim()
            if (raw.isNotEmpty()) {
                try {
                    sendViaPendingIntent(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(raw)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    speakResponse("Searching for $raw, sir.")
                } catch (e: Exception) { speakResponse("Could not open browser, sir.") }
                return true
            }
        }

        // ── YOUTUBE SEARCH ────────────────────────────────────────────────────
        if ((lower.contains("youtube") || lower.contains("you tube")) &&
            (lower.contains("search") || lower.contains("watch") || lower.contains("find") || lower.contains("show me"))) {
            val query = lower.replace("search youtube for", "").replace("search on youtube", "")
                .replace("youtube search", "").replace("watch", "").replace("find on youtube", "")
                .replace("show me on youtube", "").replace("youtube", "").replace("you tube", "").trim()
            if (query.isNotEmpty()) {
                try {
                    sendViaPendingIntent(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                        `package` = "com.google.android.youtube"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) { openApp("com.google.android.youtube") }
                speakResponse("Searching YouTube for $query, sir."); return true
            }
        }

        // ── INSTAGRAM PROFILE ─────────────────────────────────────────────────
        if ((lower.contains("instagram") || lower.contains("insta")) && lower.contains("profile")) {
            val name = lower.replace("instagram profile of", "").replace("profile of", "")
                .replace("search instagram for", "").replace("find user", "")
                .replace("instagram", "").replace("insta", "").trim()
            if (name.isNotEmpty()) {
                try {
                    sendViaPendingIntent(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.instagram.com/$name")).apply {
                        `package` = "com.instagram.android"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    speakResponse("Opening Instagram profile of $name, sir.")
                } catch (e: Exception) { openApp("com.instagram.android"); speakResponse("Opening Instagram, sir.") }
                return true
            }
        }

        // ── WHATSAPP MESSAGE ──────────────────────────────────────────────────
        if (lower.contains("whatsapp") && (lower.contains("message") ||
                lower.contains("send") || lower.contains("text") || lower.contains("chat"))) {
            openApp("com.whatsapp")
            speakResponse("Opening WhatsApp, sir."); return true
        }

        // ── SETTINGS SHORTCUTS ────────────────────────────────────────────────
        if (lower.contains("wifi settings") || lower.contains("wi-fi settings") || lower.contains("open wifi")) {
            try { sendViaPendingIntent(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            speakResponse("Opening WiFi settings, sir."); return true
        }
        if (lower.contains("bluetooth settings") || lower.contains("open bluetooth")) {
            try { sendViaPendingIntent(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            speakResponse("Opening Bluetooth settings, sir."); return true
        }
        if (lower.contains("display settings") || lower.contains("brightness settings") || lower.contains("screen settings")) {
            try { sendViaPendingIntent(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            speakResponse("Opening display settings, sir."); return true
        }
        if (lower.contains("sound settings") || lower.contains("volume settings")) {
            try { sendViaPendingIntent(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            speakResponse("Opening sound settings, sir."); return true
        }
        if (lower.contains("battery settings") || lower.contains("power settings")) {
            try { sendViaPendingIntent(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            speakResponse("Opening battery settings, sir."); return true
        }

        // ── EMAIL COMPOSE ─────────────────────────────────────────────────────
        if (lower.contains("compose email") || lower.contains("write email") ||
            lower.contains("send email") || lower.contains("new email")) {
            try {
                sendViaPendingIntent(Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speakResponse("Opening email composer, sir.")
            } catch (e: Exception) { openApp("com.google.android.gm"); speakResponse("Opening Gmail, sir.") }
            return true
        }

        // ── WEATHER ───────────────────────────────────────────────────────────
        if (lower.contains("weather") || lower.contains("temperature") ||
            lower.contains("how hot") || lower.contains("how cold") ||
            lower.contains("will it rain") || lower.contains("going to rain") ||
            lower.contains("need a jacket") || lower.contains("forecast")) {
            speakResponse("Checking the weather, sir.")
            scope.launch(Dispatchers.IO) {
                val result = fetchWeatherInService()
                withContext(Dispatchers.Main) { speakResponse(result) }
            }
            return true
        }

        // ── NEWS ──────────────────────────────────────────────────────────────
        if (lower.contains("headline") || lower.contains("brief me") ||
            (lower.contains("news") && !lower.contains("new note") && !lower.contains("new event") &&
             !lower.contains("new alarm") && !lower.contains("new timer"))) {
            speakResponse("Fetching the latest headlines, sir.")
            scope.launch(Dispatchers.IO) {
                val result = fetchNewsInService()
                withContext(Dispatchers.Main) { speakResponse(result) }
            }
            return true
        }

        // ── LANGUAGE SWITCH ───────────────────────────────────────────────────
        if (lower.contains("switch to farsi") || lower.contains("speak farsi") || lower.contains("speak persian")) {
            val farsiLocale = Locale("fa", "IR")
            val avail = tts?.isLanguageAvailable(farsiLocale)
            if (avail == TextToSpeech.LANG_AVAILABLE || avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) tts?.language = farsiLocale
            speakResponse("Switching to Farsi, sir."); return true
        }
        if (lower.contains("switch to english") || lower.contains("speak english") || lower.contains("back to english")) {
            tts?.language = Locale.US
            speakResponse("Back to English, sir."); return true
        }

        return false
    }

    private fun fetchWeatherInService(): String {
        return try {
            val url = "https://api.openweathermap.org/data/2.5/weather?q=${Uri.encode(USER_CITY)}&appid=$OPENWEATHER_API_KEY&units=metric"
            val response = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return "Could not retrieve weather, sir."
            val json = org.json.JSONObject(response.body?.string() ?: "")
            val temp = json.getJSONObject("main").getDouble("temp").toInt()
            val feelsLike = json.getJSONObject("main").getDouble("feels_like").toInt()
            val desc = json.getJSONArray("weather").getJSONObject(0).getString("description")
            val jacket = if (temp < 15) " You'll want a jacket, sir." else ""
            "It's $temp degrees Celsius and $desc in ${json.getString("name")}, sir. Feels like $feelsLike.$jacket"
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Service weather error: ${e.message}")
            "Weather check failed, sir."
        }
    }

    private fun fetchNewsInService(): String {
        return try {
            val url = "https://newsapi.org/v2/top-headlines?apiKey=$NEWSAPI_KEY&pageSize=3&language=en&sources=the-guardian-uk,bbc-news,reuters"
            val response = okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(url).addHeader("User-Agent", "JarvisApp/1.0").build()
            ).execute()
            if (!response.isSuccessful) return "Could not retrieve news, sir."
            val articles = org.json.JSONObject(response.body?.string() ?: "").getJSONArray("articles")
            val headlines = mutableListOf<String>()
            for (i in 0 until minOf(articles.length(), 3)) {
                val title = articles.getJSONObject(i).optString("title", "").trim()
                if (title.isNotEmpty() && title != "[Removed]") headlines.add("$title.")
            }
            if (headlines.isEmpty()) "No headlines found, sir."
            else "Here are today's headlines, sir. ${headlines.joinToString(" ")}"
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Service news error: ${e.message}")
            "News retrieval failed, sir."
        }
    }

    // ─── Command routing — wake word detected inline, no awake gate ──────────

    private fun processResult(text: String) {
        if (text.isBlank()) return
        if (isSpeaking) {
            Log.e("JARVIS_CMD", "Ignoring input while speaking: '$text'")
            return
        }
        val lower = text.lowercase().trim()

        // ── TTS fingerprint echo check (layer 2)
        if (isSelfVoice(lower)) {
            Log.e("JARVIS_CMD", "TTS fingerprint echo rejected: '$lower'")
            return
        }
        // ── Post-TTS lockout: reject long phrases within 1500ms of TTS ending (layer 3)
        if (lower.split(" ").size > 4 && System.currentTimeMillis() - ttsEndTime < 1500L) {
            Log.e("JARVIS_CMD", "Post-TTS echo rejected: '$lower'")
            return
        }

        // ── Self-voice feedback filter: drop anything that looks like our own TTS
        val selfVoicePhrases = listOf(
            "sir", "opening ", "playing ", "alarm set",
            "navigating to", "searching for", "resuming music",
            "music paused", "next track", "previous track",
            "volume up", "volume down", "muted,",
            "all systems", "research complete", "goodbye sir",
            "flashlight on", "flashlight off"
        )
        if (selfVoicePhrases.any { lower.contains(it) }) {
            Log.e("JARVIS_CMD", "Self-voice feedback ignored: '$lower'")
            return
        }

        // ── Post-command lockout — block ALL commands for 4s after executing one
        val now = System.currentTimeMillis()
        if (pendingCalendarDraft == null && lastCommandTimestamp > 0 && now - lastCommandTimestamp < 4000L) {
            Log.e("JARVIS_CMD", "Post-command lockout (${now - lastCommandTimestamp}ms) — ignoring: '$lower'")
            return
        }

        // ── Dedup: identical command within 5s is dropped silently
        if (lower == lastCommandText && now - lastCommandTimestamp < 5000L) {
            Log.e("JARVIS_CMD", "Duplicate command ignored: $lower")
            return
        }

        val sleepWords = listOf(
            "goodbye jarvis", "good bye jarvis", "sleep jarvis",
            "go to sleep jarvis", "jarvis goodbye", "shutdown jarvis"
        )
        if (sleepWords.any { lower.contains(it) }) {
            Log.e("JARVIS_CMD", "SLEEP WORD DETECTED: $lower")
            isAwake = false
            endConversationMode()
            sendBroadcast(Intent(ACTION_SLEEP).apply { `package` = packageName })
            return
        }

        // ── Conversation end phrases ─────────────────────────────────────────
        if (conversationActive) {
            val endConvoWords = listOf(
                "thank you jarvis", "thanks jarvis", "that's all jarvis",
                "thats all jarvis", "that will be all jarvis", "goodbye jarvis",
                "that's all", "thats all", "goodbye", "that will be all"
            )
            if (endConvoWords.any { lower == it || lower.startsWith(it) }) {
                Log.e("JARVIS_CMD", "Conversation ended by user phrase: '$lower'")
                endConversationMode()
                speakResponse("Of course, sir. I'll be here if you need me.")
                return
            }
        }

        val wakePatterns = listOf(
            "hey jarvis", "ok jarvis", "okay jarvis", "hi jarvis",
            "yo jarvis", "jarvis wake up", "wake up jarvis", "jarvis are you there",
            "jarvis please", "جارویس", "هی جارویس", "jarvis"
        )
        var command = lower
        var hadWakeWord = false
        for (pattern in wakePatterns) {
            if (lower.contains(pattern)) {
                command = lower.replace(pattern, "").trim()
                hadWakeWord = true
                break
            }
        }

        if (!hadWakeWord) {
            val isRecipeCreationPhrase = recipeCreationMode && (
                lower.startsWith("step ") || lower == "save recipe" ||
                lower == "save my recipe" || lower == "save it" ||
                lower == "done" || lower == "finished" || lower == "finish recipe" ||
                lower == "cancel recipe" || lower == "stop recipe" ||
                lower == "cancel recipe creation" || lower == "abort recipe"
            )
            val isClearCommand = lower.startsWith("open ") || lower.startsWith("launch ") ||
                lower.startsWith("play ") || lower.startsWith("call ") ||
                lower.startsWith("search ") || lower.startsWith("navigate to ") ||
                lower.startsWith("directions to ") || lower.startsWith("set ") ||
                lower.contains("alarm") || lower.contains("timer") ||
                lower.contains("what do you see") || lower.contains("on my screen") ||
                lower.contains("describe my screen") || lower.contains("read my screen") ||
                lower.contains("read the screen") || lower.contains("home screen") ||
                lower.contains("calendar") || lower.contains("appointment") ||
                lower.contains("add event") || lower.contains("remind me") ||
                lower.contains("weather") || lower.contains("temperature") ||
                lower.contains("read my") || lower.contains("what's on my") ||
                lower.contains("dental") || lower.contains("meeting") ||
                lower.contains("what do i have") || lower.contains("today's events") ||
                lower.contains("schedule")
            if (!isClearCommand && !isRecipeCreationPhrase && !conversationActive) {
                Log.e("JARVIS_CMD", "No wake word, not a clear command, ignoring: '$lower'")
                return
            }
            command = lower
        }

        // ── Reset conversation timer on any new command ──────────────────────
        if (conversationActive) {
            conversationTimeout?.cancel()
            Log.e("JARVIS_CMD", "Conversation timer reset for new command")
        }

        if (command.isBlank() || command.length < 2) {
            Log.e("JARVIS_CMD", "Wake word detected — listening for command (8s window)")
            isAwake = true
            conversationActive = true
            conversationTimeout?.cancel()
            conversationTimeout = scope.launch {
                delay(8000L)
                if (conversationActive) {
                    conversationActive = false
                    updateNotification("Listening for 'Hey Jarvis'...", false)
                    Log.e("JARVIS_CMD", "8s command window expired")
                }
            }
            updateNotification("ACTIVE — Listening to you, sir", true)
            playWakeBeep()
            sendBroadcast(Intent(ACTION_WAKE).apply { `package` = packageName })
            return
        }

        Log.e("JARVIS_CMD", "Wake detected, executing: $command")
        lastCommandTimestamp = System.currentTimeMillis()
        lastCommandText = lower
        commandHistory.addLast(System.currentTimeMillis() to command)
        while (commandHistory.size > 10) commandHistory.removeFirst()
        Log.e("JARVIS_CMD", "Command history: " +
            commandHistory.joinToString(" | ") { "${it.first}:${it.second}" })
        val handled = executeCommandInService(command)
        if (!handled) {
            val wordCount = command.split(" ").filter { it.isNotBlank() }.size
            if ((wordCount >= 4 || lower.contains("research")) && shouldForwardToAi(command)) {
                Log.e("JARVIS_CMD", "Service forwarding to MainActivity for AI response: '$command'")
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                isListening = false
                isStartingListening = false
                sendBroadcast(Intent(ACTION_COMMAND).apply {
                    `package` = packageName
                    putExtra(EXTRA_COMMAND, command)
                })
            } else {
                Log.e("JARVIS_CMD", "Command not handled and too short for agent ($wordCount words): '$command'")
            }
        }
    }

    private fun shouldForwardToAi(command: String): Boolean {
        val lower = command.lowercase(Locale.getDefault())
        val commandKeywords = listOf(
            "calendar", "appointment", "schedule", "add event", "meeting", "dental",
            "alarm", "timer", "wake me", "screen", "what do you see", "read my screen",
            "open ", "launch ", "play ", "pause", "resume", "weather", "temperature",
            "forecast", "notification", "message", "call ", "navigate", "directions",
            "settings", "wifi", "bluetooth", "volume", "brightness", "screenshot"
        )
        if (commandKeywords.any { lower.contains(it) }) return false
        if (lower.contains("research")) return true
        val questionStarters = listOf("what ", "why ", "how ", "who ", "when ", "where ", "explain ", "tell me ")
        val wordCount = lower.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return questionStarters.any { lower.startsWith(it) } || wordCount >= 3
    }

    // ─── Conversation mode helpers ───────────────────────────────────────────

    private fun startConversationMode() {
        if (!isRunning) return
        conversationActive = true
        updateNotification("Conversation active — listening...", true)
        Log.e("JARVIS_CMD", "Conversation mode activated (30s timeout)")
        conversationTimeout?.cancel()
        conversationTimeout = scope.launch {
            delay(30_000L)
            if (conversationActive) {
                conversationActive = false
                updateNotification("Listening for 'Hey Jarvis'...", false)
                playConversationFadeOut()
                Log.e("JARVIS_CMD", "Conversation mode timed out")
            }
        }
    }

    private fun endConversationMode() {
        conversationActive = false
        conversationTimeout?.cancel()
        conversationTimeout = null
        updateNotification("Listening for 'Hey Jarvis'...", false)
        Log.e("JARVIS_CMD", "Conversation mode ended by user")
    }

    private fun playConversationFadeOut() {
        try {
            val toneGen = android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40)
            toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 400)
            scope.launch { delay(500); toneGen.release() }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Fade-out tone error: ${e.message}")
        }
    }

    // ─── Wake beep (only here — no other ToneGenerator in the loop) ─────────

    private fun playWakeBeep() {
        try {
            val toneGen = android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            scope.launch { delay(200); toneGen.release() }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Service beep error: ${e.message}")
        }
    }

    // ─── Notification helpers ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Jarvis Listener", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Jarvis background wake word listener"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, awake: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, JarvisListenerService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String, awake: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, awake))
    }

    // ─── Recipe creation mode input handler ─────────────────────────────────
    private fun handleRecipeCreationInput(lower: String): Boolean {
        if (lower == "cancel recipe" || lower == "stop recipe" ||
            lower == "cancel recipe creation" || lower == "abort recipe") {
            recipeCreationMode = false
            pendingRecipeName = ""
            pendingRecipeSteps.clear()
            speakResponse("Recipe creation cancelled, sir.")
            return true
        }
        if (lower == "save recipe" || lower == "save it" || lower == "done" ||
            lower == "finish recipe" || lower == "save my recipe" || lower == "finished") {
            if (pendingRecipeSteps.isEmpty()) {
                speakResponse("No steps yet, sir. Say step one followed by your command.")
                return true
            }
            val name = pendingRecipeName
            val steps = pendingRecipeSteps.toList()
            RecipeManager.add(Recipe(name = name, trigger = name, steps = steps))
            recipeCreationMode = false
            pendingRecipeName = ""
            pendingRecipeSteps.clear()
            speakResponse("Recipe $name saved with ${steps.size} step${if (steps.size == 1) "" else "s"}, sir.")
            return true
        }
        val stepRegex = Regex(
            "^step\\s+(one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s*[:\\-,.]?\\s*(.+)$"
        )
        val match = stepRegex.find(lower)
        if (match != null) {
            val body = match.groupValues[2].trim()
            if (body.isEmpty()) {
                speakResponse("That step was empty, sir.")
                return true
            }
            pendingRecipeSteps.add(body)
            speakResponse("Step ${pendingRecipeSteps.size} saved, sir.")
            return true
        }
        return false
    }

    // ─── Recipe execution: run each step on Main with 2s delays ─────────────
    private fun runRecipeSteps(recipe: Recipe) {
        val steps = recipe.steps
        if (steps.isEmpty()) {
            speakResponse("Recipe ${recipe.name} has no steps, sir.")
            return
        }
        Log.e("JARVIS_CMD", "Running recipe '${recipe.name}' with ${steps.size} steps")
        speakResponse("Running ${recipe.name}, sir.")
        scope.launch {
            delay(1500)
            for ((i, step) in steps.withIndex()) {
                Log.e("JARVIS_CMD", "Recipe step ${i + 1}/${steps.size}: $step")
                withContext(Dispatchers.Main) {
                    try { executeCommandInService(step) } catch (e: Exception) {
                        Log.e("JARVIS_CMD", "Recipe step failed: ${e.message}")
                    }
                }
                delay(2000)
            }
            Log.e("JARVIS_CMD", "Recipe '${recipe.name}' complete")
        }
    }

    // ─── Volume helpers ─────────────────────────────────────────────────────
    private fun parseVolumePercent(text: String): Int? {
        val pctMatch = Regex("(\\d{1,3})\\s*%").find(text)
        if (pctMatch != null) return pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
        val numMatch = Regex("\\b(\\d{1,3})\\b").find(text)
        if (numMatch != null) return numMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
        return null
    }

    private fun setMusicVolumePercent(pct: Int) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * pct.coerceIn(0, 100) / 100).coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.e("JARVIS_CMD", "Set music volume to $pct% ($target/$max)")
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "setMusicVolumePercent failed: ${e.message}")
        }
    }

    // ─── DND via NotificationManager.setInterruptionFilter ──────────────────
    private fun setDoNotDisturb(enable: Boolean): Boolean {
        return try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) return false
            nm.setInterruptionFilter(
                if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            Log.e("JARVIS_CMD", "DND ${if (enable) "enabled (PRIORITY)" else "disabled (ALL)"}")
            true
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "setDoNotDisturb failed: ${e.message}")
            false
        }
    }

    // ─── Calendar create — hands-free, no agent involved ─────────────────────
    private fun handleCalendarInService(input: String, lower: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val actionHandler = CalendarActionHandler(applicationContext)
                val draft = actionHandler.mergeDraft(pendingCalendarDraft, input)
                val parsed = actionHandler.toParsedEvent(draft)
                if (parsed == null) {
                    pendingCalendarDraft = draft
                    withContext(Dispatchers.Main) {
                        speakResponse(actionHandler.followUpQuestion(draft))
                    }
                    return@launch
                }
                pendingCalendarDraft = null
                val result = CalendarActionHandler(applicationContext)
                    .addCalendarEvent(parsed.title, parsed.startTime, parsed.endTime, parsed.location)
                Log.e("JARVIS_CMD", "Calendar event inserted: ${result.eventUri}")
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        speakResponse("Your event has been added")
                    } else {
                        speakResponse("Couldn't add the event sir. ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Calendar insert failed: ${e.message}", e)
                withContext(Dispatchers.Main) { speakResponse("I need the event title, date, and time before I can add it.") }
            }
        }
    }

    // ─── Screen vision — tries stored MediaProjection first, falls back to broadcast ──
    private fun handleScreenVision() {
        Log.e("JARVIS_CMD", "Screen vision requested")
        val summary = ScreenContentRepository.currentSummary()
        if (summary.isNotBlank()) {
            Log.e("JARVIS_CMD", "Raw screen text: ${ScreenContentRepository.currentText().take(1000)}")
            speakResponse(summary)
            return
        } else {
            speakResponse("Let me take a look, sir.")
        }

        // Try 1: Use stored MediaProjection instance directly (works from any app)
        val proj = mediaProjectionInstance
        if (proj != null) {
            captureWithProjection(proj)
            return
        }

        // Try 2: Rebuild projection from stored token
        if (storedResultCode != 0 && storedProjectionData != null) {
            try {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val rebuilt = pm.getMediaProjection(storedResultCode, storedProjectionData!!)
                mediaProjectionInstance = rebuilt
                JarvisListenerService.mediaProjectionInstance = rebuilt
                Log.e("JARVIS_CMD", "MediaProjection rebuilt from stored token")
                captureWithProjection(rebuilt)
                return
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Projection rebuild failed: ${e.message}")
            }
        }

        // Try 3: Ask MainActivity via static channel + broadcast (requires app in foreground)
        Log.e("JARVIS_CMD", "No projection — asking MainActivity via static channel")
        JarvisListenerService.pendingVisionResult = null
        JarvisListenerService.visionResultReady = false
        applicationContext.sendBroadcast(
            Intent("JARVIS_TAKE_SCREENSHOT").setPackage(packageName)
        )
        scope.launch {
            var waited = 0
            while (waited < 15000) {
                delay(300)
                waited += 300
                if (JarvisListenerService.visionResultReady) {
                    val result = JarvisListenerService.pendingVisionResult
                        ?: "I couldn't see the screen, sir."
                    JarvisListenerService.visionResultReady = false
                    JarvisListenerService.pendingVisionResult = null
                    Log.e("JARVIS_CMD", "Vision result from static channel: $result")
                    speakResponse(result)
                    return@launch
                }
            }
            Log.e("JARVIS_CMD", "Vision timeout after 15s")
            speakResponse("I couldn't see the screen, sir. Please open Jarvis once to enable screen vision.")
        }
    }

    private fun captureWithProjection(projection: MediaProjection) {
        scope.launch(Dispatchers.IO) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val d = metrics.densityDpi

                val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                val vd = projection.createVirtualDisplay(
                    "JarvisCapture", w, h, d,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
                delay(800)
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val ps = planes[0].pixelStride
                    val rs = planes[0].rowStride
                    val rp = rs - ps * w
                    val bmp = Bitmap.createBitmap(w + rp / ps, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    image.close()
                    vd.release()
                    reader.close()
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    val stream = java.io.ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 55, stream)
                    val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                    Log.e("JARVIS_CMD", "Projection capture: ${base64.length} chars")
                    sendToClaudeVision(base64) { result ->
                        Log.e("JARVIS_CMD", "Projection vision result: $result")
                        scope.launch(Dispatchers.Main) { speakResponse(result) }
                    }
                } else {
                    vd.release()
                    reader.close()
                    withContext(Dispatchers.Main) {
                        speakResponse("I couldn't capture the screen clearly, sir.")
                    }
                }
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "captureWithProjection failed: ${e.message}", e)
                // Projection expired — clear so next call triggers rebuild or broadcast
                mediaProjectionInstance = null
                JarvisListenerService.mediaProjectionInstance = null
                withContext(Dispatchers.Main) {
                    speakResponse("Screen vision expired sir, please open Jarvis once to refresh it.")
                }
            }
        }
    }

    private fun sendToClaudeVision(base64: String, onResult: (String) -> Unit) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 300)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", base64)
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "Describe what is visible on this phone screen concisely in 2-3 sentences, as Jarvis would report to Tony Stark.")
                            })
                        })
                    })
                })
            }
            val response = client.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val raw = response.body?.string() ?: run { onResult("No response from vision, sir."); return }
            if (!response.isSuccessful) { onResult("Vision API returned ${response.code}, sir."); return }
            val result = JSONObject(raw).getJSONArray("content").getJSONObject(0).getString("text")
                .replace("**", "").replace("*", "").trim()
            onResult(result)
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Vision API error: ${e.message}")
            onResult("Screen vision failed, sir.")
        }
    }

    // ─── Calendar day helper ──────────────────────────────────────────────────
    private fun setToNextDay(cal: java.util.Calendar, targetDay: Int) {
        val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
        var daysToAdd = targetDay - today
        if (daysToAdd <= 0) daysToAdd += 7
        cal.add(java.util.Calendar.DAY_OF_YEAR, daysToAdd)
    }
}
