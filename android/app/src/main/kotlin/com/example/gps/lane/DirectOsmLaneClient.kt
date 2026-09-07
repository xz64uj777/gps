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
                    val specs = laneSpecs(tags, Direction.FORWARD, allowGenericCount = true)
                    addDirection(lanes, wayId, 1, base, specs)
                }
                "-1" -> {
                    val specs = laneSpecs(tags, Direction.BACKWARD, allowGenericCount = true)
                    addDirection(lanes, wayId, -1, base.asReversed(), specs)
                }
                else -> addTwoWayRoad(lanes, wayId, base, tags)
            }
        }
        return Corridor(lanes)
    }

    private fun addTwoWayRoad(
        output: MutableList<Lane>,
        wayId: Long,
        base: List<GeoPoint>,
        tags: Map<String, String>,
    ) {
        val total = tags["lanes"]?.toIntOrNull()?.coerceIn(1, 12)
        val explicitForward = tags["lanes:forward"]?.toIntOrNull()?.coerceIn(1, 8)
        val explicitBackward = tags["lanes:backward"]?.toIntOrNull()?.coerceIn(1, 8)

        // A very common OSM pattern on ordinary two-way roads is only `lanes=2`
        // (or 4, etc.) without directional lane-count tags. The old parser ignored
        // those roads entirely. Infer directional travel-lane counts conservatively.
        // For odd totals, leave the center lane unassigned because it is often a
        // shared/turn lane and cannot safely be attributed to either direction.
        val forwardCount = explicitForward ?: when {
            total != null && explicitBackward != null && total > explicitBackward -> total - explicitBackward
            total != null && total >= 2 -> total / 2
            else -> null
        }
        val backwardCount = explicitBackward ?: when {
            total != null && explicitForward != null && total > explicitForward -> total - explicitForward
            total != null && total >= 2 -> total / 2
            else -> null
        }

        if (forwardCount == null || backwardCount == null || forwardCount < 1 || backwardCount < 1) return

        val forwardInferred = explicitForward == null
        val backwardInferred = explicitBackward == null
        val forwardSpecs = laneSpecsForCount(tags, Direction.FORWARD, forwardCount, forwardInferred)
        val backwardSpecs = laneSpecsForCount(tags, Direction.BACKWARD, backwardCount, backwardInferred)

        // OSM road geometry is normally near the road center. In right-hand traffic,
        // each direction occupies the right side of its own travel direction. Since
        // the backward geometry is reversed below, the same signed offset places the
        // two directions on opposite physical sides of the road.
        val leftHandTraffic = tags["driving_side"]?.trim()?.lowercase() == "left"
        val side = if (leftHandTraffic) 1.0 else -1.0
        val forwardGroupOffset = side * forwardCount * DEFAULT_LANE_WIDTH_M / 2.0
        val backwardGroupOffset = side * backwardCount * DEFAULT_LANE_WIDTH_M / 2.0

        addDirection(
            output = output,
            wayId = wayId,
            direction = 1,
            travelLine = base,
            specs = forwardSpecs,
            groupCenterOffsetMeters = forwardGroupOffset,
        )
        addDirection(
            output = output,
            wayId = wayId,
            direction = -1,
            travelLine = base.asReversed(),
            specs = backwardSpecs,
            groupCenterOffsetMeters = backwardGroupOffset,
        )
    }

    private fun laneSpecs(
        tags: Map<String, String>,
        direction: Direction,
        allowGenericCount: Boolean,
    ): List<LaneSpec> {
        val suffix = if (direction == Direction.FORWARD) "forward" else "backward"
        val rawCount = tags["lanes:$suffix"] ?: if (allowGenericCount) tags["lanes"] else null
        val count = rawCount?.toIntOrNull()?.coerceIn(1, 8) ?: return emptyList()
        return laneSpecsForCount(tags, direction, count, inferredDirectionalCount = false, allowGenericMetadata = allowGenericCount)
    }

    private fun laneSpecsForCount(
        tags: Map<String, String>,
        direction: Direction,
        count: Int,
        inferredDirectionalCount: Boolean,
        allowGenericMetadata: Boolean = false,
    ): List<LaneSpec> {
        val suffix = if (direction == Direction.FORWARD) "forward" else "backward"
        val turnValues = (
            tags["turn:lanes:$suffix"] ?: if (allowGenericMetadata) tags["turn:lanes"] else null
        )?.split('|').orEmpty()
        val changeValues = (
            tags["change:lanes:$suffix"] ?: if (allowGenericMetadata) tags["change:lanes"] else null
        )?.split('|').orEmpty()
        val explicitTurns = turnValues.size == count
        val baseConfidence = when {
            explicitTurns && !inferredDirectionalCount -> 0.95
            !inferredDirectionalCount -> 0.80
            else -> 0.60
        }

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
        groupCenterOffsetMeters: Double = 0.0,
    ) {
        if (specs.isEmpty()) return
        val count = specs.size
        val segmentId = "osm:$wayId:$direction"
        for (spec in specs) {
            val withinDirectionOffset = ((count - 1) / 2.0 - spec.index) * DEFAULT_LANE_WIDTH_M
            val offsetMeters = groupCenterOffsetMeters + withinDirectionOffset
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
                // Positive offset is the left-hand normal relative to travel direction.
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
