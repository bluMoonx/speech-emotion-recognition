// In app/src/main/java/com/example/myapplication/AudioPlayer.kt

package com.example.myapplication
import android.content.Context
import android.net.Uri
import android.media.MediaPlayer
import androidx.core.net.toUri

class AudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(uri: Uri) {
        // Stop and release any previous player instance
        stop()

        player = MediaPlayer().apply {
            try {
                setDataSource(context, uri)
                prepare()
                start()
                setOnCompletionListener {
                    // Release resources when playback is complete
                    stop()
                }
            } catch (e: Exception) {
                stop() // Clean up on failure too
            }
        }
    }

    // --- ADD THIS FUNCTION ---
    fun stop() {
        player?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        player = null
    }
}