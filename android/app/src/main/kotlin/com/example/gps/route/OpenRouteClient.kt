package com.example.gps.route

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Lightweight development routing client.
 *
 * Geocoding uses OpenStreetMap Nominatim and routing uses the public OSRM demo
 * server. Both are intentionally isolated here so LaneGPS can swap to a
 * self-hosted service later without changing the driver UI.
 */
class OpenRouteClient {
    data class RouteSummary(
        val destinationName: String,
        val destinationLat: Double,
        val destinationLon: Double,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val nextManeuver: String,
        val nextRoad: String,
        val nextManeuverDistanceMeters: Double,
        val source: String = "OSM · OSRM",
    )

    fun plan(
        query: String,
        originLat: Double,
        originLon: Double,
    ): RouteSummary {
        val place = geocode(query)
        return route(originLat, originLon, place)
    }

    private fun geocode(query: String): Place {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=$encoded")
        val payload = get(url)
        val results = JSONArray(payload)
        if (results.length() == 0) error("Destination not found")

        val first = results.getJSONObject(0)
        return Place(
            name = first.optString("display_name", query).substringBeforeLast(", United States", first.optString("display_name", query)),
            lat = first.getString("lat").toDouble(),
            lon = first.getString("lon").toDouble(),
        )
    }

    private fun route(originLat: Double, originLon: Double, place: Place): RouteSummary {
        val coords = String.format(
            Locale.US,
            "%.7f,%.7f;%.7f,%.7f",
            originLon,
            originLat,
            place.lon,
            place.lat,
        )
        val url = URL(
            "https://router.project-osrm.org/route/v1/driving/$coords" +
                "?overview=false&alternatives=false&steps=true",
        )
        val root = JSONObject(get(url))
        if (root.optString("code") != "Ok") {
            error(root.optString("message", "Routing service could not build a route"))
        }
        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) error("No drivable route found")
        val route = routes.getJSONObject(0)
        val legs = route.getJSONArray("legs")
        if (legs.length() == 0) error("Route contained no legs")
        val steps = legs.getJSONObject(0).getJSONArray("steps")

        var cumulativeMeters = 0.0
        var maneuverLabel = "Continue"
        var maneuverRoad = ""
        var maneuverDistance = route.optDouble("distance", 0.0)

        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            val maneuver = step.optJSONObject("maneuver") ?: JSONObject()
            val type = maneuver.optString("type", "")
            if (type.isNotBlank() && type != "depart" && type != "arrive") {
                maneuverLabel = maneuverText(type, maneuver.optString("modifier", ""))
                maneuverRoad = step.optString("name", "")
                maneuverDistance = cumulativeMeters
                break
            }
            cumulativeMeters += step.optDouble("distance", 0.0)
        }

        return RouteSummary(
            destinationName = place.name,
            destinationLat = place.lat,
            destinationLon = place.lon,
            distanceMeters = route.optDouble("distance", 0.0),
            durationSeconds = route.optDouble("duration", 0.0),
            nextManeuver = maneuverLabel,
            nextRoad = maneuverRoad,
            nextManeuverDistanceMeters = maneuverDistance,
        )
    }

    private fun maneuverText(type: String, modifier: String): String {
        val direction = when (modifier) {
            "slight left" -> "Slight left"
            "left" -> "Turn left"
            "sharp left" -> "Sharp left"
            "slight right" -> "Slight right"
            "right" -> "Turn right"
            "sharp right" -> "Sharp right"
            "straight" -> "Continue straight"
            "uturn" -> "Make a U-turn"
            else -> ""
        }
        if (direction.isNotBlank()) return direction
        return when (type) {
            "merge" -> "Merge"
            "on ramp" -> "Take the ramp"
            "off ramp" -> "Take the exit"
            "fork" -> "Keep at the fork"
            "roundabout", "rotary" -> "Enter the roundabout"
            "new name" -> "Continue"
            "end of road" -> "At the end of the road"
            else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun get(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LaneGPS-Android-Development/0.2")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Route service HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private data class Place(val name: String, val lat: Double, val lon: Double)
}
