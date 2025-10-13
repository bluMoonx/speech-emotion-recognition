// In app/src/main/java/com/example/myapplication/AudioPlayer.kt

package com.example.myapplication
import android.content.Context
import android.net.Uri
import android.media.MediaPlayer
import androidx.core.net.toUri

class AudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(uri: Uri) {
        stop() // Stop any previous playback
        player = MediaPlayer.create(context, uri).apply {
            start()
        }
    }

    fun stop() {
        player?.stop()
        player?.release()
        player = null
    }
}
