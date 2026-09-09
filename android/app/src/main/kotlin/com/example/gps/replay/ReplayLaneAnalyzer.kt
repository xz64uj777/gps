package com.example.gps.replay

import com.example.gps.lane.DirectOsmLaneClient
import com.example.gps.laneengine.GeoPoint
import com.example.gps.laneengine.Lane
import com.example.gps.laneengine.LaneMatcher
import com.example.gps.laneengine.Observation
import com.example.gps.location.DriveReplaySample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Re-runs a recorded drive sample through the current LaneMatcher + current OSM data.
 *
 * This deliberately does not trust the lane decision stored in the CSV. The recorded
 * decision stays visible in ReplayActivity so we can compare "then" vs "current engine".
 */
class ReplayLaneAnalyzer(
    private val osmClient: DirectOsmLaneClient = DirectOsmLaneClient(),
) {
    private var matcher = LaneMatcher()
    private var cachedLanes: List<Lane> = emptyList()
    private var lastFetchPoint: GeoPoint? = null
    private var lastReplayTimestamp: Long? = null
    private var fetchStatus = "OSM NOT LOADED"

    fun reset(clearMapCache: Boolean = false) {
        matcher = LaneMatcher()
        lastReplayTimestamp = null
        if (clearMapCache) {
            cachedLanes = emptyList()
            lastFetchPoint = null
            fetchStatus = "OSM NOT LOADED"
        }
    }

    fun analyze(sample: DriveReplaySample): ReplayLaneAnalysis {
        val state = sample.state
        val lat = state.latitude
        val lon = state.longitude
        val accuracy = state.accuracyMeters
        if (lat == null || lon == null || accuracy == null) {
            return ReplayLaneAnalysis(status = "CURRENT ENGINE · MISSING POSITION/ACCURACY")
        }

        val timestamp = sample.recordedAtMillis
        val previous = lastReplayTimestamp
        if (previous != null && (timestamp <= previous || timestamp - previous > MATCHER_RESET_GAP_MS)) {
            matcher = LaneMatcher()
        }
        lastReplayTimestamp = timestamp

        val position = GeoPoint(lat, lon)
        maybeRefreshMap(position)
        val allLanes = cachedLanes
        if (allLanes.isEmpty()) {
            return ReplayLaneAnalysis(
                status = "CURRENT ENGINE · NO OSM LANES · $fetchStatus",
                fetchStatus = fetchStatus,
            )
        }

        val headingChoice = chooseHeading(sample)
        val heading = headingChoice.degrees
        val measured = allLanes.map { lane ->
            val distance = pointToPolylineMeters(position, lane.centerline)
            val laneHeading = laneBearingDegreesNearPoint(lane, position)
            val headingDiff = if (heading != null && laneHeading != null) {
                angleDifferenceDegrees(heading, laneHeading)
            } else {
                0.0
            }
            Candidate(lane, distance, headingDiff)
        }

        val withinDistance = measured.filter { it.distanceMeters <= CANDIDATE_MAX_DISTANCE_M }
        val nearby = withinDistance
            .filter { heading == null || it.headingErrorDegrees <= CANDIDATE_MAX_HEADING_ERROR_DEG }
            .sortedWith(compareBy<Candidate> { it.distanceMeters }.thenBy { it.headingErrorDegrees })
            .take(CANDIDATE_LIMIT)

        if (nearby.isEmpty()) {
            val rejection = if (withinDistance.isEmpty()) {
                val nearest = measured.minOfOrNull { it.distanceMeters }
                if (nearest == null || !nearest.isFinite()) "DISTANCE UNKNOWN"
                else "DISTANCE ${"%.0f".format(nearest)} m"
            } else {
                val error = withinDistance.minOfOrNull { it.headingErrorDegrees } ?: 999.0
                "HEADING ${"%.0f".format(error)}°"
            }
            return ReplayLaneAnalysis(
                status = "CURRENT ENGINE · NO PLAUSIBLE LANE · $rejection",
                candidateCount = 0,
                headingSource = headingChoice.source,
                headingDegrees = heading,
                headingDisagreementDegrees = headingChoice.disagreementDegrees,
                fetchStatus = fetchStatus,
            )
        }

        val candidates = nearby.map { it.lane }
        val estimate = matcher.update(
            observation = Observation(
                timestampMillis = timestamp,
                position = position,
                horizontalAccuracyMeters = accuracy.toDouble(),
                speedMps = (state.speedMps ?: 0f).toDouble(),
                bearingDegrees = heading,
                lateralAccelerationMps2 = state.lateralAccelerationMps2?.toDouble(),
                sensorHeadingDegrees = state.sensorHeadingDegrees?.toDouble(),
            ),
            candidates = candidates,
        )

        val topLane = candidates.firstOrNull { it.id == estimate.mostLikelyLaneId }
            ?: return ReplayLaneAnalysis(
                status = "CURRENT ENGINE · CANDIDATES BUT NO MATCH",
                candidateCount = candidates.size,
                headingSource = headingChoice.source,
                headingDegrees = heading,
                headingDisagreementDegrees = headingChoice.disagreementDegrees,
                fetchStatus = fetchStatus,
            )

        val sameSegment = allLanes.filter { it.segmentId == topLane.segmentId }
        val stale = (sample.fixAgeMillis ?: Long.MAX_VALUE) > STALE_FIX_MS
        val sourceReady = topLane.sourceConfidence >= EXACT_SOURCE_CONFIDENCE_MIN
        val confidenceReady = estimate.confidence >= EXACT_MATCH_CONFIDENCE_MIN
        val exact = estimate.claimExactLane &&
            state.sensorFrameCalibrated &&
            !stale &&
            accuracy <= 5f &&
            sourceReady &&
            confidenceReady

        val blocker = when {
            exact -> "READY"
            stale -> "STALE_GNSS"
            accuracy > 5f -> "GNSS_ACCURACY"
            !state.sensorFrameCalibrated -> "PHONE_FRAME"
            !sourceReady -> "MAP_SOURCE"
            !confidenceReady -> "MATCH_CONFIDENCE"
            else -> "STABILITY_OR_TRANSITION"
        }

        return ReplayLaneAnalysis(
            status = if (exact) {
                "CURRENT ENGINE · EXACT LANE READY"
            } else {
                "CURRENT ENGINE · LIKELY LANE · BLOCK $blocker"
            },
            candidateCount = candidates.size,
            laneNumberFromLeft = topLane.index + 1,
            laneCount = sameSegment.size.coerceAtLeast(1),
            confidence = estimate.confidence.toFloat(),
            exactClaim = exact,
            headingSource = headingChoice.source,
            headingDegrees = heading,
            headingDisagreementDegrees = headingChoice.disagreementDegrees,
            sourceConfidence = topLane.sourceConfidence.toFloat(),
            fetchStatus = fetchStatus,
        )
    }

    private fun maybeRefreshMap(position: GeoPoint) {
        val moved = lastFetchPoint?.let { distanceMeters(it, position) } ?: Double.POSITIVE_INFINITY
        if (cachedLanes.isNotEmpty() && moved < MAP_REFRESH_DISTANCE_M) return

        try {
            val corridor = osmClient.fetchCorridor(position.lat, position.lon, OSM_FETCH_RADIUS_M)
            val merged = LinkedHashMap<String, Lane>()
            cachedLanes.forEach { merged[it.id] = it }
            corridor.lanes.forEach { merged[it.id] = it }
            cachedLanes = merged.values.filter {
                pointToPolylineMeters(position, it.centerline) <= MAP_RETAIN_DISTANCE_M
            }
            lastFetchPoint = position
            val host = corridor.endpoint.substringAfter("://").substringBefore('/').take(28)
            fetchStatus = "OSM $host · ${cachedLanes.size} lanes cached"
        } catch (exc: Exception) {
            val detail = exc.message?.replace('\n', ' ')?.take(90) ?: exc.javaClass.simpleName
            fetchStatus = "OSM ERROR · $detail"
        }
    }

    /**
     * A loose phone can rotate independently from the vehicle. At road speed GNSS course
     * is generally the safer road-heading source, so prefer it aggressively there.
     */
    private fun chooseHeading(sample: DriveReplaySample): HeadingChoice {
        val state = sample.state
        val speed = state.speedMps ?: 0f
        val gnss = state.bearingDegrees?.toDouble()
        val fused = state.fusedHeadingDegrees?.toDouble()
        val disagreement = if (gnss != null && fused != null) angleDifferenceDegrees(gnss, fused) else null

        return when {
            speed >= HIGH_SPEED_GNSS_PRIORITY_MPS && gnss != null ->
                HeadingChoice(gnss, "GNSS · HIGH SPEED", disagreement)
            speed >= MOVING_GNSS_GUARD_MPS && gnss != null &&
                (disagreement ?: 0.0) >= HEADING_DISAGREEMENT_GUARD_DEG ->
                HeadingChoice(gnss, "GNSS · PHONE MOVE GUARD", disagreement)
            fused != null -> HeadingChoice(fused, "FUSED", disagreement)
            gnss != null -> HeadingChoice(gnss, "GNSS", disagreement)
            else -> HeadingChoice(null, "NONE", disagreement)
        }
    }

    private fun pointToPolylineMeters(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        val lat0 = point.lat * PI / 180.0
        fun xy(p: GeoPoint): Pair<Double, Double> {
            val x = (p.lon - point.lon) * PI / 180.0 * EARTH_RADIUS_M * cos(lat0)
            val y = (p.lat - point.lat) * PI / 180.0 * EARTH_RADIUS_M
            return x to y
        }
        if (line.size == 1) {
            val (x, y) = xy(line.first())
            return hypot(x, y)
        }
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.lastIndex) {
            val (ax, ay) = xy(line[i])
            val (bx, by) = xy(line[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val denom = dx * dx + dy * dy
            val t = if (denom <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / denom).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    private fun laneBearingDegreesNearPoint(lane: Lane, point: GeoPoint): Double? {
        if (lane.centerline.size < 2) return null
        var bestIndex = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in 0 until lane.centerline.lastIndex) {
            val d = pointToPolylineMeters(point, listOf(lane.centerline[i], lane.centerline[i + 1]))
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
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

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dp = (b.lat - a.lat) * PI / 180.0
        val dl = (b.lon - a.lon) * PI / 180.0
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1.0 - h))
    }

    private data class Candidate(
        val lane: Lane,
        val distanceMeters: Double,
        val headingErrorDegrees: Double,
    )

    private data class HeadingChoice(
        val degrees: Double?,
        val source: String,
        val disagreementDegrees: Double?,
    )

    private companion object {
        const val OSM_FETCH_RADIUS_M = 1600
        const val MAP_REFRESH_DISTANCE_M = 850.0
        const val MAP_RETAIN_DISTANCE_M = 4_500.0
        const val CANDIDATE_MAX_DISTANCE_M = 45.0
        const val CANDIDATE_MAX_HEADING_ERROR_DEG = 35.0
        const val CANDIDATE_LIMIT = 20
        const val EXACT_SOURCE_CONFIDENCE_MIN = 0.75
        const val EXACT_MATCH_CONFIDENCE_MIN = 0.70
        const val STALE_FIX_MS = 3_000L
        const val MATCHER_RESET_GAP_MS = 5_000L
        const val HIGH_SPEED_GNSS_PRIORITY_MPS = 8.0f
        const val MOVING_GNSS_GUARD_MPS = 4.5f
        const val HEADING_DISAGREEMENT_GUARD_DEG = 25.0
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

data class ReplayLaneAnalysis(
    val status: String,
    val candidateCount: Int = 0,
    val laneNumberFromLeft: Int? = null,
    val laneCount: Int? = null,
    val confidence: Float = 0f,
    val exactClaim: Boolean = false,
    val headingSource: String = "NONE",
    val headingDegrees: Double? = null,
    val headingDisagreementDegrees: Double? = null,
    val sourceConfidence: Float = 0f,
    val fetchStatus: String = "",
)
