package com.example.gps.route

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight development routing client.
 *
 * Geocoding uses OpenStreetMap Nominatim and routing uses the public OSRM demo
 * server. Both are isolated here so LaneGPS can swap to self-hosted services
 * later without changing the navigation UI.
 *
 * Normal maneuver advancement is local: the initial OSRM response contains the
 * route geometry and maneuver locations, and fresh GNSS fixes advance along
 * that geometry without another network request. A new OSRM request is only
 * needed for an actual reroute.
 */
class OpenRouteClient {
    data class RoutePoint(val lat: Double, val lon: Double)

    data class RouteManeuver(
        val label: String,
        val road: String,
        val lat: Double,
        val lon: Double,
        val routeIndex: Int,
    )

    data class RouteSummary(
        val destinationName: String,
        val destinationLat: Double,
        val destinationLon: Double,
        /** Remaining route distance from the latest position. */
        val distanceMeters: Double,
        /** Approximate remaining duration from the latest position. */
        val durationSeconds: Double,
        val nextManeuver: String,
        val nextRoad: String,
        val nextManeuverDistanceMeters: Double,
        val geometry: List<RoutePoint> = emptyList(),
        val maneuvers: List<RouteManeuver> = emptyList(),
        val totalRouteMeters: Double = distanceMeters,
        val totalRouteSeconds: Double = durationSeconds,
        val offRouteDistanceMeters: Double = 0.0,
        val arrived: Boolean = false,
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

    fun reroute(
        previous: RouteSummary,
        originLat: Double,
        originLon: Double,
    ): RouteSummary = route(
        originLat = originLat,
        originLon = originLon,
        place = Place(
            name = previous.destinationName,
            lat = previous.destinationLat,
            lon = previous.destinationLon,
        ),
    )

    /**
     * Advances the route locally from a fresh GNSS position.
     *
     * This is deliberately independent of lane matching. It updates the next
     * maneuver, remaining route distance, ETA, arrival state and cross-track
     * distance. The caller can use offRouteDistanceMeters to decide when a
     * network reroute is justified.
     */
    fun updateProgress(
        route: RouteSummary,
        currentLat: Double,
        currentLon: Double,
    ): RouteSummary {
        if (route.geometry.isEmpty()) return route

        val current = RoutePoint(currentLat, currentLon)
        val nearestIndex = nearestRouteIndex(current, route.geometry)
        val offRoute = pointToPolylineMeters(current, route.geometry)
        val destinationDistance = distanceMeters(
            current,
            RoutePoint(route.destinationLat, route.destinationLon),
        )
        val arrived = destinationDistance <= ARRIVAL_RADIUS_M

        val remaining = if (arrived) {
            0.0
        } else {
            distanceMeters(current, route.geometry[nearestIndex]) +
                routeDistance(route.geometry, nearestIndex, route.geometry.lastIndex)
        }
        val remainingSeconds = if (route.totalRouteMeters > 1.0) {
            route.totalRouteSeconds * (remaining / route.totalRouteMeters).coerceIn(0.0, 1.25)
        } else {
            0.0
        }

        val next = if (arrived) null else route.maneuvers.firstOrNull { maneuver ->
            when {
                maneuver.routeIndex > nearestIndex -> true
                maneuver.routeIndex < nearestIndex -> false
                else -> distanceMeters(
                    current,
                    RoutePoint(maneuver.lat, maneuver.lon),
                ) > PASSED_MANEUVER_RADIUS_M
            }
        }

        val maneuverDistance = when {
            arrived -> 0.0
            next == null -> destinationDistance
            next.routeIndex <= nearestIndex -> distanceMeters(
                current,
                RoutePoint(next.lat, next.lon),
            )
            else -> distanceMeters(current, route.geometry[nearestIndex]) +
                routeDistance(route.geometry, nearestIndex, next.routeIndex)
        }

        return route.copy(
            distanceMeters = remaining,
            durationSeconds = remainingSeconds,
            nextManeuver = when {
                arrived -> "Arrived"
                next != null -> next.label
                else -> "Continue to destination"
            },
            nextRoad = next?.road.orEmpty(),
            nextManeuverDistanceMeters = maneuverDistance,
            offRouteDistanceMeters = offRoute,
            arrived = arrived,
        )
    }

    private fun geocode(query: String): Place {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=$encoded")
        val payload = get(url)
        val results = JSONArray(payload)
        if (results.length() == 0) error("Destination not found")

        val first = results.getJSONObject(0)
        val fullName = first.optString("display_name", query)
        return Place(
            name = fullName.substringBeforeLast(", United States", fullName),
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
                "?overview=full&geometries=geojson&alternatives=false&steps=true",
        )
        val root = JSONObject(get(url))
        if (root.optString("code") != "Ok") {
            error(root.optString("message", "Routing service could not build a route"))
        }
        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) error("No drivable route found")
        val route = routes.getJSONObject(0)
        val geometry = parseGeometry(route.optJSONObject("geometry"))
        if (geometry.size < 2) error("Route contained no usable geometry")

        val legs = route.getJSONArray("legs")
        if (legs.length() == 0) error("Route contained no legs")

        val maneuvers = mutableListOf<RouteManeuver>()
        for (legIndex in 0 until legs.length()) {
            val steps = legs.getJSONObject(legIndex).getJSONArray("steps")
            for (stepIndex in 0 until steps.length()) {
                val step = steps.getJSONObject(stepIndex)
                val maneuver = step.optJSONObject("maneuver") ?: continue
                val type = maneuver.optString("type", "")
                if (type.isBlank() || type == "depart") continue
                val location = maneuver.optJSONArray("location") ?: continue
                if (location.length() < 2) continue
                val point = RoutePoint(
                    lat = location.optDouble(1),
                    lon = location.optDouble(0),
                )
                val label = if (type == "arrive") {
                    "Arrive at destination"
                } else {
                    maneuverText(type, maneuver.optString("modifier", ""))
                }
                maneuvers += RouteManeuver(
                    label = label,
                    road = step.optString("name", ""),
                    lat = point.lat,
                    lon = point.lon,
                    routeIndex = nearestRouteIndex(point, geometry),
                )
            }
        }

        val orderedManeuvers = maneuvers
            .sortedBy { it.routeIndex }
            .distinctBy { Triple(it.routeIndex, it.label, it.road) }
        val totalDistance = route.optDouble("distance", 0.0)
        val totalDuration = route.optDouble("duration", 0.0)

        val initial = RouteSummary(
            destinationName = place.name,
            destinationLat = place.lat,
            destinationLon = place.lon,
            distanceMeters = totalDistance,
            durationSeconds = totalDuration,
            nextManeuver = orderedManeuvers.firstOrNull()?.label ?: "Continue to destination",
            nextRoad = orderedManeuvers.firstOrNull()?.road.orEmpty(),
            nextManeuverDistanceMeters = totalDistance,
            geometry = geometry,
            maneuvers = orderedManeuvers,
            totalRouteMeters = totalDistance,
            totalRouteSeconds = totalDuration,
        )
        return updateProgress(initial, originLat, originLon)
    }

    private fun parseGeometry(geometry: JSONObject?): List<RoutePoint> {
        val coordinates = geometry?.optJSONArray("coordinates") ?: return emptyList()
        return buildList {
            for (index in 0 until coordinates.length()) {
                val coordinate = coordinates.optJSONArray(index) ?: continue
                if (coordinate.length() < 2) continue
                add(
                    RoutePoint(
                        lat = coordinate.optDouble(1),
                        lon = coordinate.optDouble(0),
                    )
                )
            }
        }
    }

    private fun nearestRouteIndex(point: RoutePoint, geometry: List<RoutePoint>): Int {
        var bestIndex = 0
        var bestDistance = Double.POSITIVE_INFINITY
        geometry.forEachIndexed { index, candidate ->
            val distance = distanceMeters(point, candidate)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun routeDistance(geometry: List<RoutePoint>, start: Int, end: Int): Double {
        if (geometry.size < 2 || end <= start) return 0.0
        var total = 0.0
        val safeStart = start.coerceIn(0, geometry.lastIndex)
        val safeEnd = end.coerceIn(safeStart, geometry.lastIndex)
        for (index in safeStart until safeEnd) {
            total += distanceMeters(geometry[index], geometry[index + 1])
        }
        return total
    }

    private fun pointToPolylineMeters(point: RoutePoint, line: List<RoutePoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return distanceMeters(point, line.first())

        val lat0 = point.lat * PI / 180.0
        fun xy(p: RoutePoint): Pair<Double, Double> {
            val x = (p.lon - point.lon) * PI / 180.0 * EARTH_RADIUS_M * cos(lat0)
            val y = (p.lat - point.lat) * PI / 180.0 * EARTH_RADIUS_M
            return x to y
        }

        var best = Double.POSITIVE_INFINITY
        for (index in 0 until line.lastIndex) {
            val (ax, ay) = xy(line[index])
            val (bx, by) = xy(line[index + 1])
            val dx = bx - ax
            val dy = by - ay
            val denominator = dx * dx + dy * dy
            val t = if (denominator <= 1e-9) {
                0.0
            } else {
                ((-ax * dx - ay * dy) / denominator).coerceIn(0.0, 1.0)
            }
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dp = (b.lat - a.lat) * PI / 180.0
        val dl = (b.lon - a.lon) * PI / 180.0
        val h = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1.0 - h))
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
            "arrive" -> "Arrive at destination"
            else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun get(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LaneGPS-Android-Development/0.3 (+https://github.com/xz64uj777/gps)")
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

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val ARRIVAL_RADIUS_M = 25.0
        const val PASSED_MANEUVER_RADIUS_M = 18.0
    }
}
