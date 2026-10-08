package com.olevasseur.glassesapp

import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import org.junit.Assert.*
import org.junit.Test

class GlassesTriggersTest {
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
