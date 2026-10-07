package com.olevasseur.glassesapp

/** Streaming 8 kHz HFP → 16 kHz linear interpolation, or 16 kHz phone passthrough.
 * AudioRecord handles hardware rates; these are the two client capture rates we request.
 * Keeps the previous sample across chunks, with half an input sample of interpolation delay.
 */
class PcmResampler(private val inputRate: Int) {
    init { require(inputRate == 8000 || inputRate == 16000) }
    private var previous: Int? = null

    fun process(samples: ShortArray, count: Int = samples.size): ByteArray {
        require(count in 0..samples.size)
        val output = ByteArray(count * if (inputRate == 8000) 4 else 2)
        var offset = 0
        fun write(sample: Int) {
            output[offset++] = sample.toByte()
            output[offset++] = (sample shr 8).toByte()
        }
        for (i in 0 until count) {
            val current = samples[i].toInt()
            if (inputRate == 8000) write(((previous ?: current) + current) / 2)
            write(current)
            previous = current
        }
        return output
    }
}
