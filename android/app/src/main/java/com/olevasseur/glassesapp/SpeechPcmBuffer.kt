package com.olevasseur.glassesapp

/** Retains startup/restart audio and partial nonblocking writes, with a visible overflow. */
internal class SpeechPcmBuffer(private val capacity: Int = 16 * 32_000) {
    private val chunks = ArrayDeque<ByteArray>()
    private var offset = 0
    private var size = 0

    @Synchronized fun offer(pcm: ByteArray): Boolean {
        if (size + pcm.size > capacity) return false
        if (pcm.isNotEmpty()) { chunks.addLast(pcm.copyOf()); size += pcm.size }
        return true
    }

    @Synchronized fun drain(write: (ByteArray, Int, Int) -> Int) {
        while (chunks.isNotEmpty()) {
            val head = chunks.first()
            val written = write(head, offset, head.size - offset)
            if (written == 0) return
            require(written in 1..(head.size - offset))
            offset += written; size -= written
            if (offset == head.size) { chunks.removeFirst(); offset = 0 }
        }
    }

    @Synchronized fun clear() { chunks.clear(); offset = 0; size = 0 }
}
