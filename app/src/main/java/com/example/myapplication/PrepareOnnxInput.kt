package com.example.myapplication

import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val TAG_PREPARE = "PrepareOnnxInput"
// In PrepareOnnxInput.kt
fun parseWavHeader(inputStream: InputStream): WavHeaderInfo? {
    val bufferedStream = inputStream.buffered()
    try {
        // --- THIS IS THE FIX ---
        // The variable declaration was stuck on the comment line. It has been moved down.
        val riffHeader = ByteArray(12)
        if (bufferedStream.read(riffHeader) < 12) return null
        if (String(riffHeader, 0, 4) != "RIFF" || String(riffHeader, 8, 4) != "WAVE") return null

        var sampleRate: Int? = null
        var numChannels: Int? = null
        var bitsPerSample: Int? = null
        var audioFormat: Int? = null // Variable to hold the format
        var foundFmtChunk = false

        while (bufferedStream.available() > 0) {
            val chunkHeader = ByteArray(8)
            if (bufferedStream.read(chunkHeader) < 8) break
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val finalChunkSize = if (chunkSize % 2 != 0) chunkSize + 1 else chunkSize

            when (chunkId) {
                "fmt " -> {
                    val fmtChunkBytes = ByteArray(chunkSize)
                    if (bufferedStream.read(fmtChunkBytes) < chunkSize) return null
                    val fmtBuffer = ByteBuffer.wrap(fmtChunkBytes).order(ByteOrder.LITTLE_ENDIAN)

                    audioFormat = fmtBuffer.short.toInt() // <-- Store the audio format
                    numChannels = fmtBuffer.short.toInt()
                    sampleRate = fmtBuffer.int
                    fmtBuffer.int // Skip byteRate
                    fmtBuffer.short // Skip blockAlign
                    bitsPerSample = fmtBuffer.short.toInt()
                    foundFmtChunk = true
                }
                "data" -> {
                    if (foundFmtChunk) {
                        // Now we can safely call the constructor with all four arguments.
                        return WavHeaderInfo(sampleRate!!, numChannels!!, bitsPerSample!!, audioFormat!!)
                    } else {
                        return null
                    }
                }
                else -> {
                    if (bufferedStream.skip(finalChunkSize.toLong()) < finalChunkSize) break
                }
            }
        }
        return null
    } catch (e: Exception) {
        return null
    }
}
// --- 2. BYTE-TO-SHORT CONVERTERS (ALL IN ONE PLACE) ---

fun pcm16BitByteArrayToShortArray(byteArray: ByteArray): ShortArray {
    if (byteArray.size % 2 != 0) {
        Log.e(TAG_PREPARE, "Input byte array size is not even for 16-bit PCM.")
        return ShortArray(0)
    }
    val shortArray = ShortArray(byteArray.size / 2)
    ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)
    return shortArray
}

fun pcm32BitFloatByteArrayToShortArray(bytes: ByteArray): ShortArray {
    if (bytes.size % 4 != 0) return ShortArray(0)
    val numSamples = bytes.size / 4
    val shorts = ShortArray(numSamples)
    val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until numSamples) {
        val floatValue = byteBuffer.getFloat(i * 4)
        val clampedValue = floatValue.coerceIn(-1.0f, 1.0f)
        shorts[i] = (clampedValue * 32767.0f).toInt().toShort()
    }
    return shorts
}

fun pcm32BitIntByteArrayToShortArray(bytes: ByteArray): ShortArray {
    if (bytes.size % 4 != 0) return ShortArray(0)
    val numSamples = bytes.size / 4
    val shorts = ShortArray(numSamples)
    val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until numSamples) {
        val intValue = byteBuffer.getInt(i * 4)
        shorts[i] = (intValue shr 16).toShort()
    }
    return shorts
}
// In PrepareOnnxInput.kt, find and REPLACE the existing preprocessAudioForModel function

// --- 3. AUDIO PREPROCESSING FUNCTION ---
fun preprocessAudioForModel(audioData: ShortArray?): FloatArray? {
    if (audioData == null || audioData.isEmpty()) {
        Log.e("PrepareOnnxInput", "Preprocessing failed: audio data is null or empty.")
        return null
    }

    // --- THIS IS THE CORRECT LOGIC FROM YESTERDAY ---
    // It normalizes audio from Short range to Float range [-1.0, 1.0].
    // This correctly preserves the audio's energy (arousal) for the model.
    val processedData = FloatArray(audioData.size) { i ->
        audioData[i] / 32768.0f
    }
    Log.d("PrepareOnnxInput", "SUCCESS: Preprocessed audio by normalizing to Float range [-1, 1].")
    return processedData
}
