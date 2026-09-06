package com.example.gps.laneengine

import kotlin.math.exp
import kotlin.math.max

class LaneMatcher(
    private val exactLaneThreshold: Double = 0.70,
    private val highConfidenceThreshold: Double = 0.85,
    private val maxExactLaneAccuracyMeters: Double = 5.0,
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
        val raw = candidates.associate { lane ->
            val distance = Geo.pointToPolylineMeters(observation.position, lane.centerline)
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

        val selectedProbability = probabilities.firstOrNull { it.laneId == selectedLaneId }?.probability ?: 0.0
        val runner = probabilities
            .asSequence()
            .filter { it.laneId != selectedLaneId }
            .map { it.probability }
            .maxOrNull() ?: 0.0
        val separation = (selectedProbability - runner).coerceIn(0.0, 1.0)
        val gpsQuality =
            (1.0 - observation.horizontalAccuracyMeters / 25.0).coerceIn(0.15, 1.0)
        val confidence =
            (0.65 * selectedProbability + 0.25 * separation + 0.10 * gpsQuality)
                .coerceIn(0.0, 1.0)

        val accuracyAllowsExactLane =
            observation.horizontalAccuracyMeters <= maxExactLaneAccuracyMeters
        val exactLane =
            confidence >= exactLaneThreshold &&
                accuracyAllowsExactLane &&
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
