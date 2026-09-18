package com.example.gps.laneengine

import kotlin.math.exp
import kotlin.math.max

class LaneMatcher(
    private val exactLaneThreshold: Double = 0.70,
    private val highConfidenceThreshold: Double = 0.85,
    private val maxExactLaneAccuracyMeters: Double = 5.0,
    private val minExactLaneSourceConfidence: Double = 0.75,
    private val laneChangeConfirmationFixes: Int = 3,
    private val exactLaneStableFixes: Int = 3,
) {
    private var previous: LaneEstimate? = null
    private var lastObservationTimestamp: Long? = null
    private var pendingLaneId: String? = null
    private var pendingLaneFixes: Int = 0
    private var stableLaneFixes: Int = 0

    data class Weights(
        val distance: Double = 2.5,
        val heading: Double = 1.0,
        val route: Double = 0.8,
        val continuity: Double = 1.3,
        val sourceConfidence: Double = 0.5,
    )

    fun update(
        observation: Observation,
        candidates: List<Lane>,
        route: RouteContext = RouteContext(),
        weights: Weights = Weights(),
    ): LaneEstimate {
        if (candidates.isEmpty()) {
            pendingLaneId = null
            pendingLaneFixes = 0
            stableLaneFixes = 0
            lastObservationTimestamp = observation.timestampMillis
            return LaneEstimate(null, 0.0, emptyList(), false).also { previous = it }
        }

        val prevLane = previous?.mostLikelyLaneId
        val laneDistances = candidates.associate { lane ->
            lane.id to Geo.pointToPolylineMeters(observation.position, lane.centerline)
        }
        val raw = candidates.associate { lane ->
            val distance = laneDistances[lane.id] ?: Double.POSITIVE_INFINITY
            val sigma = max(2.0, observation.horizontalAccuracyMeters)
            val distanceScore = exp(-0.5 * (distance / sigma) * (distance / sigma))
            val headingScore = if (observation.bearingDegrees != null) {
                val laneBearing = Geo.laneBearingDegrees(lane)
                if (laneBearing == null) 0.5
                else ((180.0 - Geo.angleDifferenceDegrees(
                    observation.bearingDegrees,
                    laneBearing,
                )) / 180.0).coerceIn(0.0, 1.0)
            } else 0.5
            val routeScore = if (lane.id in route.preferredLaneIds) 1.0 else 0.4
            val continuity = when {
                prevLane == null -> 0.5
                prevLane == lane.id -> 1.0
                else -> 0.35
            }
            val score =
                weights.distance * distanceScore +
                    weights.heading * headingScore +
                    weights.route * routeScore +
                    weights.continuity * continuity +
                    weights.sourceConfidence * lane.sourceConfidence
            lane.id to max(0.000001, score)
        }

        val total = raw.values.sum()
        val probabilities = raw.map {
            LaneProbability(it.key, it.value / total)
        }.sortedByDescending { it.probability }

        val rawTop = probabilities.first()
        val isDistinctFix = observation.timestampMillis != lastObservationTimestamp
        val previousStillCandidate = prevLane != null && candidates.any { it.id == prevLane }

        var selectedLaneId = rawTop.laneId
        var transitionPending = false

        if (prevLane != null && rawTop.laneId != prevLane && previousStillCandidate) {
            if (isDistinctFix) {
                if (pendingLaneId == rawTop.laneId) {
                    pendingLaneFixes++
                } else {
                    pendingLaneId = rawTop.laneId
                    pendingLaneFixes = 1
                }
            }

            if (pendingLaneFixes < laneChangeConfirmationFixes) {
                selectedLaneId = prevLane
                transitionPending = true
            } else {
                selectedLaneId = rawTop.laneId
                pendingLaneId = null
                pendingLaneFixes = 0
            }
        } else {
            pendingLaneId = null
            pendingLaneFixes = 0
        }

        if (isDistinctFix) {
            stableLaneFixes = if (selectedLaneId == prevLane) stableLaneFixes + 1 else 1
            lastObservationTimestamp = observation.timestampMillis
        }

        // Confidence must not collapse simply because a road has 4–6 lanes.
        // The previous normalized-probability formula made a 0.70 exact threshold
        // mathematically unreachable on most multilane roads. Instead, measure:
        // 1) fit to the selected lane centerline, 2) separation from the nearest
        // adjacent candidate, 3) persistence across distinct GNSS fixes, and
        // 4) source quality. Exact-lane safety gates below are unchanged.
        val selectedDistance = laneDistances[selectedLaneId] ?: Double.POSITIVE_INFINITY
        val runnerDistance = laneDistances
            .asSequence()
            .filter { it.key != selectedLaneId }
            .map { it.value }
            .minOrNull()
        val positionSigma = max(1.5, observation.horizontalAccuracyMeters * 0.5)
        val positionFit = if (selectedDistance.isFinite()) {
            exp(-0.5 * (selectedDistance / positionSigma) * (selectedDistance / positionSigma))
        } else 0.0
        val separationScore = if (runnerDistance == null || !runnerDistance.isFinite()) {
            1.0
        } else {
            val separationMeters = runnerDistance - selectedDistance
            val requiredSeparation = max(2.5, observation.horizontalAccuracyMeters * 0.75)
            (separationMeters / requiredSeparation).coerceIn(0.0, 1.0)
        }
        val stabilityScore =
            (stableLaneFixes.toDouble() / exactLaneStableFixes.coerceAtLeast(1).toDouble())
                .coerceIn(0.0, 1.0)
        val selectedSourceConfidence =
            candidates.firstOrNull { it.id == selectedLaneId }?.sourceConfidence ?: 0.0
        val confidence =
            (0.40 * positionFit +
                0.30 * separationScore +
                0.15 * stabilityScore +
                0.15 * selectedSourceConfidence)
                .coerceIn(0.0, 1.0)
        val accuracyAllowsExactLane =
            observation.horizontalAccuracyMeters <= maxExactLaneAccuracyMeters
        val sourceAllowsExactLane = selectedSourceConfidence >= minExactLaneSourceConfidence
        val exactLane =
            confidence >= exactLaneThreshold &&
                accuracyAllowsExactLane &&
                sourceAllowsExactLane &&
                stableLaneFixes >= exactLaneStableFixes &&
                !transitionPending

        return LaneEstimate(
            mostLikelyLaneId = selectedLaneId,
            confidence = confidence,
            probabilities = probabilities,
            claimExactLane = exactLane,
        ).also { previous = it }
    }

    fun isHighConfidence(estimate: LaneEstimate) =
        estimate.confidence >= highConfidenceThreshold
}
