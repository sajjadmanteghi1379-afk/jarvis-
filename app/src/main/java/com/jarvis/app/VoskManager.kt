package com.jarvis.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class VoskManager(private val context: Context) {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var callback: ((String) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var initialized = false
    private var startWhenReady = false

    fun init() {
        if (initialized || model != null) return
        Log.e("JARVIS_CMD", "Vosk init started")
        StorageService.unpack(
            context,
            "model/vosk-model-small-en-us-0.15",
            "model",
            { loadedModel ->
                model = loadedModel
                initialized = true
                Log.e("JARVIS_CMD", "Vosk model loaded")
                if (startWhenReady) {
                    startWhenReady = false
                    callback?.let { startListening(it, errorCallback) }
                }
            },
            { exception ->
                initialized = false
                Log.e("JARVIS_CMD", "Vosk model load failed: ${exception.message}", exception)
            }
        )
    }

    fun startListening(callback: (String) -> Unit, onRecoverableError: ((String) -> Unit)? = null) {
        this.callback = callback
        this.errorCallback = onRecoverableError
        val currentModel = model
        if (currentModel == null) {
            startWhenReady = true
            init()
            Log.e("JARVIS_CMD", "Vosk start deferred until model is ready")
            return
        }
        if (speechService != null) return
        try {
            val recognizer = Recognizer(currentModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f).also { service ->
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        // Partial results are intentionally ignored for command execution.
                    }

                    override fun onResult(hypothesis: String?) {
                        emitText(hypothesis)
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        emitText(hypothesis)
                    }

                    override fun onError(exception: Exception?) {
                        Log.e("JARVIS_CMD", "Vosk recognition error: ${exception?.message}", exception)
                        stopListening()
                        errorCallback?.invoke(exception?.message ?: "Vosk recognition error")
                    }

                    override fun onTimeout() {
                        Log.e("JARVIS_CMD", "Vosk recognition timeout")
                        stopListening()
                        errorCallback?.invoke("Vosk recognition timeout")
                    }
                })
            }
            Log.e("JARVIS_CMD", "Vosk listening started")
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Vosk start failed: ${e.message}", e)
            speechService = null
        }
    }

    fun stopListening() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Vosk stop failed: ${e.message}")
        } finally {
            speechService = null
            Log.e("JARVIS_CMD", "Vosk listening stopped")
        }
    }

    private fun emitText(hypothesis: String?) {
        val text = try {
            JSONObject(hypothesis ?: "{}").optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
        if (text.isBlank()) return
        Log.e("JARVIS_CMD", "Vosk recognized text: '$text'")
        callback?.invoke(text)
    }
}
