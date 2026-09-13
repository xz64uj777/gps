package com.example.gps.laneengine

/** Two cues per maneuver, with history retained across GPS progress jitter. */
class VoicePromptGate {
    private val closestSpoken = mutableMapOf<String, Int>()
    private var lastSpokenAt: Long? = null

    fun reset() { closestSpoken.clear(); lastSpokenAt = null }

    fun shouldSpeak(key: String, meters: Double, now: Long, force: Boolean = false): Boolean {
        if (!meters.isFinite() || meters < 0) return false
        val phase = if (meters <= 55.0) 0 else 1
        if (!force && meters > 805.0) return false
        if ((closestSpoken[key] ?: 2) <= phase) return false
        val gap = lastSpokenAt?.let { now - it }
        if (gap != null && gap < 8_000L) return false
        closestSpoken[key] = phase
        lastSpokenAt = now
        return true
    }
}
