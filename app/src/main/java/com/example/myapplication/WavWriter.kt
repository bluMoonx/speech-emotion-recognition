package com.example.myapplication

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object WavWriter {
    @Throws(IOException::class)
    fun writeWavFile(file: File, audioData: ShortArray, sampleRate: Int) {
        val outputStream = FileOutputStream(file)
        val byteData = ByteArray(audioData.size * 2)
        for (i in audioData.indices) {
            byteData[i * 2] = (audioData[i].toInt() and 0xff).toByte()
            byteData[i * 2 + 1] = (audioData[i].toInt() shr 8 and 0xff).toByte()
        }

        writeWavHeader(outputStream, audioData.size * 2, sampleRate)
        outputStream.write(byteData)
        outputStream.close()
    }

    @Throws(IOException::class)
    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Int, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] =
            'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] =
            (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] =
            (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] =
            'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] =
            't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] =
            (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] =
            (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] =
            (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] =
            't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] =
            (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte(); header[43] =
            (totalAudioLen shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }
}