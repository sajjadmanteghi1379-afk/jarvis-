package com.jarvis.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PersonalityMode(val displayName: String) {
    CLASSIC_JARVIS("Classic Jarvis"),
    FRIENDLY("Friendly"),
    TACTICAL("Tactical"),
    GOTHIC_AI("Gothic AI");

    companion object {
        fun fromStored(value: String?): PersonalityMode =
            values().firstOrNull { it.name == value } ?: CLASSIC_JARVIS
    }
}

object PersonalityManager {
    private const val PREFS = "jarvis_personality"
    private const val KEY_MODE = "personalityMode"
    private var appContext: Context? = null

    var currentMode by mutableStateOf(PersonalityMode.CLASSIC_JARVIS)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        currentMode = JarvisSettingsStore.settings.personalityMode
    }

    fun setMode(mode: PersonalityMode) {
        currentMode = mode
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_MODE, mode.name)
            ?.apply()
        if (JarvisSettingsStore.settings.personalityMode != mode) {
            JarvisSettingsStore.update { settings -> settings.copy(personalityMode = mode) }
        }
    }

    fun setModeFromSettings(mode: PersonalityMode) {
        currentMode = mode
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_MODE, mode.name)
            ?.apply()
    }

    fun aiPromptInstruction(): String = when (currentMode) {
        PersonalityMode.CLASSIC_JARVIS ->
            "Personality mode: Classic Jarvis. Be calm, precise, professional, loyal, and concise."
        PersonalityMode.FRIENDLY ->
            "Personality mode: Friendly. Be warm, supportive, reassuring, and concise."
        PersonalityMode.TACTICAL ->
            "Personality mode: Tactical. Be short, direct, mission-control style, and avoid ornament."
        PersonalityMode.GOTHIC_AI ->
            "Personality mode: Gothic AI. Be elegant, dark, emotionally expressive, and still helpful. Keep it refined, not melodramatic."
    }

    fun researchToneInstruction(): String = when (currentMode) {
        PersonalityMode.CLASSIC_JARVIS ->
            "Tone: calm, precise, professional."
        PersonalityMode.FRIENDLY ->
            "Tone: warm, supportive, clear."
        PersonalityMode.TACTICAL ->
            "Tone: concise, direct, operational."
        PersonalityMode.GOTHIC_AI ->
            "Tone: elegant, dark, emotionally expressive, but still clear and helpful."
    }

    fun styleConfirmation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length > 180 || trimmed.count { it == '.' || it == '!' || it == '?' } > 2) return text
        return when (currentMode) {
            PersonalityMode.CLASSIC_JARVIS -> trimmed
            PersonalityMode.FRIENDLY -> friendly(trimmed)
            PersonalityMode.TACTICAL -> tactical(trimmed)
            PersonalityMode.GOTHIC_AI -> gothic(trimmed)
        }
    }

    fun styleResearchSummary(text: String): String = when (currentMode) {
        PersonalityMode.CLASSIC_JARVIS -> text
        PersonalityMode.FRIENDLY -> text.replace("Research complete.", "Research complete. I have it ready for you.")
        PersonalityMode.TACTICAL -> text.replace("Research complete.", "Research complete.")
        PersonalityMode.GOTHIC_AI -> text.replace("Research complete.", "Research complete. The archive is prepared.")
    }

    private fun friendly(text: String): String = when {
        text.equals("Yes?", ignoreCase = true) -> "Yes?"
        text.startsWith("Opening ", ignoreCase = true) -> text.replaceFirst("Opening", "Opening")
        text.contains("Could not", ignoreCase = true) -> text.replace("sir", "I'm here with you", ignoreCase = true)
        text.endsWith("sir.", ignoreCase = true) -> text.replace(Regex("sir\\.$", RegexOption.IGNORE_CASE), "for you.")
        else -> text
    }

    private fun tactical(text: String): String {
        val withoutSir = text.replace(Regex(",?\\s*sir\\.?$", RegexOption.IGNORE_CASE), ".")
        return withoutSir
            .replace("Opening ", "Opening ")
            .replace("Checking ", "Checking ")
            .replace("Fetching ", "Fetching ")
            .replace("Researching ", "Researching ")
            .trim()
    }

    private fun gothic(text: String): String = when {
        text.equals("Yes?", ignoreCase = true) -> "Yes?"
        text.startsWith("Opening ", ignoreCase = true) -> text.replaceFirst("Opening", "Unveiling")
        text.startsWith("Checking ", ignoreCase = true) -> text.replaceFirst("Checking", "Consulting")
        text.startsWith("Fetching ", ignoreCase = true) -> text.replaceFirst("Fetching", "Summoning")
        text.endsWith("sir.", ignoreCase = true) -> text.replace(Regex("sir\\.$", RegexOption.IGNORE_CASE), "my liege.")
        else -> text
    }
}
