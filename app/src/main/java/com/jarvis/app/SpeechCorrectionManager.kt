package com.jarvis.app

object SpeechCorrectionManager {
    fun normalizeCommand(raw: String): String {
        return CommandNormalizer.normalize(raw).text
    }
}
