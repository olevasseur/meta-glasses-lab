package com.olevasseur.glassesapp

/** PCM24k mono: small effective buffer, spare capacity for device underruns. */
internal class PlaybackBuffer(minimumFrames: Int, val capacityFrames: Int) {
    var frames = maxOf(minimumFrames, 960).coerceAtMost(capacityFrames); private set
    private var underruns = 0
    private var wasQueued = false

    init { require(minimumFrames > 0 && capacityFrames >= minimumFrames) }

    fun observe(count: Int, queued: Boolean): Boolean {
        val grow = count > underruns && queued && wasQueued && frames < capacityFrames
        underruns = count
        wasQueued = queued
        if (grow) frames = (frames + 480).coerceAtMost(capacityFrames)
        return grow
    }
}
