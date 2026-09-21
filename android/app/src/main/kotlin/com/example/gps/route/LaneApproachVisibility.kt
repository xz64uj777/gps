package com.example.gps.route

/**
 * Stabilizes the lane panel without pinning it on for an entire maneuver.
 * A short grace period prevents flicker when speed/GPS briefly crosses the
 * approach threshold, but prolonged slow/stopped traffic can give the map
 * its space back until the maneuver becomes actionable again.
 */
internal class LaneApproachVisibility(
    private val graceMillis: Long = 10_000L,
) {
    private var key: String? = null
    private var lastEligibleAtMillis = Long.MIN_VALUE

    fun update(maneuverKey: String?, eligible: Boolean, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (maneuverKey != key) {
            key = maneuverKey
            lastEligibleAtMillis = Long.MIN_VALUE
        }
        if (maneuverKey == null) return false
        if (eligible) {
            lastEligibleAtMillis = nowMillis
            return true
        }
        return lastEligibleAtMillis != Long.MIN_VALUE &&
            nowMillis >= lastEligibleAtMillis &&
            nowMillis - lastEligibleAtMillis <= graceMillis
    }
}
