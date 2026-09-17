package com.example.gps.laneengine

/** Two cues per turn, plus an early highway-exit cue. Distance jitter cannot replay cues. */
class VoicePromptGate {
    private val closestSpoken = mutableMapOf<String, Int>()
    private var lastSpokenAt: Long? = null
    fun snapshot(): Map<String, Int> = closestSpoken.toMap()
    fun restore(phases: Map<String, Int>) { closestSpoken.clear(); closestSpoken.putAll(phases.filterValues { it in 0..2 }) }
    fun reset() { closestSpoken.clear(); lastSpokenAt = null }

    fun shouldSpeak(key: String, meters: Double, now: Long, force: Boolean = false,
                    speedMps: Double = 0.0, highwayExit: Boolean = false): Boolean {
        if (!meters.isFinite() || meters < 0) return false
        val speed = if (speedMps.isFinite()) speedMps.coerceIn(0.0, 55.0) else 0.0
        val phase = when {
            meters <= maxOf(55.0, speed * 5.0) -> 0
            meters <= maxOf(300.0, speed * 20.0) -> 1
            highwayExit && meters <= maxOf(1200.0, speed * 45.0) -> 2
            force -> 2 // One initial preview also consumes the advance cue.
            else -> return false
        }
        if ((closestSpoken[key] ?: 3) <= phase) return false
        val gap = lastSpokenAt?.let { now - it }
        if (gap != null && gap < if (phase == 0) 3_000L else 15_000L) return false
        closestSpoken[key] = phase
        lastSpokenAt = now
        return true
    }
}
