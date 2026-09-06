package com.example.gps.laneengine

data class GeoPoint(val lat: Double, val lon: Double)
data class Lane(
    val id: String,
    val segmentId: String,
    val index: Int,
    val centerline: List<GeoPoint>,
    val widthMeters: Double = 3.6,
    val changeLeft: Boolean = true,
    val changeRight: Boolean = true,
    val sourceConfidence: Double = 1.0,
)
data class LaneConnection(
    val fromLaneId: String,
    val toLaneId: String,
    val legal: Boolean = true,
    val cost: Double = 1.0,
)
data class Observation(
    val timestampMillis: Long,
    val position: GeoPoint,
    val horizontalAccuracyMeters: Double,
    val speedMps: Double,
    val bearingDegrees: Double?,
    val lateralAccelerationMps2: Double? = null,
    val sensorHeadingDegrees: Double? = null,
    val gyroscopeZRadS: Double? = null,
    val verticalAccuracyMeters: Double? = null,
)
data class LaneProbability(val laneId: String, val probability: Double)
data class LaneEstimate(
    val mostLikelyLaneId: String?,
    val confidence: Double,
    val probabilities: List<LaneProbability>,
    val claimExactLane: Boolean,
)
data class RouteContext(val preferredLaneIds: Set<String> = emptySet())
data class LanePlan(val laneIds: List<String>, val totalCost: Double)
