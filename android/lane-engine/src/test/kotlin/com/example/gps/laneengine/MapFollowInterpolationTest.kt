package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertEquals

class MapFollowInterpolationTest {
    @Test fun northCrossingDoesNotSpinAround() {
        assertEquals(0.0, MapFollowInterpolation.bearing(359.0, 1.0, 0.5), 0.001)
        assertEquals(0.0, MapFollowInterpolation.bearing(1.0, 359.0, 0.5), 0.001)
    }
    @Test fun interpolationStopsAtObservedFixAndNeverPredictsBeyondIt() {
        assertEquals(20.0, MapFollowInterpolation.linear(10.0, 20.0, 2.0))
        assertEquals(10.0, MapFollowInterpolation.linear(10.0, 20.0, -1.0))
        assertEquals(15.0, MapFollowInterpolation.linear(10.0, 20.0, 0.5))
    }
}
