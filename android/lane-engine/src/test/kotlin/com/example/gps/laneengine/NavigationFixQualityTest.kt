package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationFixQualityTest {
    @Test fun rejectsCoarseStartupAndInvalidFixes() {
        for (accuracy in listOf(null, 600.0, 122.4, 25.1, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(NavigationFixQuality.isUsable(1000, 1500, accuracy))
        }
    }
    @Test fun acceptsFreshRoadFixButRejectsStaleAndFuture() {
        assertTrue(NavigationFixQuality.isUsable(1000, 1500, 4.98))
        assertTrue(NavigationFixQuality.isUsable(1000, 1500, 25.0))
        assertFalse(NavigationFixQuality.isUsable(1000, 9091, 4.98))
        assertFalse(NavigationFixQuality.isUsable(2000, 1500, 4.98))
    }
}
