package com.example.gps.route

import com.example.gps.location.DriveSessionRuntime
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    ) {
        fun fullLabel(): String = listOf(label, subtitle)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }

    fun search(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        limit: Int = 5,
    ): List<Suggestion> {
        val q = query.trim()
        if (q.length < MIN_QUERY_CHARS) return emptyList()

        val latest = DriveSessionRuntime.latest()
        val effectiveBiasLat = biasLat ?: latest?.latitude
        val effectiveBiasLon = biasLon ?: latest?.longitude

        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
        val bias = if (effectiveBiasLat != null && effectiveBiasLon != null) {
            String.format(Locale.US, "&lat=%.6f&lon=%.6f&zoom=12", effectiveBiasLat, effectiveBiasLon)
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
            val suggestions = buildList {
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
            DestinationSuggestionRuntime.remember(suggestions)
            return suggestions
        } finally {
            connection.disconnect()
        }
    }

    private fun buildLabel(p: JSONObject, fallback: String): String {
        val name = p.optString("name").trim()
        val house = p.optString("housenumber").trim()
        val street = p.optString("street").trim()
        return when {
            // For named businesses, keep the business name visible. The street is
            // still present in the subtitle/search result metadata when Photon has it.
            name.isNotBlank() -> name
            house.isNotBlank() && street.isNotBlank() -> "$house $street"
            street.isNotBlank() -> street
            else -> fallback
        }
    }

    private fun buildSubtitle(p: JSONObject): String =
        listOf(
            listOf(p.optString("housenumber"), p.optString("street"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" "),
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

/**
 * Short-lived bridge between the autocomplete result the driver tapped and the
 * routing request. Without this, a chain-store label such as "AT&T" gets
 * geocoded a second time and can resolve to a different branch.
 */
object DestinationSuggestionRuntime {
    data class ExactDestination(
        val label: String,
        val lat: Double,
        val lon: Double,
        val rememberedAtMillis: Long,
    )

    private val entries = ConcurrentHashMap<String, ExactDestination>()

    fun remember(suggestions: List<PhotonSearchClient.Suggestion>) {
        val now = System.currentTimeMillis()
        prune(now)
        suggestions.forEach { suggestion ->
            entries[normalize(suggestion.fullLabel())] = ExactDestination(
                label = suggestion.fullLabel(),
                lat = suggestion.lat,
                lon = suggestion.lon,
                rememberedAtMillis = now,
            )
        }
    }

    fun resolve(label: String): ExactDestination? {
        val now = System.currentTimeMillis()
        prune(now)
        return entries[normalize(label)]
    }

    private fun prune(now: Long) {
        entries.entries.removeIf { now - it.value.rememberedAtMillis > MAX_AGE_MS }
        if (entries.size > MAX_ENTRIES) {
            entries.entries
                .sortedBy { it.value.rememberedAtMillis }
                .take(entries.size - MAX_ENTRIES)
                .forEach { entries.remove(it.key) }
        }
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.US)

    private const val MAX_AGE_MS = 15 * 60 * 1000L
    private const val MAX_ENTRIES = 40
}
