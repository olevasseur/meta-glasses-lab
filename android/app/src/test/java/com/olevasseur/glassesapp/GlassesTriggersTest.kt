package com.olevasseur.glassesapp

import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import org.junit.Assert.*
import org.junit.Test

class GlassesTriggersTest {
    @Test fun `approval consumes only actual capture input before other triggers`() {
        var calls = 0
        for (press in CapturePressType.entries) {
            val event = InputEvent.Capture(press, InputSource.CAPTURE_BUTTON, 1)
            assertTrue(approvalCapture(event) { calls++; true })
            assertFalse(approvalCapture(event) { false })
        }
        assertEquals(CapturePressType.entries.size, calls)
        assertFalse(approvalCapture(InputEvent.Select(InputSource.CAPTOUCH, 1)) { fail("Touchpad cannot approve"); true })
        assertFalse(approvalCapture(InputEvent.Capture(CapturePressType.HOLD, InputSource.UNKNOWN, 1)) { fail("Unknown source cannot approve"); true })
    }

    @Test fun `only capture HOLD toggles the conversation`() {
        for (press in CapturePressType.entries) {
            assertEquals(if (press == CapturePressType.HOLD) GlassesTrigger.CONVERSATION else null,
                glassesTrigger(InputEvent.Capture(press, InputSource.CAPTURE_BUTTON, 1)))
        }
        assertNull(glassesTrigger(InputEvent.Capture(CapturePressType.HOLD, InputSource.UNKNOWN, 1)))
    }

    @Test fun `only touchpad Select toggles talk or mute`() {
        assertEquals(GlassesTrigger.TALK_OR_MUTE, glassesTrigger(InputEvent.Select(InputSource.CAPTOUCH, 1)))
        assertNull(glassesTrigger(InputEvent.Select(InputSource.NEURAL_BAND, 1)))
        assertNull(glassesTrigger(InputEvent.Back(InputSource.CAPTOUCH, 1)))
    }
}
