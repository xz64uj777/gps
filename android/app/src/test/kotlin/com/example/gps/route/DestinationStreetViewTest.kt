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
    @Test fun keylessModeWaitsUntilArrivalThenOpensExternalStreetView() {
        assertEquals(
            DestinationStreetView.DeliveryMode.WAIT_FOR_ARRIVAL,
            DestinationStreetView.deliveryMode(enabled = true, hasStaticApiKey = false, arrived = false),
        )
        assertEquals(
            DestinationStreetView.DeliveryMode.OPEN_EXTERNAL,
            DestinationStreetView.deliveryMode(enabled = true, hasStaticApiKey = false, arrived = true),
        )
    }

    @Test fun staticApiKeyKeepsInlinePhotoModeAndDisabledStaysOff() {
        assertEquals(
            DestinationStreetView.DeliveryMode.INLINE_PHOTO,
            DestinationStreetView.deliveryMode(enabled = true, hasStaticApiKey = true, arrived = false),
        )
        assertEquals(
            DestinationStreetView.DeliveryMode.DISABLED,
            DestinationStreetView.deliveryMode(enabled = false, hasStaticApiKey = true, arrived = true),
        )
    }

}
