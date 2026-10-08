package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferTest {
    @Test fun startsSmallButHonorsDeviceMinimumAndCapacity() {
        assertEquals(960, PlaybackBuffer(240, 4800).frames)
        assertEquals(2400, PlaybackBuffer(2400, 4800).frames)
        assertEquals(480, PlaybackBuffer(240, 480).frames)
    }

    @Test fun growsOnActiveUnderrunsAndStopsAtCapacity() {
        val buffer = PlaybackBuffer(240, 1920)
        assertFalse(buffer.observe(0, true))
        assertTrue(buffer.observe(1, true))
        assertEquals(1440, buffer.frames)
        assertFalse(buffer.observe(1, true))
        assertTrue(buffer.observe(2, true))
        assertEquals(1920, buffer.frames)
        assertFalse(buffer.observe(3, true))
    }

    @Test fun silenceAndFirstWriteDoNotGrowTheBuffer() {
        val buffer = PlaybackBuffer(240, 4800)
        buffer.observe(0, true)
        assertFalse(buffer.observe(1, false))
        assertFalse(buffer.observe(2, true))
        assertEquals(960, buffer.frames)
        assertTrue(buffer.observe(3, true))
    }
}
