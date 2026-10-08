package com.olevasseur.glassesapp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class HfpInterpolatorTest {
    private fun process(input: IntArray): IntArray {
        val filter = HfpInterpolator()
        val output = ArrayList<Int>(input.size * 2)
        input.forEach { filter.process(it) { sample -> output.add(sample) } }
        return output.toIntArray()
    }

    @Test fun impulseMatchesPythonReferenceAndDelay() {
        val result = process(IntArray(20) { if (it == 0) 10000 else 0 })
        assertArrayEquals(intArrayOf(-34, 0, 59, 0, -135, 0, 282, 0, -536, 0, 982, 0,
            -1939, 0, 6312, 10016, 6312, 0, -1939, 0, 982, 0, -536, 0, 282, 0,
            -135, 0, 59, 0, -34, 0), result.take(32).toIntArray())
        assertTrue(result.drop(32).all { it == 0 })
    }

    private fun amplitude(samples: IntArray, frequency: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        val data = samples.drop(1600)
        data.forEachIndexed { i, value ->
            real += value * cos(2 * PI * frequency * i / 16000)
            imaginary += value * sin(2 * PI * frequency * i / 16000)
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / data.size
    }

    @Test fun preservesVoiceBandAndRejectsSpectralImage() {
        for (frequency in listOf(300, 1000, 3000)) {
            val input = IntArray(8000) { (10000 * sin(2 * PI * frequency * it / 8000)).toInt() }
            val output = process(input)
            assertEquals(16000, output.size)
            assertEquals(10000.0, amplitude(output, frequency), 150.0)
            assertTrue(amplitude(output, 8000 - frequency) < 100.0)
        }
    }

    @Test fun fullScaleRingingClipsInsteadOfWrappingAndNewStreamClearsHistory() {
        val output = process(IntArray(800) { if (it % 16 < 8) 32767 else -32768 })
        assertTrue(output.all { it in -32768..32767 })
        assertTrue(output.any { it == 32767 })
        assertTrue(output.any { it == -32768 })
        assertTrue(process(IntArray(20)).all { it == 0 })
    }
}
