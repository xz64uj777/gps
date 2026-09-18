package com.example.gps.route

import com.example.gps.location.DriveSessionRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Search-as-you-type destination suggestions backed by Photon/OpenStreetMap.
 * Nominatim is used as a conservative fallback because Photon can miss ordinary
 * street queries that Nominatim resolves correctly.
 */
class PhotonSearchClient {
    data class Suggestion(
        val label: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double,
    ) {
        fun fullLabel(): String {
            val cleanLabel = label.trim()
            val subtitleParts = subtitle.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableList()
            if (subtitleParts.firstOrNull()?.equals(cleanLabel, ignoreCase = true) == true) {
                subtitleParts.removeAt(0)
            }
            return (listOf(cleanLabel) + subtitleParts)
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }
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
        val cappedLimit = limit.coerceIn(1, 8)

        val queryVariants = buildList {
            add(q)
            if (looksLikeConnecticutPlaceQuery(q)) add("$q, Connecticut")
        }.distinct()

        val combined = mutableListOf<Suggestion>()
        queryVariants.forEach { variant ->
            if (combined.size < cappedLimit) {
                combined += searchPhoton(
                    query = variant,
                    biasLat = effectiveBiasLat,
                    biasLon = effectiveBiasLon,
                    limit = cappedLimit,
                )
            }
        }

        if (combined.distinctSuggestions().size < cappedLimit) {
            queryVariants.forEach { variant ->
                if (combined.distinctSuggestions().size < cappedLimit) {
                    combined += searchNominatim(
                        query = variant,
                        biasLat = effectiveBiasLat,
                        biasLon = effectiveBiasLon,
                        limit = cappedLimit,
                    )
                }
            }
        }

        val suggestions = combined.distinctSuggestions().take(cappedLimit)
        DestinationSuggestionRuntime.remember(suggestions)
        return suggestions
    }

    private fun searchPhoton(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int,
    ): List<Suggestion> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val bias = if (biasLat != null && biasLon != null) {
            String.format(Locale.US, "&lat=%.6f&lon=%.6f&zoom=12", biasLat, biasLon)
        } else {
            ""
        }
        val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=$limit&lang=en$bias")
        val connection = open(url, "application/geo+json, application/json")
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
                    add(
                        Suggestion(
                            label = buildLabel(p, query),
                            subtitle = buildSubtitle(p),
                            lat = lat,
                            lon = lon,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun searchNominatim(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int,
    ): List<Suggestion> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val viewbox = if (biasLat != null && biasLon != null) {
            val west = biasLon - 0.8
            val east = biasLon + 0.8
            val north = biasLat + 0.6
            val south = biasLat - 0.6
            String.format(Locale.US, "&viewbox=%.6f,%.6f,%.6f,%.6f", west, north, east, south)
        } else {
            ""
        }
        val url = URL(
            "https://nominatim.openstreetmap.org/search" +
                "?format=jsonv2&addressdetails=1&countrycodes=us&limit=$limit&q=$encoded$viewbox"
        )
        val connection = open(url, "application/json")
        try {
            if (connection.responseCode !in 200..299) return emptyList()
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(payload)
            return buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val lat = item.optString("lat").toDoubleOrNull() ?: continue
                    val lon = item.optString("lon").toDoubleOrNull() ?: continue
                    val address = item.optJSONObject("address") ?: JSONObject()
                    val displayName = item.optString("display_name", query).trim()
                    val road = address.optString("road").trim()
                    val house = address.optString("house_number").trim()
                    val named = item.optString("name").trim()
                    val label = when {
                        named.isNotBlank() && !named.equals(road, ignoreCase = true) -> named
                        house.isNotBlank() && road.isNotBlank() -> "$house $road"
                        road.isNotBlank() -> road
                        else -> displayName.substringBefore(',').ifBlank { query }
                    }
                    val city = address.optString("city")
                        .ifBlank { address.optString("town") }
                        .ifBlank { address.optString("village") }
                        .ifBlank { address.optString("municipality") }
                        .trim()
                    val subtitle = listOf(
                        listOf(house, road).filter { it.isNotBlank() }.joinToString(" "),
                        city,
                        address.optString("state").trim(),
                        address.optString("postcode").trim(),
                    ).filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(", ")
                    add(Suggestion(label, subtitle, lat, lon))
                }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL, accept: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_500
            readTimeout = 6_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "LaneGPS-Android-Development/0.5 (+https://github.com/xz64uj777/gps)")
            instanceFollowRedirects = true
        }

    private fun MutableList<Suggestion>.distinctSuggestions(): List<Suggestion> {
        val seen = HashSet<String>()
        return filter { suggestion ->
            val coordinateKey = String.format(Locale.US, "%.5f,%.5f", suggestion.lat, suggestion.lon)
            val labelKey = suggestion.fullLabel().lowercase(Locale.US)
            seen.add("$coordinateKey|$labelKey")
        }
    }

    private fun looksLikeConnecticutPlaceQuery(query: String): Boolean {
        val lower = query.lowercase(Locale.US)
        if (lower.contains("connecticut") || Regex("(^|[,\\s])ct($|[,\\s])").containsMatchIn(lower)) return false
        return CONNECTICUT_CITY_HINTS.any { lower.contains(it) }
    }

    private fun buildLabel(p: JSONObject, fallback: String): String {
        val name = p.optString("name").trim()
        val house = p.optString("housenumber").trim()
        val street = p.optString("street").trim()
        return when {
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
        val CONNECTICUT_CITY_HINTS = setOf(
            "hartford", "west hartford", "east hartford", "new haven", "bridgeport",
            "stamford", "waterbury", "norwalk", "danbury", "new britain", "meriden",
            "bristol", "manchester", "middletown", "milford", "southington", "enfield",
        )
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
