// In app/src/main/java/com/example/myapplication/WavAudioRecorder.kt

package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.sqrt
import androidx.compose.runtime.mutableStateOf


private const val TAG_REC = "WavAudioRecorder"

class WavAudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioFile: File? = null
    val amplitude = mutableStateOf(0f)


    // --- Configuration for our perfect WAV file ---
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    fun start(): File? {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG_REC, "Invalid audio parameters for AudioRecord.")
            return null
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        val outputFile = File(context.cacheDir, "recording.wav")
        audioFile = outputFile
        Log.d(TAG_REC, "Recording to file: ${outputFile.absolutePath}")

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            writeAudioDataToFile(outputFile, bufferSize)
        }
        recordingThread?.start()

        return outputFile
    }

    private fun writeAudioDataToFile(file: File, bufferSize: Int) {
        val data = ByteArray(bufferSize)
        val fileOutputStream = FileOutputStream(file)

        // Write a placeholder WAV header first
        fileOutputStream.write(ByteArray(44))

        while (isRecording) {
            val read = audioRecord?.read(data, 0, bufferSize) ?: 0
            if (read > 0) {
                try {
                    fileOutputStream.write(data, 0, read)
                    // Calculate the amplitude and update the state.
                    // This calculates the Root Mean Square (RMS) of the buffer.
                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val sample = (data[i+1].toInt() shl 8) or (data[i].toInt() and 0xFF)
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / (read / 2))
                    // Normalize the amplitude to a 0f-1f range for the waveform UI
                    amplitude.value = (rms / 32767.0).toFloat()
                } catch (e: Exception) {
                    Log.e(TAG_REC, "Error writing to file", e)
                }
            }
        }

        try {
            fileOutputStream.close()
            // After recording stops, write the real header
            writeWavHeader(file)
        } catch (e: Exception) {
            Log.e(TAG_REC, "Error closing stream or writing header", e)
        }
    }

    fun stop(): Uri? {
        if (isRecording) {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.join() // Wait for the thread to finish
            Log.d(TAG_REC, "Recording stopped.")
        }
        return audioFile?.let { Uri.fromFile(it) }
    }

    // This is the magic function that makes it a valid WAV file
    private fun writeWavHeader(file: File) {
        val fileLength = file.length() - 44 // Total data size
        val totalDataLen = fileLength + 36
        val channels = 1 // Mono
        val byteRate = (sampleRate * 16 * channels / 8).toLong()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0) // Go to the beginning of the file
            raf.write(
                byteArrayOf(
                    'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), // RIFF
                    (totalDataLen and 0xff).toByte(),
                    (totalDataLen shr 8 and 0xff).toByte(),
                    (totalDataLen shr 16 and 0xff).toByte(),
                    (totalDataLen shr 24 and 0xff).toByte(),
                    'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(), // WAVE
                    'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), // 'fmt ' chunk
                    16, 0, 0, 0, // 16 for PCM
                    1, 0, // PCM
                    channels.toByte(), 0, // Mono
                    (sampleRate and 0xff).toByte(),
                    (sampleRate shr 8 and 0xff).toByte(),
                    (sampleRate shr 16 and 0xff).toByte(),
                    (sampleRate shr 24 and 0xff).toByte(),
                    (byteRate and 0xff).toByte(),
                    (byteRate shr 8 and 0xff).toByte(),
                    (byteRate shr 16 and 0xff).toByte(),
                    (byteRate shr 24 and 0xff).toByte(),
                    (2 * 16 / 8).toByte(), 0, // Block align
                    16, 0, // Bits per sample (16)
                    'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), // data chunk
                    (fileLength and 0xff).toByte(),
                    (fileLength shr 8 and 0xff).toByte(),
                    (fileLength shr 16 and 0xff).toByte(),
                    (fileLength shr 24 and 0xff).toByte()
                )
            )
        }
        Log.d(TAG_REC, "WAV header written successfully. File size: ${file.length()}")
    }
}
