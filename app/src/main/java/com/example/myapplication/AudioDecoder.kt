package com.example.myapplication

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {
    fun decodeAudioFileToPcmShortArray(context: Context, uri: Uri): ShortArray? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val bytes = inputStream.readBytes()
        inputStream.close()
        val shortArray = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)
        return shortArray
    }
}