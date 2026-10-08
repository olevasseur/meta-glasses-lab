package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class MeetingAudioBufferTest {
    @Test fun rolloverRetainsUnsentPcmInOrderWithoutAliasing() {
        val buffer = MeetingAudioBuffer(8)
        val source = byteArrayOf(1, 2)
        buffer.add(source); source[0] = 9
        buffer.add(byteArrayOf(3, 4))
        val result = buffer.drain()
        assertArrayEquals(byteArrayOf(1, 2), result[0])
        assertArrayEquals(byteArrayOf(3, 4), result[1])
        assertTrue(buffer.drain().isEmpty())
        assertEquals(0, buffer.droppedBytes)
    }
    @Test fun longOutageIsBoundedAndReportsLostAudio() {
        val buffer = MeetingAudioBuffer(4)
        repeat(10) { buffer.add(byteArrayOf(it.toByte(), it.toByte())) }
        assertEquals(16, buffer.droppedBytes)
        assertEquals(2, buffer.drain().size)
        buffer.clear()
        assertEquals(0, buffer.droppedBytes)
    }
}
