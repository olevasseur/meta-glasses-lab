package com.olevasseur.glassesapp

import java.util.ArrayDeque

/** Unsent microphone PCM during Live rollover; callers hold GeminiSession's send lock. */
class MeetingAudioBuffer(private val maxBytes: Int = 32_000 * 30) {
    private val chunks = ArrayDeque<ByteArray>()
    private var size = 0
    var droppedBytes = 0
        private set

    fun add(pcm: ByteArray) {
        chunks.addLast(pcm.copyOf())
        size += pcm.size
        while (size > maxBytes && chunks.isNotEmpty()) {
            val removed = chunks.removeFirst().size
            size -= removed
            droppedBytes += removed
        }
    }

    fun drain(): List<ByteArray> = chunks.toList().also { chunks.clear(); size = 0 }
    fun clear() { chunks.clear(); size = 0; droppedBytes = 0 }
}
