package com.example.gps.lane

import com.example.gps.laneengine.GeoPoint
import com.example.gps.laneengine.Lane
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

class DirectOsmLaneClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
) {
    data class Corridor(val lanes: List<Lane>)

    fun fetchCorridor(lat: Double, lon: Double, radiusMeters: Int = 1600): Corridor {
        val radius = radiusMeters.coerceIn(500, 3000)
        val query = buildQuery(lat, lon, radius)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 7000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "LaneGPS-Android-MVP/0.2 (+https://github.com/xz64uj777/gps)")
            val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

            val code = connection.responseCode
            if (code == 429 || code == 504) error("OSM service busy · HTTP $code")
            if (code !in 200..299) error("OSM HTTP $code")
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            parse(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildQuery(lat: Double, lon: Double, radius: Int): String = """
        [out:json][timeout:18];
        (
          way(around:$radius,${"%.7f".format(java.util.Locale.US, lat)},${"%.7f".format(java.util.Locale.US, lon)})["highway"]["lanes"];
          way(around:$radius,${"%.7f".format(java.util.Locale.US, lat)},${"%.7f".format(java.util.Locale.US, lon)})["highway"]["lanes:forward"];
          way(around:$radius,${"%.7f".format(java.util.Locale.US, lat)},${"%.7f".format(java.util.Locale.US, lon)})["highway"]["lanes:backward"];
        );
        out tags geom;
    """.trimIndent()

    private fun parse(body: String): Corridor {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return Corridor(emptyList())
        val lanes = mutableListOf<Lane>()

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            if (element.optString("type") != "way") continue
            val geometry = element.optJSONArray("geometry") ?: continue
            if (geometry.length() < 2) continue
            val tagsObject = element.optJSONObject("tags") ?: continue
            if (!tagsObject.has("highway")) continue

            val tags = mutableMapOf<String, String>()
            val keys = tagsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                tags[key] = tagsObject.optString(key)
            }

            val base = buildList {
                for (j in 0 until geometry.length()) {
                    val point = geometry.optJSONObject(j) ?: continue
                    if (!point.has("lat") || !point.has("lon")) continue
                    add(GeoPoint(point.getDouble("lat"), point.getDouble("lon")))
                }
            }
            if (base.size < 2) continue

            val wayId = element.optLong("id")
            val oneway = tags["oneway"]?.lowercase()?.trim().orEmpty()
            when (oneway) {
                "yes", "1", "true" -> {
                    addDirection(lanes, wayId, 1, base, laneSpecs(tags, Direction.FORWARD, allowGenericCount = true))
                }
                "-1" -> {
                    addDirection(lanes, wayId, -1, base.asReversed(), laneSpecs(tags, Direction.BACKWARD, allowGenericCount = true))
                }
                else -> {
                    addDirection(lanes, wayId, 1, base, laneSpecs(tags, Direction.FORWARD, allowGenericCount = false))
                    addDirection(lanes, wayId, -1, base.asReversed(), laneSpecs(tags, Direction.BACKWARD, allowGenericCount = false))
                }
            }
        }
        return Corridor(lanes)
    }

    private fun laneSpecs(
        tags: Map<String, String>,
        direction: Direction,
        allowGenericCount: Boolean,
    ): List<LaneSpec> {
        val suffix = if (direction == Direction.FORWARD) "forward" else "backward"
        val rawCount = tags["lanes:$suffix"] ?: if (allowGenericCount) tags["lanes"] else null
        val count = rawCount?.toIntOrNull()?.coerceIn(1, 8) ?: return emptyList()

        val turnValues = (tags["turn:lanes:$suffix"] ?: tags["turn:lanes"])
            ?.split('|')
            .orEmpty()
        val changeValues = (tags["change:lanes:$suffix"] ?: tags["change:lanes"])
            ?.split('|')
            .orEmpty()
        val explicitTurns = turnValues.size == count
        val baseConfidence = if (explicitTurns) 0.95 else 0.70

        return List(count) { index ->
            val change = changeValues.getOrNull(index)?.trim().orEmpty()
            LaneSpec(
                index = index,
                changeLeft = change !in setOf("no", "not_left"),
                changeRight = change !in setOf("no", "not_right"),
                confidence = baseConfidence,
            )
        }
    }

    private fun addDirection(
        output: MutableList<Lane>,
        wayId: Long,
        direction: Int,
        travelLine: List<GeoPoint>,
        specs: List<LaneSpec>,
    ) {
        if (specs.isEmpty()) return
        val count = specs.size
        val segmentId = "osm:$wayId:$direction"
        for (spec in specs) {
            val offsetMeters = ((count - 1) / 2.0 - spec.index) * DEFAULT_LANE_WIDTH_M
            output += Lane(
                id = "$segmentId:${spec.index}",
                segmentId = segmentId,
                index = spec.index,
                centerline = offsetPolyline(travelLine, offsetMeters),
                widthMeters = DEFAULT_LANE_WIDTH_M,
                changeLeft = spec.changeLeft,
                changeRight = spec.changeRight,
                sourceConfidence = spec.confidence,
            )
        }
    }

    private fun offsetPolyline(line: List<GeoPoint>, offsetMeters: Double): List<GeoPoint> {
        if (offsetMeters == 0.0 || line.size < 2) return line
        return line.indices.map { i ->
            val current = line[i]
            val previous = line[if (i == 0) i else i - 1]
            val next = line[if (i == line.lastIndex) i else i + 1]
            val metersPerLat = 111_320.0
            val metersPerLon = (111_320.0 * cos(current.lat * PI / 180.0)).coerceAtLeast(1.0)
            val dx = (next.lon - previous.lon) * metersPerLon
            val dy = (next.lat - previous.lat) * metersPerLat
            val length = hypot(dx, dy)
            if (length < 0.01) {
                current
            } else {
                // Left-hand normal relative to travel direction.
                val east = (-dy / length) * offsetMeters
                val north = (dx / length) * offsetMeters
                GeoPoint(
                    lat = current.lat + north / metersPerLat,
                    lon = current.lon + east / metersPerLon,
                )
            }
        }
    }

    private enum class Direction { FORWARD, BACKWARD }

    private data class LaneSpec(
        val index: Int,
        val changeLeft: Boolean,
        val changeRight: Boolean,
        val confidence: Double,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val DEFAULT_LANE_WIDTH_M = 3.6
    }
}
