package com.jarvis.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// Short-lived foreground service of type mediaProjection. Activated by
// ScreenCaptureActivity after permission is granted; captures a single
// frame, asks Claude Vision to describe it, and broadcasts the result.
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_screen_vision_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_RESULT = "com.jarvis.app.SCREEN_VISION_RESULT"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.e("JARVIS_CMD", "MediaProjection stopped")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground BEFORE getMediaProjection (Android 14 requirement)
        startForeground(NOTIFICATION_ID, buildNotification())

        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("resultData")
        }
        if (code != Activity.RESULT_OK || data == null) {
            broadcast(error = "Could not acquire screen capture, sir.")
            stopSelfSafely(); return START_NOT_STICKY
        }

        val stored = JarvisListenerService.mediaProjectionInstance
        if (stored != null) {
            Log.e("JARVIS_CMD", "Using stored MediaProjection instance from companion")
            projection = stored
            capture()
        } else {
            try {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(code, data).apply {
                    registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                }
                JarvisListenerService.mediaProjectionInstance = projection
                capture()
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "getMediaProjection failed: ${e.message}")
                broadcast(error = "Screen vision failed, sir.")
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    private fun capture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection?.createVirtualDisplay(
            "JarvisScreenCap", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        scope.launch {
            delay(600) // give the reader time to receive a frame
            try {
                val image = reader.acquireLatestImage()
                if (image == null) {
                    broadcast(error = "Could not capture screen, sir.")
                    stopSelfSafely(); return@launch
                }
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                image.close()
                val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)

                // Downscale to keep API payload small
                val maxDim = 1024
                val scale = minOf(1f, maxDim.toFloat() / maxOf(width, height))
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
                    cropped, (width * scale).toInt(), (height * scale).toInt(), true
                ) else cropped

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                Log.e("JARVIS_CMD", "Screen capture: ${b64.length} chars base64")

                val text = queryClaudeVision(b64)
                broadcast(text = text)
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Screen capture error: ${e.message}")
                broadcast(error = "Screen vision failed, sir.")
            } finally {
                stopSelfSafely()
            }
        }
    }

    private fun queryClaudeVision(b64: String): String {
        return try {
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
                                    put("data", b64)
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

            val raw = response.body?.string() ?: return "No response from vision, sir."
            if (!response.isSuccessful) {
                Log.e("JARVIS_CMD", "Vision API non-OK: ${response.code} $raw")
                return "Vision API returned ${response.code}, sir."
            }
            JSONObject(raw).getJSONArray("content").getJSONObject(0).getString("text")
                .replace("**", "").replace("*", "").trim()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Vision API error: ${e.message}")
            "Screen vision failed, sir."
        }
    }

    private fun broadcast(text: String? = null, error: String? = null) {
        sendBroadcast(Intent(ACTION_RESULT).apply {
            `package` = packageName
            if (text != null) putExtra("text", text)
            if (error != null) putExtra("error", error)
        })
    }

    private fun stopSelfSafely() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        // Keep projection alive in companion so JarvisListenerService can use it directly.
        // Only stop if it is not held in the companion object.
        if (JarvisListenerService.mediaProjectionInstance == null) {
            try {
                projection?.unregisterCallback(projectionCallback)
                projection?.stop()
            } catch (_: Exception) {}
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        if (JarvisListenerService.mediaProjectionInstance == null) {
            try {
                projection?.unregisterCallback(projectionCallback)
                projection?.stop()
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis Screen Vision", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false); setSound(null, null)
            }
            mgr.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S Vision")
            .setContentText("Analyzing screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
