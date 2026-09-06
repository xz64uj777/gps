package com.example.gps.laneengine

import kotlin.test.*

class LaneEngineTest {
    @Test
    fun matcherPrefersClosestLane() {
        val lanes = listOf(
            Lane("L1", "S", 0, listOf(GeoPoint(40.0, -74.00010), GeoPoint(40.001, -74.00010))),
            Lane("L2", "S", 1, listOf(GeoPoint(40.0, -74.00005), GeoPoint(40.001, -74.00005))),
            Lane("L3", "S", 2, listOf(GeoPoint(40.0, -74.00000), GeoPoint(40.001, -74.00000))),
        )
        val e = LaneMatcher().update(
            Observation(1, GeoPoint(40.0005, -74.00000), 2.0, 20.0, 0.0),
            lanes,
        )
        assertEquals("L3", e.mostLikelyLaneId)
    }

    @Test
    fun poorGnssAccuracyNeverClaimsExactLaneEvenWithOneCandidate() {
        val lane = Lane(
            "L1",
            "S",
            0,
            listOf(GeoPoint(40.0, -74.0), GeoPoint(40.001, -74.0)),
        )
        val estimate = LaneMatcher().update(
            Observation(
                timestampMillis = 1,
                position = GeoPoint(40.0005, -74.0),
                horizontalAccuracyMeters = 37.7,
                speedMps = 20.0,
                bearingDegrees = 0.0,
            ),
            listOf(lane),
        )
        assertEquals("L1", estimate.mostLikelyLaneId)
        assertFalse(estimate.claimExactLane)
    }

    @Test
    fun plannerFindsCheapestRoute() {
        val p = LanePlanner().plan(
            setOf("L4"),
            setOf("EXIT"),
            listOf(
                LaneConnection("L4", "L3", cost = 2.0),
                LaneConnection("L3", "L2", cost = 2.0),
                LaneConnection("L2", "EXIT", cost = 1.0),
                LaneConnection("L4", "BAD", cost = 1.0),
                LaneConnection("BAD", "EXIT", cost = 10.0),
            ),
        )
        assertEquals(listOf("L4", "L3", "L2", "EXIT"), p?.laneIds)
    }
}
