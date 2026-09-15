package com.example.gps.laneengine

/** Rendering only. Never used as an input to route or lane matching. */
object MapFollowInterpolation {
    fun linear(from: Double, to: Double, fraction: Double): Double =
        from + (to - from) * fraction.coerceIn(0.0, 1.0)

    fun bearing(from: Double, to: Double, fraction: Double): Double {
        val delta = ((to - from + 540.0) % 360.0) - 180.0
        return (from + delta * fraction.coerceIn(0.0, 1.0) + 360.0) % 360.0
    }
}
