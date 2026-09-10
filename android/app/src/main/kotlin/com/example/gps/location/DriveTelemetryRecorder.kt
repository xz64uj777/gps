package com.example.gps.location

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.gps.route.NavigationTelemetryRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveTelemetryRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val logDir = File(appContext.filesDir, "drive-logs")
    private val logFile = File(logDir, "lane-gps-last-drive.csv")

    fun startNew() {
        logDir.mkdirs()
        NavigationTelemetryRuntime.resetForDrive()
        logFile.writeText(HEADER + "\n")
    }

    fun ensureLog() {
        if (!logFile.exists()) startNew()
    }

    fun append(state: GnssUiState) {
        ensureLog()
        logFile.appendText(buildRow(state) + "\n")
    }

    fun hasLog(): Boolean = logFile.exists() && logFile.length() > HEADER.length + 1

    fun exportToDownloads(): String? {
        if (!hasLog()) return null

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val displayName = "LaneGPS-drive-$timestamp.csv"
        val resolver = appContext.contentResolver
        val relativeFolder = "${Environment.DIRECTORY_DOWNLOADS}/LaneGPS"
        val exportFile = buildTrimmedExportFile()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: run {
            exportFile.delete()
            return null
        }

        return try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                exportFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open Downloads output")

            values.clear()
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "LaneGPS/$displayName"
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        } finally {
            exportFile.delete()
        }
    }

    private fun buildTrimmedExportFile(): File {
        val output = File(appContext.cacheDir, "lane-gps-export.csv")
        val lines = logFile.readLines()
        if (lines.size <= 2) {
            logFile.copyTo(output, overwrite = true)
            return output
        }

        var lastUsefulRecordedAt: Long? = null
        for (line in lines.drop(1)) {
            val fields = parseCsvLine(line)
            if (fields.size <= FIX_STALE_INDEX) continue
            val recordedAt = fields.getOrNull(RECORDED_AT_INDEX)?.toLongOrNull() ?: continue
            val fixAge = fields.getOrNull(FIX_AGE_INDEX)?.toLongOrNull() ?: Long.MAX_VALUE
            val accuracy = fields.getOrNull(ACCURACY_INDEX)?.toDoubleOrNull() ?: Double.POSITIVE_INFINITY
            val speed = fields.getOrNull(SPEED_INDEX)?.toDoubleOrNull() ?: 0.0
            val stale = fields.getOrNull(FIX_STALE_INDEX)?.toBooleanStrictOrNull() ?: true

            if (!stale && fixAge <= STALE_FIX_MS && accuracy <= USEFUL_ACCURACY_M && speed >= MOVING_SPEED_MPS) {
                lastUsefulRecordedAt = recordedAt
            }
        }

        val lastUseful = lastUsefulRecordedAt
        if (lastUseful == null) {
            logFile.copyTo(output, overwrite = true)
            return output
        }

        val cutoff = lastUseful + EXPORT_TAIL_GRACE_MS
        output.bufferedWriter().use { writer ->
            writer.appendLine(lines.first())
            for (line in lines.drop(1)) {
                val fields = parseCsvLine(line)
                val recordedAt = fields.getOrNull(RECORDED_AT_INDEX)?.toLongOrNull()
                if (recordedAt != null && recordedAt > cutoff) break
                writer.appendLine(line)
            }
        }
        return output
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        values += current.toString()
        return values
    }

    private fun buildRow(state: GnssUiState): String {
        val recordedAtMillis = System.currentTimeMillis()
        val fixTimestampMillis = state.lastUpdateMillis
        val fixAgeMillis = fixTimestampMillis?.let {
            (recordedAtMillis - it).coerceAtLeast(0L)
        }
        val fixStale = fixAgeMillis != null && fixAgeMillis > STALE_FIX_MS
        val nav = NavigationTelemetryRuntime.snapshot()

        return listOf(
            (fixTimestampMillis ?: recordedAtMillis).toString(),
            number(state.latitude),
            number(state.longitude),
            number(state.accuracyMeters),
            state.qualityScore.toString(),
            csv(state.qualityLabel),
            state.satellitesUsedInFix.toString(),
            state.satellitesVisible.toString(),
            number(state.averageUsedCn0DbHz),
            number(state.speedMps),
            number(state.bearingDegrees),
            number(state.sensorHeadingDegrees),
            number(state.fusedHeadingDegrees),
            number(state.lateralAccelerationMps2),
            number(state.yawRateDegS),
            csv(state.motionHint),
            state.sensorFrameCalibrated.toString(),
            state.sensorLaneReady.toString(),
            csv(state.laneDataStatus),
            state.laneCandidateCount.toString(),
            state.likelyLaneNumberFromLeft?.toString() ?: "",
            state.likelyLaneCount?.toString() ?: "",
            number(state.laneConfidence),
            state.laneExactClaim.toString(),
            recordedAtMillis.toString(),
            fixTimestampMillis?.toString() ?: "",
            fixAgeMillis?.toString() ?: "",
            fixStale.toString(),
            csv(nav.event),
            csv(nav.destination),
            csv(nav.nextManeuver),
            csv(nav.nextRoad),
            number(nav.nextManeuverDistanceMeters),
            number(nav.remainingDistanceMeters),
            number(nav.remainingSeconds),
            number(nav.offRouteDistanceMeters),
            nav.routePointIndex?.toString() ?: "",
            nav.maneuverIndex?.toString() ?: "",
            nav.arrived.toString(),
            nav.rerouteCount.toString(),
            nav.updatedAtMillis.takeIf { it > 0L }?.toString() ?: "",
        ).joinToString(",")
    }

    private fun number(value: Number?): String = value?.let {
        String.format(Locale.US, "%.6f", it.toDouble())
    } ?: ""

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        private const val STALE_FIX_MS = 3000L
        private const val USEFUL_ACCURACY_M = 50.0
        private const val MOVING_SPEED_MPS = 1.0
        private const val EXPORT_TAIL_GRACE_MS = 90_000L

        private const val ACCURACY_INDEX = 3
        private const val SPEED_INDEX = 9
        private const val RECORDED_AT_INDEX = 24
        private const val FIX_AGE_INDEX = 26
        private const val FIX_STALE_INDEX = 27

        private const val HEADER =
            "timestamp_ms,lat,lon,accuracy_m,quality_score,quality_label,sat_used,sat_visible,avg_cn0_dbhz," +
                "speed_mps,gnss_bearing_deg,sensor_heading_deg,fused_heading_deg,lateral_mps2,yaw_deg_s," +
                "motion_hint,calibrated,sensor_lane_ready,lane_status,lane_candidates,lane_from_left," +
                "lane_count,lane_confidence,exact_lane_claim,recorded_at_ms,fix_timestamp_ms,fix_age_ms,fix_stale," +
                "nav_event,nav_destination,nav_next_maneuver,nav_next_road,nav_maneuver_distance_m," +
                "nav_remaining_distance_m,nav_remaining_seconds,nav_off_route_m,nav_route_point_index," +
                "nav_maneuver_index,nav_arrived,nav_reroute_count,nav_updated_at_ms"
    }
}
