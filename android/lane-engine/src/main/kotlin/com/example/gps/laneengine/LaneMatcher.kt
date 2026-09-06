package com.example.gps.laneengine

import kotlin.math.exp
import kotlin.math.max

class LaneMatcher(
    private val exactLaneThreshold: Double = 0.70,
    private val highConfidenceThreshold: Double = 0.85,
    private val maxExactLaneAccuracyMeters: Double = 5.0,
) {
    private var previous: LaneEstimate? = null

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

        val top = probabilities.first()
        val runner = probabilities.getOrNull(1)?.probability ?: 0.0
        val separation = (top.probability - runner).coerceIn(0.0, 1.0)
        val gpsQuality =
            (1.0 - observation.horizontalAccuracyMeters / 25.0).coerceIn(0.15, 1.0)
        val confidence =
            (0.65 * top.probability + 0.25 * separation + 0.10 * gpsQuality)
                .coerceIn(0.0, 1.0)

        // A single candidate must never turn a poor GNSS fix into a confident
        // exact-lane claim. The probability estimate remains useful, but the UI
        // must show uncertainty until horizontal accuracy is lane-scale again.
        val accuracyAllowsExactLane =
            observation.horizontalAccuracyMeters <= maxExactLaneAccuracyMeters
        val exactLane = confidence >= exactLaneThreshold && accuracyAllowsExactLane

        return LaneEstimate(
            mostLikelyLaneId = top.laneId,
            confidence = confidence,
            probabilities = probabilities,
            claimExactLane = exactLane,
        ).also { previous = it }
    }

    fun isHighConfidence(estimate: LaneEstimate) =
        estimate.confidence >= highConfidenceThreshold
}
