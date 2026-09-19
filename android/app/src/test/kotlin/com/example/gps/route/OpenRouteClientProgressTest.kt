package com.example.gps.route

import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouteClientProgressTest {
    @Test
    fun remainingDistanceFallsInsideOneLongGeometrySegment() {
        val geometry = listOf(
            OpenRouteClient.RoutePoint(41.0000, -72.0000),
            OpenRouteClient.RoutePoint(41.0900, -72.0000),
        )
        val route = OpenRouteClient.RouteSummary(
            destinationName = "Test",
            destinationLat = geometry.last().lat,
            destinationLon = geometry.last().lon,
            distanceMeters = 10_000.0,
            durationSeconds = 1_000.0,
            nextManeuver = "Continue",
            nextRoad = "",
            nextManeuverDistanceMeters = 10_000.0,
            geometry = geometry,
            maneuvers = emptyList(),
            totalRouteMeters = 10_000.0,
            totalRouteSeconds = 1_000.0,
            progressIndex = 0,
            routeStartedAtMillis = System.currentTimeMillis() - 60_000L,
        )

        val progressed = OpenRouteClient().updateProgress(
            route = route,
            currentLat = 41.0180,
            currentLon = -72.0000,
        )

        assertTrue(progressed.distanceMeters < 9_000.0)
        assertTrue(progressed.distanceMeters > 7_000.0)
        assertTrue(progressed.nextManeuverDistanceMeters < 9_000.0)
    }
}
