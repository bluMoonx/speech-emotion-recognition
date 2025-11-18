package com.example.myapplication

// In app/src/main/java/com/example/myapplication/WavAudioChunkRecorder.kt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

private const val TAG = "CHUNK_RECORDER"

class WavAudioChunkRecorder(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // This buffer should be large enough for a chunk, e.g., 1 second of audio at 16kHz
    private val bufferSize = AudioRecord.getMinBufferSize(
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2
    private val audioBuffer = ShortArray(bufferSize / 2) // bufferSize is in bytes, we use shorts

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    @SuppressLint("MissingPermission")
    fun start(onChunkReady: (ShortArray) -> Unit) {
        if (recordingJob?.isActive == true) {
            Log.w(TAG, "Recording is already active.")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000, // Record directly at target sample rate
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not be initialized.")
            return
        }

        audioRecord?.startRecording()

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readSize > 0) {
                    // Create a copy of the chunk that was read
                    val chunk = audioBuffer.copyOf(readSize)
                    onChunkReady(chunk)

                    // Calculate and update amplitude for the UI
                    val maxAmplitude = chunk.maxOfOrNull { abs(it.toFloat()) } ?: 0f
                    _amplitude.value = maxAmplitude / 32768.0f

                }
            }
        }
    }

    fun stop() {
        if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord?.stop()
        }
        audioRecord?.release()
        audioRecord = null
        recordingJob?.cancel()
        recordingJob = null
        _amplitude.value = 0f
        Log.d(TAG, "Chunk recorder stopped.")
    }
}