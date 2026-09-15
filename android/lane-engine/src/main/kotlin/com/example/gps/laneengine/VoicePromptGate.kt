package com.example.gps.laneengine

/** Staged advance, approach, preparation and immediate cues; jitter cannot replay a phase. */
class VoicePromptGate {
    private val closestSpoken = mutableMapOf<String, Int>()
    private var lastSpokenAt: Long? = null

    fun reset() { closestSpoken.clear(); lastSpokenAt = null }

    fun shouldSpeak(key: String, meters: Double, now: Long, force: Boolean = false, speedMps: Double = 0.0): Boolean {
        if (!meters.isFinite() || meters < 0) return false
        val speed = if (speedMps.isFinite()) speedMps.coerceIn(0.0, 55.0) else 0.0
        val phase = when {
            meters <= 55.0 -> 0
            meters <= maxOf(300.0, speed * 10.0) -> 1
            meters <= maxOf(805.0, speed * 25.0) -> 2
            meters <= maxOf(1609.0, speed * 45.0) -> 3
            force -> 4 // Route preview does not consume the later advance reminder.
            else -> return false
        }
        if ((closestSpoken[key] ?: 5) <= phase) return false
        val gap = lastSpokenAt?.let { now - it }
        // An immediate cue for a closely following exit/turn must not be lost to the long cooldown.
        if (gap != null && gap < if (phase == 0) 2_000L else 8_000L) return false
        closestSpoken[key] = phase
        lastSpokenAt = now
        return true
    }
}
