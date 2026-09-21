package com.example.gps.route

import org.junit.Assert.*
import org.junit.Test

class LaneApproachVisibilityTest {
    @Test fun briefSpeedChangesDoNotFlickerVisibleApproach() {
        val visibility = LaneApproachVisibility(graceMillis = 10_000L)
        assertFalse(visibility.update("route1:turn1", false, 1_000L))
        assertTrue(visibility.update("route1:turn1", true, 2_000L))
        assertTrue(visibility.update("route1:turn1", false, 8_000L))
        assertTrue(visibility.update("route1:turn1", false, 12_000L))
    }

    @Test fun prolongedSlowTrafficCanReturnSpaceToMap() {
        val visibility = LaneApproachVisibility(graceMillis = 10_000L)
        assertTrue(visibility.update("route1:turn1", true, 2_000L))
        assertFalse(visibility.update("route1:turn1", false, 12_001L))
        assertTrue(visibility.update("route1:turn1", true, 20_000L))
    }

    @Test fun nextManeuverAndRerouteCannotInheritVisibleState() {
        val visibility = LaneApproachVisibility()
        assertTrue(visibility.update("route1:turn1", true, 1_000L))
        assertFalse(visibility.update("route1:turn2", false, 2_000L))
        assertTrue(visibility.update("route1:turn2", true, 3_000L))
        assertFalse(visibility.update("route2:turn2", false, 4_000L))
        assertTrue(visibility.update("route2:turn2", true, 5_000L))
        assertFalse(visibility.update(null, true, 6_000L))
    }
}
