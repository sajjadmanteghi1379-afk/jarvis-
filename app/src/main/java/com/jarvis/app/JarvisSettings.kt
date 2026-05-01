package com.jarvis.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class HudSettings(
    val floatingHudEnabled: Boolean = false,
    val opacity: Float = 0.82f,
    val size: Float = 1.0f,
    val animationIntensity: Float = 0.75f
)

data class JarvisSettings(
    val backgroundActive: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val conversationTimeoutSeconds: Float = 30f,
    val proactiveSuggestions: Boolean = true,
    val ttsSpeed: Float = 0.85f,
    val ttsPitch: Float = 1.0f,
    val voiceFeedback: Boolean = true,
    val aiFallback: Boolean = true,
    val researchMode: String = "PDF",
    val screenVision: Boolean = true,
    val readNotifications: Boolean = true,
    val saveMemory: Boolean = true,
    val hud: HudSettings = HudSettings()
)

object JarvisSettingsStore {
    private const val PREFS = "jarvis_phase_settings"
    private var appContext: Context? = null

    var settings by mutableStateOf(JarvisSettings())
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = JarvisSettings(
            backgroundActive = prefs.getBoolean("backgroundActive", true),
            wakeWordEnabled = prefs.getBoolean("wakeWordEnabled", false),
            conversationTimeoutSeconds = prefs.getFloat("conversationTimeoutSeconds", 30f),
            proactiveSuggestions = prefs.getBoolean("proactiveSuggestions", true),
            ttsSpeed = prefs.getFloat("ttsSpeed", 0.85f),
            ttsPitch = prefs.getFloat("ttsPitch", 1.0f),
            voiceFeedback = prefs.getBoolean("voiceFeedback", true),
            aiFallback = prefs.getBoolean("aiFallback", true),
            researchMode = prefs.getString("researchMode", "PDF") ?: "PDF",
            screenVision = prefs.getBoolean("screenVision", true),
            readNotifications = prefs.getBoolean("readNotifications", true),
            saveMemory = prefs.getBoolean("saveMemory", true),
            hud = HudSettings(
                floatingHudEnabled = prefs.getBoolean("floatingHudEnabled", false),
                opacity = prefs.getFloat("hudOpacity", 0.82f),
                size = prefs.getFloat("hudSize", 1.0f),
                animationIntensity = prefs.getFloat("animationIntensity", 0.75f)
            )
        )
    }

    fun update(transform: (JarvisSettings) -> JarvisSettings) {
        settings = transform(settings)
        persist()
    }

    private fun persist() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putBoolean("backgroundActive", settings.backgroundActive)
            .putBoolean("wakeWordEnabled", settings.wakeWordEnabled)
            .putFloat("conversationTimeoutSeconds", settings.conversationTimeoutSeconds)
            .putBoolean("proactiveSuggestions", settings.proactiveSuggestions)
            .putFloat("ttsSpeed", settings.ttsSpeed)
            .putFloat("ttsPitch", settings.ttsPitch)
            .putBoolean("voiceFeedback", settings.voiceFeedback)
            .putBoolean("aiFallback", settings.aiFallback)
            .putString("researchMode", settings.researchMode)
            .putBoolean("screenVision", settings.screenVision)
            .putBoolean("readNotifications", settings.readNotifications)
            .putBoolean("saveMemory", settings.saveMemory)
            .putBoolean("floatingHudEnabled", settings.hud.floatingHudEnabled)
            .putFloat("hudOpacity", settings.hud.opacity)
            .putFloat("hudSize", settings.hud.size)
            .putFloat("animationIntensity", settings.hud.animationIntensity)
            .apply()
    }
}

object FloatingHudController {
    fun applySettings(context: Context, settings: HudSettings) {
        if (settings.floatingHudEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)
        ) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
