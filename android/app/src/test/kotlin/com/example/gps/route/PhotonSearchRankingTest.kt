package com.example.gps.route

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotonSearchRankingTest {
    private val client = PhotonSearchClient()

    @Test
    fun exactNearbyBusinessBeatsSameBrandInAnotherTown() {
        val results = client.rankSuggestions(
            query = "Burger King",
            suggestions = listOf(
                PhotonSearchClient.Suggestion("Burger King", "New Haven, Connecticut", 41.3083, -72.9279),
                PhotonSearchClient.Suggestion("Burger King", "Hartford, Connecticut", 41.7658, -72.6734),
            ),
            biasLat = 41.76,
            biasLon = -72.68,
        )
        assertEquals("Hartford, Connecticut", results.first().subtitle)
    }

    @Test
    fun strongNameMatchBeatsNearbyWeakTextMatch() {
        val results = client.rankSuggestions(
            query = "City Fish Market",
            suggestions = listOf(
                PhotonSearchClient.Suggestion("Fish Market", "Nearby", 41.7601, -72.6801),
                PhotonSearchClient.Suggestion("City Fish Market", "A few miles away", 41.78, -72.70),
            ),
            biasLat = 41.76,
            biasLon = -72.68,
        )
        assertEquals("City Fish Market", results.first().label)
    }
}
