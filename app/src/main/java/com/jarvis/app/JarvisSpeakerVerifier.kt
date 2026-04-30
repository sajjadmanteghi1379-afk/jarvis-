package com.jarvis.app

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.math.*
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

class JarvisSpeakerVerifier(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_SECONDS = 3
        const val NUM_MFCC = 13
        const val VERIFICATION_THRESHOLD = 0.75f
        const val ENROLLMENT_PHRASES = 10

        val ENROLLMENT_PROMPTS = listOf(
            "Hey Jarvis, activate",
            "Open Instagram",
            "What is the weather today",
            "Set an alarm for tomorrow",
            "Play music on Spotify",
            "Navigate to university",
            "Research dental implants",
            "What do I have today",
            "Turn on the flashlight",
            "Goodbye Jarvis"
        )
    }

    private var enrolledEmbedding: FloatArray? = null
    private val embeddingFile = File(context.filesDir, "jarvis_voiceprint.dat")
    private var isEnrolled = false

    init {
        loadEnrollment()
    }

    fun isEnrolled(): Boolean = isEnrolled

    private fun loadEnrollment() {
        try {
            if (embeddingFile.exists()) {
                ObjectInputStream(embeddingFile.inputStream()).use { ois ->
                    enrolledEmbedding = ois.readObject() as FloatArray
                    isEnrolled = true
                    Log.e("JARVIS_SPEAKER", "Loaded voiceprint from disk")
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_SPEAKER", "Load enrollment failed: ${e.message}")
            isEnrolled = false
        }
    }

    private fun saveEnrollment(embedding: FloatArray) {
        try {
            ObjectOutputStream(FileOutputStream(embeddingFile)).use { oos ->
                oos.writeObject(embedding)
            }
            Log.e("JARVIS_SPEAKER", "Saved voiceprint to disk")
        } catch (e: Exception) {
            Log.e("JARVIS_SPEAKER", "Save enrollment failed: ${e.message}")
        }
    }

    suspend fun recordAudio(durationSeconds: Int = BUFFER_SIZE_SECONDS): ShortArray = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return@withContext ShortArray(0)
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val totalSamples = SAMPLE_RATE * durationSeconds
        val audioData = ShortArray(totalSamples)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        recorder.startRecording()
        var samplesRead = 0
        while (samplesRead < totalSamples) {
            val read = recorder.read(audioData, samplesRead, minOf(bufferSize, totalSamples - samplesRead))
            if (read > 0) samplesRead += read else break
        }
        recorder.stop()
        recorder.release()
        audioData
    }

    private fun extractMFCC(audioData: ShortArray): FloatArray {
        val floatData = FloatArray(audioData.size) { audioData[it] / 32768.0f }
        val frameSize = 512
        val hopSize = 256
        val numFrames = (floatData.size - frameSize) / hopSize

        if (numFrames <= 0) return FloatArray(NUM_MFCC)

        val mfccSum = FloatArray(NUM_MFCC)

        for (frameIdx in 0 until numFrames) {
            val start = frameIdx * hopSize
            val frame = floatData.copyOfRange(start, start + frameSize)

            for (i in frame.indices) {
                frame[i] *= (0.54f - 0.46f * cos(2.0 * Math.PI * i / (frame.size - 1))).toFloat()
            }

            val frameMFCC = computeSimpleMFCC(frame)
            for (i in 0 until NUM_MFCC) {
                mfccSum[i] += frameMFCC[i]
            }
        }

        for (i in 0 until NUM_MFCC) {
            mfccSum[i] = mfccSum[i]/ numFrames
        }

        return normalizeVector(mfccSum)
    }

    private fun computeSimpleMFCC(frame: FloatArray): FloatArray {
        val numFilters = NUM_MFCC
        val result = FloatArray(numFilters)
        val frameSize = frame.size

        for (filterIdx in 0 until numFilters) {
            val lowFreq = 300.0 * (filterIdx.toDouble() / numFilters)
            val highFreq = 300.0 * ((filterIdx + 2).toDouble() / numFilters)
            val lowBin = (lowFreq * frameSize / SAMPLE_RATE).toInt().coerceIn(0, frameSize / 2)
            val highBin = (highFreq * frameSize / SAMPLE_RATE).toInt().coerceIn(0, frameSize / 2)

            var energy = 0.0f
            for (i in lowBin..highBin) {
                if (i < frameSize) energy += frame[i] * frame[i]
            }
            result[filterIdx] = ln((energy + 1e-8f).toDouble()).toFloat()
        }

        return result
    }

    private fun normalizeVector(v: FloatArray): FloatArray {
        val norm = sqrt(v.map { it * it }.sum())
        return if (norm > 0) FloatArray(v.size) { v[it] / norm } else v
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in a.indices.take(minOf(a.size, b.size))) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) dot / (sqrt(normA) * sqrt(normB)) else 0.0f
    }

    suspend fun enrollVoice(
        onProgress: (phraseIndex: Int, totalPhrases: Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val allEmbeddings = mutableListOf<FloatArray>()

            for (i in 0 until ENROLLMENT_PHRASES) {
                withContext(Dispatchers.Main) {
                    onProgress(i, ENROLLMENT_PHRASES)
                }
                delay(500)

                val audio = recordAudio(3)
                val embedding = extractMFCC(audio)
                allEmbeddings.add(embedding)

                Log.e("JARVIS_SPEAKER", "Enrolled phrase ${i + 1}/$ENROLLMENT_PHRASES")
            }

            val masterEmbedding = FloatArray(NUM_MFCC)
            for (emb in allEmbeddings) {
                for (i in emb.indices) masterEmbedding[i] += emb[i]
            }
            for (i in masterEmbedding.indices) masterEmbedding[i] = masterEmbedding[i]/ allEmbeddings.size

            enrolledEmbedding = normalizeVector(masterEmbedding)
            saveEnrollment(enrolledEmbedding!!)
            isEnrolled = true

            withContext(Dispatchers.Main) { onDone() }
        } catch (e: Exception) {
            Log.e("JARVIS_SPEAKER", "Enrollment error: ${e.message}")
            withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
        }
    }

    suspend fun verifyCurrentSpeaker(audioData: ShortArray? = null): Boolean = withContext(Dispatchers.IO) {
        if (!isEnrolled || enrolledEmbedding == null) return@withContext true

        try {
            val audio = audioData ?: recordAudio(2)
            val embedding = extractMFCC(audio)
            val similarity = cosineSimilarity(embedding, enrolledEmbedding!!)
            Log.e("JARVIS_SPEAKER", "Similarity: $similarity threshold: $VERIFICATION_THRESHOLD")
            similarity >= VERIFICATION_THRESHOLD
        } catch (e: Exception) {
            Log.e("JARVIS_SPEAKER", "Verify error: ${e.message}")
            true
        }
    }

    fun resetEnrollment() {
        enrolledEmbedding = null
        isEnrolled = false
        embeddingFile.delete()
        Log.e("JARVIS_SPEAKER", "Enrollment reset")
    }
}
