package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class CoachTimersTest {
    @Test fun multipleTimersPersistCancelAndIgnoreStaleBroadcasts() {
        val book = CoachTimers()
        val pasta = book.add("Pasta", 60.0, 1000, 100, 1)
        val glue = book.add("Glue", 120.0, 1000, 100, 1)
        val restored = CoachTimers(book.serialized())
        assertEquals(listOf(pasta, glue), restored.all())
        assertEquals(pasta, restored.cancel(" PASTA "))
        val replacement = restored.add("pasta", 10.0, 1000, 100, 1)
        assertNotEquals(pasta.id, replacement.id)
        assertNull(restored.due(pasta.id, 999999, 999999, 1))
        assertNull(restored.due(replacement.id, 1001, 101, 1))
        assertEquals(replacement, restored.due(replacement.id, 11000, 10100, 1))
        assertNull(restored.due(replacement.id, 11000, 10100, 1))
        assertEquals(listOf(glue), restored.all())
    }
    @Test fun elapsedClockSurvivesWallClockChangesAndRebootUsesWallDeadline() {
        val timer = CoachTimers().add("Pasta", 60.0, 1000, 100, 1)
        assertEquals(59000, timer.remaining(9999999, 1100, 1))
        assertEquals(59000, timer.remaining(-999999, 1100, 1))
        assertEquals(1000, timer.remaining(60000, 10, 2))
        assertEquals(0, timer.remaining(62000, 10, 2))
    }
    @Test fun timeChangeRebasesRebootFallbackAndRebootRebasesElapsedTime() {
        val book = CoachTimers()
        book.add("Pasta", 60.0, 1000, 100, 1)
        book.rebase(500000, 1100, 1)
        assertEquals(559000, book.all().single().deadline)
        val restored = CoachTimers(book.serialized())
        restored.rebase(510000, 100, 2)
        assertEquals(49000, restored.all().single().remaining(9999999, 100, 2))
    }
    @Test fun invalidArgumentsAndDuplicateNamesAreRejected() {
        val book = CoachTimers()
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 604801.0)) {
            assertThrows(IllegalArgumentException::class.java) { book.add("test", duration, 0, 0, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { book.add(" ", 1.0, 0, 0, 0) }
        book.add("Pasta", 1.5, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { book.add(" PASTA ", 10.0, 0, 0, 0) }
        assertEquals(1500, book.all().single().deadline)
        repeat(19) { book.add("timer $it", 1.0, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { book.add("extra", 1.0, 0, 0, 0) }
    }
}
