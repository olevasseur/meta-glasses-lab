package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class OverTurnDetectorTest {
    private fun pcm(sample: (Int) -> Int) = ByteArray(640).also { bytes ->
        repeat(320) { i ->
            val value = sample(i)
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
    }
    private fun tone(amplitude: Int) = pcm { (amplitude * sin(2 * PI * 300 * it / 16_000)).toInt() }
    private val voice = tone(2048)
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

    private fun keepOpen(detector: OverTurnDetector, audio: ByteArray, milliseconds: Int) {
        repeat(milliseconds / 20) { assertNull(detector.audio(audio)) }
    }

    @Test fun tenSecondsOfContinuousSilenceEndsExactlyOnce() {
        val detector = OverTurnDetector("over")
        keepOpen(detector, voice, 1000)
        detector.transcript("I am still thinking", true, 1000)
        keepOpen(detector, silence, 9980)
        assertNull(detector.tick(10_980))
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(silence))
        assertNull(detector.audio(silence))
        assertNull(detector.transcript("over and out", true, 11_000))
    }

    @Test fun silenceAtStartAlsoEndsButMissingAudioDoesNotCountAsSilence() {
        val detector = OverTurnDetector("over")
        assertNull(detector.tick(100_000))
        assertNull(detector.audio(byteArrayOf()))
        keepOpen(detector, silence, 9980)
        assertNull(detector.tick(1_000_000))
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(silence))
    }

    @Test fun threeMinutesOfContinuousSpeechNeverEndsEvenAtShortestSetting() {
        val detector = OverTurnDetector("over", silenceSeconds = 5)
        for (time in 0L until 180_000L step 20) {
            assertNull(detector.audio(voice))
            assertNull(detector.tick(time))
        }
        keepOpen(detector, silence, 4980)
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(silence))
    }

    @Test fun pausesDoNotAccumulateAcrossSpeechOrRecognizerRestarts() {
        val detector = OverTurnDetector("over")
        repeat(20) {
            keepOpen(detector, voice, 1000)
            keepOpen(detector, silence, 9000)
            detector.transcript("still thinking", true, it * 10_000L)
        }
        keepOpen(detector, silence, 980)
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(silence))
    }

    @Test fun speechBeginningJustBeforeTimeoutCanFinishItsOnsetDebounce() {
        val detector = OverTurnDetector("over")
        keepOpen(detector, silence, 9980)
        keepOpen(detector, voice, 1000)
        keepOpen(detector, silence, 9980)
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(silence))
    }

    @Test fun breathsLowNoiseDcAndBroadbandNoiseDoNotKeepTheTurnOpen() {
        val random = Random(7)
        val hiss = pcm { random.nextInt(-4000, 4001) }
        val hfpHiss = PcmResampler(8000).process(ShortArray(160) { random.nextInt(-4000, 4001).toShort() })
        for (background in listOf(tone(500), pcm { 3000 }, hiss, hfpHiss)) {
            val detector = OverTurnDetector("over")
            keepOpen(detector, voice, 1000)
            keepOpen(detector, background, 9980)
            assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(background))
        }
    }

    @Test fun repeatedClicksAndBriefEnergyBurstsDoNotResetSilence() {
        val detector = OverTurnDetector("over")
        repeat(9) {
            keepOpen(detector, silence, 960)
            keepOpen(detector, voice, 40)
        }
        keepOpen(detector, silence, 980)
        // A click at the deadline can delay the decision only until the next quiet frame.
        assertNull(detector.audio(voice))
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(silence))
    }

    @Test fun weakEnergyHysteresisBridgesSyllablesButCannotLatchBackgroundForever() {
        val weak = tone(800) // RMS between the 450 hold and 700 onset thresholds.
        val detector = OverTurnDetector("over")
        repeat(100) {
            keepOpen(detector, voice, 200)
            keepOpen(detector, weak, 100)
        }
        keepOpen(detector, voice, 200)
        keepOpen(detector, weak, 10_160)
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(weak))
    }

    @Test fun timeoutSettingClampsAndCountsPcmDurationNotCallbackCount() {
        for ((setting, seconds) in listOf(-1 to 5, 5 to 5, 30 to 30, 99 to 30)) {
            val detector = OverTurnDetector("over", silenceSeconds = setting)
            // Half-size callbacks represent 10 ms, not 20 ms.
            repeat(seconds * 100 - 1) { assertNull(detector.audio(ByteArray(320))) }
            assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, detector.audio(ByteArray(320)))
        }
    }

    @Test fun newTurnDoesNotReusePreviousKeywordsOrSilenceClock() {
        val first = OverTurnDetector("over")
        keepOpen(first, silence, 9000)
        first.transcript("over", true, 9000)
        assertNotNull(first.tick(10_200))
        val next = OverTurnDetector("over")
        keepOpen(next, silence, 9980)
        assertEquals(OverTurnDetector.End.SILENCE_TIMEOUT, next.audio(silence))
    }
}
