package com.example.gps.route

import org.junit.Assert.*
import org.junit.Test

class RouteCheckpointTest {
    private fun route() = OpenRouteClient.RouteSummary(
        destinationName = "Park St, Hartford", destinationLat = 41.76, destinationLon = -72.68,
        distanceMeters = 1200.0, durationSeconds = 180.0, nextManeuver = "Turn right",
        nextRoad = "Park St", nextManeuverDistanceMeters = 300.0,
        geometry = listOf(OpenRouteClient.RoutePoint(41.74, -72.68), OpenRouteClient.RoutePoint(41.75, -72.68), OpenRouteClient.RoutePoint(41.76, -72.68)),
        maneuvers = listOf(OpenRouteClient.RouteManeuver("Turn right", "Park St", 41.76, -72.68, 2)),
        totalRouteMeters = 2400.0, totalRouteSeconds = 360.0, progressIndex = 1,
        routeStartedAtMillis = 123456789L, offRouteDistanceMeters = 4.0,
    )

    @Test fun recoveryPreservesExactDestinationGeometryAndProgress() {
        val original = route()
        assertEquals(original, RouteCheckpoint.decode(RouteCheckpoint.encode(original)))
    }

    @Test fun arrivedRouteRemainsArrivedAfterRecovery() {
        val original = route().copy(arrived = true, distanceMeters = 0.0, progressIndex = 2)
        assertEquals(original, RouteCheckpoint.decode(RouteCheckpoint.encode(original)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidProgressCannotBeRecovered() {
        RouteCheckpoint.decode(RouteCheckpoint.encode(route().copy(progressIndex = 99)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingGeometryCannotLookLikeActiveNavigation() {
        RouteCheckpoint.decode(RouteCheckpoint.encode(route().copy(geometry = emptyList())))
    }

    @Test fun lifecycleTelemetryDistinguishesRecoveryAndStop() {
        NavigationTelemetryRuntime.resetForDrive()
        val original = route()
        NavigationTelemetryRuntime.publish(original, "ROUTE_RECOVERED", 1, 0)
        assertEquals("ROUTE_RECOVERED", NavigationTelemetryRuntime.snapshot().event)
        NavigationTelemetryRuntime.lifecycle("NAVIGATION_STOPPED")
        assertEquals("NAVIGATION_STOPPED", NavigationTelemetryRuntime.snapshot().event)
        assertEquals(original.destinationName, NavigationTelemetryRuntime.snapshot().destination)
    }
}
