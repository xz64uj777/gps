package com.example.gps.laneengine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Monotonic matcher for progress along a route polyline.
 *
 * It deliberately searches only a local forward corridor around the last accepted
 * route index. That prevents a noisy GNSS fix, a loop in the route, or a nearby
 * earlier road segment from moving navigation backwards.
 */
class RouteProgressTracker {
    data class Match(
        val routeIndex: Int,
        val crossTrackMeters: Double,
        val segmentIndex: Int,
        val segmentFraction: Double,
        val projectedPoint: GeoPoint,
    )

    fun match(
        position: GeoPoint,
        geometry: List<GeoPoint>,
        previousIndex: Int = 0,
        forwardHorizonMeters: Double = DEFAULT_FORWARD_HORIZON_M,
        backwardPoints: Int = DEFAULT_BACKWARD_POINTS,
        allowStartRecovery: Boolean = false,
    ): Match {
        if (geometry.isEmpty()) {
            return Match(
                routeIndex = 0,
                crossTrackMeters = Double.POSITIVE_INFINITY,
                segmentIndex = 0,
                segmentFraction = 0.0,
                projectedPoint = position,
            )
        }
        if (geometry.size == 1) {
            return Match(
                routeIndex = 0,
                crossTrackMeters = distanceMeters(position, geometry.first()),
                segmentIndex = 0,
                segmentFraction = 0.0,
                projectedPoint = geometry.first(),
            )
        }

        val previous = previousIndex.coerceIn(0, geometry.lastIndex)
        val startSegment = (previous - backwardPoints).coerceAtLeast(0).coerceAtMost(geometry.lastIndex - 1)
        val endPoint = forwardEndIndex(geometry, previous, forwardHorizonMeters)
            .coerceAtLeast(startSegment + 1)
            .coerceAtMost(geometry.lastIndex)

        var bestDistance = Double.POSITIVE_INFINITY
        var bestIndex = previous
        var bestSegment = startSegment
        var bestFraction = 0.0
        var bestProjectedPoint = geometry[startSegment]

        for (segmentIndex in startSegment until endPoint) {
            val projection = project(position, geometry[segmentIndex], geometry[segmentIndex + 1])
            if (projection.distanceMeters < bestDistance) {
                bestDistance = projection.distanceMeters
                val projectedIndex = if (projection.t >= 0.5) segmentIndex + 1 else segmentIndex
                bestIndex = max(previous, projectedIndex)
                bestSegment = segmentIndex
                bestFraction = projection.t
                bestProjectedPoint = projection.projectedPoint
            }
        }

        // A restored or delayed route can still be at index zero after the car
        // has passed the local search corridor. Recover only an unambiguous,
        // close match; ordinary progress retains its local anti-loop behavior.
        if (allowStartRecovery && previous == 0 && bestDistance > 75.0) {
            val candidates = mutableListOf<Pair<Match, Double>>()
            var along = 0.0
            for (i in 0 until geometry.lastIndex) {
                val projection = project(position, geometry[i], geometry[i + 1])
                val length = distanceMeters(geometry[i], geometry[i + 1])
                if (projection.distanceMeters <= 25.0) {
                    candidates += Match(
                        if (projection.t >= 0.5) i + 1 else i,
                        projection.distanceMeters, i, projection.t, projection.projectedPoint,
                    ) to (along + length * projection.t)
                }
                along += length
            }
            val closest = candidates.minByOrNull { it.first.crossTrackMeters }
            if (closest != null && candidates.none {
                    kotlin.math.abs(it.second - closest.second) > 100.0
                }) return closest.first
        }

        return Match(
            routeIndex = bestIndex.coerceIn(previous, geometry.lastIndex),
            crossTrackMeters = bestDistance,
            segmentIndex = bestSegment.coerceIn(0, geometry.lastIndex - 1),
            segmentFraction = bestFraction.coerceIn(0.0, 1.0),
            projectedPoint = bestProjectedPoint,
        )
    }

    private fun forwardEndIndex(
        geometry: List<GeoPoint>,
        startIndex: Int,
        horizonMeters: Double,
    ): Int {
        var index = startIndex.coerceIn(0, geometry.lastIndex)
        var traveled = 0.0
        while (index < geometry.lastIndex && traveled < horizonMeters) {
            traveled += distanceMeters(geometry[index], geometry[index + 1])
            index++
        }
        return index
    }

    private data class Projection(
        val distanceMeters: Double,
        val t: Double,
        val projectedPoint: GeoPoint,
    )

    private fun project(point: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val lat0 = point.lat * PI / 180.0
        fun xy(p: GeoPoint): Pair<Double, Double> {
            val east = (p.lon - point.lon) * PI / 180.0 * EARTH_RADIUS_M * cos(lat0)
            val north = (p.lat - point.lat) * PI / 180.0 * EARTH_RADIUS_M
            return east to north
        }

        val (ax, ay) = xy(a)
        val (bx, by) = xy(b)
        val dx = bx - ax
        val dy = by - ay
        val denom = dx * dx + dy * dy
        val t = if (denom <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / denom).coerceIn(0.0, 1.0)
        val east = ax + t * dx
        val north = ay + t * dy
        val projected = GeoPoint(
            lat = a.lat + (b.lat - a.lat) * t,
            lon = a.lon + (b.lon - a.lon) * t,
        )
        return Projection(hypot(east, north), t, projected)
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat0 = (a.lat + b.lat) * 0.5 * PI / 180.0
        val east = (b.lon - a.lon) * PI / 180.0 * EARTH_RADIUS_M * cos(lat0)
        val north = (b.lat - a.lat) * PI / 180.0 * EARTH_RADIUS_M
        return hypot(east, north)
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val DEFAULT_FORWARD_HORIZON_M = 1_600.0
        const val DEFAULT_BACKWARD_POINTS = 2
    }
}
