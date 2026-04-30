package com.jarvis.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

// Transparent activity whose only job is to request MediaProjection permission
// and then hand the resulting intent off to ScreenCaptureService.
class ScreenCaptureActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.e("JARVIS_CMD", "Screen capture permission granted")
            // Store raw token for service-side rebuild
            JarvisListenerService.storedResultCode = result.resultCode
            JarvisListenerService.storedProjectionData = result.data
            // Create and store projection instance for direct use by JarvisListenerService
            try {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val proj = pm.getMediaProjection(result.resultCode, result.data!!)
                JarvisListenerService.mediaProjectionInstance = proj
                Log.e("JARVIS_CMD", "MediaProjection instance stored globally — screen vision enabled everywhere")
                // Tell the running service about the stored token
                startService(Intent(this, JarvisListenerService::class.java).apply {
                    action = "ACTION_STORE_PROJECTION_TOKEN"
                    putExtra("projection_result_code", result.resultCode)
                    putExtra("projection_data", result.data)
                })
                // Use stored instance in ScreenCaptureService (token already consumed above)
                val svc = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                }
                try {
                    startForegroundService(svc)
                } catch (e: Exception) {
                    Log.e("JARVIS_CMD", "ScreenCaptureService start failed: ${e.message}")
                    broadcastError("Screen vision failed to start, sir.")
                }
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "Failed to store projection: ${e.message}")
                // Fall back to original flow
                val svc = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                }
                try {
                    startForegroundService(svc)
                } catch (e2: Exception) {
                    Log.e("JARVIS_CMD", "ScreenCaptureService start failed: ${e2.message}")
                    broadcastError("Screen vision failed to start, sir.")
                }
            }
        } else {
            Log.e("JARVIS_CMD", "Screen capture permission denied")
            broadcastError("Screen capture permission denied, sir.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    private fun broadcastError(message: String) {
        sendBroadcast(Intent(ScreenCaptureService.ACTION_RESULT).apply {
            `package` = packageName
            putExtra("error", message)
        })
    }
}
