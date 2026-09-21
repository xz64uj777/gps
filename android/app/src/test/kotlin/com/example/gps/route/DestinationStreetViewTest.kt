package com.example.gps.route

import org.junit.Assert.*
import org.junit.Test

class DestinationStreetViewTest {
    @Test fun arrivalCardUsesRemainingDistance() {
        assertFalse(DestinationStreetView.nearArrival(251.0))
        assertTrue(DestinationStreetView.nearArrival(250.0))
        assertTrue(DestinationStreetView.nearArrival(0.0))
        assertFalse(DestinationStreetView.nearArrival(Double.NaN))
    }
    @Test fun linkRequestsPanoramaAtDestination() {
        assertEquals("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=41.0000000%2C-72.0000000", DestinationStreetView.url(41.0, -72.0))
    }
}
