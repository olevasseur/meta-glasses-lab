package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class WorkoutEngineTest {
    @Test fun repMilestonesAreSparseAndTargetCompletesExactlyOnce() {
        val engine = WorkoutEngine(WorkoutConfig("squats", targetReps = 12), 0)
        val cues = (1..15).flatMap { engine.addRep(it * 1_000L) }
        assertEquals(listOf("[rep 5/12 squats]", "[rep 10/12 squats]", "[rep 11/12 squats]",
            "[rep 12/12 squats; workout complete; 1/1 sets squats]"), cues)
        assertEquals(listOf(12), engine.repsPerSet)
        assertEquals(1, engine.completedSets)
        assertEquals("completed", engine.phase)
        assertEquals(12L, engine.activeSeconds(100_000))
        assertFalse(engine.active)
        assertFalse(engine.needsMotion)
    }

    @Test fun restExcludesRepsAndWorkTimeThenStartsNextSet() {
        val engine = WorkoutEngine(WorkoutConfig("push_ups", 2, sets = 2, restSeconds = 10), 1_000)
        engine.addRep(2_000)
        assertEquals(listOf("[rep 2/2 push_ups; set 1/2 complete; rest 10 seconds]"), engine.addRep(3_000))
        assertEquals("rest", engine.phase)
        assertEquals(10, engine.remainingSeconds(3_000))
        assertFalse(engine.needsMotion)
        assertTrue(engine.addRep(4_000).isEmpty())
        assertEquals(listOf(2), engine.repsPerSet)
        assertEquals(2L, engine.activeSeconds(8_000))
        assertEquals(listOf("[rest countdown: 3, 2, 1]"), engine.tick(10_000))
        assertTrue(engine.tick(10_500).isEmpty())
        assertTrue(engine.tick(12_999).isEmpty())
        assertEquals(listOf("[set 2/2; push_ups; start]"), engine.addRep(13_000))
        assertEquals(listOf(2, 0), engine.repsPerSet)
        assertNull(engine.remainingSeconds(13_000))
        engine.addRep(14_000)
        engine.addRep(15_000)
        assertEquals(listOf(2, 2), engine.repsPerSet)
        assertEquals(4L, engine.activeSeconds(30_000))
    }

    @Test fun noRestStartsAnotherSetImmediatelyWithoutDoubleCounting() {
        val engine = WorkoutEngine(WorkoutConfig("lunges", 1, sets = 2, restSeconds = 0), 0)
        assertEquals(listOf("[rep 1/1 lunges; set 1/2 complete; set 2/2; lunges; start]"), engine.addRep(500))
        assertEquals("work", engine.phase)
        assertEquals(listOf(1, 0), engine.repsPerSet)
        engine.addRep(1_000)
        assertEquals(listOf(1, 1), engine.repsPerSet)
        assertEquals("completed", engine.phase)
    }

    @Test fun openEndedWorkStopsWithPartialRepsAndFrozenDuration() {
        val engine = WorkoutEngine(WorkoutConfig("generic"), 10_000)
        assertNull(engine.remainingSeconds(10_000))
        assertEquals(listOf("[rep 5 generic]"), (1..7).flatMap { engine.addRep(10_000 + it * 1_000L) })
        engine.stop(18_500)
        assertEquals("stopped", engine.phase)
        assertEquals(0, engine.completedSets)
        assertEquals(listOf(7), engine.repsPerSet)
        assertEquals(8L, engine.activeSeconds(50_000))
        assertTrue(engine.tick(60_000).isEmpty())
        assertTrue(engine.addRep(60_000).isEmpty())
        engine.stop(100_000)
        assertEquals(8L, engine.activeSeconds(100_000))
    }

    @Test fun plankUsesThirtySecondDefaultAndNeverCountsMotion() {
        val engine = WorkoutEngine(WorkoutConfig("plank"), 500)
        assertFalse(engine.needsMotion)
        assertEquals(30, engine.remainingSeconds(500))
        assertTrue(engine.addRep(1_000).isEmpty())
        assertEquals(listOf(0), engine.repsPerSet)
        assertEquals(listOf("[work countdown: 3, 2, 1]"), engine.tick(27_500))
        assertTrue(engine.tick(28_000).isEmpty())
        assertEquals(listOf("[workout complete; 1/1 sets plank]"), engine.tick(30_500))
        assertEquals(30L, engine.activeSeconds(200_000))
        assertNull(engine.remainingSeconds(200_000))
    }

    @Test fun eightIntervalsUseDeadlinesAndExcludeRest() {
        val engine = WorkoutEngine(WorkoutConfig("intervals", sets = 8, workSeconds = 30, restSeconds = 15), 0)
        for (set in 0..7) {
            val start = set * 45_000L
            assertEquals("work", engine.phase)
            assertEquals(listOf("[work countdown: 3, 2, 1]"), engine.tick(start + 27_000))
            assertEquals(1, engine.tick(start + 30_000).size)
            if (set < 7) {
                assertEquals("rest", engine.phase)
                assertEquals(listOf("[rest countdown: 3, 2, 1]"), engine.tick(start + 42_000))
                assertEquals(1, engine.tick(start + 45_000).size)
            }
        }
        assertEquals("completed", engine.phase)
        assertEquals(8, engine.completedSets)
        assertEquals(List(8) { 0 }, engine.repsPerSet)
        assertEquals(240L, engine.activeSeconds(1_000_000))
    }

    @Test fun lateUpdatesSkipExpiredCuesAndDoNotExtendIntervals() {
        val engine = WorkoutEngine(WorkoutConfig("intervals", sets = 8, workSeconds = 30, restSeconds = 15), 0)
        assertEquals(listOf("[set 3/8; intervals; start 30 seconds]"), engine.tick(100_000))
        assertEquals(2, engine.completedSets)
        assertEquals(listOf(0, 0, 0), engine.repsPerSet)
        assertEquals(20, engine.remainingSeconds(100_000))
        assertEquals(70L, engine.activeSeconds(100_000))
        assertEquals(listOf("[workout complete; 8/8 sets intervals]"), engine.tick(1_000_000))
        assertEquals(240L, engine.activeSeconds(1_000_000))
        assertTrue(engine.tick(1_000_001).isEmpty())
    }

    @Test fun missedCountdownIsNotSpokenLate() {
        val engine = WorkoutEngine(WorkoutConfig("plank", workSeconds = 5), 0)
        assertTrue(engine.tick(1_000).isEmpty())
        assertTrue(engine.tick(3_001).isEmpty())
        assertEquals(1, engine.tick(5_000).size)
    }

    @Test fun oldClockValuesCannotRewindOrCountReps() {
        val engine = WorkoutEngine(WorkoutConfig("squats", 10), 5_000)
        assertTrue(engine.addRep(4_999).isEmpty())
        assertEquals(listOf(0), engine.repsPerSet)
        engine.addRep(6_000)
        assertTrue(engine.addRep(5_999).isEmpty())
        assertTrue(engine.tick(5_999).isEmpty())
        assertEquals(listOf(1), engine.repsPerSet)
        engine.stop(0)
        assertEquals(1L, engine.activeSeconds(0))
    }

    @Test fun stoppingDuringRestDoesNotAccrueRestAndCompletedStaysComplete() {
        val engine = WorkoutEngine(WorkoutConfig("plank", sets = 2, workSeconds = 10, restSeconds = 30), 0)
        engine.stop(20_000)
        assertEquals("stopped", engine.phase)
        assertEquals(1, engine.completedSets)
        assertEquals(10L, engine.activeSeconds(50_000))
        val finished = WorkoutEngine(WorkoutConfig("plank", workSeconds = 10), 0)
        finished.stop(15_000)
        assertEquals("completed", finished.phase)
        assertEquals(10L, finished.activeSeconds(20_000))
    }

    @Test fun zeroRestTimersRemainFiniteAndCompleteAllSets() {
        val engine = WorkoutEngine(WorkoutConfig("intervals", sets = 100, workSeconds = 1, restSeconds = 0), 0)
        assertEquals(listOf("[workout complete; 100/100 sets intervals]"), engine.tick(100_000))
        assertEquals(100L, engine.activeSeconds(100_000))
        assertEquals(100, engine.completedSets)
    }

    @Test fun configurationRejectsAmbiguousOrUnboundedWorkouts() {
        val invalid = listOf<() -> WorkoutConfig>(
            { WorkoutConfig("walking") }, { WorkoutConfig("squats", sets = 0) },
            { WorkoutConfig("squats", 1, sets = 101) }, { WorkoutConfig("squats", 0) },
            { WorkoutConfig("squats", 1_001) }, { WorkoutConfig("squats", sets = 2) },
            { WorkoutConfig("squats", workSeconds = 30) }, { WorkoutConfig("plank", targetReps = 10) },
            { WorkoutConfig("plank", workSeconds = 0) }, { WorkoutConfig("plank", workSeconds = 3_601) },
            { WorkoutConfig("plank", restSeconds = -1) }, { WorkoutConfig("plank", restSeconds = 3_601) },
        )
        invalid.forEach { create ->
            try { create(); fail("Expected invalid config") } catch (_: IllegalArgumentException) { }
        }
        listOf("squats", "push_ups", "sit_ups", "crunches", "lunges", "jumping_jacks", "burpees", "generic", "plank", "intervals")
            .forEach { assertTrue(WorkoutEngine(WorkoutConfig(it), 0).active) }
    }
}
