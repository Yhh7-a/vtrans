package com.vtuber.vtransoverlay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object WavUtil {

    /**
     * 计算 PCM 16-bit 采样数组的均方根能量 (RMS)，用于动态语音端点检测 (VAD)
     */
    fun calculateRms(samples: ShortArray, length: Int): Double {
        if (length <= 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until length) {
            val sample = samples[i].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / length)
    }

    /**
     * 将 16kHz 单声道 16-bit PCM 数据封装为标准 44 字节 RIFF WAV 字节流
     */
    fun pcmToWavBytes(pcmShorts: ShortArray, sampleRate: Int = 16000): ByteArray {
        val pcmBytes = ByteArray(pcmShorts.size * 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmShorts)

        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 1 * 2 // 1 channel, 16-bit (2 bytes)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt sub-chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat 1 = PCM
        header.putShort(1) // NumChannels 1 = Mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign = NumChannels * BitsPerSample/8
        header.putShort(16) // BitsPerSample = 16
        // data sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.size)

        val out = ByteArrayOutputStream(44 + pcmBytes.size)
        out.write(header.array())
        out.write(pcmBytes)
        return out.toByteArray()
    }
}
