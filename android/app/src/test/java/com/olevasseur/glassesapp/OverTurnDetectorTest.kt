package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class OverTurnDetectorTest {
    private val voice = ByteArray(640) { if (it % 2 == 0) 0 else 8 } // 2048 PCM amplitude.
    private val silence = ByteArray(640)

    @Test fun matchesOnlyNormalizedTrailingWords() {
        for (text in listOf("OVER!", "Please answer. Over…", "Okay, over\n")) {
            val detector = OverTurnDetector("over")
            assertNull(detector.transcript(text, true, 0))
            assertEquals(OverTurnDetector.End.KEYWORD, detector.tick(1200))
        }
        for (text in listOf("clover", "overhead", "over there", "turn over the page", "")) {
            val detector = OverTurnDetector("over")
            assertNull(detector.transcript(text, true, 0))
            assertNull(detector.tick(10_000))
        }
    }

    @Test fun keywordsAreConfigurableAndSignOffAlwaysWorks() {
        for (keyword in ConversationSettings.KEYWORDS) {
            val detector = OverTurnDetector(keyword)
            detector.transcript("Please answer, $keyword!", false, 0)
            assertEquals(OverTurnDetector.End.KEYWORD, detector.tick(1200))
            assertEquals(OverTurnDetector.End.SIGN_OFF,
                OverTurnDetector(keyword).transcript("Thanks, OVER and OUT.", true, 0))
        }
        val detector = OverTurnDetector("your turn")
        assertNull(detector.transcript("over", true, 0))
        assertNull(detector.tick(1200))
        assertEquals(OverTurnDetector.End.KEYWORD, detector.transcript("your TURN!", true, 1300))
    }

    @Test fun partialRevisionCancelsCandidateAndDuplicatesDoNotDelayIt() {
        val detector = OverTurnDetector("over")
        detector.transcript("look over", false, 0)
        assertNull(detector.tick(1199))
        detector.transcript("look over there", false, 800)
        assertNull(detector.tick(2000))
        detector.transcript("answer over", false, 2100)
        detector.transcript("answer over", false, 2500)
        assertEquals(OverTurnDetector.End.KEYWORD, detector.tick(3300))
        assertNull(detector.transcript("over and out", true, 3400))
        assertNull(detector.tick(5000))
    }

    @Test fun overAndOutWinsOverShortKeywordIncludingAcrossSegments() {
        val detector = OverTurnDetector("over")
        detector.transcript("hello over", false, 0)
        detector.transcript("hello over and", false, 600)
        assertNull(detector.tick(1300))
        assertEquals(OverTurnDetector.End.SIGN_OFF, detector.transcript("hello over and out", true, 1400))
        val segmented = OverTurnDetector("over")
        segmented.transcript("hello over", true, 0)
        segmented.transcript("and", true, 400)
        assertEquals(OverTurnDetector.End.SIGN_OFF, segmented.transcript("out", true, 900))
    }

    @Test fun silenceAndNonKeywordFinalsNeverEndTheTurn() {
        val detector = OverTurnDetector("over")
        for (time in 0L..120_000L step 20) {
            assertNull(detector.audio(silence, time))
            assertNull(detector.tick(time))
        }
        detector.transcript("I am still thinking", true, 120_001)
        assertNull(detector.tick(999_999))
    }

    @Test fun safetyUsesContinuousSpeechNotSessionAgeOrAccumulatedSpeech() {
        val detector = OverTurnDetector("over")
        for (time in 0L until 30_000L step 20) assertNull(detector.audio(voice, time))
        assertNull(detector.audio(silence, 31_000))
        for (time in 50_000L until 95_000L step 20) assertNull(detector.audio(voice, time))
        assertEquals(OverTurnDetector.End.SAFETY_TIMEOUT, detector.audio(voice, 95_000))
        assertNull(detector.audio(voice, 95_020))
        val custom = OverTurnDetector("over", safetySeconds = 10)
        for (time in 0L until 10_000L step 20) custom.audio(voice, time)
        assertEquals(OverTurnDetector.End.SAFETY_TIMEOUT, custom.audio(voice, 10_000))
    }

    @Test fun newTurnDoesNotReusePreviousKeywordsOrSafetyClock() {
        val first = OverTurnDetector("over")
        first.transcript("over", true, 0)
        assertNotNull(first.tick(1200))
        val next = OverTurnDetector("over")
        assertNull(next.tick(100_000))
        assertNull(next.audio(voice, 100_000))
        next.transcript("over", true, 100_020)
        assertNotNull(next.tick(101_220))
    }
}
