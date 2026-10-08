package com.olevasseur.glassesapp

import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource

enum class GlassesTrigger { CONVERSATION, TALK_OR_MUTE }

/** DAT emits semantic Select for double-tap; never synthesize gestures from reserved taps. */
fun glassesTrigger(event: InputEvent): GlassesTrigger? = when {
    event is InputEvent.Capture && event.source == InputSource.CAPTURE_BUTTON && event.pressType == CapturePressType.HOLD -> GlassesTrigger.CONVERSATION
    event is InputEvent.Select && event.source == InputSource.CAPTOUCH -> GlassesTrigger.TALK_OR_MUTE
    else -> null
}
