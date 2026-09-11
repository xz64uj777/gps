package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertEquals

class RerouteStartupGuardTest {
    @Test
    fun suppressesLargeCrossTrackSpikeImmediatelyAfterRouteStarts() {
        val effective = RerouteStartupGuard.effectiveOffRouteMeters(
            rawOffRouteMeters = 120.0,
            routeAgeMillis = 5_000L,
            routeProgressMeters = 30.0,
        )
        assertEquals(0.0, effective)
    }

    @Test
    fun releasesAfterEnoughTime() {
        val effective = RerouteStartupGuard.effectiveOffRouteMeters(
            rawOffRouteMeters = 120.0,
            routeAgeMillis = 25_000L,
            routeProgressMeters = 30.0,
        )
        assertEquals(120.0, effective)
    }

    @Test
    fun releasesEarlyAfterMeaningfulForwardProgress() {
        val effective = RerouteStartupGuard.effectiveOffRouteMeters(
            rawOffRouteMeters = 120.0,
            routeAgeMillis = 5_000L,
            routeProgressMeters = 150.0,
        )
        assertEquals(120.0, effective)
    }
}
