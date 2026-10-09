package com.olevasseur.glassesapp

import kotlin.math.sqrt

/** Local gate for the shared 16 kHz PCM16 stream (normally 20 ms per callback). */
internal class SpeechEnergyDetector(private val startRms: Double = 700.0, private val holdRms: Double = 450.0) {
    private var onsetSamples = 0
    private var weakSamples = 0
    private var speaking = false
    val pendingSpeech: Boolean get() = onsetSamples > 0 && !speaking

    fun accept(pcm: ByteArray): Boolean {
        val count = pcm.size / 2
        if (count == 0) return false
        var sum = 0.0
        var squares = 0.0
        for (i in 0 until count) {
            val sample = sample(pcm, i).toDouble()
            sum += sample
            squares += sample * sample
        }
        val mean = sum / count
        val rms = sqrt((squares / count - mean * mean).coerceAtLeast(0.0))
        var crossings = 0
        for (i in 1 until count) {
            if ((sample(pcm, i - 1) >= mean) != (sample(pcm, i) >= mean)) crossings++
        }
        // Reject DC/rumble and broadband hiss/breath; voiced speech has fewer crossings.
        val speechLike = crossings.toDouble() / count in 0.008..0.20
        val strong = speechLike && rms >= startRms
        val weak = speechLike && rms >= holdRms
        if (strong) {
            onsetSamples = (onsetSamples + count).coerceAtMost(1600)
            weakSamples = 0
            if (onsetSamples >= 1600) speaking = true // 100 ms attack rejects clicks.
        } else {
            onsetSamples = 0
            weakSamples = (weakSamples + count).coerceAtMost(3200)
            // A weak background cannot latch speech forever after one loud syllable.
            if (!weak || weakSamples >= 3200) speaking = false // 200 ms release.
        }
        // ponytail: energy/crossings cannot separate nearby voices or all wind from speech.
        // Validate HFP thresholds on hardware; use a trained local VAD if needed.
        return speaking
    }

    private fun sample(pcm: ByteArray, index: Int): Int =
        ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort().toInt()
}
