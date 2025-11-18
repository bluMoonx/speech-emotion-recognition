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
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

private const val RECORDER_TAG = "WavClipRecorder"

class WavClipRecorder(private val context: Context, private val coroutineScope: CoroutineScope) {
    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null
    private var audioFile: File? = null

    // This part is correct
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    @SuppressLint("MissingPermission")
    fun start(outputFile: File) {
        if (recordingJob?.isActive == true) {
            Log.w(RECORDER_TAG, "Recording is already active.")
            return
        }
        audioFile = outputFile
        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        recorder?.startRecording()

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            val fileOutputStream = FileOutputStream(outputFile)
            // --- START OF FIX ---
            // 1. Create a buffer for Shorts (for amplitude) AND a buffer for Bytes (for file)
            val audioShortsBuffer = ShortArray(bufferSize / 2)
            val audioByteBuffer = ByteArray(bufferSize)
            // --- END OF FIX ---

            fileOutputStream.write(ByteArray(44)) // Placeholder for WAV header

            while (isActive) {
                // 2. Read audio data into the Short array
                val readSize = recorder?.read(audioShortsBuffer, 0, audioShortsBuffer.size) ?: 0

                if (readSize > 0) {
                    // 3. Calculate amplitude from the Short array (this now works)
                    val maxAmplitude = audioShortsBuffer.take(readSize).maxOfOrNull { kotlin.math.abs(it.toFloat()) } ?: 0f
                    _amplitude.value = maxAmplitude / 32768.0f

                    // 4. Convert the Shorts to Bytes to write to the file
                    for (i in 0 until readSize) {
                        audioByteBuffer[i * 2] = (audioShortsBuffer[i].toInt() and 0xFF).toByte()
                        audioByteBuffer[i * 2 + 1] = (audioShortsBuffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    fileOutputStream.write(audioByteBuffer, 0, readSize * 2)
                }
            }
            fileOutputStream.close()
        }
    }

    fun stop() {
        if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            recorder?.stop()
        }
        recorder?.release()
        recorder = null
        recordingJob?.cancel()
        _amplitude.value = 0f // Also reset amplitude on stop

        audioFile?.let {
            coroutineScope.launch(Dispatchers.IO) {
                writeWavHeader(it)
            }
        }
    }

    private fun writeWavHeader(file: File) {
        // This function was already correct and remains unchanged
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val sampleRate = 16000L
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8
        val header = ByteArray(44)

        // RIFF/WAVE header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // 'fmt ' chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // Sub-chunk size
        header[20] = 1; header[21] = 0 // Audio format (PCM)
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte(); header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0 // block align
        header[34] = 16; header[35] = 0 // bits per sample
        // 'data' chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = (totalAudioLen shr 8 and 0xff).toByte(); header[42] = (totalAudioLen shr 16 and 0xff).toByte(); header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header) }
    }
}
