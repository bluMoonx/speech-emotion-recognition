package com.example.myapplication

import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.isNullOrEmpty
import kotlin.math.sqrt

private const val TAG_PREPARE = "PrepareOnnxInput"

// --- 1. WAV HEADER PARSER ---
// In PrepareOnnxInput.kt

// --- 1. WAV HEADER PARSER ---
// REPLACE your old function with this new one.
// In PrepareOnnxInput.kt

// In PrepareOnnxInput.kt

fun parseWavHeader(inputStream: InputStream): Triple<Int, Int, Int>? {
    // This is a special input stream that allows us to peek ahead without consuming bytes.
    // This is critical for reading chunk sizes correctly without losing our place.
    val bufferedStream = inputStream.buffered()

    try {
        val riffHeader = ByteArray(12)
        if (bufferedStream.read(riffHeader) < 12) {
            Log.e(TAG_PREPARE, "FATAL: File is too small to be a WAV file (< 12 bytes).")
            return null
        }

        if (String(riffHeader, 0, 4) != "RIFF" || String(riffHeader, 8, 4) != "WAVE") {
            Log.e(TAG_PREPARE, "FATAL: Missing 'RIFF' or 'WAVE' markers. This is not a WAV file.")
            return null
        }
        Log.i(TAG_PREPARE, "SUCCESS: 'RIFF' and 'WAVE' markers found. Proceeding to parse chunks.")

        var sampleRate: Int? = null
        var numChannels: Int? = null
        var bitsPerSample: Int? = null
        var foundFmtChunk = false

        while (bufferedStream.available() > 0) {
            val chunkHeader = ByteArray(8)
            val bytesRead = bufferedStream.read(chunkHeader)

            if (bytesRead < 8) {
                Log.w(TAG_PREPARE, "PARSING END: Could not read next full chunk header. End of stream or corrupt file.")
                break
            }

            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            Log.d(TAG_PREPARE, "--> Found chunk: '$chunkId' with reported size: $chunkSize")

            // CRITICAL CHECK for odd-sized chunks. Some software writes an extra padding byte.
            val finalChunkSize = if (chunkSize % 2 != 0) chunkSize + 1 else chunkSize

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) {
                        Log.e(TAG_PREPARE, "FATAL: 'fmt ' chunk is malformed (size < 16).")
                        return null
                    }
                    val fmtChunkBytes = ByteArray(chunkSize)
                    if (bufferedStream.read(fmtChunkBytes) < chunkSize) {
                        Log.e(TAG_PREPARE, "FATAL: Could not read the full 'fmt ' chunk body.")
                        return null
                    }

                    val fmtBuffer = ByteBuffer.wrap(fmtChunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = fmtBuffer.short.toInt()
                    if (audioFormat != 1 && audioFormat != 3) { // 1 = PCM Integer, 3 = PCM Float
                        Log.e(TAG_PREPARE, "FATAL: Unsupported audio format. Expected PCM (1) or PCM Float (3), but got $audioFormat.")
                        return null
                    }


                    numChannels = fmtBuffer.short.toInt()
                    sampleRate = fmtBuffer.int
                    fmtBuffer.int // Skip byteRate
                    fmtBuffer.short // Skip blockAlign
                    bitsPerSample = fmtBuffer.short.toInt()
                    foundFmtChunk = true
                    Log.i(TAG_PREPARE, "SUCCESS: Parsed 'fmt ' chunk. SR=$sampleRate, Channels=$numChannels, Bits=$bitsPerSample")
                }
                "data" -> {
                    if (foundFmtChunk) {
                        Log.i(TAG_PREPARE, "SUCCESS: Found 'data' chunk after 'fmt'. Header parsing is complete.")
                        return Triple(sampleRate!!, numChannels!!, bitsPerSample!!)
                    } else {
                        Log.e(TAG_PREPARE, "FATAL: Found 'data' chunk before 'fmt' chunk. This parser cannot handle that file structure.")
                        return null
                    }
                }
                else -> {
                    Log.d(TAG_PREPARE, "INFO: Skipping non-essential chunk '$chunkId' of size $finalChunkSize bytes.")
                    val skipped = bufferedStream.skip(finalChunkSize.toLong())
                    if (skipped < finalChunkSize.toLong()) {
                        Log.w(TAG_PREPARE, "PARSING END: Could not skip full chunk. Reached end of stream.")
                        break
                    }
                }
            }
        }

        Log.e(TAG_PREPARE, "FATAL: Looped through the entire file but did not find a 'data' chunk after the 'fmt ' chunk. 'fmt' found: $foundFmtChunk")
        return null

    } catch (e: Exception) {
        Log.e(TAG_PREPARE, "FATAL EXCEPTION during WAV header parsing: ${e.message}", e)
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
