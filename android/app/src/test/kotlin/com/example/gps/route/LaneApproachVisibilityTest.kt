package com.example.gps.route

import org.junit.Assert.*
import org.junit.Test

class LaneApproachVisibilityTest {
    @Test fun slowingDownDoesNotCollapseAnApproachAlreadyShown() {
        val visibility = LaneApproachVisibility()
        assertFalse(visibility.update("route1:turn1", false))
        assertTrue(visibility.update("route1:turn1", true))
        repeat(30) { assertTrue(visibility.update("route1:turn1", it % 2 == 0)) }
    }

    @Test fun nextManeuverAndRerouteCannotInheritVisibleState() {
        val visibility = LaneApproachVisibility()
        assertTrue(visibility.update("route1:turn1", true))
        assertFalse(visibility.update("route1:turn2", false))
        assertTrue(visibility.update("route1:turn2", true))
        assertFalse(visibility.update("route2:turn2", false))
        assertTrue(visibility.update("route2:turn2", true))
        assertFalse(visibility.update(null, true))
    }
}
