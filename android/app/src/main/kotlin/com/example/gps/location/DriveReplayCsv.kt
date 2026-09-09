package com.example.gps.location

import kotlin.math.max

data class DriveReplaySample(
    val recordedAtMillis: Long,
    val fixTimestampMillis: Long?,
    val fixAgeMillis: Long?,
    val state: GnssUiState,
)

object DriveReplayCsv {
    fun parse(csvText: String): List<DriveReplaySample> {
        val rows = csvText.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (rows.size < 2) return emptyList()

        val header = parseCsvLine(rows.first())
        val index = header.withIndex().associate { it.value to it.index }
        if (!index.containsKey("lat") || !index.containsKey("lon")) return emptyList()

        val result = ArrayList<DriveReplaySample>(rows.size - 1)
        var previousRecordedAt: Long? = null

        rows.drop(1).forEach { line ->
            val columns = parseCsvLine(line)
            fun raw(name: String): String? = index[name]
                ?.takeIf { it in columns.indices }
                ?.let { columns[it] }
                ?.takeIf { it.isNotBlank() }
            fun long(name: String): Long? = raw(name)?.toLongOrNull()
            fun int(name: String): Int? = raw(name)?.toIntOrNull()
            fun float(name: String): Float? = raw(name)?.toFloatOrNull()
            fun double(name: String): Double? = raw(name)?.toDoubleOrNull()
            fun bool(name: String): Boolean = raw(name)?.equals("true", ignoreCase = true) == true

            val lat = double("lat") ?: return@forEach
            val lon = double("lon") ?: return@forEach

            // Legacy files used timestamp_ms for the GNSS fix time. New files also
            // contain recorded_at_ms so stale-fix time can be distinguished from
            // logger time. For legacy files, reconstruct an approximate 1 Hz logger
            // timeline while never moving backward past an actual GNSS timestamp.
            val legacyTimestamp = long("timestamp_ms")
            val fixTimestamp = long("fix_timestamp_ms") ?: legacyTimestamp
            val explicitRecordedAt = long("recorded_at_ms")
            val reconstructed = when {
                explicitRecordedAt != null -> explicitRecordedAt
                previousRecordedAt == null -> fixTimestamp ?: 0L
                fixTimestamp == null -> previousRecordedAt!! + LEGACY_ROW_INTERVAL_MS
                else -> max(previousRecordedAt!! + LEGACY_ROW_INTERVAL_MS, fixTimestamp)
            }
            previousRecordedAt = reconstructed

            val explicitFixAge = long("fix_age_ms")
            val fixAge = explicitFixAge ?: fixTimestamp?.let {
                (reconstructed - it).coerceAtLeast(0L)
            }
            val stale = raw("fix_stale")?.equals("true", ignoreCase = true)
                ?: ((fixAge ?: 0L) > STALE_FIX_MS)

            val laneFromLeft = int("lane_from_left")
            val laneCount = int("lane_count")
            val qualityLabel = raw("quality_label") ?: "UNAVAILABLE"
            val message = when {
                stale && fixAge != null -> "REPLAY · GNSS STALE ${fixAge / 1000.0}s"
                else -> "REPLAY · GNSS $qualityLabel"
            }

            val state = GnssUiState(
                permissionFine = true,
                permissionCoarse = true,
                providerEnabled = true,
                fixReceived = true,
                latitude = lat,
                longitude = lon,
                accuracyMeters = float("accuracy_m"),
                speedMps = float("speed_mps"),
                bearingDegrees = float("gnss_bearing_deg"),
                satellitesVisible = int("sat_visible") ?: 0,
                satellitesUsedInFix = int("sat_used") ?: 0,
                averageUsedCn0DbHz = float("avg_cn0_dbhz"),
                provider = "replay",
                lastUpdateMillis = fixTimestamp,
                sensorHeadingDegrees = float("sensor_heading_deg"),
                fusedHeadingDegrees = float("fused_heading_deg"),
                lateralAccelerationMps2 = float("lateral_mps2"),
                yawRateDegS = float("yaw_deg_s"),
                motionHint = raw("motion_hint") ?: "REPLAY",
                sensorFrameCalibrated = bool("calibrated"),
                rotationSensorAvailable = true,
                linearAccelerationAvailable = true,
                gyroscopeAvailable = true,
                qualityScore = int("quality_score") ?: 0,
                qualityLabel = qualityLabel,
                sensorLaneReady = bool("sensor_lane_ready") && !stale,
                qualityReason = if (stale) "Recorded GNSS fix was stale" else "Recorded drive sample",
                message = message,
                laneDataStatus = raw("lane_status") ?: "REPLAY",
                laneCandidateCount = int("lane_candidates") ?: 0,
                likelyLaneNumberFromLeft = laneFromLeft,
                likelyLaneCount = laneCount,
                laneConfidence = float("lane_confidence") ?: 0f,
                laneExactClaim = bool("exact_lane_claim") && !stale,
            )

            result += DriveReplaySample(
                recordedAtMillis = reconstructed,
                fixTimestampMillis = fixTimestamp,
                fixAgeMillis = fixAge,
                state = state,
            )
        }

        return result
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    private const val LEGACY_ROW_INTERVAL_MS = 1000L
    private const val STALE_FIX_MS = 3000L
}
