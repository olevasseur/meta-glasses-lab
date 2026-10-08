package com.olevasseur.glassesapp

import android.content.Intent

/** Release has no fixture reader, queue, intent controls, or telemetry. */
internal object SmokeHooks {
    const val wavEnabled = false
    fun configure(intent: Intent, model: ConversationViewModel) = Unit
    suspend fun capture(onAudio: (ByteArray) -> Unit): Unit = error("Debug only")
    fun mockChanged(enabled: Boolean) = Unit
    fun played(count: Int) = Unit
}
