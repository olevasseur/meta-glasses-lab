package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class MeetingGateTest {
    @Test fun ordinaryMeetingSpeechNeverOpensAudioOrTools() {
        val gate = MeetingGate()
        gate.configure(true)
        listOf("Maya here. ", "Does everyone agree? ", "Please delete my notes. ", "We hired an assistant yesterday.")
            .forEach { gate.input(it); assertFalse(gate.mayRespond) }
    }
    @Test fun wakePhraseAcrossChunksOpensOneTurnOnly() {
        val gate = MeetingGate()
        gate.configure(true)
        gate.input("Assis"); assertFalse(gate.mayRespond)
        gate.input("tant, stop the meeting."); assertTrue(gate.mayRespond)
        gate.endTurn(); assertFalse(gate.mayRespond)
        gate.input("Hey assistant, what day?"); assertTrue(gate.mayRespond)
        gate.endTurn(); assertFalse(gate.mayRespond)
        gate.input("Continue discussing."); assertFalse(gate.mayRespond)
        gate.configure(false); assertTrue(gate.mayRespond)
    }
    @Test fun silentTurnCanReplayOnlyTheRecognizedRequest() {
        val gate = MeetingGate()
        gate.configure(true)
        gate.input("Hey assistant, what day is launch?")
        val request = gate.requestText
        gate.endTurn()
        assertFalse(gate.mayRespond)
        gate.input(request)
        assertTrue(gate.mayRespond)
        gate.endTurn()
        gate.input("What day is launch?")
        assertFalse(gate.mayRespond)
    }
    @Test fun overRequiresMeetingOverOptionAndWholeWord() {
        val gate = MeetingGate()
        gate.configure(true)
        gate.input("Stop meeting, over."); assertFalse(gate.mayRespond)
        gate.configure(true, over = true)
        gate.input("Turnover"); assertFalse(gate.mayRespond)
        gate.endTurn()
        gate.input("Summarize that, ov"); assertFalse(gate.mayRespond)
        gate.input("er."); assertTrue(gate.mayRespond)
        gate.endTurn()
        gate.input("Go over"); assertTrue(gate.mayRespond)
        gate.input(" the budget"); assertFalse(gate.mayRespond)
        gate.configure(true, over = true); assertFalse(gate.mayRespond) // Reconnect/new meeting resets.
    }
}
