package com.example.gps.route

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteLaneGuidanceTest {
    private val lanes = listOf(
        RouteLane(listOf("slight left"), false),
        RouteLane(listOf("straight"), true),
        RouteLane(listOf("straight", "slight right"), true),
        RouteLane(listOf("slight right"), false),
    )

    @Test fun middleTwoLanesSurviveParsingWithoutChoosingOuterLane() {
        val parsed = RouteLaneGuidance.decode(RouteLaneGuidance.encode(lanes))
        assertEquals(lanes, parsed)
        assertEquals(listOf(2, 3), parsed.mapIndexedNotNull { i, lane -> (i + 1).takeIf { lane.valid } })
    }

    @Test fun absentOrIncompleteDataDoesNotInventLanes() {
        assertTrue(RouteLaneGuidance.decode(null).isEmpty())
        assertTrue(RouteLaneGuidance.decode(JSONArray("[{\"indications\":[\"right\"]}]")).isEmpty())
    }

    @Test fun laterIntersectionIsNotUsedForThisTurn() {
        val step = JSONObject("""{"maneuver":{"location":[-72.0,41.0]},"intersections":[{"location":[-72.01,41.0]}]}""")
        step.getJSONArray("intersections").getJSONObject(0).put("lanes", RouteLaneGuidance.encode(lanes))
        assertTrue(RouteLaneGuidance.forStep(step).isEmpty())
        step.getJSONArray("intersections").getJSONObject(0).put("location", JSONArray("[-72.0,41.0]"))
        assertEquals(lanes, RouteLaneGuidance.forStep(step))
    }

    @Test fun highwayAndCityApproachesAppearEarlyThenClearOnArrival() {
        assertTrue(RouteLaneGuidance.visible(1800.0, 30f, false))
        assertTrue(RouteLaneGuidance.visible(600.0, 8f, false))
        assertFalse(RouteLaneGuidance.visible(3000.0, 30f, false))
        assertFalse(RouteLaneGuidance.visible(0.0, 0f, true))
    }

    @Test fun currentCarRequiresConfirmedMatchingNearbyLayout() {
        val hints = RouteLaneGuidance.hints(lanes)
        assertEquals(2, RouteLaneGuidance.currentLane(lanes, 4, hints, 2, true, 100.0))
        assertNull(RouteLaneGuidance.currentLane(lanes, 4, hints, 2, false, 100.0))
        assertNull(RouteLaneGuidance.currentLane(lanes, 4, hints, 2, true, 1000.0))
        assertNull(RouteLaneGuidance.currentLane(lanes, 4, listOf("through", "through", "through", "right"), 2, true, 100.0))
    }

    @Test fun resumePreservesLaneChoicesForNextAndLaterManeuvers() {
        val route = OpenRouteClient.RouteSummary("Test", 41.0, -72.0, 1000.0, 60.0, "Keep right", "I-84", 500.0,
            geometry = listOf(OpenRouteClient.RoutePoint(40.99, -72.0), OpenRouteClient.RoutePoint(41.0, -72.0)),
            maneuvers = listOf(OpenRouteClient.RouteManeuver("Keep right", "I-84", 41.0, -72.0, 1, lanes)), nextLanes = lanes)
        assertEquals(route, RouteCheckpoint.decode(RouteCheckpoint.encode(route)))
    }

    @Test fun progressSelectsLanesWithTheManeuver() {
        val route = OpenRouteClient.RouteSummary("Test", 41.02, -72.0, 2200.0, 120.0, "Old turn", "Old road", 0.0,
            geometry = listOf(OpenRouteClient.RoutePoint(41.0, -72.0), OpenRouteClient.RoutePoint(41.01, -72.0), OpenRouteClient.RoutePoint(41.02, -72.0)),
            maneuvers = listOf(OpenRouteClient.RouteManeuver("Turn right", "Park St", 41.01, -72.0, 1, lanes)))
        val progressed = OpenRouteClient().updateProgress(route, 41.001, -72.0)
        assertEquals("Turn right", progressed.nextManeuver)
        assertEquals(lanes, progressed.nextLanes)
        assertTrue(OpenRouteClient().updateProgress(progressed, 41.02, -72.0).nextLanes.isEmpty())
    }
    @Test fun foldedPanelOnlyTreatsRealLaneChoicesAsActionable() {
        val allStraight = listOf(
            RouteLane(listOf("straight"), true),
            RouteLane(listOf("straight"), true),
            RouteLane(listOf("straight"), true),
        )
        val avoidRightLane = listOf(
            RouteLane(listOf("straight"), true),
            RouteLane(listOf("straight"), true),
            RouteLane(listOf("slight right"), false),
        )
        assertFalse(RouteLaneGuidance.actionable(allStraight, "Continue on route"))
        assertTrue(RouteLaneGuidance.actionable(allStraight, "Turn right"))
        assertTrue(RouteLaneGuidance.actionable(avoidRightLane, "Continue on route"))
        assertFalse(RouteLaneGuidance.actionable(emptyList(), "Turn right"))
    }

}
