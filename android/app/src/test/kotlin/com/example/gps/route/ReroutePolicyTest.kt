package com.example.gps.route

import org.junit.Assert.assertEquals
import org.junit.Test

class ReroutePolicyTest {
    @Test
    fun cityDrivingKeepsExistingGate() {
        assertEquals(75.0, ReroutePolicy.distanceThresholdMeters(12.0), 0.0)
        assertEquals(5, ReroutePolicy.fixesRequired(12.0))
        assertEquals(30_000L, ReroutePolicy.cooldownMillis(12.0))
    }

    @Test
    fun highwayDrivingRequiresSustainedDeviation() {
        assertEquals(110.0, ReroutePolicy.distanceThresholdMeters(30.0), 0.0)
        assertEquals(8, ReroutePolicy.fixesRequired(30.0))
        assertEquals(60_000L, ReroutePolicy.cooldownMillis(30.0))
    }
}
