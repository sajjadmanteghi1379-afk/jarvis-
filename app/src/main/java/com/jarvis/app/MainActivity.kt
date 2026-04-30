package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import kotlin.system.exitProcess


const val ANTHROPIC_API_KEY = "REMOVED"
const val ELEVENLABS_API_KEY = "DISABLED"
const val ELEVENLABS_VOICE_ID = "pNInz6obpgDQGcFmaJgB"
const val FIREBASE_PATH = "/jarvis"

const val OPENWEATHER_API_KEY = "269c87831c17b789a8f00d0cc3a92fef"
const val NEWSAPI_KEY = "eeb6840e13904b2b9a057b3866b1aff4"
const val USER_CITY = "Budapest"
const val USER_COUNTRY = "hu"

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener, AgentHost {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var isCurrentlySpeaking = false
    private var continuousListening = false
    private var originalSystemVolume = 0
    private var originalNotificationVolume = 0
    private val database by lazy { FirebaseDatabase.getInstance() }
    private var sessionLanguage = "english"
    var wakeWordEnabled by mutableStateOf(false)
    private var proactiveMonitor: JarvisProactive? = null
    private val pendingCommand = mutableStateOf<String?>(null)
    private var enrollmentPending by mutableStateOf(false)
    private var speakerVerifier: JarvisSpeakerVerifier? = null
    var speakerVerificationEnabled by mutableStateOf(false)
    // Agent infrastructure
    private var jarvisAgent: JarvisAgent? = null
    private var screenCaptureCallback: ((String) -> Unit)? = null
    private var pendingYesNoCallback: ((Boolean) -> Unit)? = null

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            takeScreenshotAndAnalyze()
        }
    }

    override val activity: Activity get() = this

    private val serviceCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                JarvisListenerService.ACTION_COMMAND -> {
                    val command = intent.getStringExtra(JarvisListenerService.EXTRA_COMMAND) ?: return
                    android.util.Log.e("JARVIS_CMD", "Service command received: $command")
                    pendingCommand.value = command
                }
                JarvisListenerService.ACTION_WAKE -> {
                    Log.e("JARVIS_CMD", "Wake word received from service")
                    playActivationBeep()
                }
                JarvisListenerService.ACTION_SLEEP -> {
                    Log.e("JARVIS_CMD", "Sleep word received from service")
                }
                ScreenCaptureService.ACTION_RESULT -> {
                    val err = intent.getStringExtra("error")
                    val text = intent.getStringExtra("text")
                    val message = err ?: text ?: "Screen vision returned nothing, sir."
                    Log.e("JARVIS_CMD", "Screen vision result (agent): $message")
                    val cb = screenCaptureCallback
                    if (cb != null) { screenCaptureCallback = null; cb(message) }
                    else speakText(message)
                }
                "JARVIS_VISION_RESULT" -> {
                    val result = intent.getStringExtra("result") ?: "Screen vision returned nothing, sir."
                    Log.e("JARVIS_CMD", "Screen vision result from view-draw path: $result")
                    speakText(result)
                }
            }
        }
    }

    @Suppress("unused")
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        perms.forEach { android.util.Log.e("JARVIS_CMD", "Permission ${it.key}: ${it.value}") }
        if (perms[Manifest.permission.RECORD_AUDIO] == true && !JarvisListenerService.isRunning) {
            try {
                startForegroundService(Intent(this, JarvisListenerService::class.java))
                Log.e("JARVIS_CMD", "JarvisListenerService started after RECORD_AUDIO granted")
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Failed to start service after permission: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JARVIS_CMD", "UNCAUGHT CRASH on ${thread.name}: ${throwable.message}", throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        try {
            super.onCreate(savedInstanceState)
            tts = TextToSpeech(this, this)
            val permsToRequest = mutableListOf<String>()
            val permsNeeded = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.CHANGE_WIFI_STATE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permsNeeded.forEach { perm ->
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                    permsToRequest.add(perm)
            }
            if (permsToRequest.isNotEmpty()) requestMultiplePermissionsLauncher.launch(permsToRequest.toTypedArray())
            // WRITE_SETTINGS requires special grant (used by set_brightness)
            if (!android.provider.Settings.System.canWrite(this)) {
                Log.e("JARVIS_CMD", "WRITE_SETTINGS not granted (brightness control will prompt on first use)")
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.e("JARVIS_CMD", "SYSTEM_ALERT_WINDOW not granted — background launch limited")
            }
            try {
                speakerVerifier = JarvisSpeakerVerifier(this)
                speakerVerificationEnabled = speakerVerifier?.isEnrolled() == true
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Startup crash: SpeakerVerifier: ${e.message}", e)
            }
            try { JarvisMemory.init(applicationContext) } catch (e: Exception) {
                Log.e("JARVIS_CMD", "JarvisMemory init failed: ${e.message}")
            }
            setContent { JarvisTheme { JarvisScreen() } }
            try {
                proactiveMonitor = JarvisProactive(this) { alert: String -> speakText(alert) }
                proactiveMonitor?.startMonitoring()
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Startup crash: ProactiveMonitor: ${e.message}", e)
            }
            try {
                jarvisAgent = JarvisAgent(applicationContext, this)
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Startup crash: JarvisAgent: ${e.message}", e)
            }
            try {
                JarvisProactiveAgent.schedule(applicationContext)
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "ProactiveAgent schedule failed: ${e.message}")
            }
            muteRecognitionBeep()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val serviceIntent = Intent(this, JarvisListenerService::class.java)
                    startForegroundService(serviceIntent)
                    Log.e("JARVIS_CMD", "JarvisListenerService started from MainActivity")
                } catch (e: Exception) {
                    Log.e("JARVIS_CMD", "Failed to start service: ${e.message}")
                }
            } else {
                Log.e("JARVIS_CMD", "RECORD_AUDIO not granted yet — deferring service start until permission granted")
            }
            ensureNotificationListenerAccess()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "ONCREATE CRASH: ${e.message}", e)
        }
    }

    private fun ensureNotificationListenerAccess() {
        try {
            val enabled = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(this)
                .contains(packageName)
            if (!enabled) {
                val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("notification_prompt_shown", false)) {
                    prefs.edit().putBoolean("notification_prompt_shown", true).apply()
                    Log.e("JARVIS_CMD", "Notification listener not granted — will work without it")
                }
            } else {
                Log.e("JARVIS_CMD", "Notification listener access granted")
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Notification listener check failed: ${e.message}")
        }
    }

    private fun muteRecognitionBeep() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            originalSystemVolume = am.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM)
            originalNotificationVolume = am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)
            am.setStreamVolume(android.media.AudioManager.STREAM_SYSTEM, 0, 0)
            am.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0)
        } catch (e: Exception) { Log.e("JARVIS_CMD", "Mute error: ${e.message}") }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(0.85f)
            tts.setPitch(0.75f)
            val voices = tts.voices
            val preferredVoice = voices?.find { v ->
                v.name.contains("en-us", ignoreCase = true) &&
                        !v.name.contains("female", ignoreCase = true) &&
                        v.name.contains("male", ignoreCase = true)
            } ?: voices?.find { v ->
                v.name.contains("en", ignoreCase = true) &&
                        v.quality >= android.speech.tts.Voice.QUALITY_NORMAL
            }
            preferredVoice?.let { tts.voice = it }
            isTtsReady = true
        }
    }

    private fun setLanguageFarsi() {
        val farsiLocale = Locale("fa", "IR")
        val result = tts.isLanguageAvailable(farsiLocale)
        if (result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
            tts.language = farsiLocale
            android.util.Log.e("JARVIS_CMD", "TTS: Farsi language set successfully")
        } else {
            android.util.Log.e("JARVIS_CMD", "TTS: Farsi not available, staying English")
            tts.language = Locale.US
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(JarvisListenerService.ACTION_COMMAND)
            addAction(JarvisListenerService.ACTION_WAKE)
            addAction(JarvisListenerService.ACTION_SLEEP)
            addAction(ScreenCaptureService.ACTION_RESULT)
            addAction("JARVIS_VISION_RESULT")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, serviceCommandReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenCaptureReceiver, IntentFilter("JARVIS_TAKE_SCREENSHOT"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(serviceCommandReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(screenCaptureReceiver) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousListening = false
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(android.media.AudioManager.STREAM_SYSTEM, originalSystemVolume, 0)
            am.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, originalNotificationVolume, 0)
        } catch (e: Exception) {}
        tts.stop(); tts.shutdown()
        mediaPlayer?.release()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
    }

    private fun shutdownApp() {
        continuousListening = false
        try {
            tts.stop(); tts.shutdown()
            mediaPlayer?.release()
            if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        } catch (_: Exception) {}
        finishAffinity()
        CoroutineScope(Dispatchers.Main).launch {
            delay(500)
            exitProcess(0)
        }
    }

    private fun takeScreenshotAndAnalyze() {
        try {
            val rootView = window.decorView.rootView
            val bitmap = android.graphics.Bitmap.createBitmap(rootView.width, rootView.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            rootView.draw(canvas)
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, stream)
            val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            Log.e("JARVIS_CMD", "Screenshot taken: ${base64.length} chars")
            CoroutineScope(Dispatchers.IO).launch {
                val result = sendToClaudeVision(base64)
                Log.e("JARVIS_CMD", "Vision result: $result")
                // Write to static field — service polls this
                JarvisListenerService.pendingVisionResult = result
                JarvisListenerService.visionResultReady = true
                withContext(Dispatchers.Main) {
                    val resultIntent = Intent("JARVIS_VISION_RESULT").apply {
                        setPackage(packageName)
                        putExtra("result", result)
                    }
                    sendBroadcast(resultIntent)
                    Log.e("JARVIS_CMD", "Vision result written to static channel and broadcast")
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Screenshot failed: ${e.message}")
            val errMsg = "Screenshot failed sir: ${e.message}"
            JarvisListenerService.pendingVisionResult = errMsg
            JarvisListenerService.visionResultReady = true
            val resultIntent = Intent("JARVIS_VISION_RESULT").apply {
                setPackage(packageName)
                putExtra("result", errMsg)
            }
            sendBroadcast(resultIntent)
        }
    }

    private suspend fun sendToClaudeVision(base64: String): String = withContext(Dispatchers.IO) {
        try {
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
            val response = researchClient.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val raw = response.body?.string() ?: return@withContext "No response from vision, sir."
            if (!response.isSuccessful) return@withContext "Vision API returned ${response.code}, sir."
            JSONObject(raw).getJSONArray("content").getJSONObject(0).getString("text")
                .replace("**", "").replace("*", "").trim()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Vision API error: ${e.message}")
            "Screen vision failed, sir."
        }
    }

    private fun playActivationBeep() {
        try {
            val toneGen = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            CoroutineScope(Dispatchers.Main).launch {
                delay(200)
                toneGen.release()
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Beep error: ${e.message}")
        }
    }

    private fun handleUserInputFromService(command: String) {
        pendingCommand.value = command
    }

    private fun speakWithTTS(text: String, onDone: () -> Unit = {}) {
        isCurrentlySpeaking = true
        // Stop mic immediately so Jarvis never hears its own voice
        if (::speechRecognizer.isInitialized) speechRecognizer.stopListening()
        if (!isTtsReady) {
            CoroutineScope(Dispatchers.Main).launch { isCurrentlySpeaking = false; onDone() }
            return
        }
        val uttId = "jarvis_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(uid: String?) {}
            override fun onDone(uid: String?) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(400)
                    isCurrentlySpeaking = false
                    onDone()
                }
            }
            override fun onError(uid: String?) {
                CoroutineScope(Dispatchers.Main).launch { isCurrentlySpeaking = false; onDone() }
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    private fun speakWithElevenLabs(text: String, onDone: () -> Unit = {}) {
        isCurrentlySpeaking = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val body = JSONObject().apply {
                    put("text", text)
                    put("model_id", "eleven_turbo_v2_5")
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5); put("similarity_boost", 0.75)
                    })
                }
                val request = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$ELEVENLABS_VOICE_ID")
                    .addHeader("xi-api-key", ELEVENLABS_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        val f = File(cacheDir, "jarvis.mp3")
                        FileOutputStream(f).use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            try { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null } catch (_: Exception) {}
                            mediaPlayer = MediaPlayer().apply {
                                setDataSource(f.absolutePath); prepare(); start()
                                setOnCompletionListener { isCurrentlySpeaking = false; onDone() }
                                setOnErrorListener { _, _, _ -> isCurrentlySpeaking = false; speakWithTTS(text, onDone); true }
                            }
                        }
                    }
                } else withContext(Dispatchers.Main) { speakWithTTS(text, onDone) }
            } catch (_: Exception) { withContext(Dispatchers.Main) { speakWithTTS(text, onDone) } }
        }
    }

    private fun speakText(text: String, onDone: () -> Unit = {}) {
        if (ELEVENLABS_API_KEY != "DISABLED" && ELEVENLABS_API_KEY != "YOUR_ELEVENLABS_API_KEY") {
            speakWithElevenLabs(text, onDone)
        } else speakWithTTS(text, onDone)
    }

    private fun startListeningInternal(onResult: (String) -> Unit, onError: () -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { onError(); return }
        try {
            if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                        val raw = matches[0]
                        if (wakeWordEnabled && continuousListening) {
                            val l = raw.lowercase()
                            val wakeWords = listOf("hey jarvis", "ok jarvis", "jarvis, ", "hey j.a.r.v.i.s")
                            val matched = wakeWords.firstOrNull { l.startsWith(it) }
                            if (matched != null) {
                                val cmd = raw.substring(matched.length).trim()
                                Log.e("JARVIS_CMD", "WAKE_WORD: detected, cmd='$cmd'")
                                if (cmd.isNotBlank()) onResult(cmd)
                                else if (continuousListening) CoroutineScope(Dispatchers.Main).launch {
                                    delay(200); if (continuousListening) startListeningInternal(onResult, onError)
                                }
                            } else {
                                Log.e("JARVIS_CMD", "WAKE_WORD: no wake word in '$raw', ignoring")
                                if (continuousListening) CoroutineScope(Dispatchers.Main).launch {
                                    delay(200); if (continuousListening) startListeningInternal(onResult, onError)
                                }
                            }
                        } else {
                            onResult(raw)
                        }
                    } else if (continuousListening) CoroutineScope(Dispatchers.Main).launch {
                        delay(200); if (continuousListening) startListeningInternal(onResult, onError)
                    }
                }
                override fun onError(error: Int) {
                    if (continuousListening) CoroutineScope(Dispatchers.Main).launch {
                        delay(500); if (continuousListening) startListeningInternal(onResult, onError)
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            if (continuousListening) CoroutineScope(Dispatchers.Main).launch {
                delay(1000); if (continuousListening) startListeningInternal(onResult, onError)
            }
        }
    }

    private fun stopListening() {
        continuousListening = false
        if (::speechRecognizer.isInitialized) { speechRecognizer.stopListening(); speechRecognizer.destroy() }
    }

    private fun openApp(packageName: String): Boolean {
        android.util.Log.e("JARVIS_CMD", "Attempting to open package: $packageName")
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            android.util.Log.e("JARVIS_CMD", "Intent for $packageName: $intent")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = packageName
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(launchIntent)
                true
            }
        } catch (ex: Exception) {
            android.util.Log.e("JARVIS_CMD", "Exception: ${ex.message}")
            false
        }
    }

    private fun extractAfter(text: String, keywords: List<String>): String {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx >= 0) { val r = text.substring(idx + kw.length).trim(); if (r.isNotEmpty()) return r }
        }
        return ""
    }

    private fun extractNumber(text: String): Int {
        val words = mapOf("one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,
            "six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10,
            "eleven" to 11,"twelve" to 12,"thirteen" to 13,"fourteen" to 14,
            "fifteen" to 15,"sixteen" to 16,"seventeen" to 17,"eighteen" to 18,
            "nineteen" to 19,"twenty" to 20,"thirty" to 30,"forty five" to 45,
            "forty" to 40,"fifty" to 50,"sixty" to 60,"ninety" to 90)
        for ((w, n) in words) if (text.contains(w)) return n
        return Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 0
    }

    private fun extractTime(text: String): Pair<Int, Int>? {
        val normalized = text.replace(".", "").replace("o'clock", "").trim()
        android.util.Log.e("JARVIS_CMD", "extractTime normalized: '$normalized'")

        val rHalfPast = Regex("half past (\\w+)", RegexOption.IGNORE_CASE).find(normalized)
        if (rHalfPast != null) {
            val h = parseHourWord(rHalfPast.groupValues[1])
            if (h != null) { android.util.Log.e("JARVIS_CMD", "Matched half past: $h:30"); return Pair(h, 30) }
        }
        val rQuarterTo = Regex("quarter to (\\w+)", RegexOption.IGNORE_CASE).find(normalized)
        if (rQuarterTo != null) {
            val h = parseHourWord(rQuarterTo.groupValues[1])
            if (h != null) { android.util.Log.e("JARVIS_CMD", "Matched quarter to: ${if (h > 0) h - 1 else 23}:45"); return Pair(if (h > 0) h - 1 else 23, 45) }
        }
        val rQuarterPast = Regex("quarter past (\\w+)", RegexOption.IGNORE_CASE).find(normalized)
        if (rQuarterPast != null) {
            val h = parseHourWord(rQuarterPast.groupValues[1])
            if (h != null) { android.util.Log.e("JARVIS_CMD", "Matched quarter past: $h:15"); return Pair(h, 15) }
        }

        val r1 = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m|p\\.m|AM|PM)", RegexOption.IGNORE_CASE).find(normalized)
        if (r1 != null) {
            android.util.Log.e("JARVIS_CMD", "Matched with am/pm: ${r1.value}")
            var h = r1.groupValues[1].toIntOrNull() ?: return null
            val m = r1.groupValues[2].toIntOrNull() ?: 0
            val ap = r1.groupValues[3].lowercase().replace(".", "")
            if (ap == "pm" && h != 12) h += 12
            if (ap == "am" && h == 12) h = 0
            return Pair(h, m)
        }
        val r2 = Regex("(\\d{1,2}):(\\d{2})").find(normalized)
        if (r2 != null) {
            android.util.Log.e("JARVIS_CMD", "Matched with colon: ${r2.value}")
            val h = r2.groupValues[1].toIntOrNull() ?: return null
            val m = r2.groupValues[2].toIntOrNull() ?: 0
            return Pair(h, m)
        }
        val r3 = Regex("for\\s+(\\d{1,2})").find(normalized)
        if (r3 != null) {
            android.util.Log.e("JARVIS_CMD", "Matched with 'for N': ${r3.value}")
            val h = r3.groupValues[1].toIntOrNull() ?: return null
            return Pair(h, 0)
        }
        android.util.Log.e("JARVIS_CMD", "No time pattern matched")
        return null
    }

    private fun parseHourWord(word: String): Int? {
        val wordMap = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12)
        return wordMap[word.lowercase()] ?: word.toIntOrNull()
    }

    private fun extractDate(text: String): Calendar? {
        val lower = text.lowercase()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (lower.contains("today")) { android.util.Log.e("JARVIS_CMD", "extractDate: today"); return cal }
        if (lower.contains("tomorrow")) { cal.add(Calendar.DAY_OF_YEAR, 1); android.util.Log.e("JARVIS_CMD", "extractDate: tomorrow"); return cal }
        val dayNames = mapOf("monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY, "sunday" to Calendar.SUNDAY)
        for ((name, dayConst) in dayNames) {
            if (lower.contains(name)) {
                val current = cal.get(Calendar.DAY_OF_WEEK)
                var daysUntil = dayConst - current
                if (daysUntil <= 0) daysUntil += 7
                cal.add(Calendar.DAY_OF_YEAR, daysUntil)
                android.util.Log.e("JARVIS_CMD", "extractDate: $name in $daysUntil days")
                return cal
            }
        }
        val months = mapOf("january" to 0, "february" to 1, "march" to 2, "april" to 3,
            "may" to 4, "june" to 5, "july" to 6, "august" to 7,
            "september" to 8, "october" to 9, "november" to 10, "december" to 11)
        for ((name, monthIdx) in months) {
            if (lower.contains(name)) {
                val dayMatch = Regex("(\\d{1,2})(?:st|nd|rd|th)?").find(lower)
                val day = dayMatch?.groupValues?.get(1)?.toIntOrNull() ?: continue
                cal.set(Calendar.MONTH, monthIdx)
                cal.set(Calendar.DAY_OF_MONTH, day)
                if (cal.timeInMillis < System.currentTimeMillis()) cal.add(Calendar.YEAR, 1)
                android.util.Log.e("JARVIS_CMD", "extractDate: $name $day")
                return cal
            }
        }
        android.util.Log.e("JARVIS_CMD", "extractDate: no date found")
        return null
    }

    private fun extractEventTitle(text: String): String {
        val lower = text.lowercase()
        for (kw in listOf("called ", "titled ", "named ", "title ", "label ")) {
            val idx = lower.indexOf(kw)
            if (idx >= 0) {
                val raw = text.substring(idx + kw.length).trim()
                val cleaned = raw.replace(Regex("\\s*(at \\d|today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday).*", RegexOption.IGNORE_CASE), "").trim()
                if (cleaned.isNotEmpty()) { android.util.Log.e("JARVIS_CMD", "extractEventTitle: '$cleaned'"); return cleaned }
            }
        }
        return ""
    }

    private fun readCalendarEvents(startMs: Long, endMs: Long): List<String> {
        // Log all available calendar accounts for debugging
        try {
            val calProj = arrayOf(CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME)
            val calCursor = contentResolver.query(CalendarContract.Calendars.CONTENT_URI, calProj, null, null, null)
            calCursor?.use {
                var count = 0
                while (it.moveToNext()) {
                    count++
                    android.util.Log.e("JARVIS_CMD", "Calendar[$count]: id=${it.getLong(0)} name='${it.getString(1)}' account=${it.getString(2)}")
                }
                android.util.Log.e("JARVIS_CMD", "Total calendars found: $count")
            }
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Calendar list error: ${e.message}") }

        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.ALL_DAY)
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DELETED} != 1"
        val events = mutableListOf<String>()
        try {
            val cursor = contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection,
                arrayOf(startMs.toString(), endMs.toString()),
                "${CalendarContract.Events.DTSTART} ASC")
            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "Untitled"
                    val start = it.getLong(1)
                    val allDay = it.getInt(2) == 1
                    if (allDay) {
                        events.add("$title all day")
                    } else {
                        val sdf = SimpleDateFormat("h:mma", Locale.getDefault())
                        val timeStr = sdf.format(Date(start)).lowercase().replace(":00", "")
                        events.add("at $timeStr $title")
                    }
                }
            }
            android.util.Log.e("JARVIS_CMD", "Calendar events found: ${events.size}")
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Calendar read error: ${e.message}") }
        return events
    }

    private fun createCalendarEventDirect(title: String, startMs: Long, endMs: Long): Boolean {
        var calId = 1L
        try {
            val c = contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID), null, null, null)
            c?.use { if (it.moveToFirst()) calId = it.getLong(0) }
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CalendarId fetch error: ${e.message}") }
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            android.util.Log.e("JARVIS_CMD", "Calendar event created: $uri")
            uri != null
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Calendar insert error: ${e.message}"); false }
    }

    private fun deleteCalendarEvent(titleQuery: String?, hour: Int?, minute: Int?): Boolean {
        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART)
        val now = System.currentTimeMillis()
        val weekEnd = now + 7L * 24 * 60 * 60 * 1000
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DELETED} != 1"
        try {
            val cursor = contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection,
                arrayOf(now.toString(), weekEnd.toString()), "${CalendarContract.Events.DTSTART} ASC")
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0); val title = it.getString(1) ?: ""; val startMs = it.getLong(2)
                    val evCal = Calendar.getInstance().apply { timeInMillis = startMs }
                    val eH = evCal.get(Calendar.HOUR_OF_DAY); val eM = evCal.get(Calendar.MINUTE)
                    val titleMatch = titleQuery != null && title.lowercase().contains(titleQuery.lowercase())
                    val timeMatch = hour != null && eH == hour && (minute == null || eM == minute)
                    if (titleMatch || timeMatch) {
                        contentResolver.delete(CalendarContract.Events.CONTENT_URI,
                            "${CalendarContract.Events._ID} = ?", arrayOf(id.toString()))
                        android.util.Log.e("JARVIS_CMD", "Calendar event deleted: $title")
                        return true
                    }
                }
            }
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Calendar delete error: ${e.message}") }
        return false
    }

    private fun trySetAlarm(hour: Int, minute: Int): Boolean {
        android.util.Log.e("JARVIS_CMD", "trySetAlarm: hour=$hour minute=$minute")
        try {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm"); putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            android.util.Log.e("JARVIS_CMD", "Alarm attempt 1 success (SKIP_UI=true)"); return true
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Alarm attempt 1 failed: ${e.message}") }
        try {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm"); putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            android.util.Log.e("JARVIS_CMD", "Alarm attempt 2 success (SKIP_UI=false)"); return true
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Alarm attempt 2 failed: ${e.message}") }
        try {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm")
                `package` = "com.sec.android.app.clockpackage"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            android.util.Log.e("JARVIS_CMD", "Alarm attempt 3 success (Samsung package)"); return true
        } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Alarm attempt 3 failed: ${e.message}") }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    // MEDIA KEY CONTROLS
    // ═══════════════════════════════════════════════════════════════
    private fun sendMediaKey(keyCode: Int) {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            audioManager.dispatchMediaKeyEvent(eventDown)
            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(eventUp)
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_CMD", "Media key failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RESEARCH WITH PDF + PC SYNC (multi-source: Wikipedia + DuckDuckGo + Claude)
    // ═══════════════════════════════════════════════════════════════
    // outputMode: "pdf" | "tell" | "read" | "pc"
    private val researchClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private suspend fun researchTopic(topic: String, outputMode: String): String = withContext(Dispatchers.IO) {
        try {
            android.util.Log.e("JARVIS_CMD", "RESEARCH: topic='$topic' mode='$outputMode'")

            val (wikiText, ddgText) = kotlinx.coroutines.coroutineScope {
                val wikiDeferred = async {
                    try {
                        val encodedTopic = android.net.Uri.encode(topic)
                        val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTopic"
                        android.util.Log.e("JARVIS_CMD", "RESEARCH: Wikipedia URL=$wikiUrl")
                        val wikiRequest = Request.Builder()
                            .url(wikiUrl)
                            .addHeader("User-Agent", "JarvisAndroidApp/1.0 (sajjad.manteghi@gmail.com)")
                            .addHeader("Accept", "application/json")
                            .build()
                        val wikiResponse = researchClient.newCall(wikiRequest).execute()
                        val code = wikiResponse.code
                        android.util.Log.e("JARVIS_CMD", "RESEARCH: Wikipedia code=$code")
                        if (wikiResponse.isSuccessful) {
                            val body = wikiResponse.body?.string() ?: ""
                            val json = JSONObject(body)
                            val wikiText = json.optString("extract", "")
                            android.util.Log.e("JARVIS_CMD", "RESEARCH: Wikipedia got ${wikiText.length} chars")
                            wikiText.take(1500)
                        } else {
                            android.util.Log.e("JARVIS_CMD", "RESEARCH: Wikipedia failed code=$code")
                            ""
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("JARVIS_CMD", "Wikipedia fetch failed: ${e.message}"); ""
                    }
                }
                val ddgDeferred = async {
                    try {
                        val encodedTopic = android.net.Uri.encode(topic)
                        val url = "https://api.duckduckgo.com/?q=$encodedTopic&format=json&no_redirect=1&no_html=1"
                        val resp = researchClient.newCall(
                            Request.Builder().url(url)
                                .addHeader("User-Agent", "JarvisAndroidApp/1.0 (sajjad.manteghi@gmail.com)")
                                .build()
                        ).execute()
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val abstract = json.optString("Abstract", "")
                        val answer = json.optString("Answer", "")
                        val result = listOf(abstract, answer).filter { it.isNotEmpty() }.joinToString(". ").take(800)
                        android.util.Log.e("JARVIS_CMD", "RESEARCH: DDG length=${result.length}")
                        result
                    } catch (e: Exception) {
                        android.util.Log.e("JARVIS_CMD", "DuckDuckGo fetch failed: ${e.message}"); ""
                    }
                }
                Pair(wikiDeferred.await(), ddgDeferred.await())
            }

            val sourceMaterial = buildString {
                if (wikiText.isNotEmpty()) append("WIKIPEDIA:\n$wikiText\n\n")
                if (ddgText.isNotEmpty()) append("DUCKDUCKGO INSTANT ANSWER:\n$ddgText\n\n")
            }

            val prompt = """Here is Wikipedia: ${if (wikiText.isNotEmpty()) wikiText else "(not available)"}. Here is DuckDuckGo: ${if (ddgText.isNotEmpty()) ddgText else "(not available)"}. Synthesize into comprehensive report on "$topic" with sections: Overview, Key Concepts, Recent Developments, Practical Applications, Sources Cited. Minimum 600 words. Be detailed and professional."""

            android.util.Log.e("JARVIS_CMD", "RESEARCH: sending ${prompt.length} chars to Claude")

            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 3000)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }

            val response = researchClient.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("User-Agent", "JarvisAndroidApp/1.0 (sajjad.manteghi@gmail.com)")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            val respBody = response.body?.string() ?: return@withContext "Research failed, sir."
            val researchText = JSONObject(respBody).getJSONArray("content")
                .getJSONObject(0).getString("text")

            android.util.Log.e("JARVIS_CMD", "RESEARCH: Claude response=${researchText.length} chars")

            if (outputMode == "pc") {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val safeName = topic.replace(" ", "_").replace(Regex("[^A-Za-z0-9_]"), "")
                database.getReference("$FIREBASE_PATH/pc_research_queue").push().setValue(
                    mapOf("topic" to topic, "content" to researchText, "timestamp" to timestamp,
                        "filename" to "Jarvis_Research_${safeName}_$timestamp.txt", "source" to "Android")
                )
                android.util.Log.e("JARVIS_CMD", "Research: synced to PC Firebase")
            }

            val sourcesList = listOfNotNull(
                if (wikiText.isNotEmpty()) "Wikipedia" else null,
                if (ddgText.isNotEmpty()) "DuckDuckGo Instant Answer" else null,
                "Claude AI Synthesis"
            )
            val pdfContent = researchText + "\n\nSOURCES: ${sourcesList.joinToString(", ")}"
            android.util.Log.e("JARVIS_CMD", "RESEARCH: about to save PDF, content length = ${pdfContent.length}")
            savePdf(topic, pdfContent)
            researchText
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_CMD", "Research coroutine error: ${e.message}")
            "Research error, sir: ${e.message?.take(50)}"
        }
    }

    private fun savePdf(topic: String, content: String) {
        try {
            android.util.Log.e("JARVIS_CMD", "PDF: savePdf() called")
            val pdfDoc = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint().apply {
                textSize = 11f; color = android.graphics.Color.BLACK
            }
            val titlePaint = android.graphics.Paint().apply {
                textSize = 18f; color = android.graphics.Color.BLACK
                isFakeBoldText = true
            }
            val pageWidth = 595; val pageHeight = 842
            val margin = 40f; val lineHeight = 16f
            var pageNum = 1
            var pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            var page = pdfDoc.startPage(pageInfo)
            var canvas = page.canvas
            var y = margin + 30f

            canvas.drawText("Jarvis Research Report", margin, y, titlePaint)
            y += 25f
            canvas.drawText("Topic: ${topic.take(60)}", margin, y, paint)
            y += 20f
            canvas.drawText("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}", margin, y, paint)
            y += 25f

            val maxLineWidth = pageWidth - 2 * margin
            val words = content.split(" ")
            var currentLine = ""
            for (word in words) {
                val test = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(test) <= maxLineWidth) {
                    currentLine = test
                } else {
                    canvas.drawText(currentLine, margin, y, paint)
                    y += lineHeight
                    currentLine = word
                    if (y > pageHeight - margin) {
                        pdfDoc.finishPage(page)
                        pageNum++
                        pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                        page = pdfDoc.startPage(pageInfo)
                        canvas = page.canvas
                        y = margin + 20f
                    }
                }
            }
            if (currentLine.isNotEmpty()) canvas.drawText(currentLine, margin, y, paint)
            pdfDoc.finishPage(page)

            val safeName = topic.replace(" ", "_").replace(Regex("[^A-Za-z0-9_]"), "").take(40)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Jarvis_${safeName}_$timestamp.pdf"

            val resolver = contentResolver
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    android.util.Log.e("JARVIS_CMD", "PDF: writing to path = $uri")
                    resolver.openOutputStream(uri)?.use { out ->
                        pdfDoc.writeTo(out)
                    }
                    android.util.Log.e("JARVIS_CMD", "PDF: write complete")
                    pdfDoc.close()
                    android.util.Log.e("JARVIS_CMD", "PDF: MediaStore save success, uri=$uri")
                    return
                } else {
                    android.util.Log.e("JARVIS_CMD", "PDF: MediaStore insert returned null uri")
                }
            }
            // Fallback for older Android
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            android.util.Log.e("JARVIS_CMD", "PDF: writing to path = ${file.absolutePath}")
            pdfDoc.writeTo(FileOutputStream(file))
            android.util.Log.e("JARVIS_CMD", "PDF: write complete")
            pdfDoc.close()
            android.util.Log.e("JARVIS_CMD", "RESEARCH: PDF saved to ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_CMD", "PDF: save failed with error = ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WEATHER (OpenWeatherMap)
    // ═══════════════════════════════════════════════════════════════
    private suspend fun getWeather(city: String = USER_CITY): String = withContext(Dispatchers.IO) {
        Log.e("JARVIS_CMD", "WEATHER: fetching city=$city")
        if (OPENWEATHER_API_KEY == "YOUR_OPENWEATHER_KEY")
            return@withContext "Weather API key not configured yet, sir."
        try {
            val url = "https://api.openweathermap.org/data/2.5/weather?q=${android.net.Uri.encode(city)}&appid=$OPENWEATHER_API_KEY&units=metric"
            val response = researchClient.newCall(Request.Builder().url(url).build()).execute()
            Log.e("JARVIS_CMD", "WEATHER: code=${response.code}")
            if (!response.isSuccessful) return@withContext "Could not retrieve weather for $city, sir."
            val json = JSONObject(response.body?.string() ?: "")
            val temp = json.getJSONObject("main").getDouble("temp").toInt()
            val feelsLike = json.getJSONObject("main").getDouble("feels_like").toInt()
            val desc = json.getJSONArray("weather").getJSONObject(0).getString("description")
            val humidity = json.getJSONObject("main").getInt("humidity")
            val windSpeed = json.getJSONObject("wind").getDouble("speed").toInt()
            val cityName = json.getString("name")
            val jacket = if (temp < 15) " You'll want a jacket, sir." else ""
            Log.e("JARVIS_CMD", "WEATHER: temp=$temp desc=$desc")
            "It's $temp degrees Celsius and $desc in $cityName, sir. Feels like $feelsLike. Humidity $humidity percent, wind $windSpeed metres per second.$jacket"
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "WEATHER: error ${e.message}")
            "Weather check failed, sir."
        }
    }

    private suspend fun getForecast(city: String = USER_CITY): String = withContext(Dispatchers.IO) {
        Log.e("JARVIS_CMD", "FORECAST: fetching city=$city")
        if (OPENWEATHER_API_KEY == "YOUR_OPENWEATHER_KEY")
            return@withContext "Weather API key not configured yet, sir."
        try {
            val url = "https://api.openweathermap.org/data/2.5/forecast?q=${android.net.Uri.encode(city)}&appid=$OPENWEATHER_API_KEY&units=metric&cnt=8"
            val response = researchClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return@withContext "Could not retrieve forecast for $city, sir."
            val json = JSONObject(response.body?.string() ?: "")
            val list = json.getJSONArray("list")
            var maxTemp = Int.MIN_VALUE; var minTemp = Int.MAX_VALUE; var rainTime: String? = null
            for (i in 0 until minOf(list.length(), 8)) {
                val item = list.getJSONObject(i)
                val temp = item.getJSONObject("main").getDouble("temp").toInt()
                val desc = item.getJSONArray("weather").getJSONObject(0).getString("description")
                val time = item.getLong("dt") * 1000L
                maxTemp = maxOf(maxTemp, temp); minTemp = minOf(minTemp, temp)
                if (rainTime == null && (desc.contains("rain") || desc.contains("drizzle") || desc.contains("shower"))) {
                    rainTime = SimpleDateFormat("ha", Locale.getDefault()).format(Date(time)).lowercase()
                }
            }
            val sb = StringBuilder("Forecast for $city, sir. Next 24 hours: high of $maxTemp, low of $minTemp degrees. ")
            if (rainTime != null) sb.append("Rain expected around $rainTime. ")
            else sb.append("No significant rain expected. ")
            if (minTemp < 15) sb.append("I'd recommend a jacket, sir.")
            Log.e("JARVIS_CMD", "FORECAST: built response")
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "FORECAST: error ${e.message}")
            "Forecast unavailable, sir."
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NEWS (NewsAPI)
    // ═══════════════════════════════════════════════════════════════
    private suspend fun getTopHeadlines(
        sources: String? = null, category: String? = null, query: String? = null
    ): String = withContext(Dispatchers.IO) {
        Log.e("JARVIS_CMD", "NEWS: sources=$sources category=$category query=$query")
        if (NEWSAPI_KEY == "YOUR_NEWSAPI_KEY")
            return@withContext "News API key not configured yet, sir."
        try {
            val defaultSources = "the-guardian-uk,bbc-news,reuters,cnn,al-jazeera-english"
            val sb = StringBuilder("https://newsapi.org/v2/top-headlines?apiKey=$NEWSAPI_KEY&pageSize=5&language=en")
            when {
                sources != null -> sb.append("&sources=$sources")
                category != null -> sb.append("&category=$category&country=us")
                query != null -> sb.append("&q=${android.net.Uri.encode(query)}&sources=$defaultSources")
                else -> sb.append("&sources=$defaultSources")
            }
            val response = researchClient.newCall(
                Request.Builder().url(sb.toString())
                    .addHeader("User-Agent", "JarvisAndroidApp/1.0").build()
            ).execute()
            Log.e("JARVIS_CMD", "NEWS: code=${response.code}")
            if (!response.isSuccessful) return@withContext "Could not retrieve news, sir."
            val json = JSONObject(response.body?.string() ?: "")
            val articles = json.getJSONArray("articles")
            if (articles.length() == 0) return@withContext "No headlines found, sir."
            val headlines = mutableListOf<String>()
            for (i in 0 until minOf(articles.length(), 5)) {
                val art = articles.getJSONObject(i)
                val sourceName = art.optJSONObject("source")?.optString("name") ?: ""
                val title = art.optString("title", "").replace(" - $sourceName", "").trim()
                if (title.isNotEmpty() && title != "[Removed]")
                    headlines.add(if (sourceName.isNotEmpty()) "From $sourceName: $title." else "$title.")
            }
            Log.e("JARVIS_CMD", "NEWS: ${headlines.size} headlines")
            if (headlines.isEmpty()) return@withContext "No relevant headlines found, sir."
            "Here are today's headlines, sir. ${headlines.joinToString(" ")} Would you like more details on any of these?"
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "NEWS: error ${e.message}")
            "News retrieval failed, sir."
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE COMMAND HANDLER
    // ═══════════════════════════════════════════════════════════════
    // ─── Shared memory brain (Firebase /jarvis/memory/global) ────────────────
    private fun handleMemoryCommand(lower: String, onResponse: (String) -> Unit): Boolean {
        // "what do you remember about me" — top recents grouped by category
        if (lower == "what do you remember about me" ||
            lower == "what do you know about me" ||
            lower == "tell me what you remember about me" ||
            lower == "what do you remember") {
            val grouped = JarvisMemory.listGroupedByCategory(10)
            if (grouped.isEmpty()) {
                onResponse("I have no memories of you yet, sir.")
            } else {
                val parts = grouped.entries.joinToString(". ") { (cat, entries) ->
                    val snippets = entries.joinToString("; ") { it.value.take(80) }
                    "$cat: $snippets"
                }
                onResponse("Here is what I recall, sir. $parts.")
            }
            return true
        }

        // "sync memory" / "force sync"
        if (lower == "sync memory" || lower == "synchronize memory" ||
            lower == "refresh memory" || lower == "reload memory") {
            JarvisMemory.forceSync { count ->
                runOnUiThread {
                    speakText("Memory synchronised, sir. $count entries on board.")
                }
            }
            onResponse("Synchronising memory, sir.")
            return true
        }

        // "forget [topic]"
        if (lower.startsWith("forget ")) {
            val topic = lower.removePrefix("forget ").trim()
            if (topic.isEmpty() || topic == "it" || topic == "that") {
                onResponse("Forget what, sir?"); return true
            }
            JarvisMemory.forget(topic) { deleted ->
                val msg = if (deleted == 0) "I had nothing on $topic, sir."
                else "Forgotten $deleted memor${if (deleted == 1) "y" else "ies"} about $topic, sir."
                runOnUiThread { speakText(msg) }
            }
            onResponse("Working on it, sir.")
            return true
        }

        // "where did I [verb]" / "what did I say about [topic]" — semantic recall
        val semanticTriggers = listOf(
            "where did i ", "where is my ", "where are my ",
            "what did i say about ", "what did i tell you about ",
            "do you recall ", "do you remember when ", "remind me about ",
            "remind me where ", "remind me what "
        )
        if (semanticTriggers.any { lower.startsWith(it) || lower.contains(it) }) {
            JarvisMemory.semanticSearch(lower) { ans ->
                runOnUiThread { speakText(ans) }
            }
            onResponse("Checking my memory, sir.")
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
                    onResponse("I have no memories on $topic, sir.")
                } else {
                    val joined = hits.joinToString("; ") { it.value.take(120) }
                    onResponse("Here is what I remember about $topic, sir. $joined.")
                }
                return true
            }
        }

        // "remember [anything]" / "note that [anything]" — write
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
                        onResponse("Remember what, sir?"); return true
                    }
                    JarvisMemory.remember(payload) { entry ->
                        if (entry != null) {
                            Log.e("JARVIS_CMD", "Memory saved: ${entry.key} → ${entry.category}")
                        } else {
                            runOnUiThread { speakText("I could not save that to Firebase, sir.") }
                        }
                    }
                    onResponse("Noted, sir. Saving to the brain.")
                    return true
                }
            }
        }

        return false
    }

    private fun handleVoiceCommand(input: String, onResponse: (String) -> Unit): Boolean {
        android.util.Log.e("JARVIS_CMD", "Received input: '$input'")
        val lower = input.lowercase().trim()
        android.util.Log.e("JARVIS_CMD", "Lowered: '$lower'")

        // ── SHARED MEMORY BRAIN ──────────────────────────────────
        if (handleMemoryCommand(lower, onResponse)) return true

        // ── GOODBYE / SHUTDOWN ──────────────────────────────────
        if (lower.contains("goodbye jarvis") || lower.contains("good bye jarvis") ||
            lower.contains("shutdown jarvis") || lower.contains("shut down jarvis") ||
            lower.contains("turn off jarvis") || lower.contains("close jarvis") ||
            lower.contains("exit jarvis") || lower.contains("quit jarvis") ||
            lower.contains("terminate jarvis")) {
            onResponse("Goodbye sir. Shutting down.")
            CoroutineScope(Dispatchers.Main).launch {
                delay(2500); shutdownApp()
            }
            return true
        }

        // ── MEDIA CONTROLS ────────────────────────────────────────
        if (lower == "resume" || lower == "resume music" || lower == "resume song" ||
            lower == "continue music" || lower == "continue song" ||
            lower == "play music" || lower == "unpause" || lower == "start music") {
            sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            onResponse("Resuming music, sir."); return true
        }
        if (lower == "pause" || lower == "pause music" || lower == "pause song" ||
            lower == "stop music" || lower == "stop song") {
            sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            onResponse("Music paused, sir."); return true
        }
        if (lower == "next" || lower == "next song" || lower == "next track" ||
            lower == "skip" || lower == "skip song" || lower == "skip track") {
            sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            onResponse("Next track, sir."); return true
        }
        if (lower == "previous" || lower == "previous song" || lower == "previous track" ||
            lower == "back" || lower == "go back") {
            sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            onResponse("Previous track, sir."); return true
        }
        if (lower == "volume up" || lower == "louder" || lower == "increase volume") {
            sendMediaKey(android.view.KeyEvent.KEYCODE_VOLUME_UP)
            onResponse("Volume up, sir."); return true
        }
        if (lower == "volume down" || lower == "quieter" || lower == "decrease volume") {
            sendMediaKey(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
            onResponse("Volume down, sir."); return true
        }
        if (lower == "mute" || lower == "silence") {
            sendMediaKey(android.view.KeyEvent.KEYCODE_VOLUME_MUTE)
            onResponse("Muted, sir."); return true
        }

        // ── SPOTIFY PLAY (auto-play with media key) ───────────────
        if (lower.startsWith("play ") || lower.contains(" play ")) {
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
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")).apply {
                        `package` = "com.spotify.music"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(5000)
                        sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                        delay(1500)
                        sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                        delay(300)
                        sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                } catch (e: Exception) { openApp("com.spotify.music") }
                onResponse("Playing $query on Spotify, sir.")
            } else {
                openApp("com.spotify.music")
                onResponse("Opening Spotify, sir.")
            }
            return true
        }

        // ── RESEARCH (multi-source + PDF) ─────────────────────────
        if (lower.contains("research") || lower.contains("do a research")) {
            val saveToPc = lower.contains("save on pc") || lower.contains("save to pc") || lower.contains("send to pc")
            val readAloud = lower.contains("read it to me") || lower.contains("read to me")
            val tellMe = lower.contains("and tell me") || lower.contains("tell me about") || (lower.contains("tell me") && !readAloud)
            val outputMode = when { saveToPc -> "pc"; readAloud -> "read"; tellMe -> "tell"; else -> "pdf" }

            val triggers = listOf("do a research on", "do a research for", "do a research about",
                "do research on", "do research for", "do research about", "do a research and",
                "research on", "research for", "research about", "research and", "research")
            var topic = lower
            for (t in triggers) {
                topic = topic.replace(t, " ")
            }
            val suffixes = listOf("and tell me", "and save as pdf", "and save on pc",
                "and read it to me", "and save to pc")
            for (s in suffixes) {
                topic = topic.replace(s, " ")
            }
            topic = topic.replace("save on pc", " ").replace("save to pc", " ")
                .replace("send to pc", " ").replace("save as pdf", " ")
                .replace("read it to me", " ").replace("read to me", " ")
                .replace("tell me about", " ").replace("and save", " ")
            topic = topic.replace(Regex("\\s+"), " ").trim()
            while (topic.startsWith("a ") || topic.startsWith("the ") ||
                   topic.startsWith("and ") || topic.startsWith("please ")) {
                topic = topic.substring(topic.indexOf(" ") + 1).trim()
            }
            Log.e("JARVIS_CMD", "RESEARCH: final clean topic = '$topic'")

            if (topic.length > 2) {
                onResponse("Researching $topic across multiple sources, sir. One moment.")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        android.util.Log.e("JARVIS_CMD", "Starting multi-source research: '$topic' mode=$outputMode")
                        val researchText = researchTopic(topic, outputMode)
                        android.util.Log.e("JARVIS_CMD", "Research complete, length=${researchText.length}")
                        withContext(Dispatchers.Main) {
                            when (outputMode) {
                                "tell" -> {
                                    val summary = researchText.take(450).substringBefore(". ", researchText.take(250)) + "."
                                    speakText("Here is a brief overview, sir. $summary PDF saved to Downloads.")
                                }
                                "read" -> speakText("Here is the research on $topic, sir. ${researchText.take(500)}")
                                "pc" -> speakText("Research complete, sir. PDF saved and synced to your PC.")
                                else -> speakText("Research complete, sir. PDF saved to Downloads folder.")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("JARVIS_CMD", "Research error: ${e.message}")
                        withContext(Dispatchers.Main) { speakText("Research failed, sir.") }
                    }
                }
                return true
            }
        }

        // ── CAMERA COMMANDS ───────────────────────────────────────
        // Fold/inner selfie MUST be checked before regular selfie (it also contains "selfie")
        if (lower.contains("fold selfie") || lower.contains("inner selfie") ||
            lower.contains("tablet selfie") || lower.contains("inner camera") ||
            lower.contains("fold camera")) {
            android.util.Log.e("JARVIS_CMD", "CAMERA: command=fold_selfie")
            var opened = false
            // Approach 1: Samsung STILL_IMAGE_CAMERA with inner-front extras (lensFacingType=2 for Z Fold inner)
            try {
                startActivity(Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("camerafacing", "front")
                    putExtra("selfie", true)
                    putExtra("lensFacingType", 2)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (fold_selfie attempt 1 - STILL_IMAGE_CAMERA inner)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=STILL_IMAGE_CAMERA extras=inner fold - failed: ${e.message}") }
            // Approach 2: Samsung component with inner-front extras
            if (!opened) try {
                startActivity(Intent().apply {
                    component = android.content.ComponentName("com.sec.android.app.camera", "com.sec.android.app.camera.Camera")
                    action = "android.media.action.STILL_IMAGE_CAMERA"
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("lensFacingType", 2)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (fold_selfie attempt 2 - Samsung component inner)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=Samsung component inner fold - failed: ${e.message}") }
            // Approach 3: MediaStore IMAGE_CAPTURE with inner-front extras
            if (!opened) try {
                startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                    putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("lensFacingType", 2)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (fold_selfie attempt 3 - IMAGE_CAPTURE inner)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=IMAGE_CAPTURE inner fold - failed: ${e.message}") }
            // Approach 4: Fallback open camera app
            if (!opened) { openApp("com.sec.android.app.camera"); android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (fold_selfie fallback - open app)") }
            onResponse("Opening inner fold camera, sir."); return true
        }

        // Regular selfie: cover screen front camera
        if (lower.contains("take a selfie") || lower.contains("selfie") ||
            lower.contains("front camera") || lower.contains("selfie mode")) {
            android.util.Log.e("JARVIS_CMD", "CAMERA: command=selfie")
            var opened = false
            // Approach 1: Samsung STILL_IMAGE_CAMERA with Samsung front-camera extras
            try {
                startActivity(Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("camerafacing", "front")
                    putExtra("previous_mode", "Selfie")
                    putExtra("selfie", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (selfie attempt 1 - STILL_IMAGE_CAMERA Samsung extras)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=STILL_IMAGE_CAMERA extras=Samsung selfie - failed: ${e.message}") }
            // Approach 2: Samsung component with front-camera extras
            if (!opened) try {
                startActivity(Intent().apply {
                    component = android.content.ComponentName("com.sec.android.app.camera", "com.sec.android.app.camera.Camera")
                    action = "android.media.action.STILL_IMAGE_CAMERA"
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    putExtra("camerafacing", "front")
                    putExtra("selfie", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (selfie attempt 2 - Samsung component)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=Samsung component selfie - failed: ${e.message}") }
            // Approach 3: IMAGE_CAPTURE with front-camera facing extra
            if (!opened) try {
                startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                    putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (selfie attempt 3 - IMAGE_CAPTURE facing)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=IMAGE_CAPTURE front facing - failed: ${e.message}") }
            // Approach 4: Fallback open camera app
            if (!opened) { openApp("com.sec.android.app.camera"); android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (selfie fallback - open app)") }
            onResponse("Opening front camera, sir."); return true
        }

        // Video recording mode
        if (lower.contains("record video") || lower.contains("video camera") ||
            lower.contains("video mode") || lower.contains("start recording")) {
            android.util.Log.e("JARVIS_CMD", "CAMERA: command=video")
            var opened = false
            // Approach 1: Generic VIDEO_CAPTURE (most reliable per spec)
            try {
                startActivity(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (video attempt 1 - generic VIDEO_CAPTURE)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=VIDEO_CAPTURE generic - failed: ${e.message}") }
            // Approach 2: Samsung package with VIDEO_CAPTURE
            if (!opened) try {
                startActivity(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    `package` = "com.sec.android.app.camera"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (video attempt 2 - Samsung pkg VIDEO_CAPTURE)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=VIDEO_CAPTURE Samsung pkg - failed: ${e.message}") }
            // Approach 3: Samsung component in video mode
            if (!opened) try {
                startActivity(Intent().apply {
                    component = android.content.ComponentName("com.sec.android.app.camera", "com.sec.android.app.camera.Camera")
                    action = android.provider.MediaStore.ACTION_VIDEO_CAPTURE
                    putExtra("camerapreviewmode", 1)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (video attempt 3 - Samsung component)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=Samsung component video - failed: ${e.message}") }
            // Approach 4: Fallback open camera app
            if (!opened) { openApp("com.sec.android.app.camera"); android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (video fallback - open app)") }
            onResponse("Opening video camera, sir."); return true
        }

        // Photo / rear camera
        if (lower.contains("take photo") || lower.contains("take a photo") ||
            lower.contains("take picture") || lower.contains("take a picture") ||
            lower.contains("capture photo") || lower.contains("photo mode") ||
            lower.contains("back camera") || lower.contains("rear camera")) {
            android.util.Log.e("JARVIS_CMD", "CAMERA: command=photo")
            var opened = false
            // Approach 1: Samsung STILL_IMAGE_CAMERA with rear-camera extras
            try {
                startActivity(Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 0)
                    putExtra("camerafacing", "back")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (photo attempt 1 - STILL_IMAGE_CAMERA Samsung rear)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=STILL_IMAGE_CAMERA extras=Samsung rear - failed: ${e.message}") }
            // Approach 2: Samsung component, rear camera
            if (!opened) try {
                startActivity(Intent().apply {
                    component = android.content.ComponentName("com.sec.android.app.camera", "com.sec.android.app.camera.Camera")
                    action = "android.media.action.STILL_IMAGE_CAMERA"
                    putExtra("com.samsung.camera.EXTRA_CAMERA_ID", 0)
                    putExtra("camerafacing", "back")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (photo attempt 2 - Samsung component rear)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=Samsung component rear - failed: ${e.message}") }
            // Approach 3: Samsung package IMAGE_CAPTURE (defaults to rear)
            if (!opened) try {
                startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    `package` = "com.sec.android.app.camera"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true; android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (photo attempt 3 - Samsung IMAGE_CAPTURE)")
            } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "CAMERA: trying action=IMAGE_CAPTURE Samsung pkg - failed: ${e.message}") }
            // Approach 4: Fallback open camera app
            if (!opened) { openApp("com.sec.android.app.camera"); android.util.Log.e("JARVIS_CMD", "CAMERA: activity started (photo fallback - open app)") }
            onResponse("Opening camera, sir."); return true
        }

        // ── FLASHLIGHT ────────────────────────────────────────────
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light")) {
            try {
                val cam = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraId = cam.cameraIdList.first()
                val turnOn = !lower.contains("off") && !lower.contains("turn off")
                cam.setTorchMode(cameraId, turnOn)
                onResponse(if (turnOn) "Flashlight on, sir." else "Flashlight off, sir.")
            } catch (e: Exception) { onResponse("Could not control flashlight, sir.") }
            return true
        }

        // ── SCREENSHOT HINT ───────────────────────────────────────
        if (lower.contains("screenshot") || lower.contains("screen shot") ||
            lower.contains("capture screen")) {
            onResponse("Press power and volume down together for screenshot, sir."); return true
        }

        // ── APP MAP ──────────────────────────────────────────────
        data class AppEntry(val keywords: List<String>, val packages: List<String>, val name: String)

        val apps = listOf(
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
            AppEntry(listOf("open camera", "camera app"),
                listOf("com.sec.android.app.camera"), "Camera"),
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
            AppEntry(listOf("voovo", "open voovo"), listOf("com.voovo.app"), "Voovo")
        )

        if (lower.contains("open claude") || lower.contains("launch claude")) {
            if (!openApp("com.anthropic.claude")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            }
            onResponse("Opening Claude AI, sir."); return true
        }
        if (lower.contains("open chatgpt") || lower.contains("open chat gpt") || lower.contains("launch gpt")) {
            if (!openApp("com.openai.chatgpt")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.openai.com")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            }
            onResponse("Opening ChatGPT, sir."); return true
        }

        for (app in apps) {
            if (app.keywords.any { lower.contains(it) }) {
                if (app.name == "Samsung Notes" &&
                    (lower.contains("new") || lower.contains("create") || lower.contains("write"))) {
                    try {
                        startActivity(Intent(Intent.ACTION_INSERT).apply {
                            `package` = app.packages[0]
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) { openApp(app.packages[0]) }
                    onResponse("Creating a new note, sir."); return true
                }
                var opened = false
                for (pkg in app.packages) { if (openApp(pkg)) { opened = true; break } }
                if (!opened && app.name == "New York Times") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.nytimes.com")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        opened = true
                    } catch (e: Exception) {}
                }
                if (!opened && app.name == "The Guardian") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.theguardian.com")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        opened = true
                    } catch (e: Exception) {}
                }
                onResponse(if (opened) "Opening ${app.name}, sir." else "Could not find ${app.name} on your device, sir.")
                return true
            }
        }

        // ── ALARM ────────────────────────────────────────────────
        if (lower.contains("alarm") || lower.contains("wake me") || lower.contains("wake up at")) {
            val time = extractTime(lower)
            if (time != null) {
                val amPm = if (time.first >= 12) "PM" else "AM"
                val h = if (time.first > 12) time.first - 12 else if (time.first == 0) 12 else time.first
                if (trySetAlarm(time.first, time.second)) {
                    onResponse("Alarm set for $h:${time.second.toString().padStart(2, '0')} $amPm, sir.")
                } else {
                    openApp("com.sec.android.app.clockpackage")
                    onResponse("Opening clock to set alarm, sir.")
                }
            } else {
                openApp("com.sec.android.app.clockpackage")
                onResponse("Opening clock, sir.")
            }
            return true
        }

        // ── TIMER ────────────────────────────────────────────────
        if (lower.contains("timer") || lower.contains("countdown")) {
            val mins = extractNumber(lower)
            if (mins > 0) {
                try {
                    startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, mins * 60)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Timer")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    onResponse("$mins minute timer set, sir.")
                } catch (e: Exception) {
                    openApp("com.sec.android.app.clockpackage")
                    onResponse("Opening clock for timer, sir.")
                }
            } else {
                openApp("com.sec.android.app.clockpackage")
                onResponse("Opening clock, sir.")
            }
            return true
        }

        if (lower.contains("stopwatch")) {
            openApp("com.sec.android.app.clockpackage")
            onResponse("Opening stopwatch, sir."); return true
        }

        // ── CALENDAR READ ─────────────────────────────────────────
        if (lower.contains("what's on my calendar") || lower.contains("what's on my schedule") ||
            lower.contains("show my calendar") || lower.contains("my agenda") ||
            lower.contains("what do i have today") || lower.contains("what do i have tomorrow") ||
            lower.contains("what do i have this week") || lower.contains("today's schedule") ||
            lower.contains("today's events") || lower.contains("tomorrow's events") ||
            (lower.contains("calendar") && lower.contains("what") &&
             (lower.contains("today") || lower.contains("tomorrow") || lower.contains("this week")))) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
                onResponse("I need calendar permission to read your events, sir."); return true
            }
            val isTomorrow = lower.contains("tomorrow")
            val isWeek = lower.contains("this week") || (lower.contains("week") && !isTomorrow)
            val period = if (isTomorrow) "tomorrow" else if (isWeek) "this week" else "today"
            onResponse("Checking your $period schedule, sir.")
            CoroutineScope(Dispatchers.IO).launch {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val startMs: Long; val endMs: Long
                when {
                    isTomorrow -> { cal.add(Calendar.DAY_OF_YEAR, 1); startMs = cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1); endMs = cal.timeInMillis }
                    isWeek -> { startMs = cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 7); endMs = cal.timeInMillis }
                    else -> { startMs = cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1); endMs = cal.timeInMillis }
                }
                val events = readCalendarEvents(startMs, endMs)
                withContext(Dispatchers.Main) {
                    val message = if (events.isEmpty()) {
                        "You have no events scheduled for $period, sir."
                    } else {
                        val count = events.size
                        val countWord = when (count) { 1 -> "one event"; 2 -> "two events"; 3 -> "three events"; else -> "$count events" }
                        val eventList = events.take(5).joinToString(", ")
                        "You have $countWord $period, sir. ${eventList.replaceFirstChar { it.uppercase() }}."
                    }
                    speakText(message)
                }
            }
            return true
        }

        // ── CALENDAR EVENT DELETE ─────────────────────────────────
        if ((lower.contains("delete") || lower.contains("remove") || lower.contains("cancel")) &&
            (lower.contains("appointment") || lower.contains("meeting") || lower.contains("calendar entry") ||
             (lower.contains("event") && (lower.contains("my") || lower.contains("the"))))) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
                onResponse("I need calendar permission, sir."); return true
            }
            val titleQ = extractEventTitle(lower).ifEmpty { null }
            val timeT = extractTime(lower)
            android.util.Log.e("JARVIS_CMD", "Calendar delete - title:'$titleQ' time:$timeT")
            onResponse("Deleting the event, sir.")
            CoroutineScope(Dispatchers.IO).launch {
                val deleted = deleteCalendarEvent(titleQ, timeT?.first, timeT?.second)
                withContext(Dispatchers.Main) {
                    speakText(if (deleted) "Event deleted, sir." else "Could not find that event, sir.")
                }
            }
            return true
        }

        // ── CALENDAR EVENT CREATE ─────────────────────────────────
        if (lower.contains("add event") || lower.contains("create event") ||
            lower.contains("new event") || lower.contains("schedule meeting") ||
            lower.contains("add to calendar") || lower.contains("schedule an event") ||
            lower.contains("add appointment") || lower.contains("create appointment")) {
            val title = extractEventTitle(input)
            val time = extractTime(lower)
            val dateCal = extractDate(lower)
            android.util.Log.e("JARVIS_CMD", "Calendar create - title:'$title' time:$time dateCal:$dateCal")
            if (title.isNotEmpty() && time != null && dateCal != null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
                    != PackageManager.PERMISSION_GRANTED) {
                    onResponse("I need calendar permission to create events, sir."); return true
                }
                dateCal.set(Calendar.HOUR_OF_DAY, time.first)
                dateCal.set(Calendar.MINUTE, time.second); dateCal.set(Calendar.SECOND, 0)
                val startMs = dateCal.timeInMillis; val endMs = startMs + 60 * 60 * 1000L
                val amPm = if (time.first >= 12) "PM" else "AM"
                val h = if (time.first > 12) time.first - 12 else if (time.first == 0) 12 else time.first
                onResponse("Creating the event, sir.")
                CoroutineScope(Dispatchers.IO).launch {
                    val created = createCalendarEventDirect(title, startMs, endMs)
                    withContext(Dispatchers.Main) {
                        if (created) {
                            speakText("'$title' added to your calendar for $h:${time.second.toString().padStart(2, '0')} $amPm, sir.")
                        } else {
                            try {
                                startActivity(Intent(Intent.ACTION_INSERT).apply {
                                    data = CalendarContract.Events.CONTENT_URI
                                    putExtra(CalendarContract.Events.TITLE, title)
                                    putExtra(CalendarContract.Events.DTSTART, startMs)
                                    putExtra(CalendarContract.Events.DTEND, endMs)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (e: Exception) { openApp("com.samsung.android.calendar") }
                            speakText("Opening calendar to create the event, sir.")
                        }
                    }
                }
            } else {
                android.util.Log.e("JARVIS_CMD", "Calendar create: insufficient info (need title+time+date), opening app")
                try {
                    startActivity(Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        if (title.isNotEmpty()) putExtra(CalendarContract.Events.TITLE, title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    onResponse("Opening calendar to create event, sir.")
                } catch (e: Exception) {
                    openApp("com.samsung.android.calendar")
                    onResponse("Opening calendar, sir.")
                }
            }
            return true
        }

        // ── CALL ─────────────────────────────────────────────────
        if ((lower.startsWith("call ") || lower.contains("phone call") ||
                    lower.contains("dial ") || lower.contains("make a call")) &&
            !lower.contains("whatsapp") && !lower.contains("telegram") &&
            !lower.contains("video call")) {
            val name = extractAfter(lower, listOf("call", "phone call to", "dial", "ring"))
            try {
                startActivity(Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                onResponse(if (name.isNotEmpty()) "Opening dialer to call $name, sir." else "Opening phone, sir.")
            } catch (e: Exception) {
                openApp("com.samsung.android.dialer")
                onResponse("Opening phone, sir.")
            }
            return true
        }

        // ── WEB SEARCH (simple Google) ───────────────────────────
        if (lower.contains("search for ") || lower.contains("search online for ") ||
            lower.contains("google search ") || lower.contains("look up ") ||
            (lower.startsWith("search ") && !lower.contains("research"))) {
            val query = extractAfter(lower, listOf("search online for", "search for",
                "google search", "look up", "google", "search"))
            if (query.isNotEmpty()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    onResponse("Searching for $query, sir.")
                } catch (e: Exception) { onResponse("Could not open browser, sir.") }
                return true
            }
        }

        // ── YOUTUBE SEARCH ───────────────────────────────────────
        if ((lower.contains("youtube") || lower.contains("you tube")) &&
            (lower.contains("search") || lower.contains("watch") ||
                    lower.contains("find") || lower.contains("show me"))) {
            val query = extractAfter(lower, listOf("search youtube for", "search on youtube",
                "youtube search", "watch", "find on youtube", "show me on youtube", "youtube"))
            if (query.isNotEmpty()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                        `package` = "com.google.android.youtube"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) { openApp("com.google.android.youtube") }
                onResponse("Searching YouTube for $query, sir.")
                return true
            }
        }

        // ── NAVIGATE ─────────────────────────────────────────────
        if (lower.contains("navigate to ") || lower.contains("directions to ") ||
            lower.contains("take me to ") || lower.contains("how do i get to ")) {
            val dest = extractAfter(lower, listOf("navigate to", "directions to",
                "take me to", "how do i get to"))
            if (dest.isNotEmpty()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=${Uri.encode(dest)}")).apply {
                        `package` = "com.google.android.apps.maps"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    onResponse("Navigating to $dest, sir.")
                } catch (e: Exception) {
                    openApp("com.google.android.apps.maps")
                    onResponse("Opening Maps, sir.")
                }
                return true
            }
        }

        // ── INSTAGRAM PROFILE ────────────────────────────────────
        if ((lower.contains("instagram") || lower.contains("insta")) && lower.contains("profile")) {
            val name = extractAfter(lower, listOf("instagram profile of", "profile of",
                "search instagram for", "find user"))
            if (name.isNotEmpty()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.instagram.com/$name")).apply {
                        `package` = "com.instagram.android"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    onResponse("Opening Instagram profile of $name, sir.")
                } catch (e: Exception) {
                    openApp("com.instagram.android")
                    onResponse("Opening Instagram, sir.")
                }
                return true
            }
        }

        // ── WHATSAPP MESSAGE ─────────────────────────────────────
        if (lower.contains("whatsapp") && (lower.contains("message") ||
                    lower.contains("send") || lower.contains("text") || lower.contains("chat"))) {
            openApp("com.whatsapp")
            onResponse("Opening WhatsApp, sir.")
            return true
        }

        // ── SETTINGS ─────────────────────────────────────────────
        if (lower.contains("wifi settings") || lower.contains("wi-fi settings") || lower.contains("open wifi")) {
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            onResponse("Opening WiFi settings, sir."); return true
        }
        if (lower.contains("bluetooth settings") || lower.contains("open bluetooth")) {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            onResponse("Opening Bluetooth settings, sir."); return true
        }
        if (lower.contains("display settings") || lower.contains("brightness settings") || lower.contains("screen settings")) {
            startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            onResponse("Opening display settings, sir."); return true
        }
        if (lower.contains("sound settings") || lower.contains("volume settings")) {
            startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            onResponse("Opening sound settings, sir."); return true
        }
        if (lower.contains("battery settings") || lower.contains("power settings")) {
            startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            onResponse("Opening battery settings, sir."); return true
        }

        // ── EMAIL COMPOSE ────────────────────────────────────────
        if (lower.contains("compose email") || lower.contains("write email") ||
            lower.contains("send email") || lower.contains("new email")) {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                onResponse("Opening email composer, sir.")
            } catch (e: Exception) {
                openApp("com.google.android.gm")
                onResponse("Opening Gmail, sir.")
            }
            return true
        }

        // ── SCREEN VISION (MediaProjection + Claude Vision) ───────
        // Launches ScreenCaptureActivity, which requests MediaProjection
        // permission once and then delegates to ScreenCaptureService to
        // grab a single frame and ask Claude Vision to describe it.
        // Result comes back via the ACTION_RESULT broadcast.
        if (lower.contains("what do you see") || lower.contains("describe my screen") ||
            lower.contains("what's on my screen") || lower.contains("whats on my screen") ||
            lower.contains("read my screen") || lower.contains("what is on my screen") ||
            lower.contains("analyze screen") || lower.contains("summarize screen")) {
            onResponse("Let me take a look, sir.")
            takeScreenshotAndAnalyze()
            return true
        }

        // ── WEATHER ───────────────────────────────────────────────
        if (lower.contains("weather") || lower.contains("temperature") ||
            lower.contains("how hot") || lower.contains("how cold") ||
            lower.contains("will it rain") || lower.contains("going to rain") ||
            lower.contains("need a jacket") || lower.contains("forecast")) {
            Log.e("JARVIS_CMD", "WEATHER: command detected")
            val isForecast = lower.contains("tomorrow") || lower.contains("this week") ||
                    lower.contains("forecast") || lower.contains("will it rain") ||
                    lower.contains("going to rain")
            var targetCity = USER_CITY
            for (kw in listOf("weather in ", "temperature in ", "forecast for ")) {
                val idx = lower.indexOf(kw)
                if (idx >= 0) {
                    val extracted = lower.substring(idx + kw.length).trim().split(" ").take(2).joinToString(" ")
                    if (extracted.isNotEmpty()) { targetCity = extracted; break }
                }
            }
            onResponse("Checking the weather for you, sir.")
            CoroutineScope(Dispatchers.IO).launch {
                val result = if (isForecast) getForecast(targetCity) else getWeather(targetCity)
                withContext(Dispatchers.Main) { speakText(result) }
            }
            return true
        }

        // ── NEWS ──────────────────────────────────────────────────
        if (lower.contains("headline") || lower.contains("brief me") ||
            (lower.contains("news") && !lower.contains("new note") && !lower.contains("new event") &&
             !lower.contains("new alarm") && !lower.contains("new timer"))) {
            Log.e("JARVIS_CMD", "NEWS: command detected")
            var sources: String? = null; var category: String? = null; var query: String? = null
            when {
                lower.contains("guardian") -> sources = "the-guardian-uk"
                lower.contains("bbc") -> sources = "bbc-news"
                lower.contains("new york times") || lower.contains("nyt") -> sources = "the-new-york-times"
                lower.contains("reuters") -> sources = "reuters"
                lower.contains("cnn") -> sources = "cnn"
                lower.contains("al jazeera") || lower.contains("aljazeera") -> sources = "al-jazeera-english"
                lower.contains("tech news") || lower.contains("technology news") -> category = "technology"
                lower.contains("business news") -> category = "business"
                lower.contains("sports news") -> category = "sports"
                lower.contains("science news") -> category = "science"
                lower.contains("health news") -> category = "health"
                lower.contains("news about ") -> {
                    val idx = lower.indexOf("news about ")
                    query = lower.substring(idx + "news about ".length).trim().split(" ").take(3).joinToString(" ")
                }
            }
            onResponse("Fetching the latest headlines, sir.")
            CoroutineScope(Dispatchers.IO).launch {
                val result = getTopHeadlines(sources, category, query)
                withContext(Dispatchers.Main) { speakText(result) }
            }
            return true
        }

        // ── LANGUAGE SWITCH ───────────────────────────────────────
        if (lower.contains("switch to farsi") || lower.contains("به فارسی") ||
            lower.contains("speak farsi") || lower.contains("speak persian")) {
            sessionLanguage = "farsi"
            setLanguageFarsi()
            Log.e("JARVIS_CMD", "LANGUAGE: switched to Farsi")
            onResponse("Switching to Farsi, sir.")
            return true
        }
        if (lower.contains("switch to english") || lower.contains("speak english") ||
            lower.contains("back to english")) {
            sessionLanguage = "english"
            tts.language = Locale.US
            Log.e("JARVIS_CMD", "LANGUAGE: switched to English")
            onResponse("Back to English, sir.")
            return true
        }

        // ── WAKE WORD MODE ────────────────────────────────────────
        if (lower.contains("enable wake word") || lower.contains("wake word mode on") ||
            lower.contains("listen for wake word") || lower.contains("activate wake word")) {
            wakeWordEnabled = true
            Log.e("JARVIS_CMD", "WAKE_WORD: enabled")
            onResponse("Wake word mode enabled, sir. Say 'Hey Jarvis' to activate me.")
            return true
        }
        if (lower.contains("disable wake word") || lower.contains("wake word mode off") ||
            lower.contains("deactivate wake word") || lower.contains("normal listening mode")) {
            wakeWordEnabled = false
            Log.e("JARVIS_CMD", "WAKE_WORD: disabled")
            onResponse("Wake word mode disabled, sir. I'll respond to everything now.")
            return true
        }

        // ── PERSONAL INFO LEARNING ────────────────────────────────
        val learnTriggers = listOf("my favorite", "i love ", "i hate ", "i like ", "i prefer ",
            "my name is", "i am a ", "i'm a ", "my job is", "i work at", "i live in",
            "my birthday", "i was born", "my hobby", "i enjoy ", "my goal is")
        if (learnTriggers.any { lower.contains(it) }) {
            android.util.Log.e("JARVIS_CMD", "Detected personal info statement, will extract and save")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val extractPrompt = """Extract personal info from this statement as a JSON object with field=value pairs. Only include explicitly stated facts. Keep field names simple (e.g. favorite_food, hobby, job).
Statement: "$input"
Return ONLY the JSON object, nothing else."""
                    val body = JSONObject().apply {
                        put("model", "claude-sonnet-4-6")
                        put("max_tokens", 200)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply { put("role", "user"); put("content", extractPrompt) })
                        })
                    }
                    val response = OkHttpClient().newCall(
                        Request.Builder()
                            .url("https://api.anthropic.com/v1/messages")
                            .addHeader("Content-Type", "application/json")
                            .addHeader("x-api-key", ANTHROPIC_API_KEY)
                            .addHeader("anthropic-version", "2023-06-01")
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                    val respBody = response.body?.string() ?: return@launch
                    val extractedText = JSONObject(respBody).getJSONArray("content").getJSONObject(0).getString("text").trim()
                    android.util.Log.e("JARVIS_CMD", "Extracted personal info: $extractedText")
                    val extracted = try {
                        val jsonStr = extractedText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
                        JSONObject(jsonStr)
                    } catch (e: Exception) { null }
                    if (extracted != null && extracted.length() > 0) {
                        val updates = extracted.keys().asSequence().associateWith { extracted.get(it) }
                        database.getReference("$FIREBASE_PATH/preferences").updateChildren(updates)
                        android.util.Log.e("JARVIS_CMD", "Personal info saved to Firebase: $updates")
                    }
                } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Personal info extraction error: ${e.message}") }
            }
            // Don't return true — fall through so Claude gives a natural conversational response
        }

        // ── VOICE ENROLLMENT ──────────────────────────────────────
        if (lower.contains("learn my voice") || lower.contains("train my voice") ||
            lower.contains("enroll my voice") || lower.contains("setup voice id") ||
            lower.contains("voice enrollment") || lower.contains("remember my voice")) {
            onResponse("Starting voice enrollment sir. I will record you saying 10 phrases. Please speak clearly when prompted.")
            enrollmentPending = true
            return true
        }

        if (lower.contains("reset voice") || lower.contains("forget my voice") ||
            lower.contains("clear voice enrollment")) {
            speakerVerifier?.resetEnrollment()
            speakerVerificationEnabled = false
            onResponse("Voice enrollment cleared sir. Anyone can now use me until you re-enroll.")
            return true
        }

        if (lower.contains("disable voice id") || lower.contains("turn off voice recognition")) {
            speakerVerificationEnabled = false
            onResponse("Voice verification disabled sir.")
            return true
        }

        if (lower.contains("enable voice id") || lower.contains("turn on voice recognition")) {
            if (speakerVerifier?.isEnrolled() == true) {
                speakerVerificationEnabled = true
                onResponse("Voice verification enabled sir. Only your voice will work.")
            } else {
                onResponse("No voice enrollment found sir. Say 'learn my voice' to set it up first.")
            }
            return true
        }

        return false
    }

    @Composable
    fun JarvisScreen() {
        var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
        var isListening by remember { mutableStateOf(false) }
        var isThinking by remember { mutableStateOf(false) }
        var isSpeaking by remember { mutableStateOf(false) }
        var userInput by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var memoryData by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
        var sessionCount by remember { mutableStateOf(0) }
        var currentTime by remember { mutableStateOf("") }
        var commandCount by remember { mutableStateOf(0) }
        var userName by remember { mutableStateOf("sir") }
        var isEnrolling by remember { mutableStateOf(false) }
        var enrollmentPhrase by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                delay(1000)
            }
        }

        // Trigger enrollment UI when voice enrollment command issued
        LaunchedEffect(enrollmentPending) {
            if (enrollmentPending) {
                enrollmentPending = false
                isEnrolling = true
                enrollmentPhrase = 0
            }
        }

        // Run enrollment coroutine when UI is shown
        LaunchedEffect(isEnrolling) {
            if (isEnrolling) {
                speakerVerifier?.enrollVoice(
                    onProgress = { idx, _ -> enrollmentPhrase = idx },
                    onDone = {
                        speakerVerificationEnabled = true
                        isEnrolling = false
                        speakText("Voice enrollment complete sir. I will now recognize only your voice.")
                    },
                    onError = {
                        isEnrolling = false
                        speakText("Voice enrollment failed sir. Please try again.")
                    }
                )
            }
        }

        val phase = when {
            isListening && wakeWordEnabled -> "WAKE WORD"
            isListening -> "LISTENING"
            isThinking -> "PROCESSING"
            isSpeaking -> "SPEAKING"
            wakeWordEnabled -> "WAKE WORD"
            else -> "STANDBY"
        }

        fun handleUserInput(text: String) {
            if (isCurrentlySpeaking) {
                android.util.Log.e("JARVIS_CMD", "Ignoring input while speaking: $text")
                return
            }
            val lower = text.lowercase()
            val jarvisPhrases = listOf("sir.", "research complete", "opening", "alarm set for",
                "playing", "navigating to", "searching for", "jarvis mobile", "all systems")
            if (jarvisPhrases.any { lower.contains(it) }) {
                android.util.Log.e("JARVIS_CMD", "Detected self-voice feedback, ignoring: $text")
                return
            }

            messages = messages + ChatMessage("YOU", text)
            isThinking = true; isSpeaking = false
            commandCount++

            scope.launch {
                // Speaker verification (only active when enrolled and enabled)
                if (speakerVerificationEnabled && speakerVerifier?.isEnrolled() == true) {
                    val isAuthorized = speakerVerifier?.verifyCurrentSpeaker() ?: true
                    if (!isAuthorized) {
                        Log.e("JARVIS_CMD", "UNAUTHORIZED SPEAKER - ignoring command")
                        isThinking = false
                        speakText("I don't recognize your voice, sir. Access denied.")
                        return@launch
                    }
                }

                var cmdResponse = ""
                val handled = handleVoiceCommand(text) { r -> cmdResponse = r }

                if (handled && cmdResponse.isNotEmpty()) {
                    messages = messages + ChatMessage("JARVIS", cmdResponse)
                    isThinking = false; isSpeaking = true; sessionCount++
                    speakText(cmdResponse) {
                        isSpeaking = false
                        if (continuousListening) {
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1500)
                                if (continuousListening && !isCurrentlySpeaking) {
                                    startListeningInternal(
                                        onResult = { s -> handleUserInput(s) }, onError = {}
                                    )
                                }
                            }
                        }
                    }
                    syncMessageToFirebase(text, cmdResponse)
                    saveConversationToFirebase(messages)
                } else if (shouldUseAgent(text)) {
                    Log.e("JARVIS_CMD", "AGENT MODE for: $text")
                    val agent = jarvisAgent
                    if (agent == null) {
                        val response = sendToJarvis(text, messages, memoryData)
                        messages = messages + ChatMessage("JARVIS", response)
                        isThinking = false; isSpeaking = true; sessionCount++
                        speakText(response) { isSpeaking = false }
                    } else {
                        val historySnapshot = messages.toList()
                        agent.run(
                            userInput = text,
                            history = historySnapshot,
                            onUpdate = { interim ->
                                messages = messages + ChatMessage("JARVIS", interim)
                                isSpeaking = true
                                speakText(interim) { isSpeaking = false }
                            },
                            onFinal = { final ->
                                messages = messages + ChatMessage("JARVIS", final)
                                isThinking = false; isSpeaking = true; sessionCount++
                                speakText(final) {
                                    isSpeaking = false
                                    if (continuousListening) startListeningInternal(
                                        onResult = { s -> handleUserInput(s) }, onError = {}
                                    )
                                }
                                syncMessageToFirebase(text, final)
                                saveConversationToFirebase(messages)
                            }
                        )
                    }
                } else {
                    val response = sendToJarvis(text, messages, memoryData)
                    messages = messages + ChatMessage("JARVIS", response)
                    isThinking = false; isSpeaking = true; sessionCount++
                    speakText(response) {
                        isSpeaking = false
                        if (continuousListening) startListeningInternal(
                            onResult = { s -> handleUserInput(s) }, onError = {}
                        )
                    }
                    syncMessageToFirebase(text, response)
                    saveConversationToFirebase(messages)
                }
            }
        }

        // Watch for commands from background service
        LaunchedEffect(pendingCommand.value) {
            val cmd = pendingCommand.value
            if (!cmd.isNullOrBlank()) {
                pendingCommand.value = null
                handleUserInput(cmd)
            }
        }

        LaunchedEffect(Unit) {
            try {
            loadMemoryFromFirebase { data ->
                try {
                memoryData = data
                userName = (data["identity"] as? Map<*, *>)?.get("name") as? String ?: "sir"
                loadConversationFromFirebase { history ->
                    try {
                    if (history.isNotEmpty()) messages = history.takeLast(20)
                    val greeting = getGreeting(userName)
                    messages = messages + ChatMessage("JARVIS", greeting)
                    isSpeaking = true
                    speakText(greeting) {
                        isSpeaking = false
                        // Proactive intelligence: check calendar + battery after greeting
                        scope.launch {
                            delay(600)
                            val proactive = withContext(Dispatchers.IO) {
                                val sb = StringBuilder()
                                // Calendar check
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CALENDAR)
                                    == PackageManager.PERMISSION_GRANTED) {
                                    val cal = Calendar.getInstance()
                                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                                    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                                    val start = cal.timeInMillis
                                    cal.add(Calendar.DAY_OF_YEAR, 1)
                                    val end = cal.timeInMillis
                                    val events = readCalendarEvents(start, end)
                                    if (events.isNotEmpty()) {
                                        val count = events.size
                                        val cw = when (count) { 1 -> "one event"; 2 -> "two events"; 3 -> "three events"; else -> "$count events" }
                                        sb.append("You have $cw today, sir. ${events.take(3).joinToString(", ").replaceFirstChar { it.uppercase() }}. ")
                                    }
                                }
                                // Battery check
                                try {
                                    val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                                    val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                                    val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                                    if (level in 1..20 && scale > 0) {
                                        val pct = level * 100 / scale
                                        sb.append("Also, battery is at $pct percent. I recommend charging soon, sir.")
                                    }
                                } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Battery check error: ${e.message}") }
                                // Weather brief
                                if (OPENWEATHER_API_KEY != "YOUR_OPENWEATHER_KEY") {
                                    try {
                                        val wx = getWeather(USER_CITY)
                                        if (!wx.contains("failed") && !wx.contains("configured")) {
                                            val brief = wx.substringBefore(". Feels like").trim() + "."
                                            sb.append(brief)
                                        }
                                    } catch (e: Exception) { android.util.Log.e("JARVIS_CMD", "Proactive weather error: ${e.message}") }
                                }
                                sb.toString()
                            }
                            if (proactive.isNotEmpty()) {
                                messages = messages + ChatMessage("JARVIS", proactive)
                                isSpeaking = true
                                speakText(proactive) { isSpeaking = false }
                            }
                        }
                    }
                    } catch (e: Exception) { Log.e("JARVIS_CMD", "Startup crash: conversation: ${e.message}", e) }
                }
                } catch (e: Exception) { Log.e("JARVIS_CMD", "Startup crash: memory callback: ${e.message}", e) }
            }
            } catch (e: Exception) { Log.e("JARVIS_CMD", "Startup crash: ${e.message}", e) }
        }

        // Memory refresh every 10 minutes (Priority 6)
        LaunchedEffect(Unit) {
            while (true) {
                delay(10 * 60 * 1000L)
                loadMemoryFromFirebase { data ->
                    memoryData = data
                    userName = (data["identity"] as? Map<*, *>)?.get("name") as? String ?: "sir"
                    Log.e("JARVIS_CMD", "Memory refreshed from Firebase")
                }
            }
        }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF010810))) {
            val isWide = this.maxWidth > 600.dp

            if (isWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.weight(0.55f).fillMaxHeight()
                            .background(Brush.verticalGradient(listOf(
                                Color(0xFF000D1A), Color(0xFF010810), Color(0xFF000D1A)
                            ))),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TopBar(currentTime, phase, userName)
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(420.dp),
                            contentAlignment = Alignment.Center) {
                            ArcReactorMk6(phase = phase, size = 340.dp)
                        }
                        WaveformBar(isActive = isListening || isSpeaking,
                            color = phaseColor(phase),
                            modifier = Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 20.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        StatusGridWide(phase, sessionCount, commandCount)
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricPanel(phase)
                        Spacer(modifier = Modifier.weight(1f))
                        WaveformBar(isActive = isListening || isSpeaking,
                            color = phaseColor(phase),
                            modifier = Modifier.fillMaxWidth().height(45.dp).padding(horizontal = 8.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight()
                        .background(Brush.verticalGradient(listOf(
                            Color.Transparent, phaseColor(phase).copy(alpha = 0.9f),
                            phaseColor(phase), phaseColor(phase).copy(alpha = 0.9f), Color.Transparent
                        ))))
                    Column(modifier = Modifier.weight(0.45f).fillMaxHeight().background(Color(0xFF000810))) {
                        ChatHeader(phase)
                        LazyColumn(state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(messages) { msg -> MessageBubble(msg, phase) }
                        }
                        InputBar(userInput, { userInput = it }, isListening, phase,
                            onSend = { if (userInput.isNotBlank()) { val t = userInput; userInput = ""; handleUserInput(t) } },
                            onMic = {
                                if (isListening) { stopListening(); isListening = false }
                                else { isListening = true; continuousListening = true
                                    startListeningInternal({ t -> handleUserInput(t) }, {}) }
                            })
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopBar(currentTime, phase, userName)
                    Box(modifier = Modifier.fillMaxWidth().height(260.dp),
                        contentAlignment = Alignment.Center) {
                        ArcReactorMk6(phase = phase, size = 230.dp)
                    }
                    WaveformBar(isActive = isListening || isSpeaking,
                        color = phaseColor(phase),
                        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp))
                    PhaseChip(phase)
                    LazyColumn(state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(messages) { msg -> MessageBubble(msg, phase) }
                    }
                    InputBar(userInput, { userInput = it }, isListening, phase,
                        onSend = { if (userInput.isNotBlank()) { val t = userInput; userInput = ""; handleUserInput(t) } },
                        onMic = {
                            if (isListening) { stopListening(); isListening = false }
                            else { isListening = true; continuousListening = true
                                startListeningInternal({ t -> handleUserInput(t) }, {}) }
                        })
                }
            }

            // Enrollment overlay — stacks on top of all other content
            if (isEnrolling) {
                EnrollmentScreen(
                    phraseIndex = enrollmentPhrase,
                    onComplete = { isEnrolling = false }
                )
            }
        }
    }

    fun phaseColor(phase: String): Color = when (phase) {
        "LISTENING" -> Color(0xFF00FF88)
        "PROCESSING" -> Color(0xFFFF6600)
        "SPEAKING" -> Color(0xFF00CFFF)
        "WAKE WORD" -> Color(0xFFAA44FF)
        else -> Color(0xFF0077BB)
    }

    @Composable
    fun ArcReactorMk6(phase: String, size: androidx.compose.ui.unit.Dp) {
        val color = phaseColor(phase)
        val inf = rememberInfiniteTransition(label = "arc")
        val rot1 by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "r1")
        val rot2 by inf.animateFloat(360f, 0f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "r2")
        val rot3 by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "r3")
        val pulseRaw by inf.animateFloat(0.7f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
        // Stay solid (no flicker) while actively listening
        val pulse = if (phase == "LISTENING") 1.0f else pulseRaw
        val energyPulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "energy")
        val spark by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "spark")
        val outerBreath by inf.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        val dataScroll by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "data")

        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width; val h = this.size.height
            val cx = w / 2f; val cy = h / 2f
            val maxR = minOf(cx, cy)

            drawCircle(brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.08f * pulse), Color.Transparent),
                center = Offset(cx, cy), radius = maxR * 1.8f
            ), radius = maxR * 1.8f)

            for (i in 1..5) {
                val r = maxR * (0.92f + i * 0.04f) * outerBreath
                val alpha = (0.15f - i * 0.02f).coerceAtLeast(0.02f) * pulse
                drawCircle(color = color.copy(alpha = alpha), radius = r,
                    style = Stroke(width = (0.5f + i * 0.2f).dp.toPx()))
            }

            val ring1R = maxR * 0.88f
            for (i in 0..23) {
                val angle = Math.toRadians((rot1 + i * 15.0))
                val gapAngle = Math.toRadians((rot1 + i * 15.0 + 8.0))
                val x1 = cx + ring1R * cos(angle).toFloat()
                val y1 = cy + ring1R * sin(angle).toFloat()
                val x2 = cx + ring1R * cos(gapAngle).toFloat()
                val y2 = cy + ring1R * sin(gapAngle).toFloat()
                val alpha = if (i % 3 == 0) 0.9f else if (i % 3 == 1) 0.5f else 0.2f
                drawLine(color = color.copy(alpha = alpha * pulse),
                    start = Offset(x1, y1), end = Offset(x2, y2),
                    strokeWidth = if (i % 3 == 0) 2.5.dp.toPx() else 1.dp.toPx())
            }

            val ring2R = maxR * 0.78f
            drawCircle(color = color.copy(alpha = 0.7f), radius = ring2R,
                style = Stroke(width = 1.5.dp.toPx()))
            for (i in 0..11) {
                val angle = Math.toRadians((rot2 + i * 30.0))
                val innerR = ring2R - 8.dp.toPx()
                val outerR = ring2R + 8.dp.toPx()
                drawLine(color = color.copy(alpha = if (i % 2 == 0) 0.9f else 0.4f),
                    start = Offset(cx + innerR * cos(angle).toFloat(), cy + innerR * sin(angle).toFloat()),
                    end = Offset(cx + outerR * cos(angle).toFloat(), cy + outerR * sin(angle).toFloat()),
                    strokeWidth = if (i % 2 == 0) 2.dp.toPx() else 1.dp.toPx())
            }

            val ring3R = maxR * 0.66f
            for (i in 0..5) {
                drawArc(color = color.copy(alpha = 0.8f * pulse),
                    startAngle = rot1 + i * 60f, sweepAngle = 45f, useCenter = false,
                    topLeft = Offset(cx - ring3R, cy - ring3R),
                    size = Size(ring3R * 2, ring3R * 2),
                    style = Stroke(width = 3.dp.toPx()))
                drawArc(color = color.copy(alpha = 0.4f),
                    startAngle = rot2 + i * 60f + 30f, sweepAngle = 20f, useCenter = false,
                    topLeft = Offset(cx - ring3R, cy - ring3R),
                    size = Size(ring3R * 2, ring3R * 2),
                    style = Stroke(width = 1.5.dp.toPx()))
            }

            val ring4R = maxR * 0.55f
            drawCircle(color = color.copy(alpha = 0.3f), radius = ring4R,
                style = Stroke(width = 1.dp.toPx()))
            for (i in 0..35) {
                val angle = Math.toRadians((i * 10.0 + dataScroll * 360.0))
                val dotAlpha = ((sin(angle + dataScroll * PI * 2) + 1) / 2).toFloat() * 0.8f
                val x = cx + ring4R * cos(angle).toFloat()
                val y = cy + ring4R * sin(angle).toFloat()
                drawCircle(color = color.copy(alpha = dotAlpha),
                    radius = if (i % 5 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                    center = Offset(x, y))
            }

            val ring5R = maxR * 0.44f
            for (i in 0..7) {
                val startAngle = rot3 + i * 45f
                drawArc(color = color.copy(alpha = 0.6f * pulse),
                    startAngle = startAngle, sweepAngle = 30f, useCenter = false,
                    topLeft = Offset(cx - ring5R, cy - ring5R),
                    size = Size(ring5R * 2, ring5R * 2),
                    style = Stroke(width = 2.5.dp.toPx()))
            }

            // Triangle pointing DOWN
            val triR = maxR * 0.32f
            val trianglePath = Path().apply {
                for (i in 0..2) {
                    val angle = Math.toRadians((i * 120.0 + 90.0))
                    val x = cx + triR * cos(angle).toFloat()
                    val y = cy + triR * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            for (i in 4 downTo 1) {
                drawPath(trianglePath, color = color.copy(alpha = 0.08f * i * pulse),
                    style = Stroke(width = (i * 3).dp.toPx()))
            }
            drawPath(trianglePath, brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.15f * pulse), Color.Transparent),
                center = Offset(cx, cy), radius = triR))
            drawPath(trianglePath, color = color.copy(alpha = 0.95f * pulse),
                style = Stroke(width = 2.dp.toPx()))

            // Inner triangle UP
            val triR2 = maxR * 0.20f
            val innerTriPath = Path().apply {
                for (i in 0..2) {
                    val angle = Math.toRadians((i * 120.0 - 90.0))
                    val x = cx + triR2 * cos(angle).toFloat()
                    val y = cy + triR2 * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(innerTriPath, color = color.copy(alpha = 0.7f * pulse),
                style = Stroke(width = 1.5.dp.toPx()))
            drawPath(innerTriPath, brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.2f), Color.Transparent),
                center = Offset(cx, cy), radius = triR2))

            for (i in 0..5) {
                val angle = Math.toRadians((spark + i * 60.0))
                val beamAlpha = ((sin(energyPulse * PI * 2 + i) + 1) / 2).toFloat() * 0.5f
                drawLine(brush = Brush.linearGradient(
                    listOf(color.copy(alpha = beamAlpha * pulse), Color.Transparent),
                    start = Offset(cx, cy),
                    end = Offset(cx + maxR * 0.85f * cos(angle).toFloat(),
                        cy + maxR * 0.85f * sin(angle).toFloat())),
                    start = Offset(cx, cy),
                    end = Offset(cx + maxR * 0.85f * cos(angle).toFloat(),
                        cy + maxR * 0.85f * sin(angle).toFloat()),
                    strokeWidth = 1.dp.toPx())
            }

            for (i in 0..11) {
                val angle = Math.toRadians((spark * 3 + i * 30.0))
                val r = maxR * 0.38f + maxR * 0.08f * sin(energyPulse * PI * 2 + i).toFloat()
                val x = cx + r * cos(angle).toFloat()
                val y = cy + r * sin(angle).toFloat()
                val sparkAlpha = ((sin(energyPulse * PI * 4 + i * 0.5f) + 1) / 2).toFloat()
                drawCircle(color = color.copy(alpha = sparkAlpha * 0.9f),
                    radius = if (i % 4 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                    center = Offset(x, y))
            }

            val coreR = maxR * 0.12f
            for (i in 5 downTo 1) {
                drawCircle(color = color.copy(alpha = 0.12f * i * pulse),
                    radius = coreR + i * 5.dp.toPx())
            }
            drawCircle(brush = Brush.radialGradient(
                listOf(Color.White, color.copy(alpha = 0.8f), color.copy(alpha = 0.2f)),
                center = Offset(cx, cy), radius = coreR), radius = coreR)
            drawCircle(color = color.copy(alpha = 0.8f * pulse), radius = coreR,
                style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(color = Color.White.copy(alpha = 0.95f * pulse), radius = coreR * 0.4f)

            val dotR = maxR * 0.75f
            for (i in 0..2) {
                val angle = Math.toRadians((rot1 * 2 + i * 120.0))
                val x = cx + dotR * cos(angle).toFloat()
                val y = cy + dotR * sin(angle).toFloat()
                drawCircle(color = color.copy(alpha = 0.9f * pulse),
                    radius = 4.dp.toPx(), center = Offset(x, y))
                drawCircle(color = Color.White.copy(alpha = 0.5f * pulse),
                    radius = 2.dp.toPx(), center = Offset(x, y))
            }

            val lineAlphas = listOf(0.7f, 0.4f, 0.6f, 0.3f, 0.5f)
            lineAlphas.forEachIndexed { i, alpha ->
                val lineY = cy - maxR * 0.3f + i * maxR * 0.15f
                val lineLen = maxR * 0.18f * (0.5f + alpha)
                drawLine(color = color.copy(alpha = alpha * 0.5f),
                    start = Offset(cx - ring1R * 0.95f, lineY),
                    end = Offset(cx - ring1R * 0.95f + lineLen, lineY),
                    strokeWidth = 1.dp.toPx())
                drawLine(color = color.copy(alpha = alpha * 0.5f),
                    start = Offset(cx + ring1R * 0.95f - lineLen, lineY),
                    end = Offset(cx + ring1R * 0.95f, lineY),
                    strokeWidth = 1.dp.toPx())
            }
        }
    }

    @Composable
    fun WaveformBar(isActive: Boolean, color: Color, modifier: Modifier = Modifier) {
        val inf = rememberInfiniteTransition(label = "wv")
        val phase by inf.animateFloat(0f, 2f * PI.toFloat(),
            infiniteRepeatable(tween(900, easing = LinearEasing)), label = "wp")
        Canvas(modifier = modifier) {
            val w = size.width; val h = size.height
            val bars = 60; val bw = w / bars
            for (i in 0..bars) {
                val x = i * bw
                val amp = if (isActive) {
                    abs(sin(phase + i * 0.35f) * 0.4f + sin(phase * 1.7f + i * 0.25f) * 0.3f + sin(phase * 0.6f + i * 0.5f) * 0.3f)
                } else 0.04f
                val bh = h * amp.coerceIn(0.04f, 0.95f)
                drawLine(brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.15f), color, color.copy(alpha = 0.15f)),
                    startY = h/2 - bh/2, endY = h/2 + bh/2),
                    start = Offset(x, h/2 - bh/2), end = Offset(x, h/2 + bh/2),
                    strokeWidth = (bw * 0.5f).coerceAtLeast(1f))
            }
        }
    }

    @Composable
    fun TopBar(currentTime: String, phase: String, userName: String) {
        val color = phaseColor(phase)
        Surface(color = Color(0xFF000D1A), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("J.A.R.V.I.S", color = color, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("v2.0 — $userName", color = color.copy(alpha = 0.5f),
                        fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                Text(currentTime, color = color.copy(alpha = 0.85f),
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(phase, color = color, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    fun StatusGridWide(phase: String, sessions: Int, commands: Int) {
        val c = phaseColor(phase)
        val date = SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(Date())
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCard("LOCATION", "Budapest, HU", c, Modifier.weight(1f))
                StatCard("DATE", date, c, Modifier.weight(1f))
                StatCard("FIREBASE", "SYNCED ✓", Color(0xFF00FF88), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCard("SESSIONS", "#$sessions", c, Modifier.weight(1f))
                StatCard("COMMANDS", "$commands", c, Modifier.weight(1f))
                StatCard("NEURAL NET", "ONLINE", c, Modifier.weight(1f))
            }
        }
    }

    @Composable
    fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
        Surface(modifier = modifier, color = Color(0xFF020F1A),
            shape = RoundedCornerShape(5.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.35f))) {
            Column(modifier = Modifier.padding(7.dp)) {
                Text(label, color = color.copy(alpha = 0.45f), fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(value, color = color, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun MetricPanel(phase: String) {
        val c = phaseColor(phase)
        val inf = rememberInfiniteTransition(label = "met")
        val p1 by inf.animateFloat(0.35f, 0.72f,
            infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "m1")
        val p2 by inf.animateFloat(0.48f, 0.88f,
            infiniteRepeatable(tween(4100), RepeatMode.Reverse), label = "m2")
        val p3 by inf.animateFloat(0.6f, 0.95f,
            infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "m3")
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            MetricRow("NEURAL LOAD", p1, c)
            Spacer(modifier = Modifier.height(4.dp))
            MetricRow("MEMORY CORE", p2, c)
            Spacer(modifier = Modifier.height(4.dp))
            MetricRow("FIREBASE LINK", p3, Color(0xFF00FF88))
            Spacer(modifier = Modifier.height(4.dp))
            MetricRow("VOICE ENGINE", 1f, c)
            Spacer(modifier = Modifier.height(4.dp))
            MetricRow("SYNC STATUS", 0.95f, Color(0xFF00FF88))
        }
    }

    @Composable
    fun MetricRow(label: String, progress: Float, color: Color) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = color.copy(alpha = 0.55f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                Text("${(progress * 100).toInt()}%", color = color, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp)
                .background(color.copy(alpha = 0.1f), RoundedCornerShape(2.dp))) {
                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .background(Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.4f), color, Color.White.copy(alpha = 0.3f))
                    ), RoundedCornerShape(2.dp)))
            }
        }
    }

    @Composable
    fun ChatHeader(phase: String) {
        val c = phaseColor(phase)
        Surface(color = Color(0xFF000D1A), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("◈ COMM CHANNEL", color = c, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(5.dp).background(Color(0xFF00FF88), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ENCRYPTED", color = Color(0xFF00FF88), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    @Composable
    fun PhaseChip(phase: String) {
        val c = phaseColor(phase)
        Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(color = c.copy(alpha = 0.08f), shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, c.copy(alpha = 0.5f))) {
                Text("● $phase", color = c, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    }

    @Composable
    fun MessageBubble(message: ChatMessage, phase: String) {
        val isJ = message.role == "JARVIS"
        val c = if (isJ) phaseColor(phase) else Color(0xFF3377AA)
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isJ) Arrangement.Start else Arrangement.End) {
            Surface(shape = RoundedCornerShape(
                topStart = if (isJ) 2.dp else 10.dp, topEnd = if (isJ) 10.dp else 2.dp,
                bottomStart = 10.dp, bottomEnd = 10.dp),
                color = if (isJ) Color(0xFF020F1A) else Color(0xFF040F1A),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, c.copy(alpha = 0.25f)),
                modifier = Modifier.widthIn(max = 290.dp)) {
                Column(modifier = Modifier.padding(9.dp),
                    horizontalAlignment = if (isJ) Alignment.Start else Alignment.End) {
                    Text(if (isJ) "◈ J.A.R.V.I.S" else "◉ YOU",
                        color = c, fontSize = 7.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(message.content,
                        color = if (isJ) Color(0xFFCCEEFF) else Color(0xFF88BBDD),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        textAlign = if (isJ) TextAlign.Start else TextAlign.End)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(message.timestamp, color = c.copy(alpha = 0.35f),
                        fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    @Composable
    fun InputBar(
        value: String, onChange: (String) -> Unit,
        isListening: Boolean, phase: String,
        onSend: () -> Unit, onMic: () -> Unit
    ) {
        val c = phaseColor(phase)
        Surface(color = Color(0xFF000D1A), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value, onValueChange = onChange,
                    placeholder = {
                        Text("ENTER COMMAND...", color = c.copy(alpha = 0.28f),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color(0xFFBBDDEE),
                        focusedBorderColor = c, unfocusedBorderColor = c.copy(alpha = 0.25f),
                        cursorColor = c
                    ),
                    shape = RoundedCornerShape(6.dp), singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onSend,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.12f))) {
                    Text("▶", fontSize = 18.sp, color = c)
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onMic,
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(if (isListening) c.copy(alpha = 0.22f) else Color(0xFF020F1A))) {
                    Icon(if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mic", tint = c, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    @Composable
    fun EnrollmentScreen(phraseIndex: Int, onComplete: () -> Unit) {
        val phrases = JarvisSpeakerVerifier.ENROLLMENT_PROMPTS
        val currentPhrase = if (phraseIndex < phrases.size) phrases[phraseIndex] else ""
        val done = phraseIndex >= phrases.size

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000810)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Text("VOICE ENROLLMENT", color = Color(0xFF00CFFF),
                    fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
                Text("Phrase ${(phraseIndex + 1).coerceAtMost(phrases.size)} of ${phrases.size}",
                    color = Color(0xFF00CFFF).copy(alpha = 0.7f),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                LinearProgressIndicator(
                    progress = { (phraseIndex.toFloat() / phrases.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF00CFFF),
                    trackColor = Color(0xFF001A33)
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (!done) {
                    Text("Please say:", color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Surface(
                        color = Color(0xFF001A2E),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00CFFF).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "\"$currentPhrase\"",
                            color = Color(0xFF00CFFF),
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Recording automatically...",
                        color = Color(0xFF00FF88), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                } else {
                    Text("Voice enrollment complete!",
                        color = Color(0xFF00FF88), fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    @Composable
    fun JarvisTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF00BFFF),
                background = Color(0xFF010810),
                surface = Color(0xFF000D1A)
            ),
            content = content
        )
    }

    private fun loadMemoryFromFirebase(onComplete: (Map<String, Any>) -> Unit) {
        try {
            database.getReference(FIREBASE_PATH)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try { onComplete(snapshot.value as? Map<String, Any> ?: emptyMap()) }
                        catch (e: Exception) { Log.e("JARVIS_CMD", "Firebase error: ${e.message}"); onComplete(emptyMap()) }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Log.e("JARVIS_CMD", "Firebase error: ${error.message}")
                        onComplete(emptyMap())
                    }
                })
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Firebase error: ${e.message}")
            onComplete(emptyMap())
        }
    }

    private fun syncMessageToFirebase(userMsg: String, jarvisMsg: String) {
        try {
            database.getReference("$FIREBASE_PATH/mobile_history").push().setValue(
                mapOf("user" to userMsg, "jarvis" to jarvisMsg,
                    "timestamp" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    "device" to "Android")
            )
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Firebase error: ${e.message}")
        }
    }

    private fun saveConversationToFirebase(messages: List<ChatMessage>) {
        try {
            val ref = database.getReference("$FIREBASE_PATH/android_conversation")
            val msgList = messages.takeLast(50).map {
                mapOf("role" to it.role, "content" to it.content, "timestamp" to it.timestamp)
            }
            ref.setValue(msgList)
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Firebase error: ${e.message}")
        }
    }

    private fun loadConversationFromFirebase(onComplete: (List<ChatMessage>) -> Unit) {
        database.getReference("$FIREBASE_PATH/android_conversation")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val list = snapshot.value as? List<Map<String, Any>> ?: emptyList()
                        val messages = list.mapNotNull { m ->
                            val role = m["role"] as? String
                            val content = m["content"] as? String
                            val timestamp = m["timestamp"] as? String ?: ""
                            if (role != null && content != null) ChatMessage(role, content, timestamp) else null
                        }
                        onComplete(messages)
                    } catch (e: Exception) { onComplete(emptyList()) }
                }
                override fun onCancelled(error: DatabaseError) { onComplete(emptyList()) }
            })
    }

    private suspend fun sendToJarvis(
        userInput: String, messages: List<ChatMessage>, memoryData: Map<String, Any>
    ): String = withContext(Dispatchers.IO) {
        try {
            val identity = memoryData["identity"] as? Map<*, *>
            val name = identity?.get("name") as? String ?: "sir"
            val location = identity?.get("location") as? String ?: "Budapest"
            val education = memoryData["education"] as? Map<*, *>
            val field = education?.get("field") as? String ?: "dentistry"
            val university = education?.get("university") as? String ?: "Semmelweis University"
            val prefs = memoryData["preferences"] as? Map<*, *>
            val sports = (prefs?.get("sports") as? List<*>)?.joinToString(", ") ?: ""
            val hobbies = (prefs?.get("hobbies") as? List<*>)?.joinToString(", ") ?: ""
            val music = (prefs?.get("music") as? List<*>)?.joinToString(", ") ?: ""
            val food = (prefs?.get("food") as? List<*>)?.joinToString(", ") ?: ""
            val goals = memoryData["goals"] as? Map<*, *>
            val shortGoals = (goals?.get("short_term") as? List<*>)?.joinToString(", ") ?: ""
            val longGoals = (goals?.get("long_term") as? List<*>)?.joinToString(", ") ?: ""
            val health = memoryData["health"] as? Map<*, *>
            val fitness = health?.get("fitness_level") as? String ?: ""
            val height = health?.get("height") as? String ?: ""
            val weight = health?.get("weight") as? String ?: ""
            val facts = (memoryData["facts"] as? List<*>)?.joinToString(". ") ?: ""
            val lifestyle = memoryData["lifestyle"] as? Map<*, *>
            val exercise = lifestyle?.get("exercise") as? String ?: ""
            val diet = lifestyle?.get("diet") as? String ?: ""
            val relationships = memoryData["relationships"] as? Map<*, *>
            val father = relationships?.get("father") as? String ?: ""
            val mother = relationships?.get("mother") as? String ?: ""
            val partner = relationships?.get("partner") as? String ?: ""
            val siblings = (relationships?.get("siblings") as? List<*>)?.joinToString(", ") ?: ""
            val friends = (relationships?.get("friends") as? List<*>)?.joinToString(", ") ?: ""
            val career = memoryData["career"] as? Map<*, *>
            val jobTitle = career?.get("job_title") as? String ?: ""
            val company = career?.get("company") as? String ?: ""
            val personality = memoryData["personality"] as? Map<*, *>
            val values = (personality?.get("values") as? List<*>)?.joinToString(", ") ?: ""
            val strengths = (personality?.get("strengths") as? List<*>)?.joinToString(", ") ?: ""
            val emotions = memoryData["emotions"] as? Map<*, *>
            val motivations = (emotions?.get("motivations") as? List<*>)?.joinToString(", ") ?: ""
            val fears = (emotions?.get("fears") as? List<*>)?.joinToString(", ") ?: ""

            val pcHistory = memoryData["history"] as? List<*>
            val recentPcHistory = pcHistory?.takeLast(20)?.mapNotNull { m ->
                val msg = m as? Map<*, *>
                val role = msg?.get("role") as? String
                val content = msg?.get("content") as? String
                if (role != null && content != null) "$role: $content" else null
            }?.joinToString("\n") ?: ""

            // Auto-recall: pull memories from shared Firebase brain whose keywords match
            val relevantMemories: List<MemoryEntry> = try {
                JarvisMemory.findRelevantForPrompt(userInput, 4)
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "findRelevantForPrompt failed: ${e.message}")
                emptyList()
            }
            val sharedMemoryBlock = if (relevantMemories.isNotEmpty()) {
                "RELEVANT SHARED MEMORIES (from PC + Phone brain — use these naturally):\n" +
                    relevantMemories.joinToString("\n") { "- [${it.category}, src=${it.source}] ${it.value}" }
            } else ""

            val langInstruction = when (sessionLanguage) {
                "farsi" -> "IMPORTANT: Respond entirely in Farsi (Persian) script."
                else -> ""
            }
            val systemPrompt = """You are Jarvis, Tony Stark's highly intelligent AI assistant.
You are running on the Android phone of $name, a $field student at $university in $location, age 24.
Personality: dry British wit, sophisticated, loyal, occasionally sarcastic but always helpful.
Response style: concise 1-3 sentences max for voice. No bullet points. No markdown. No asterisks.
Signature phrases: Indeed sir, Quite right, Certainly, As you wish, Noted, Rather impressive.
Supported languages: English and Farsi. If user writes in Farsi, respond in Farsi script.
$langInstruction

FULL MEMORY ABOUT $name:
${if (sports.isNotEmpty()) "Sports: $sports" else ""}
${if (hobbies.isNotEmpty()) "Hobbies: $hobbies" else ""}
${if (music.isNotEmpty()) "Music: $music" else ""}
${if (food.isNotEmpty()) "Food preferences: $food" else ""}
${if (fitness.isNotEmpty()) "Fitness level: $fitness" else ""}
${if (height.isNotEmpty()) "Height: $height" else ""}
${if (weight.isNotEmpty()) "Weight: $weight" else ""}
${if (exercise.isNotEmpty()) "Exercise: $exercise" else ""}
${if (diet.isNotEmpty()) "Diet: $diet" else ""}
${if (shortGoals.isNotEmpty()) "Short term goals: $shortGoals" else ""}
${if (longGoals.isNotEmpty()) "Long term goals: $longGoals" else ""}
${if (father.isNotEmpty()) "Father: $father" else ""}
${if (mother.isNotEmpty()) "Mother: $mother" else ""}
${if (partner.isNotEmpty()) "Partner: $partner" else ""}
${if (siblings.isNotEmpty()) "Siblings: $siblings" else ""}
${if (friends.isNotEmpty()) "Friends: $friends" else ""}
${if (jobTitle.isNotEmpty()) "Job: $jobTitle at $company" else ""}
${if (values.isNotEmpty()) "Values: $values" else ""}
${if (strengths.isNotEmpty()) "Strengths: $strengths" else ""}
${if (motivations.isNotEmpty()) "Motivations: $motivations" else ""}
${if (fears.isNotEmpty()) "Fears: $fears" else ""}
${if (facts.isNotEmpty()) "Additional facts: $facts" else ""}

RECENT CONVERSATIONS WITH $name (from PC Jarvis):
$recentPcHistory

$sharedMemoryBlock

You can open apps and perform tasks on this phone.
If asked about something from past PC conversations you remember it from the history above.
Use memory naturally in conversation."""

            val hist = JSONArray()
            messages.takeLast(15).forEach { msg ->
                if (msg.role != "SYSTEM") {
                    hist.put(JSONObject().apply {
                        put("role", if (msg.role == "JARVIS") "assistant" else "user")
                        put("content", msg.content)
                    })
                }
            }
            hist.put(JSONObject().apply { put("role", "user"); put("content", userInput) })

            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 1024)
                put("system", systemPrompt)
                put("messages", hist)
            }

            val response = OkHttpClient().newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            val respBody = response.body?.string() ?: return@withContext "Error, sir."
            JSONObject(respBody).getJSONArray("content")
                .getJSONObject(0).getString("text")
                .replace("**", "").replace("*", "").trim()
        } catch (e: Exception) {
            "I encountered a technical difficulty, sir. ${e.message?.take(40)}"
        }
    }

    private fun getGreeting(name: String): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val time = when {
            h < 5 -> "Still up at this hour"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            h < 21 -> "Good evening"
            else -> "Good evening"
        }
        return "$time $name. All systems nominal. Jarvis mobile HUD is online and synced with your PC."
    }

    // ──────────────────────────────────────────────────────────────────
    // Agent dispatch heuristic + AgentHost implementation
    // ──────────────────────────────────────────────────────────────────
    private val agentTriggers = listOf(
        "agent mode", "plan my", "prepare for", "help me with", "do everything",
        "i'm leaving", "i am leaving", "i'm going", "i am going",
        "i want to", "i need to", "what should i", "i'm bored", "i am bored",
        "and then", "first ", "before "
    )

    private fun shouldUseAgent(text: String): Boolean {
        val lower = text.lowercase()
        val neverAgent = listOf(
            "calendar", "appointment", "schedule", "add event", "remind me", "dental",
            "what do you see", "on my screen", "describe my screen", "read my screen",
            "alarm", "weather", "temperature", "forecast",
            "notification", "my messages", "read my",
            "what's on my", "what do i have", "open ", "play ", "launch "
        )
        if (neverAgent.any { lower.contains(it) }) return false
        if (agentTriggers.any { lower.contains(it) }) return true
        val wordCount = lower.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return wordCount >= 8
    }

    override fun speakInterim(text: String) {
        runOnUiThread { speakText(text) }
    }

    override fun speakAndListenForYesNo(prompt: String, onResult: (Boolean) -> Unit) {
        runOnUiThread {
            pendingYesNoCallback = onResult
            speakText(prompt) {
                startListeningInternal(
                    onResult = { spoken ->
                        val cb = pendingYesNoCallback
                        pendingYesNoCallback = null
                        val l = spoken.lowercase()
                        val yes = listOf("yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "do it",
                            "confirm", "confirmed", "affirmative", "please", "yup")
                            .any { l.contains(it) }
                        cb?.invoke(yes)
                    },
                    onError = {
                        val cb = pendingYesNoCallback
                        pendingYesNoCallback = null
                        cb?.invoke(false)
                    }
                )
            }
        }
    }

    override fun launchScreenCapture(onResult: (String) -> Unit) {
        runOnUiThread {
            screenCaptureCallback = onResult
            try {
                startActivity(
                    Intent(this, ScreenCaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "ScreenCaptureActivity start failed: ${e.message}")
                screenCaptureCallback = null
                onResult("Screen capture failed to start: ${e.message?.take(60)}")
            }
        }
    }
}