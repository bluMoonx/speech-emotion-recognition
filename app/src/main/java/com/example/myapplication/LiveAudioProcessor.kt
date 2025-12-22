package com.example.myapplication

// In app/src/main/java/com/example/myapplication/LiveAudioProcessor.kt

import android.annotation.SuppressLint
import android.content.Context
import java.text.SimpleDateFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt
import kotlin.text.format
import java.util.Date

data class AmplitudePoint(val value: Float, val color: androidx.compose.ui.graphics.Color)

// THIS IS THE ORIGINAL, SIMPLER PROCESSOR FROM YOUR GITHUB VERSION
class LiveAudioProcessor(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val emotionPredictor: EmotionPredictor,
    private val sampleRate: Int = 16000
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var tempAudioFile: File? = null

    // Change this line
    private val _amplitudeFlow = MutableStateFlow(listOf<AmplitudePoint>())
    val amplitudeFlow: StateFlow<List<AmplitudePoint>> = _amplitudeFlow


    private val _latestEmotion = MutableStateFlow<MappedEmotion?>(null)
    val latestEmotion: StateFlow<MappedEmotion?> = _latestEmotion

    private val _sessionEmotions = MutableStateFlow<List<MappedEmotion>>(emptyList())
    val sessionEmotions: StateFlow<List<MappedEmotion>> = _sessionEmotions

    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @Volatile
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        isRecording = true
        _sessionEmotions.value = emptyList() // Clear previous session

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )
        // Corrected Timestamp and Filename logic
        val formatter = SimpleDateFormat("MMMdd_HHmm_ss", java.util.Locale.getDefault())
        val humanTimestamp = formatter.format(java.util.Date())
        tempAudioFile = File(context.cacheDir, "Live_$humanTimestamp.wav")



        audioRecord?.startRecording()
        Log.d("ULTRA_DEBUG", "Live recording started.")

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            val audioData = ShortArray(minBufferSize)

            // Use our new AmplitudePoint class
            val amplitudes = mutableListOf<AmplitudePoint>()
            val fullRecording = mutableListOf<Short>()
            val inferenceAccumulator = mutableListOf<Short>()
            val samplesNeeded = (sampleRate * 1.5).toInt()

            while (isRecording && isActive) {
                val readSize = audioRecord?.read(audioData, 0, audioData.size) ?: 0
                if (readSize > 0) {
                    val currentBatch = audioData.take(readSize)
                    fullRecording.addAll(currentBatch)

                    // 1. WAVEFORM LOGIC
                    val rms = sqrt(currentBatch.map { it.toDouble() * it.toDouble() }.average())
                    val normalizedRms = (rms / 32767.0f).toFloat().coerceIn(0f, 1f)
                    val visualAmplitude = (normalizedRms * 15.0f).coerceIn(0f, 1f)

                    // GET THE CURRENT COLOR: Use the color of the last detected emotion
                    val currentColor = _latestEmotion.value?.color ?: androidx.compose.ui.graphics.Color.White

                    // Add the point with its current color
                    amplitudes.add(AmplitudePoint(visualAmplitude, currentColor))
                    if (amplitudes.size > 150) amplitudes.removeAt(0)
                    _amplitudeFlow.value = amplitudes.toList()

                    // 2. ACCUMULATION & 3. INFERENCE (Keep your existing logic here...)
                    if (normalizedRms > 0.02f) {
                        inferenceAccumulator.addAll(currentBatch)
                    }

                    if (inferenceAccumulator.size >= samplesNeeded) {
                        val floatData = inferenceAccumulator.map { it / 32768.0f }.toFloatArray()
                        inferenceAccumulator.clear()

                        val prediction = emotionPredictor.predict(floatData)
                        if (prediction != null && prediction.size >= 3) {
                            val vector = EmotionVector(arousal = prediction[0], dominance = prediction[1], valence = prediction[2])
                            val mapped = mapVectorToEmotion(vector)

                            // This update will change the color of ALL NEW bars moving forward
                            _latestEmotion.value = mapped
                            _sessionEmotions.value = _sessionEmotions.value + mapped
                        }
                    }
                }
            }



        // Write the full session to a file after stopping
            try {
                WavWriter.writeWavFile(tempAudioFile!!, fullRecording.toShortArray(), sampleRate)
                Log.d("ULTRA_DEBUG", "Live session WAV file saved to ${tempAudioFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e("ULTRA_DEBUG", "Failed to save live session WAV", e)
            }
        }
    }


    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d("ULTRA_DEBUG", "Live recording stopped.")
    }

    fun getSessionFile(): File? {
        return tempAudioFile
    }
}