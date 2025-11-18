// In app/src/main/java/com/example/myapplication/AudioConverter.kt

package com.example.myapplication

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt


internal val Triple<Int, Int, Int>.numChannels: Int
    get() = this.second
internal val Triple<Int, Int, Int>.bitsPerSample: Int
    get() = this.third
internal val Triple<Int, Int, Int>.sampleRate: Int
    get() = this.first
const val TARGET_SAMPLE_RATE = 16000

/**
 * A universal audio processing utility to convert any standard PCM WAV format
 * into the target format required by the model: 16kHz, 16-bit, Mono PCM.
 */
object AudioConverter {

    /**
     * The main entry point for conversion. It takes the raw audio bytes and header info,
     * and returns a ShortArray in the target format.
     *
     * @param audioBytes The raw audio data from the WAV file.
     * @param header The parsed WAV header containing the original format info.
     * @return A ShortArray of 16-bit mono audio samples at 16kHz, or null on failure.
     */
    fun convertToTargetFormat(audioBytes: ByteArray, header: Triple<Int, Int, Int>): ShortArray? {
        // 1. Convert various bit depths and channel counts to a normalized FloatArray (mono, -1.0 to 1.0)
        val monoFloatSamples = normalizeToMonoFloat(audioBytes, header) ?: return null

        // 2. Resample the audio if the sample rate doesn't match the target
        val resampledSamples = if (header.sampleRate != TARGET_SAMPLE_RATE) {
            resample(monoFloatSamples, header.sampleRate as Int, TARGET_SAMPLE_RATE)
        } else {
            monoFloatSamples
        }

        // 3. Convert the final FloatArray to the target 16-bit ShortArray format
        return floatArrayTo16BitShortArray(resampledSamples)
    }

    /**
     * Step 1: Normalizes raw byte data from any supported bit depth into a mono FloatArray.
     */
    private fun normalizeToMonoFloat(bytes: ByteArray, header: Triple<Int, Int, Int>): FloatArray? {
        val samples = bytes.size / (header.bitsPerSample as Int / 8)
        val channels = header.numChannels
        val outputSamples = samples / channels
        val output = FloatArray(outputSamples)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outputSamples) {
            var left: Float
            var right: Float

            when (header.bitsPerSample) {
                8 -> { // 8-bit unsigned PCM (0 to 255)
                    left = (buffer.get().toInt() and 0xFF - 128) / 128.0f
                    if (channels == 2) {
                        right = (buffer.get().toInt() and 0xFF - 128) / 128.0f
                        left = (left + right) / 2.0f // Average to mono
                    }
                }
                16 -> { // 16-bit signed PCM (-32768 to 32767)
                    left = buffer.short / 32768.0f
                    if (channels == 2) {
                        right = buffer.short / 32768.0f
                        left = (left + right) / 2.0f // Average to mono
                    }
                }
                24 -> { // 24-bit signed PCM
                    val l1 = buffer.get().toInt() and 0xFF
                    val l2 = buffer.get().toInt() and 0xFF
                    val l3 = buffer.get().toInt() and 0xFF
                    var sampleL = (l1 shl 8) or (l2 shl 16) or (l3 shl 24)
                    left = (sampleL / 8388608.0f)

                    if (channels == 2) {
                        val r1 = buffer.get().toInt() and 0xFF
                        val r2 = buffer.get().toInt() and 0xFF
                        val r3 = buffer.get().toInt() and 0xFF
                        var sampleR = (r1 shl 8) or (r2 shl 16) or (r3 shl 24)
                        val rightFloat = (sampleR / 8388608.0f)
                        left = (left + rightFloat) / 2.0f // Average to mono
                    }
                }
                32 -> { // 32-bit float PCM (-1.0 to 1.0)
                    left = buffer.float
                    if (channels == 2) {
                        right = buffer.float
                        left = (left + right) / 2.0f // Average to mono
                    }
                }
                else -> return null // Unsupported bit depth
            }
            output[i] = left
        }
        return output
    }

    /**
     * Step 2: Resamples audio data using linear interpolation.
     */
    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        val outputSize = (input.size.toLong() * toRate / fromRate).toInt()
        val output = FloatArray(outputSize)
        val ratio = input.size.toDouble() / outputSize.toDouble()

        for (i in 0 until outputSize) {
            val fromIndex = i * ratio
            val index1 = fromIndex.toInt()
            val index2 = index1 + 1
            val fraction = fromIndex - index1

            val value1 = input.getOrElse(index1) { if (index1 <= 0) input.first() else input.last() }
            val value2 = input.getOrElse(index2) { if (index2 <= 0) input.first() else input.last() }

            // Linear interpolation
            output[i] = (value1 + (value2 - value1) * fraction).toFloat()
        }
        return output
    }

    /**
     * Step 3: Converts a normalized FloatArray back to a 16-bit ShortArray for the model.
     */
    private fun floatArrayTo16BitShortArray(floatArray: FloatArray): ShortArray {
        val shortArray = ShortArray(floatArray.size)
        for (i in floatArray.indices) {
            val clampedValue = floatArray[i].coerceIn(-1.0f, 1.0f)
            shortArray[i] = (clampedValue * 32767.0f).roundToInt().toShort()
        }
        return shortArray
    }
}
