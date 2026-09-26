package com.example.gps.laneengine

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProgressTrackerTest {
    private val tracker = RouteProgressTracker()
    private val origin = GeoPoint(41.75, -72.66)

    @Test
    fun progressNeverMovesBackward() {
        val route = (0..12).map { eastPoint(it * 20.0) }
        val result = tracker.match(
            position = eastPoint(72.0),
            geometry = route,
            previousIndex = 6,
        )

        assertEquals(6, result.routeIndex)
    }

    @Test
    fun progressAdvancesNormally() {
        val route = (0..12).map { eastPoint(it * 20.0) }
        val result = tracker.match(
            position = eastPoint(132.0),
            geometry = route,
            previousIndex = 4,
        )

        assertTrue(result.routeIndex >= 6)
        assertTrue(result.crossTrackMeters < 2.0)
    }

    @Test
    fun localForwardCorridorIgnoresUnrelatedDistantFutureLoop() {
        val route = mutableListOf<GeoPoint>()
        for (i in 0..90) route += eastPoint(i * 20.0)
        // A later loop returns close to the start, but it is beyond the 1.6 km
        // forward search horizon and must not steal progress from the local road.
        for (i in 90 downTo 0) route += northOffset(eastPoint(i * 20.0), 8.0)

        val result = tracker.match(
            position = eastPoint(220.0),
            geometry = route,
            previousIndex = 8,
        )

        assertTrue(result.routeIndex in 10..13)
        assertTrue(result.crossTrackMeters < 3.0)
    }


    @Test
    fun longSegmentReportsFractionalProjection() {
        val route = listOf(eastPoint(0.0), eastPoint(2_000.0))
        val result = tracker.match(
            position = eastPoint(500.0),
            geometry = route,
            previousIndex = 0,
        )

        assertEquals(0, result.segmentIndex)
        assertTrue(result.segmentFraction in 0.20..0.30)
        assertTrue(result.crossTrackMeters < 2.0)
        assertTrue(kotlin.math.abs(result.projectedPoint.lon - eastPoint(500.0).lon) < 0.00002)
    }

    @Test
    fun crossTrackDistanceReflectsLeavingRoute() {
        val route = (0..12).map { eastPoint(it * 20.0) }
        val result = tracker.match(
            position = northOffset(eastPoint(120.0), 90.0),
            geometry = route,
            previousIndex = 4,
        )

        assertTrue(result.crossTrackMeters > 80.0)
    }

    @Test fun startRecoveryFindsCarBeyondInitialCorridor() {
        val route = (0..250).map { eastPoint(it * 20.0) }
        val old = tracker.match(eastPoint(3_000.0), route)
        val recovered = tracker.match(eastPoint(3_000.0), route, allowStartRecovery = true)
        assertTrue(old.crossTrackMeters > 1_000.0)
        assertTrue(recovered.crossTrackMeters < 2.0)
        assertEquals(150, recovered.routeIndex)
    }

    @Test fun startRecoveryRejectsParallelOrDistantRoad() {
        val route = (0..250).map { eastPoint(it * 20.0) }
        val result = tracker.match(northOffset(eastPoint(3_000.0), 40.0), route,
            allowStartRecovery = true)
        assertTrue(result.crossTrackMeters > 1_000.0)
    }

    @Test fun startRecoveryRejectsTwoDifferentNearbyRoutePasses() {
        val route = (0..250).map { eastPoint(it * 20.0) } +
            (250 downTo 0).map { northOffset(eastPoint(it * 20.0), 8.0) }
        val result = tracker.match(eastPoint(3_000.0), route, allowStartRecovery = true)
        assertTrue(result.crossTrackMeters > 1_000.0)
    }

    @Test fun establishedProgressDoesNotJumpOutsideCorridor() {
        val route = (0..250).map { eastPoint(it * 20.0) }
        val result = tracker.match(eastPoint(3_000.0), route, previousIndex = 5,
            allowStartRecovery = true)
        assertTrue(result.crossTrackMeters > 1_000.0)
    }

    private fun eastPoint(eastMeters: Double): GeoPoint {
        val lonOffset = eastMeters / (111_320.0 * cos(origin.lat * Math.PI / 180.0))
        return GeoPoint(origin.lat, origin.lon + lonOffset)
    }

    private fun northOffset(point: GeoPoint, northMeters: Double): GeoPoint =
        GeoPoint(point.lat + northMeters / 111_320.0, point.lon)
}
