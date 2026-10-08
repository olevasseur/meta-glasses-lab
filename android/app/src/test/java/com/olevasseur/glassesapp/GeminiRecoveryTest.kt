package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class GeminiRecoveryTest {
    @Test fun overloadIsNotMisreportedAsConfiguration() {
        for (code in listOf(1011, 1013, 429, 503)) assertFalse(GeminiRecovery.classify(code).fatal)
        for (reason in listOf("This model is currently experiencing high demand", "RESOURCE_EXHAUSTED", "quota exceeded", "UNAVAILABLE", "Internal error encountered")) {
            val failure = GeminiRecovery.classify(1008, reason)
            assertFalse(reason, failure.fatal)
            assertEquals("Gemini is busy, retrying", failure.message)
        }
        assertTrue(GeminiRecovery.classify(1008, "Invalid fieldMask constraint").fatal)
        assertFalse(GeminiRecovery.classify(1008, "").fatal)
    }
    @Test fun expiryRefreshesAccessAndOnlyInvalidResumptionDropsHandle() {
        assertTrue(GeminiRecovery.classify(401).refreshToken)
        assertFalse(GeminiRecovery.classify(401).clearHandle)
        assertTrue(GeminiRecovery.classify(1008, "invalid resumption handle").clearHandle)
        assertTrue(GeminiRecovery.classify(401, backend = true).fatal)
        assertFalse(GeminiRecovery.classify(reason = "network lost").fatal)
    }
    @Test fun backoffIsExponentialJitteredCappedAndUnlimited() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L),
            (1..7).map { GeminiRecovery.backoff(it, 1.0) })
        for (attempt in 1..1000) {
            val max = GeminiRecovery.backoff(attempt, 1.0)
            assertEquals(max / 2, GeminiRecovery.backoff(attempt, 0.0))
            assertTrue(max <= 60_000)
        }
    }
    @Test fun announcementsAreRateLimitedButRecoveryCanSpeakImmediately() {
        val gate = AnnouncementLimit()
        assertTrue(gate.allow("busy", 0))
        assertFalse(gate.allow("busy", 59_999))
        assertTrue(gate.allow("online", 1000))
        assertTrue(gate.allow("busy", 60_000))
    }
}
