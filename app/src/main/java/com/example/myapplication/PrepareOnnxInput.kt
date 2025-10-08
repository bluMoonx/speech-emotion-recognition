package com.example.myapplication

import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.isNullOrEmpty
import kotlin.math.sqrt

private const val TAG_PREPARE = "PrepareOnnxInput"

// --- 1. WAV HEADER PARSER ---
fun parseWavHeader(inputStream: InputStream): Triple<Int, Int, Int>? {
    try {
        val headerBytes = ByteArray(12)
        inputStream.read(headerBytes, 0, 12)

        if (String(headerBytes, 0, 4) != "RIFF" || String(headerBytes, 8, 4) != "WAVE") {
            Log.e(TAG_PREPARE, "Invalid WAV file: missing RIFF/WAVE markers.")
            return null
        }

        var sampleRate = 0
        var numChannels = 0
        var bitsPerSample = 0
        var foundFmt = false

        while (true) {
            val chunkHeader = ByteArray(8)
            val read = inputStream.read(chunkHeader, 0, 8)
            if (read < 8) {
                Log.e(TAG_PREPARE, "Reached end of stream before finding 'data' chunk.")
                return null
            }

            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            Log.d(TAG_PREPARE, "Found chunk: '$chunkId' with size: $chunkSize")

            when (chunkId) {
                "fmt " -> {
                    foundFmt = true
                    val fmtChunk = ByteArray(chunkSize)
                    inputStream.read(fmtChunk, 0, chunkSize)
                    val fmtBuffer = ByteBuffer.wrap(fmtChunk).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = fmtBuffer.short
                    if (audioFormat.toInt() != 1) { // 1 = PCM
                        Log.e(TAG_PREPARE, "Not PCM format. Code: $audioFormat")
                        return null
                    }
                    numChannels = fmtBuffer.short.toInt()
                    sampleRate = fmtBuffer.int
                    fmtBuffer.int // Skip byteRate
                    fmtBuffer.short // Skip blockAlign
                    bitsPerSample = fmtBuffer.short.toInt()
                }
                "data" -> {
                    if (!foundFmt) {
                        Log.e(TAG_PREPARE, "'data' chunk found before 'fmt ' chunk.")
                        return null
                    }
                    Log.d(TAG_PREPARE, "Header parsing complete. SR=$sampleRate, Channels=$numChannels, Bits=$bitsPerSample")
                    // After finding 'data', we have what we need and can stop parsing the header.
                    return Triple(sampleRate, numChannels, bitsPerSample)
                }
                else -> {
                    // Skip chunks we don't care about
                    val skipped = inputStream.skip(chunkSize.toLong())
                    if (skipped != chunkSize.toLong()) {
                        Log.e(TAG_PREPARE, "Failed to skip chunk '$chunkId' completely.")
                        return null
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG_PREPARE, "Exception during WAV header parsing: ${e.message}", e)
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


// --- 3. AUDIO PREPROCESSING FUNCTION ---
fun preprocessAudioForModel(
    rawAudioData: ShortArray?,
    originalSampleRate: Int,
    targetSampleRate: Int,
    numChannels: Int
): FloatArray? {
    // If rawAudioData is null or empty, this block runs and the function returns early.
// If rawAudioData is null or empty, this block runs and the function returns early.
// Manual check to bypass the problematic isNullOrEmpty() extension function.
    if (rawAudioData == null || rawAudioData.isEmpty()) {
        Log.e(TAG_PREPARE, "Preprocessing failed: raw audio data is null or empty.")
        return null
    }


    // --- THE DEFINITIVE FIX ---
    // Create a new, guaranteed non-null variable. The compiler cannot get confused about this one.
    val audioData: ShortArray = rawAudioData!!

    // --- STEP A: Convert to Mono (if needed) ---
    // Now, use the new 'audioData' variable, which the compiler knows is 100% safe.
    val monoShorts: ShortArray = if (numChannels > 1) {
        ShortArray(audioData.size / numChannels) { i ->
            var sum = 0
            for (j in 0 until numChannels) {
                // All errors are gone because 'audioData' is not nullable.
                sum += audioData[i * numChannels + j]
            }
            (sum / numChannels).toShort()
        }
    } else {
        // Here, we can just assign the guaranteed non-null variable.
        audioData
    }

    // --- STEP B: Convert to Float32 in range [-1, 1] ---
    val normalizedSignal = FloatArray(monoShorts.size) { i ->
        monoShorts[i] / 32768.0f
    }

    // --- STEP C: Replicate the Wav2Vec2Processor's internal normalization ---
    return normalizeForWav2Vec2(normalizedSignal)
}


// --- 4. HELPER FOR WAV2VEC2 NORMALIZATION ---
private fun normalizeForWav2Vec2(audio: FloatArray, epsilon: Float = 1e-5f): FloatArray {
    if (audio.isEmpty()) return audio

    val mean = audio.average().toFloat()
    val variance = audio.map { (it - mean) * (it - mean) }.average().toFloat()

    val normalizedAudio = FloatArray(audio.size) { i ->
        (audio[i] - mean) / (sqrt(variance) + epsilon)
    }
    Log.d(TAG_PREPARE, "Wav2Vec2 Normalization complete. Mean: $mean, Variance: $variance")
    return normalizedAudio
}
