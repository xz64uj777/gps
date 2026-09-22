package com.example.gps.route

import org.junit.Assert.*
import org.junit.Test

class RerouteOriginTest {
    @Test fun movingCourseConstrainsOnlyOrigin() {
        val course = RerouteOrigin.usableBearing(174.6, 13.19, 9.97)
        assertEquals("&bearings=175,45;", RerouteOrigin.bearingQuery(course))
    }
    @Test fun stationaryOrWalkingDoesNotConstrainRoute() {
        assertNull(RerouteOrigin.usableBearing(180.0, 0.0, 5.0))
        assertNull(RerouteOrigin.usableBearing(180.0, 2.9, 5.0))
    }
    @Test fun poorOrMissingAccuracyDoesNotConstrainRoute() {
        for (accuracy in listOf(null, 0.0, -1.0, 26.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(RerouteOrigin.usableBearing(180.0, 15.0, accuracy))
        }
    }
    @Test fun missingOrInvalidMotionDoesNotInventHeading() {
        for (bearing in listOf(null, -1.0, 360.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(RerouteOrigin.usableBearing(bearing, 15.0, 10.0))
            assertEquals("", RerouteOrigin.bearingQuery(bearing))
        }
        assertNull(RerouteOrigin.usableBearing(180.0, null, 10.0))
        assertNull(RerouteOrigin.usableBearing(180.0, Double.NaN, 10.0))
    }
    @Test fun northWrapsAndDestinationRemainsUnrestricted() {
        assertEquals("&bearings=0,45;", RerouteOrigin.bearingQuery(359.9))
        assertEquals("&bearings=0,45;", RerouteOrigin.bearingQuery(0.0))
        assertEquals("", RerouteOrigin.bearingQuery(null))
    }
}
