package com.example.myapplication

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

private const val TAG = "LivePipeline"

class WavAudioChunkRecorder(
    private val context: Context,
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    // This is the dedicated scope for this class. It's correct.
    private val recorderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bufferSize = AudioRecord.getMinBufferSize(
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2
    private val audioBuffer = ShortArray(bufferSize / 2)

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    @SuppressLint("MissingPermission")
    fun start(onChunkReady: (ShortArray) -> Unit) {
        if (recordingJob?.isActive == true) {
            Log.w(TAG, "WavAudioChunkRecorder: Recording is already active.")
            return
        }
        Log.d(TAG, "WavAudioChunkRecorder: START method called.")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "WavAudioChunkRecorder: AudioRecord could not be initialized.")
            return
        }

        audioRecord?.startRecording()

        // --- FIX #1: Use recorderScope, not coroutineScope ---
        recordingJob = recorderScope.launch {
            Log.d(TAG, "WavAudioChunkRecorder: Recording coroutine started.")
            while (isActive) {
                val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readSize > 0) {
                    val chunk = audioBuffer.copyOf(readSize)
                    onChunkReady(chunk)

                    val maxAmplitude = chunk.maxOfOrNull { abs(it.toFloat()) } ?: 0f
                    _amplitude.value = maxAmplitude / 32768.0f
                }
            }
        }
    }

    fun stop() {
        recordingJob?.cancel()
        if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord?.stop()
        }
        audioRecord?.release()
        audioRecord = null
        _amplitude.value = 0f
        Log.d(TAG, "WavAudioChunkRecorder has been explicitly stopped.")
    }
}
