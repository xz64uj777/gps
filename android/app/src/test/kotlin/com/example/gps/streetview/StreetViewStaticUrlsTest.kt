package com.example.gps.streetview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetViewStaticUrlsTest {
    @Test
    fun metadataUsesDestinationCoordinates() {
        val url = StreetViewStaticUrls.metadataUrl("abc 123", 41.12345678, -72.98765432)
        assertTrue(url.contains("location=41.1234568,-72.9876543"))
        assertTrue(url.contains("key=abc+123"))
    }

    @Test
    fun imageRequestIsOneStaticStillFacingDestination() {
        val url = StreetViewStaticUrls.imageUrl("key", "pano id", 91.24)
        assertTrue(url.contains("size=640x360"))
        assertTrue(url.contains("pano=pano+id"))
        assertTrue(url.contains("heading=91.2"))
        assertTrue(url.contains("return_error_code=true"))
    }

    @Test
    fun bearingPointsEastWhenDestinationIsEastOfPanorama() {
        val bearing = StreetViewStaticUrls.bearingDegrees(41.0, -72.0, 41.0, -71.99)
        assertEquals(90.0, bearing, 1.0)
    }
}
