// In your AudioViewModel.kt file

package com.example.myapplication

import android.Manifest
import android.content.Context
import java.io.File
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.media3.common.util.Log
import java.nio.FloatBuffer
import java.util.Collections

class AudioViewModel(private val context: Context) : ViewModel() {

    // --- State for the UI ---
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val audioAmplitudes: StateFlow<List<Float>> = _audioAmplitudes.asStateFlow()

    private val _smoothedEmotion = MutableStateFlow<MappedEmotion?>(null)
    val liveEmotion: StateFlow<MappedEmotion?> = _smoothedEmotion.asStateFlow()

    // --- NEW: Add the Smoothing Buffer Logic ---
    private val vadHistory = ArrayDeque<EmotionVector>()
    private val HISTORY_SIZE = 5 // You can tune this! Start with 5.

    // --- ONNX Model and Audio Recording Configuration ---
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private var audioRecord: AudioRecord? = null

    // Configuration must match the model's requirements
    private val SAMPLE_RATE = 16000
    private val CHUNK_SIZE_IN_SECONDS = 1 // Process audio in 1-second chunks
    private val BUFFER_SIZE = SAMPLE_RATE * CHUNK_SIZE_IN_SECONDS

    init {
        // Load the ONNX model when the ViewModel is created
        loadOnnxModel()
    }

    private fun loadOnnxModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelFile = File(context.cacheDir, "emotion_model_final.onnx")
                context.assets.open("emotion_model_final.onnx").use { inputStream ->
                    modelFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // 2. Load the model from the file path, NOT from a byte array.
                // This is much more memory efficient.
                ortSession = ortEnv.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
                Log.d("AudioViewModel", "ONNX Model loaded successfully.")
            } catch (e: Exception) {
                Log.e("AudioViewModel", "Error loading ONNX model", e)
            }
        }
    }

    // --- Core Functions ---

    fun startLiveProcessing() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioViewModel", "Audio recording permission not granted.")
            // You should handle this by requesting permission from the UI
            return
        }

        // Initialize AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(BUFFER_SIZE, minBufferSize)
        )

        _isRecording.value = true
        audioRecord?.startRecording()

        viewModelScope.launch(Dispatchers.IO) {
            val audioBuffer = ShortArray(BUFFER_SIZE)
            while (isActive && _isRecording.value) {
                val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readSize > 0) {
                    // Convert ShortArray to FloatArray and normalize for the model
                    val floatAudioData = audioBuffer.map { it / 32768.0f }.toFloatArray()
                    onNewAudioChunk(floatAudioData)

                    // Update waveform for UI (optional, can be performance intensive)
                    val maxAmplitude = floatAudioData.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
                    val currentAmplitudes = _audioAmplitudes.value.toMutableList()
                    currentAmplitudes.add(maxAmplitude)
                    if (currentAmplitudes.size > 100) { // Keep only the last 100 amplitude values
                        currentAmplitudes.removeAt(0)
                    }
                    _audioAmplitudes.value = currentAmplitudes
                }
            }
        }
    }

    fun stopLiveProcessing() {
        if (_isRecording.value) {
            _isRecording.value = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            vadHistory.clear() // Clear history for the next session
            _audioAmplitudes.value = emptyList() // Clear waveform
            Log.d("AudioViewModel", "Live processing stopped.")
        }
    }

    // This function is now the core processing function for each live chunk
    private fun onNewAudioChunk(audioData: FloatArray) {
        val rawVadVector = runOnnxInference(audioData)

        if (rawVadVector != null) {
            if (vadHistory.size >= HISTORY_SIZE) {
                vadHistory.removeFirst()
            }
            vadHistory.addLast(rawVadVector)

            val smoothedVector = getSmoothedVector()
            _smoothedEmotion.value = mapVectorToEmotion(smoothedVector)
        }
    }

    private fun getSmoothedVector(): EmotionVector {
        if (vadHistory.isEmpty()) {
            return EmotionVector(arousal = 0.5f, valence = 0.5f, dominance = 0.5f)
        }
        val avgArousal = vadHistory.map { it.arousal }.average().toFloat()
        val avgValence = vadHistory.map { it.valence }.average().toFloat()
        val avgDominance = vadHistory.map { it.dominance }.average().toFloat()

        return EmotionVector(
            arousal = avgArousal,
            valence = avgValence,
            dominance = avgDominance
        )
    }

    // --- FILLED IN: This now runs the actual ONNX model ---
    private fun runOnnxInference(audioData: FloatArray): EmotionVector? {
        val session = ortSession ?: return null // Don't run if the model isn't loaded

        return try {
            val inputName = session.inputNames.first()
            // The model expects a shape of [batch_size, num_samples], so [1, 16000]
            val tensorShape = longArrayOf(1, audioData.size.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(audioData), tensorShape)

            val result = session.run(Collections.singletonMap(inputName, inputTensor))

            // The output is an array of Tensors, get the first one.
            val outputTensor = result[0] as OnnxTensor
            val vadResult = outputTensor.floatBuffer.array()
            result.close() // IMPORTANT: Close the result to free memory

            // Model output order: [Valence, Dominance, Arousal] based on Audeering standard.
            // Your mapper expects (arousal, valence, dominance). Remap it here.
            EmotionVector(arousal = vadResult[2], valence = vadResult[0], dominance = vadResult[1])
        } catch (e: Exception) {
            Log.e("AudioViewModel", "Error during ONNX inference", e)
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        ortSession?.close()
        ortEnv.close()
    }
}
