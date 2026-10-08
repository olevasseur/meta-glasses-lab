package com.olevasseur.glassesapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWavTest {
    private fun wav(metadata: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(if (metadata) 58 else 48).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(b.capacity() - 8).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000)
            .putInt(32000).putShort(2).putShort(16)
        if (metadata) b.put("JUNK".toByteArray()).putInt(1).put(7).put(0)
        b.put("data".toByteArray()).putInt(4).put(byteArrayOf(1, 2, 3, 4))
        return b.array()
    }
    @Test fun readsPcmAndSkipsOddMetadata() {
        for (metadata in listOf(false, true)) assertArrayEquals(byteArrayOf(1, 2, 3, 4), PcmWav.read(wav(metadata).inputStream()))
    }
    @Test fun rejectsWrongFormatTruncationAndOversizedChunks() {
        val valid = wav()
        val invalid = listOf(valid.copyOf(47), valid.clone().apply { this[20] = 3 },
            valid.clone().apply { this[24] = 1 }, valid.clone().apply { this[22] = 2 },
            valid.clone().apply { this[40] = 127 }, valid.clone().apply { this[36] = 0 },
            ByteArray(4 * 1024 * 1024 + 1))
        invalid.forEach { bytes -> assertThrows(IllegalArgumentException::class.java) { PcmWav.read(bytes.inputStream()) } }
    }
}
