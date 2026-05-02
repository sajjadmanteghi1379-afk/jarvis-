package com.jarvis.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AvatarStyle(val displayName: String) {
    ORB("Orb"),
    FULL_BODY_PLACEHOLDER("Full body placeholder"),
    MINIMAL_HUD("Minimal HUD");

    companion object {
        fun fromStored(value: String?): AvatarStyle =
            values().firstOrNull { it.name == value } ?: ORB
    }
}

data class HudSettings(
    val floatingHudEnabled: Boolean = false,
    val opacity: Float = 0.82f,
    val size: Float = 1.0f,
    val animationIntensity: Float = 0.75f
)

data class AvatarSettings(
    val enabled: Boolean = true,
    val style: AvatarStyle = AvatarStyle.ORB,
    val animationIntensity: Float = 0.75f,
    val voiceReactive: Boolean = true
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
    val personalityMode: PersonalityMode = PersonalityMode.CLASSIC_JARVIS,
    val researchMode: String = "PDF",
    val screenVision: Boolean = true,
    val readNotifications: Boolean = true,
    val saveMemory: Boolean = true,
    val hud: HudSettings = HudSettings(),
    val avatar: AvatarSettings = AvatarSettings()
)

object JarvisSettingsRepository {
    private const val PREFS = "jarvis_phase_settings"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun load(context: Context): JarvisSettings {
        init(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = JarvisSettings(
            backgroundActive = prefs.getBoolean("backgroundActive", true),
            wakeWordEnabled = prefs.getBoolean("wakeWordEnabled", false),
            conversationTimeoutSeconds = prefs.getFloat("conversationTimeoutSeconds", 30f),
            proactiveSuggestions = prefs.getBoolean("proactiveSuggestions", true),
            ttsSpeed = prefs.getFloat("ttsSpeed", 0.85f),
            ttsPitch = prefs.getFloat("ttsPitch", 1.0f),
            voiceFeedback = prefs.getBoolean("voiceFeedback", true),
            aiFallback = prefs.getBoolean("aiFallback", true),
            personalityMode = PersonalityMode.fromStored(prefs.getString("personalityMode", null)),
            researchMode = prefs.getString("researchMode", "PDF") ?: "PDF",
            screenVision = prefs.getBoolean("screenVision", true),
            readNotifications = prefs.getBoolean("readNotifications", true),
            saveMemory = prefs.getBoolean("saveMemory", true),
            hud = HudSettings(
                floatingHudEnabled = prefs.getBoolean("floatingHudEnabled", false),
                opacity = prefs.getFloat("hudOpacity", 0.82f),
                size = prefs.getFloat("hudSize", 1.0f),
                animationIntensity = prefs.getFloat("animationIntensity", 0.75f)
            ),
            avatar = AvatarSettings(
                enabled = prefs.getBoolean("avatarEnabled", true),
                style = AvatarStyle.fromStored(prefs.getString("avatarStyle", null)),
                animationIntensity = prefs.getFloat("avatarAnimationIntensity", 0.75f),
                voiceReactive = prefs.getBoolean("avatarVoiceReactive", true)
            )
        )
        Log.e("JARVIS_CMD", "Settings loaded")
        return settings
    }

    fun save(settings: JarvisSettings) {
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
            .putString("personalityMode", settings.personalityMode.name)
            .putString("researchMode", settings.researchMode)
            .putBoolean("screenVision", settings.screenVision)
            .putBoolean("readNotifications", settings.readNotifications)
            .putBoolean("saveMemory", settings.saveMemory)
            .putBoolean("floatingHudEnabled", settings.hud.floatingHudEnabled)
            .putFloat("hudOpacity", settings.hud.opacity)
            .putFloat("hudSize", settings.hud.size)
            .putFloat("animationIntensity", settings.hud.animationIntensity)
            .putBoolean("avatarEnabled", settings.avatar.enabled)
            .putString("avatarStyle", settings.avatar.style.name)
            .putFloat("avatarAnimationIntensity", settings.avatar.animationIntensity)
            .putBoolean("avatarVoiceReactive", settings.avatar.voiceReactive)
            .apply()
    }

    fun applyToService(settings: JarvisSettings) {
        Log.e("JARVIS_CMD", "Settings applied to service")
    }
}

object JarvisSettingsStore {
    private var appContext: Context? = null

    var settings by mutableStateOf(JarvisSettings())
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        settings = JarvisSettingsRepository.load(context)
        PersonalityManager.setModeFromSettings(settings.personalityMode)
        JarvisSettingsRepository.applyToService(settings)
    }

    fun update(transform: (JarvisSettings) -> JarvisSettings) {
        val previous = settings
        val next = transform(settings)
        settings = next
        logChanges(previous, next)
        JarvisSettingsRepository.save(settings)
        PersonalityManager.setModeFromSettings(settings.personalityMode)
        JarvisSettingsRepository.applyToService(settings)
    }

    private fun logChanges(previous: JarvisSettings, next: JarvisSettings) {
        if (previous.backgroundActive != next.backgroundActive) logSetting("backgroundActive", next.backgroundActive)
        if (previous.wakeWordEnabled != next.wakeWordEnabled) logSetting("wakeWordEnabled", next.wakeWordEnabled)
        if (previous.conversationTimeoutSeconds != next.conversationTimeoutSeconds) logSetting("conversationTimeoutSeconds", next.conversationTimeoutSeconds)
        if (previous.ttsSpeed != next.ttsSpeed) logSetting("ttsSpeed", next.ttsSpeed)
        if (previous.ttsPitch != next.ttsPitch) logSetting("ttsPitch", next.ttsPitch)
        if (previous.personalityMode != next.personalityMode) logSetting("personalityMode", next.personalityMode.name)
        if (previous.aiFallback != next.aiFallback) logSetting("aiFallback", next.aiFallback)
        if (previous.researchMode != next.researchMode) logSetting("researchMode", next.researchMode)
        if (previous.hud.floatingHudEnabled != next.hud.floatingHudEnabled) logSetting("floatingHudEnabled", next.hud.floatingHudEnabled)
        if (previous.hud.opacity != next.hud.opacity) logSetting("hudOpacity", next.hud.opacity)
        if (previous.hud.size != next.hud.size) logSetting("hudSize", next.hud.size)
        if (previous.hud.animationIntensity != next.hud.animationIntensity) logSetting("animationIntensity", next.hud.animationIntensity)
        if (previous.screenVision != next.screenVision) logSetting("screenVision", next.screenVision)
        if (previous.readNotifications != next.readNotifications) logSetting("readNotifications", next.readNotifications)
        if (previous.saveMemory != next.saveMemory) logSetting("saveMemory", next.saveMemory)
        if (previous.avatar.enabled != next.avatar.enabled) logSetting("avatarEnabled", next.avatar.enabled)
        if (previous.avatar.style != next.avatar.style) logSetting("avatarStyle", next.avatar.style.name)
        if (previous.avatar.animationIntensity != next.avatar.animationIntensity) logSetting("avatarAnimationIntensity", next.avatar.animationIntensity)
        if (previous.avatar.voiceReactive != next.avatar.voiceReactive) logSetting("avatarVoiceReactive", next.avatar.voiceReactive)
    }

    private fun logSetting(name: String, value: Any) {
        Log.e("JARVIS_CMD", "Setting updated: $name=$value")
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
