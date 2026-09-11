package com.example.gps.route

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Search-as-you-type destination suggestions backed by Photon/OpenStreetMap.
 * Kept separate from final routing so the provider can be swapped/self-hosted later.
 */
class PhotonSearchClient {
    data class Suggestion(
        val label: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double,
    )

    fun search(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        limit: Int = 5,
    ): List<Suggestion> {
        val q = query.trim()
        if (q.length < MIN_QUERY_CHARS) return emptyList()

        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
        val bias = if (biasLat != null && biasLon != null) {
            String.format(Locale.US, "&lat=%.6f&lon=%.6f&zoom=12", biasLat, biasLon)
        } else {
            ""
        }
        val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=${limit.coerceIn(1, 8)}&lang=en$bias")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/geo+json, application/json")
            setRequestProperty("User-Agent", "LaneGPS-Android-Development/0.4 (+https://github.com/xz64uj777/gps)")
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            return buildList {
                for (index in 0 until features.length()) {
                    val feature = features.optJSONObject(index) ?: continue
                    val geometry = feature.optJSONObject("geometry") ?: continue
                    val coords = geometry.optJSONArray("coordinates") ?: continue
                    if (coords.length() < 2) continue
                    val lon = coords.optDouble(0, Double.NaN)
                    val lat = coords.optDouble(1, Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    val p = feature.optJSONObject("properties") ?: JSONObject()
                    val label = buildLabel(p, q)
                    val subtitle = buildSubtitle(p)
                    if (none { it.label.equals(label, ignoreCase = true) && it.subtitle.equals(subtitle, ignoreCase = true) }) {
                        add(Suggestion(label, subtitle, lat, lon))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildLabel(p: JSONObject, fallback: String): String {
        val name = p.optString("name").trim()
        val house = p.optString("housenumber").trim()
        val street = p.optString("street").trim()
        return when {
            house.isNotBlank() && street.isNotBlank() -> "$house $street"
            name.isNotBlank() -> name
            street.isNotBlank() -> street
            else -> fallback
        }
    }

    private fun buildSubtitle(p: JSONObject): String =
        listOf(
            p.optString("city").ifBlank { p.optString("district") },
            p.optString("state"),
            p.optString("postcode"),
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")

    private companion object {
        const val MIN_QUERY_CHARS = 3
    }
}
