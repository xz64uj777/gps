package com.example.gps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldedLaneUiTest {
    @Test
    fun foldedLaneOverlayStaysHiddenDuringOrdinaryDriving() {
        assertFalse(
            shouldShowCompactLaneOverlay(
                laneCount = 4,
                targetLanes = emptySet(),
                maneuver = "Continue straight",
                maneuverDistanceMeters = 250.0,
                arrived = false,
            ),
        )
    }

    @Test
    fun foldedLaneOverlayAppearsForMappedLaneChangeApproach() {
        assertTrue(
            shouldShowCompactLaneOverlay(
                laneCount = 4,
                targetLanes = setOf(3, 4),
                maneuver = "Take exit right",
                maneuverDistanceMeters = 1_200.0,
                arrived = false,
            ),
        )
    }

    @Test
    fun foldedLaneOverlayAppearsForMultiLaneTurnEvenWithoutTurnLaneTags() {
        assertTrue(
            shouldShowCompactLaneOverlay(
                laneCount = 3,
                targetLanes = emptySet(),
                maneuver = "Turn left",
                maneuverDistanceMeters = 500.0,
                arrived = false,
            ),
        )
    }

    @Test
    fun foldedLaneOverlayDoesNotConsumeMapWhenNotActionable() {
        assertFalse(
            shouldShowCompactLaneOverlay(
                laneCount = 1,
                targetLanes = setOf(1),
                maneuver = "Turn right",
                maneuverDistanceMeters = 200.0,
                arrived = false,
            ),
        )
        assertFalse(
            shouldShowCompactLaneOverlay(
                laneCount = 4,
                targetLanes = setOf(4),
                maneuver = "Take exit right",
                maneuverDistanceMeters = 2_000.0,
                arrived = false,
            ),
        )
        assertFalse(
            shouldShowCompactLaneOverlay(
                laneCount = 4,
                targetLanes = setOf(4),
                maneuver = "Arrived",
                maneuverDistanceMeters = 0.0,
                arrived = true,
            ),
        )
    }
}
