// In your AudioViewModel.kt file

package com.example.myapplication

import android.Manifest
import android.content.Context
import java.text.SimpleDateFormat
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.Locale
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
import kotlin.concurrent.write
import kotlin.text.format

class AudioViewModel(private val context: Context) : ViewModel() {

    // --- State for the UI ---
    private val _isRecording = MutableStateFlow(false)

    private var sessionAudioFile: File? = null
    private var fileOutputStream: FileOutputStream? = null
    private var totalAudioDataSize: Long = 0
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
    // Inside your AudioViewModel class
    val amplitudeFlow: StateFlow<List<Float>> get() = audioAmplitudes // Expose the flow

    // Assuming latestEmotion is a MutableStateFlow<MappedEmotion?>
    val latestEmotion: StateFlow<MappedEmotion?> get() = liveEmotion // Expose this too


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
            } catch (e: Exception) {
            }
        }
    }
    // ADD THIS FUNCTION
    fun startRecording() {
        startLiveProcessing()
    }

    // ADD THIS FUNCTION
    fun stopRecording() {
        stopLiveProcessing()
    }

    // ADD THIS FUNCTION
    val emotionHistory: StateFlow<List<MappedEmotion>>
        get() {
            // This creates a new flow representing the current emotion history in the buffer
            return MutableStateFlow(vadHistory.map { mapVectorToEmotion(it) }).asStateFlow()
        }
    // --- Core Functions ---

    fun startLiveProcessing() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // You should handle this by requesting permission from the UI
            return
        }
        totalAudioDataSize = 0 // Reset size counter
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionAudioFile = File(context.cacheDir, "SESSION_${timeStamp}.wav")

        try {
            fileOutputStream = FileOutputStream(sessionAudioFile)
            // Write a placeholder WAV header. We will update it when we're done.
            fileOutputStream?.write(createWavHeader(0, 0))
        } catch (e: IOException) {
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
                    // --- WRITE THE DATA TO THE FILE ---
                    // Convert ShortArray to ByteArray for file writing
                    val byteBuffer = ByteBuffer.allocate(readSize * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until readSize) {
                        byteBuffer.putShort(audioBuffer[i])
                    }
                    try {
                        fileOutputStream?.write(byteBuffer.array())
                        totalAudioDataSize += byteBuffer.array().size
                    } catch (e: IOException) {
                        // Log.e("AudioViewModel", "Failed to write to session file", e)
                    }
                    // ---------------------------------

                    // Convert ShortArray to FloatArray and normalize for the model
                    val floatAudioData = audioBuffer.map { it / 32768.0f }.toFloatArray()
                    onNewAudioChunk(floatAudioData)

                    // Update waveform for UI (optional, can be performance intensive)
                    val maxAmplitude = audioBuffer.maxOfOrNull { kotlin.math.abs(it.toFloat() / 32767.0f) } ?: 0f
                    val currentAmplitudes = _audioAmplitudes.value.toMutableList()
                    currentAmplitudes.add((maxAmplitude * 3.0f).coerceIn(0f, 1f))
                    if (currentAmplitudes.size > 100) {
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
            try {
                fileOutputStream?.close()
                updateWavHeader() // Go back and write the correct file sizes
            } catch (e: IOException) {
                // Log.e("AudioViewModel", "Failed to close and update session file", e)
            }
            fileOutputStream = null
        }
    }

    private fun updateWavHeader() {
        sessionAudioFile?.let { file ->
            try {
                val updatedHeader = createWavHeader(totalAudioDataSize + 36, totalAudioDataSize)
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(0)
                    raf.write(updatedHeader)
                }
              //  Log.d("AudioViewModel", "WAV header updated successfully.")
            } catch (e: IOException) {
            //    Log.e("AudioViewModel", "Error updating WAV header", e)
            }
        }
    }

    // Helper function to create a WAV header
    private fun createWavHeader(chunkSize: Long, subchunk2Size: Long): ByteArray {
        val header = ByteArray(44)
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (chunkSize and 0xff).toByte(); header[5] = (chunkSize shr 8 and 0xff).toByte(); header[6] = (chunkSize shr 16 and 0xff).toByte(); header[7] = (chunkSize shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // Subchunk1Size
        header[20] = 1; header[21] = 0 // AudioFormat (PCM)
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte(); header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0 // BlockAlign
        header[34] = bitsPerSample.toByte(); header[35] = 0 // BitsPerSample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (subchunk2Size and 0xff).toByte(); header[41] = (subchunk2Size shr 8 and 0xff).toByte(); header[42] = (subchunk2Size shr 16 and 0xff).toByte(); header[43] = (subchunk2Size shr 24 and 0xff).toByte()
        return header
    }
    fun getSessionFile(): File? {
        return sessionAudioFile
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
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        ortSession?.close()
        ortEnv.close()
    }
}
