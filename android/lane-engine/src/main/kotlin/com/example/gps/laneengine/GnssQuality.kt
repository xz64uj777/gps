package com.example.gps.laneengine

enum class GnssQualityGrade {
    EXCELLENT,
    STRONG,
    FAIR,
    WEAK,
    UNAVAILABLE,
}

data class GnssQualityInput(
    val hasFix: Boolean,
    val finePermission: Boolean,
    val gpsProvider: Boolean,
    val accuracyMeters: Double?,
    val satellitesUsed: Int,
    val satellitesVisible: Int,
    val averageUsedCn0DbHz: Double?,
    val fixAgeMillis: Long?,
)

data class GnssQualityResult(
    val score: Int,
    val grade: GnssQualityGrade,
    val laneSensorsReady: Boolean,
    val reason: String,
)

object GnssQualityEvaluator {
    fun evaluate(input: GnssQualityInput): GnssQualityResult {
        if (!input.hasFix) {
            return GnssQualityResult(
                score = 0,
                grade = GnssQualityGrade.UNAVAILABLE,
                laneSensorsReady = false,
                reason = "Waiting for GNSS fix",
            )
        }

        var score = 0
        if (input.finePermission) score += 15
        if (input.gpsProvider) score += 10

        score += when (val accuracy = input.accuracyMeters) {
            null -> 0
            in 0.0..2.5 -> 35
            in 2.5..4.0 -> 30
            in 4.0..6.0 -> 24
            in 6.0..10.0 -> 16
            in 10.0..20.0 -> 8
            else -> 2
        }

        score += when {
            input.satellitesUsed >= 14 -> 20
            input.satellitesUsed >= 10 -> 17
            input.satellitesUsed >= 7 -> 13
            input.satellitesUsed >= 4 -> 7
            input.satellitesUsed > 0 -> 2
            else -> 0
        }

        score += when (val cn0 = input.averageUsedCn0DbHz) {
            null -> 0
            else -> when {
                cn0 >= 35.0 -> 10
                cn0 >= 30.0 -> 8
                cn0 >= 25.0 -> 5
                else -> 2
            }
        }

        score += when (val age = input.fixAgeMillis) {
            null -> 0
            in 0L..1500L -> 10
            in 1501L..3000L -> 6
            in 3001L..5000L -> 3
            else -> 0
        }

        score = score.coerceIn(0, 100)

        val fresh = (input.fixAgeMillis ?: Long.MAX_VALUE) <= 3000L
        val accurate = (input.accuracyMeters ?: Double.POSITIVE_INFINITY) <= 5.0
        val enoughSatellites = input.satellitesUsed >= 6
        val signalUsable = input.averageUsedCn0DbHz == null || input.averageUsedCn0DbHz >= 20.0

        val ready = input.finePermission &&
            input.gpsProvider &&
            fresh &&
            accurate &&
            enoughSatellites &&
            signalUsable

        val reason = when {
            !input.finePermission -> "Precise location permission required"
            !input.gpsProvider -> "Waiting for direct GPS/GNSS fix"
            !fresh -> "GNSS fix is stale"
            !accurate -> "Horizontal accuracy must be 5 m or better"
            !enoughSatellites -> "Need at least 6 satellites in the fix"
            !signalUsable -> "GNSS signal strength is weak"
            else -> "Sensor gate ready; map lane graph still required"
        }

        val grade = when {
            score >= 90 -> GnssQualityGrade.EXCELLENT
            score >= 75 -> GnssQualityGrade.STRONG
            score >= 55 -> GnssQualityGrade.FAIR
            else -> GnssQualityGrade.WEAK
        }

        return GnssQualityResult(
            score = score,
            grade = grade,
            laneSensorsReady = ready,
            reason = reason,
        )
    }
}
