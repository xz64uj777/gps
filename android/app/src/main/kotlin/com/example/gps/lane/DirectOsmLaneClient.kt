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
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) {
    data class Corridor(
        val lanes: List<Lane>,
        val endpoint: String,
    )

    fun fetchCorridor(lat: Double, lon: Double, radiusMeters: Int = 1200): Corridor {
        val radius = radiusMeters.coerceIn(500, 2500)
        val query = buildQuery(lat, lon, radius)
        val failures = mutableListOf<String>()
        val orderedEndpoints = buildList {
            preferredEndpoint?.let(::add)
            addAll(endpoints)
        }.distinct()

        for (endpoint in orderedEndpoints) {
            try {
                val payload = fetchPayload(endpoint, query)
                preferredEndpoint = endpoint
                return Corridor(
                    lanes = parse(payload),
                    endpoint = endpoint,
                )
            } catch (exc: Exception) {
                val detail = exc.message?.replace('\n', ' ')?.take(80) ?: exc.javaClass.simpleName
                failures += "${endpointHost(endpoint)}: $detail"
            }
        }

        error(
            if (failures.isEmpty()) "No OSM endpoint configured"
            else "All OSM endpoints failed · ${failures.joinToString(" | ").take(220)}"
        )
    }

    private fun fetchPayload(endpoint: String, query: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "LaneGPS-Android-MVP/0.5 (+https://github.com/xz64uj777/gps)")
            val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

            val code = connection.responseCode
            if (code == 429 || code in 500..599) error("OSM service busy · HTTP $code")
            if (code !in 200..299) error("OSM HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun endpointHost(endpoint: String): String =
        runCatching { URL(endpoint).host }.getOrDefault(endpoint)

    private fun buildQuery(lat: Double, lon: Double, radius: Int): String = """
        [out:json][timeout:12];
        way(around:$radius,${"%.7f".format(java.util.Locale.US, lat)},${"%.7f".format(java.util.Locale.US, lon)})
          ["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|living_street|service)$"];
        out tags geom;
    """.trimIndent()

    private fun parse(body: String): List<Lane> {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return emptyList()
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
            if (tags["motor_vehicle"] == "no" || tags["vehicle"] == "no") continue

            val base = buildList {
                for (j in 0 until geometry.length()) {
                    val point = geometry.optJSONObject(j) ?: continue
                    if (!point.has("lat") || !point.has("lon")) continue
                    add(GeoPoint(point.getDouble("lat"), point.getDouble("lon")))
                }
            }
            if (base.size < 2) continue

            val wayId = element.optLong("id")
            val highway = tags["highway"]?.lowercase()?.trim().orEmpty()
            val oneway = tags["oneway"]?.lowercase()?.trim().orEmpty()
            val implicitMotorwayOneWay =
                highway in setOf("motorway", "motorway_link") &&
                    oneway !in setOf("no", "0", "false")

            when {
                oneway in setOf("yes", "1", "true") ->
                    addOneWay(lanes, wayId, 1, base, tags, Direction.FORWARD)
                oneway == "-1" ->
                    addOneWay(lanes, wayId, -1, base.asReversed(), tags, Direction.BACKWARD)
                implicitMotorwayOneWay ->
                    addOneWay(lanes, wayId, 1, base, tags, Direction.FORWARD)
                else -> addTwoWay(lanes, wayId, base, tags)
            }
        }
        return lanes
    }

    private fun addOneWay(
        output: MutableList<Lane>,
        wayId: Long,
        direction: Int,
        travelLine: List<GeoPoint>,
        tags: Map<String, String>,
        laneDirection: Direction,
    ) {
        val suffix = if (laneDirection == Direction.FORWARD) "forward" else "backward"
        val explicitCount = parseLaneCount(tags["lanes:$suffix"])
        val genericCount = parseLaneCount(tags["lanes"])
        val count = explicitCount ?: genericCount ?: 1
        val confidence = when {
            explicitCount != null -> 0.90
            genericCount != null -> 0.80
            else -> 0.35
        }
        val specs = buildSpecs(tags, laneDirection, count, confidence, allowGenericTurnTags = true)
        val offsets = List(count) { index ->
            ((count - 1) / 2.0 - index) * DEFAULT_LANE_WIDTH_M
        }
        addDirection(output, wayId, direction, travelLine, specs, offsets)
    }

    private fun addTwoWay(
        output: MutableList<Lane>,
        wayId: Long,
        base: List<GeoPoint>,
        tags: Map<String, String>,
    ) {
        val genericTotal = parseLaneCount(tags["lanes"])
        val explicitForward = parseLaneCount(tags["lanes:forward"])
        val explicitBackward = parseLaneCount(tags["lanes:backward"])

        var forwardCount = explicitForward
        var backwardCount = explicitBackward

        if (genericTotal != null) {
            if (forwardCount != null && backwardCount == null) {
                backwardCount = (genericTotal - forwardCount).coerceAtLeast(1)
            } else if (backwardCount != null && forwardCount == null) {
                forwardCount = (genericTotal - backwardCount).coerceAtLeast(1)
            } else if (forwardCount == null && backwardCount == null) {
                if (genericTotal >= 2) {
                    // For odd totals, conservatively leave the middle lane unassigned. It may
                    // be a center-turn/reversible lane and should not become an exact claim.
                    forwardCount = (genericTotal / 2).coerceAtLeast(1)
                    backwardCount = (genericTotal / 2).coerceAtLeast(1)
                } else {
                    forwardCount = 1
                    backwardCount = 1
                }
            }
        }

        forwardCount = forwardCount ?: 1
        backwardCount = backwardCount ?: 1

        val knownDirectionalCounts = explicitForward != null || explicitBackward != null
        val inferredFromGeneric = genericTotal != null && !knownDirectionalCounts
        val physicalTotal = maxOf(
            genericTotal ?: 0,
            forwardCount + backwardCount,
        )

        val baseConfidence = when {
            explicitForward != null && explicitBackward != null -> 0.90
            knownDirectionalCounts -> 0.78
            inferredFromGeneric -> 0.55
            else -> 0.30
        }

        val forwardSpecs = buildSpecs(
            tags = tags,
            direction = Direction.FORWARD,
            count = forwardCount,
            baseConfidence = baseConfidence,
            allowGenericTurnTags = false,
        )
        val backwardSpecs = buildSpecs(
            tags = tags,
            direction = Direction.BACKWARD,
            count = backwardCount,
            baseConfidence = baseConfidence,
            allowGenericTurnTags = false,
        )

        // OSM way geometry is normally near the center of an undivided two-way road.
        // Put each directional lane on its physical side of that centerline rather than
        // centering each direction group on the same geometry.
        val forwardOffsets = List(forwardCount) { index ->
            val physicalIndexFromLeft = (physicalTotal - forwardCount) + index
            ((physicalTotal - 1) / 2.0 - physicalIndexFromLeft) * DEFAULT_LANE_WIDTH_M
        }
        val backwardOffsets = List(backwardCount) { index ->
            val physicalIndexFromLeft = backwardCount - 1 - index
            val originalWayOffset =
                ((physicalTotal - 1) / 2.0 - physicalIndexFromLeft) * DEFAULT_LANE_WIDTH_M
            -originalWayOffset // travelLine is reversed, so its left-normal is reversed too.
        }

        addDirection(output, wayId, 1, base, forwardSpecs, forwardOffsets)
        addDirection(output, wayId, -1, base.asReversed(), backwardSpecs, backwardOffsets)
    }

    private fun buildSpecs(
        tags: Map<String, String>,
        direction: Direction,
        count: Int,
        baseConfidence: Double,
        allowGenericTurnTags: Boolean,
    ): List<LaneSpec> {
        val suffix = if (direction == Direction.FORWARD) "forward" else "backward"
        val directionalTurns = tags["turn:lanes:$suffix"]?.split('|').orEmpty()
        val genericTurns = if (allowGenericTurnTags) tags["turn:lanes"]?.split('|').orEmpty() else emptyList()
        val turnValues = if (directionalTurns.size == count) directionalTurns else genericTurns

        val directionalChanges = tags["change:lanes:$suffix"]?.split('|').orEmpty()
        val genericChanges = if (allowGenericTurnTags) tags["change:lanes"]?.split('|').orEmpty() else emptyList()
        val changeValues = if (directionalChanges.size == count) directionalChanges else genericChanges

        val explicitTurns = turnValues.size == count
        val confidence = if (explicitTurns) maxOf(baseConfidence, 0.95) else baseConfidence

        return List(count) { index ->
            val change = changeValues.getOrNull(index)?.trim().orEmpty()
            LaneSpec(
                index = index,
                changeLeft = change !in setOf("no", "not_left"),
                changeRight = change !in setOf("no", "not_right"),
                confidence = confidence,
            )
        }
    }

    private fun parseLaneCount(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.coerceIn(1, 8)

    private fun addDirection(
        output: MutableList<Lane>,
        wayId: Long,
        direction: Int,
        travelLine: List<GeoPoint>,
        specs: List<LaneSpec>,
        offsetsMeters: List<Double>,
    ) {
        if (specs.isEmpty()) return
        val segmentId = "osm:$wayId:$direction"
        for (spec in specs) {
            val offsetMeters = offsetsMeters.getOrElse(spec.index) { 0.0 }
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
        @Volatile
        var preferredEndpoint: String? = null

        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        const val DEFAULT_LANE_WIDTH_M = 3.6
    }
}
