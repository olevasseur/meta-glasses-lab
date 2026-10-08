package com.olevasseur.glassesapp

/** Streaming 8 kHz HFP → 16 kHz band-limited interpolation, or 16 kHz phone passthrough.
 * AudioRecord handles hardware rates; these are the two client capture rates we request.
 * Retains FIR history across chunks, with 0.94 ms of interpolation delay.
 */
class PcmResampler(private val inputRate: Int) {
    init { require(inputRate == 8000 || inputRate == 16000) }
    private val hfp = if (inputRate == 8000) HfpInterpolator() else null

    fun process(samples: ShortArray, count: Int = samples.size): ByteArray {
        require(count in 0..samples.size)
        val output = ByteArray(count * if (inputRate == 8000) 4 else 2)
        var offset = 0
        fun write(sample: Int) {
            output[offset++] = sample.toByte()
            output[offset++] = (sample shr 8).toByte()
        }
        val writeSample: (Int) -> Unit = ::write
        for (i in 0 until count) {
            val current = samples[i].toInt()
            if (hfp != null) hfp.process(current, writeSample) else write(current)
        }
        return output
    }
}
