// In app/src/main/java/com/example/myapplication/WavHeaderInfo.kt

package com.example.myapplication

/**
 * A simple data class to hold the essential properties parsed from a WAV file's header.
 */
data class WavHeaderInfo(
    val sampleRate: Int,
    val numChannels: Int,
    val bitsPerSample: Int,
    val audioFormat: Int
)
