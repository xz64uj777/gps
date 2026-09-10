package com.example.gps.laneengine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Resolves the physical carriageway visible to a driver when OSM represents one
 * road as several parallel way segments. It deliberately works from geometry,
 * not raw segment IDs, and keeps the result conservative around separated roads.
 */
class PhysicalCarriagewayResolver {
    data class Result(
        val laneNumberFromLeft: Int,
        val laneCount: Int,
        val mergedSegments: Boolean,
        val segmentCount: Int,
    )

    fun resolve(
        position: GeoPoint,
        travelHeadingDegrees: Double?,
        matchedLane: Lane,
        lanes: List<Lane>,
    ): Result {
        val matchedSegment = lanes.filter { it.segmentId == matchedLane.segmentId }
        val fallback = Result(
            laneNumberFromLeft = matchedLane.index + 1,
            laneCount = matchedSegment.size.coerceAtLeast(1),
            mergedSegments = false,
            segmentCount = 1,
        )

        val referenceHeading =
            laneBearingDegreesNearPoint(matchedLane, position) ?: travelHeadingDegrees ?: return fallback

        val samples = lanes.mapNotNull { lane ->
            val distance = pointToPolylineMeters(position, lane.centerline)
            if (!distance.isFinite() || distance > SEARCH_RADIUS_M) return@mapNotNull null

            val laneHeading = laneBearingDegreesNearPoint(lane, position) ?: return@mapNotNull null
            if (angleDifferenceDegrees(referenceHeading, laneHeading) > MAX_PARALLEL_HEADING_ERROR_DEG) {
                return@mapNotNull null
            }

            val lateral = signedLateralMeters(position, lane.centerline, referenceHeading)
                ?: return@mapNotNull null
            Sample(lane, lateral)
        }
        if (samples.none { it.lane.id == matchedLane.id }) return fallback

        // Consecutive OSM ways often overlap briefly at a node. Cluster lanes that
        // occupy the same lateral position so a 3-lane road does not become 6.
        val clusters = mutableListOf<MutableList<Sample>>()
        samples.sortedByDescending { it.lateralMeters }.forEach { sample ->
            val existing = clusters.lastOrNull()
            val center = existing?.map { it.lateralMeters }?.average()
            if (existing != null && center != null && abs(sample.lateralMeters - center) <= DUPLICATE_LATERAL_TOLERANCE_M) {
                existing += sample
            } else {
                clusters += mutableListOf(sample)
            }
        }

        val matchedClusterIndex = clusters.indexOfFirst { cluster ->
            cluster.any { it.lane.id == matchedLane.id }
        }
        if (matchedClusterIndex < 0) return fallback

        fun center(index: Int): Double = clusters[index].map { it.lateralMeters }.average()

        // Only keep the contiguous lane band around the matched lane. This stops a
        // nearby frontage road or separated collector roadway from being merged just
        // because it is parallel and happens to be within the search radius.
        var first = matchedClusterIndex
        while (first > 0) {
            val gap = center(first - 1) - center(first)
            if (gap > MAX_CONTIGUOUS_LANE_GAP_M) break
            first--
        }
        var last = matchedClusterIndex
        while (last < clusters.lastIndex) {
            val gap = center(last) - center(last + 1)
            if (gap > MAX_CONTIGUOUS_LANE_GAP_M) break
            last++
        }

        val selected = clusters.subList(first, last + 1)
        val laneCount = selected.size.coerceIn(1, MAX_LANES)
        val physicalIndex = matchedClusterIndex - first
        if (physicalIndex !in 0 until laneCount) return fallback

        val segmentIds = selected
            .flatMap { it }
            .map { it.lane.segmentId }
            .toSet()

        return Result(
            laneNumberFromLeft = physicalIndex + 1,
            laneCount = laneCount,
            mergedSegments = segmentIds.size > 1,
            segmentCount = segmentIds.size.coerceAtLeast(1),
        )
    }

    private fun signedLateralMeters(
        point: GeoPoint,
        line: List<GeoPoint>,
        headingDegrees: Double,
    ): Double? {
        val offset = closestPointOffsetMeters(point, line) ?: return null
        val heading = headingDegrees * PI / 180.0
        val leftEast = -cos(heading)
        val leftNorth = sin(heading)
        return offset.first * leftEast + offset.second * leftNorth
    }

    private fun pointToPolylineMeters(point: GeoPoint, line: List<GeoPoint>): Double {
        val offset = closestPointOffsetMeters(point, line) ?: return Double.POSITIVE_INFINITY
        return hypot(offset.first, offset.second)
    }

    private fun closestPointOffsetMeters(
        point: GeoPoint,
        line: List<GeoPoint>,
    ): Pair<Double, Double>? {
        if (line.isEmpty()) return null
        val lat0 = point.lat * PI / 180.0
        fun xy(p: GeoPoint): Pair<Double, Double> {
            val east = (p.lon - point.lon) * PI / 180.0 * EARTH_RADIUS_M * cos(lat0)
            val north = (p.lat - point.lat) * PI / 180.0 * EARTH_RADIUS_M
            return east to north
        }

        if (line.size == 1) return xy(line.first())

        var bestEast = 0.0
        var bestNorth = 0.0
        var bestDistance = Double.POSITIVE_INFINITY
        for (index in 0 until line.lastIndex) {
            val (ax, ay) = xy(line[index])
            val (bx, by) = xy(line[index + 1])
            val dx = bx - ax
            val dy = by - ay
            val denom = dx * dx + dy * dy
            val t = if (denom <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / denom).coerceIn(0.0, 1.0)
            val east = ax + t * dx
            val north = ay + t * dy
            val distance = hypot(east, north)
            if (distance < bestDistance) {
                bestDistance = distance
                bestEast = east
                bestNorth = north
            }
        }
        return bestEast to bestNorth
    }

    private fun laneBearingDegreesNearPoint(lane: Lane, point: GeoPoint): Double? {
        if (lane.centerline.size < 2) return null
        var bestIndex = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (index in 0 until lane.centerline.lastIndex) {
            val distance = pointToPolylineMeters(
                point,
                listOf(lane.centerline[index], lane.centerline[index + 1]),
            )
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return initialBearingDegrees(lane.centerline[bestIndex], lane.centerline[bestIndex + 1])
    }

    private fun initialBearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dl = (b.lon - a.lon) * PI / 180.0
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
    }

    private fun angleDifferenceDegrees(a: Double, b: Double): Double {
        val raw = abs((a - b + 180.0) % 360.0 - 180.0)
        return if (raw > 180.0) 360.0 - raw else raw
    }

    private data class Sample(
        val lane: Lane,
        val lateralMeters: Double,
    )

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val SEARCH_RADIUS_M = 32.0
        const val MAX_PARALLEL_HEADING_ERROR_DEG = 12.0
        const val DUPLICATE_LATERAL_TOLERANCE_M = 1.6
        const val MAX_CONTIGUOUS_LANE_GAP_M = 6.4
        const val MAX_LANES = 8
    }
}
