// In WavAudioChunkRecorder.kt

package com.example.myapplication

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class WavAudioChunkRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    // FIX: Added a callback to send audio data out
    private val onChunkReady: (ShortArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    // Process audio in 1-second chunks
    private val bufferSize = sampleRate * 1

    fun start() {
        if (isRecording) return
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize.coerceAtLeast(bufferSize)
            ).also {
                it.startRecording()
                isRecording = true
            }
        } catch (e: SecurityException) {
            Log.e("WavAudioChunkRecorder", "AudioRecord permission denied.", e)
            return
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            val audioBuffer = ShortArray(bufferSize)
            while (isRecording) {
                val readResult = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (readResult > 0) {
                    // Send the chunk to the ViewModel
                    onChunkReady(audioBuffer.clone())

                    // Calculate and update amplitude for the UI
                    val maxAmplitude = audioBuffer.maxOfOrNull { abs(it.toInt()) } ?: 0
                    _amplitude.value = maxAmplitude.toFloat() / Short.MAX_VALUE
                }
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        _amplitude.value = 0f
    }
}
