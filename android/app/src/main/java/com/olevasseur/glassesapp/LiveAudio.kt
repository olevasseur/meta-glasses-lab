package com.olevasseur.glassesapp

/** The Live session's audio boundary; flush/enqueue are safe on socket callbacks. */
interface LiveAudio {
    val translationSpeaker: TranslationPlayback
    val playbackIdle: Boolean
    fun enqueue(pcm: ByteArray)
    fun flush()
}

interface TranslationPlayback {
    val playing: Boolean
    fun play(pcm: ByteArray, onError: () -> Unit)
    fun stop()
}
