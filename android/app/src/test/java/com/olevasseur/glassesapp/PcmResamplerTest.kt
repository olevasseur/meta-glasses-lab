package com.olevasseur.glassesapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmResamplerTest {
    private fun samples(bytes: ByteArray): ShortArray {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    }

    @Test fun phonePcmIsSignedLittleEndianAndUnchanged() {
        val input = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        val output = PcmResampler(16000).process(input)
        assertArrayEquals(byteArrayOf(0, -128, -1, -1, 0, 0, 1, 0, -1, 127), output)
        assertArrayEquals(input, samples(output))
    }
    @Test fun hfpInterpolatesAndDoublesSampleCount() {
        val output = samples(PcmResampler(8000).process(shortArrayOf(0, 1000, 2000, -2000)))
        assertArrayEquals(shortArrayOf(0, 0, -3, 0, -1, 0, 5, 0), output)
    }
    @Test fun boundariesDoNotChangeWaveform() {
        val input = ShortArray(8000) { ((it % 500 - 250) * 100).toShort() }
        val whole = PcmResampler(8000).process(input)
        val stream = PcmResampler(8000)
        val chunks = input.toList().chunked(137).flatMap { stream.process(it.toShortArray()).toList() }.toByteArray()
        assertArrayEquals(whole, chunks)
        assertEquals(32000, chunks.size) // Exactly one second of 16 kHz, 16-bit mono.
    }
    @Test fun extremeSamplesDoNotOverflow() {
        assertArrayEquals(shortArrayOf(-111, 0, 304, 0),
            samples(PcmResampler(8000).process(shortArrayOf(32767, -32768))))
    }
    @Test fun emptyChunkPreservesContinuityAndCountExcludesUnusedBuffer() {
        val stream = PcmResampler(8000)
        stream.process(shortArrayOf(1000))
        assertEquals(0, stream.process(shortArrayOf()).size)
        assertArrayEquals(shortArrayOf(-1, 0), samples(stream.process(shortArrayOf(2000, 9999), 1)))
    }
    @Test(expected = IllegalArgumentException::class) fun unsupportedCaptureRatesAreRejected() { PcmResampler(48000) }
    @Test(expected = IllegalArgumentException::class) fun invalidReadCountIsRejected() { PcmResampler(16000).process(shortArrayOf(1), 2) }
}
