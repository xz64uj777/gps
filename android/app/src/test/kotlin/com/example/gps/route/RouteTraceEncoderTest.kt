package com.example.gps.route

import org.junit.Assert.*
import org.junit.Test

class RouteTraceEncoderTest {
    private val route = listOf(
        OpenRouteClient.RoutePoint(1.0, 2.0),
        OpenRouteClient.RoutePoint(1.001, 2.002),
    )

    @Test fun progressDoesNotRepeatGeometryButKeepsRouteIdentity() {
        val encoder = RouteTraceEncoder()
        val first = encoder.next(route)
        assertEquals("1.0:2.0|1.001:2.002", first.geometry)
        assertEquals(64, first.routeId.length)
        assertEquals(RouteTraceEncoder.Trace(first.routeId), encoder.next(route))
        assertEquals(RouteTraceEncoder.Trace(first.routeId), encoder.next(route.toList()))
    }

    @Test fun rerouteWithSamePointCountGetsItsOwnPayload() {
        val encoder = RouteTraceEncoder()
        val first = encoder.next(route)
        val changed = encoder.next(listOf(route[0], OpenRouteClient.RoutePoint(1.002, 2.002)))
        assertNotEquals(first.routeId, changed.routeId)
        assertTrue(changed.geometry.isNotEmpty())
    }

    @Test fun newRecordingAndProcessRestartAreSelfContained() {
        val encoder = RouteTraceEncoder()
        val first = encoder.next(route)
        encoder.reset()
        assertEquals(first, encoder.next(route))
        assertEquals(first, RouteTraceEncoder().next(route))
    }

    @Test fun clearingRouteCannotAttachOldGeometryToFreeDrive() {
        val encoder = RouteTraceEncoder()
        val first = encoder.next(route)
        assertEquals(RouteTraceEncoder.Trace(), encoder.next(emptyList()))
        assertEquals(first, encoder.next(route))
    }
}
