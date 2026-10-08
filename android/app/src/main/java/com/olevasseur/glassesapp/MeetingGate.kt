package com.olevasseur.glassesapp

/** A prompt cannot guarantee silence. Only a transcribed wake phrase opens one response. */
class MeetingGate {
    var active = false
        private set
    private var overMode = false
    private var heard = ""
    private var addressed = false
    val requestText: String get() = heard
    val mayRespond: Boolean get() = !active || addressed

    fun configure(enabled: Boolean, over: Boolean = false) {
        active = enabled
        overMode = over
        endTurn()
    }

    fun input(text: String) {
        heard = (heard + text).takeLast(12_000)
        addressed = Regex("(?i)(?:^|[.!?]\\s*)(?:hey\\s+)?assistant\\b").containsMatchIn(heard.trim()) ||
            (overMode && Regex("(?i)\\bover[.!?,\\s]*$").containsMatchIn(heard))
    }

    fun endTurn() { heard = ""; addressed = false }
}
