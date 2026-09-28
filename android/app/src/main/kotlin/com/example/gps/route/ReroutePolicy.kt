package com.example.gps.route

/**
 * Conservative reroute gate.
 *
 * At highway speed LaneGPS waits longer before abandoning the current route.
 * This avoids repeatedly nagging a driver who deliberately stays on the highway
 * instead of taking a marginal surface-road shortcut. It still reroutes after a
 * sustained, clearly real deviation.
 */
object ReroutePolicy {
    private const val HIGHWAY_SPEED_MPS = 20.0

    fun distanceThresholdMeters(speedMps: Double): Double =
        if (speedMps >= HIGHWAY_SPEED_MPS) 110.0 else 75.0

    fun fixesRequired(speedMps: Double): Int =
        if (speedMps >= HIGHWAY_SPEED_MPS) 8 else 5

    fun cooldownMillis(speedMps: Double): Long =
        if (speedMps >= HIGHWAY_SPEED_MPS) 60_000L else 30_000L
}
