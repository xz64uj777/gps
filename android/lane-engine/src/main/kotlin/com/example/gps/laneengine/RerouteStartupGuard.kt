package com.example.gps.laneengine

/**
 * Prevents route-start GPS/map matching noise from causing an immediate reroute.
 * The guard is intentionally short-lived: it releases as soon as either enough
 * time has passed or the vehicle has made meaningful progress along the route.
 */
object RerouteStartupGuard {
    fun effectiveOffRouteMeters(
        rawOffRouteMeters: Double,
        routeAgeMillis: Long,
        routeProgressMeters: Double,
    ): Double {
        val startupWindow =
            routeAgeMillis in 0 until STARTUP_GRACE_MS &&
                routeProgressMeters < STARTUP_PROGRESS_RELEASE_M
        return if (startupWindow) 0.0 else rawOffRouteMeters
    }

    private const val STARTUP_GRACE_MS = 20_000L
    private const val STARTUP_PROGRESS_RELEASE_M = 120.0
}
