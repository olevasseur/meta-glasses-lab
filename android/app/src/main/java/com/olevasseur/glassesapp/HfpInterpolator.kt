package com.olevasseur.glassesapp

import kotlin.math.roundToInt

/** Causal 31-tap half-band 8→16 kHz filter; 15 output samples (0.94 ms) delay.
 * Preserves narrowband consonants and suppresses the 4–8 kHz interpolation image.
 * Coefficients: 2 * scipy.signal.firwin(31, 4000, fs=16000, window="hamming").
 */
internal class HfpInterpolator {
    private val history = IntArray(16)
    private var position = 0

    fun process(current: Int, write: (Int) -> Unit) {
        history[position] = current
        var even = 0.0
        for (i in EVEN.indices) even += EVEN[i] * history[(position - i) and 15]
        write(even.roundToInt().coerceIn(-32768, 32767))
        write((1.001616453894 * history[(position - 7) and 15]).roundToInt().coerceIn(-32768, 32767))
        position = (position + 1) and 15
    }

    companion object {
        private val EVEN = doubleArrayOf(
            -0.003400793807, 0.005874663142, -0.013460182733, 0.028187775808,
            -0.053570071640, 0.098197921187, -0.193876665553, 0.631239126649,
            0.631239126649, -0.193876665553, 0.098197921187, -0.053570071640,
            0.028187775808, -0.013460182733, 0.005874663142, -0.003400793807,
        )
    }
}
