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
        val matcher = LaneMatcher()
        repeat(5) { index ->
            val estimate = matcher.update(
                Observation(
                    timestampMillis = index.toLong() + 1,
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
    }

    @Test
    fun inferredLowConfidenceLaneNeverClaimsExact() {
        val lane = Lane(
            "L1",
            "S",
            0,
            listOf(GeoPoint(40.0, -74.0), GeoPoint(40.001, -74.0)),
            sourceConfidence = 0.30,
        )
        val matcher = LaneMatcher()
        repeat(6) { index ->
            val estimate = matcher.update(
                Observation(index.toLong() + 1, GeoPoint(40.0005, -74.0), 2.0, 20.0, 0.0),
                listOf(lane),
            )
            assertEquals("L1", estimate.mostLikelyLaneId)
            assertFalse(estimate.claimExactLane)
        }
    }

    @Test
    fun exactLaneRequiresSeveralDistinctGoodFixes() {
        val lane = Lane(
            "L1",
            "S",
            0,
            listOf(GeoPoint(40.0, -74.0), GeoPoint(40.001, -74.0)),
        )
        val matcher = LaneMatcher()

        val first = matcher.update(
            Observation(1, GeoPoint(40.0005, -74.0), 2.0, 20.0, 0.0),
            listOf(lane),
        )
        val second = matcher.update(
            Observation(2, GeoPoint(40.0005, -74.0), 2.0, 20.0, 0.0),
            listOf(lane),
        )
        val third = matcher.update(
            Observation(3, GeoPoint(40.0005, -74.0), 2.0, 20.0, 0.0),
            listOf(lane),
        )

        assertFalse(first.claimExactLane)
        assertFalse(second.claimExactLane)
        assertTrue(third.claimExactLane)
    }

    @Test
    fun oneAdjacentNoiseFixDoesNotFlipLane() {
        val lanes = threeParallelLanes()
        val matcher = LaneMatcher()

        repeat(3) { index ->
            matcher.update(
                Observation(index.toLong() + 1, lanePoint(1), 2.0, 20.0, 0.0),
                lanes,
            )
        }

        val noisy = matcher.update(
            Observation(4, lanePoint(2), 2.0, 20.0, 0.0),
            lanes,
        )

        assertEquals("L2", noisy.mostLikelyLaneId)
        assertFalse(noisy.claimExactLane)
    }

    @Test
    fun repeatedSensorCallbacksWithSameGpsTimestampCannotConfirmLaneChange() {
        val lanes = threeParallelLanes()
        val matcher = LaneMatcher()

        repeat(3) { index ->
            matcher.update(
                Observation(index.toLong() + 1, lanePoint(1), 2.0, 20.0, 0.0),
                lanes,
            )
        }

        repeat(12) {
            val estimate = matcher.update(
                Observation(4, lanePoint(2), 2.0, 20.0, 0.0),
                lanes,
            )
            assertEquals("L2", estimate.mostLikelyLaneId)
        }
    }

    @Test
    fun threeDistinctAdjacentFixesConfirmLaneChange() {
        val lanes = threeParallelLanes()
        val matcher = LaneMatcher()

        repeat(3) { index ->
            matcher.update(
                Observation(index.toLong() + 1, lanePoint(1), 2.0, 20.0, 0.0),
                lanes,
            )
        }

        val first = matcher.update(Observation(4, lanePoint(2), 2.0, 20.0, 0.0), lanes)
        val second = matcher.update(Observation(5, lanePoint(2), 2.0, 20.0, 0.0), lanes)
        val third = matcher.update(Observation(6, lanePoint(2), 2.0, 20.0, 0.0), lanes)

        assertEquals("L2", first.mostLikelyLaneId)
        assertEquals("L2", second.mostLikelyLaneId)
        assertEquals("L3", third.mostLikelyLaneId)
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

    private fun threeParallelLanes(): List<Lane> = listOf(
        Lane("L1", "S", 0, listOf(GeoPoint(40.0, -74.00008), GeoPoint(40.001, -74.00008))),
        Lane("L2", "S", 1, listOf(GeoPoint(40.0, -74.00004), GeoPoint(40.001, -74.00004))),
        Lane("L3", "S", 2, listOf(GeoPoint(40.0, -74.00000), GeoPoint(40.001, -74.00000))),
    )

    private fun lanePoint(index: Int): GeoPoint = when (index) {
        0 -> GeoPoint(40.0005, -74.00008)
        1 -> GeoPoint(40.0005, -74.00004)
        else -> GeoPoint(40.0005, -74.00000)
    }
}
