package com.example.gps.traffic
import org.junit.Test
import org.junit.Assert.*
class TrafficTilesTest {
    @Test fun keyCannotInjectAdditionalQueryParameters() {
        val url = TrafficTiles.template("test&style=bad")
        assertTrue(url.endsWith("key=test%26style%3Dbad"))
        assertTrue(url.contains("relative0/{z}/{x}/{y}.png"))
    }
    @Test fun probeClampsPolesAndLongitudeEdges() {
        for (lat in listOf(-90.0, 90.0)) for (lon in listOf(-180.0,180.0)) {
            val parts = TrafficTiles.probeUrl("test", lat, lon).substringAfter("relative0/").substringBefore(".png").split('/')
            assertEquals(10, parts[0].toInt())
            assertTrue(parts[1].toInt() in 0..1023)
            assertTrue(parts[2].toInt() in 0..1023)
        }
    }
    @Test fun deniedAndLimitedServiceDoesNotClaimLiveTraffic() {
        assertTrue(TrafficTiles.failure(403).contains("rejected"))
        assertTrue(TrafficTiles.failure(429).contains("limit"))
        assertTrue(TrafficTiles.failure(500).contains("unavailable"))
    }
}
