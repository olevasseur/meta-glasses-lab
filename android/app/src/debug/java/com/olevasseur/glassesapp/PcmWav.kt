package com.olevasseur.glassesapp

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded RIFF parser: PCM16 little endian, mono, 16 kHz; skips padded metadata chunks. */
object PcmWav {
    fun read(input: InputStream): ByteArray {
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                require(output.size() + count <= 4 * 1024 * 1024) { "WAV must be at most 4 MiB" }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size in 44..4 * 1024 * 1024) { "WAV must be at most 4 MiB" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE" && buffer.getInt(4).toLong() + 8 == bytes.size.toLong()) { "Invalid RIFF/WAVE" }
        var offset = 12
        var format = false
        var pcm: ByteArray? = null
        while (offset < bytes.size) {
            require(offset + 8 <= bytes.size) { "Truncated WAV chunk" }
            val size = buffer.getInt(offset + 4)
            val end = offset.toLong() + 8 + size
            require(size >= 0 && end <= bytes.size) { "Truncated WAV data" }
            when (tag(offset)) {
                "fmt " -> {
                    require(size >= 16 && buffer.getShort(offset + 8).toInt() == 1 &&
                        buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 16000 &&
                        buffer.getInt(offset + 16) == 32000 && buffer.getShort(offset + 20).toInt() == 2 &&
                        buffer.getShort(offset + 22).toInt() == 16) { "Expected PCM16 mono 16000 Hz WAV" }
                    format = true
                }
                "data" -> { require(pcm == null && size > 0 && size % 2 == 0); pcm = bytes.copyOfRange(offset + 8, end.toInt()) }
            }
            offset = end.toInt() + size % 2
        }
        require(format && pcm != null && offset == bytes.size) { "Missing WAV format/data or padding" }
        return pcm
    }
}
