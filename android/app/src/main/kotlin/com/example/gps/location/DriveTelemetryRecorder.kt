package com.example.gps.location

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
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
        val relativeFolder = Environment.DIRECTORY_DOWNLOADS + "/LaneGPS/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        return try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                logFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open Downloads output")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "LaneGPS/$displayName"
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun buildRow(state: GnssUiState): String {
        val recordedAtMillis = System.currentTimeMillis()
        val fixTimestampMillis = state.lastUpdateMillis
        val fixAgeMillis = fixTimestampMillis?.let {
            (recordedAtMillis - it).coerceAtLeast(0L)
        }
        val fixStale = fixAgeMillis != null && fixAgeMillis > STALE_FIX_MS

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
        ).joinToString(",")
    }

    private fun number(value: Number?): String = value?.let {
        String.format(Locale.US, "%.6f", it.toDouble())
    } ?: ""

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        private const val STALE_FIX_MS = 3000L
        private const val HEADER =
            "timestamp_ms,lat,lon,accuracy_m,quality_score,quality_label,sat_used,sat_visible,avg_cn0_dbhz," +
                "speed_mps,gnss_bearing_deg,sensor_heading_deg,fused_heading_deg,lateral_mps2,yaw_deg_s," +
                "motion_hint,calibrated,sensor_lane_ready,lane_status,lane_candidates,lane_from_left," +
                "lane_count,lane_confidence,exact_lane_claim,recorded_at_ms,fix_timestamp_ms,fix_age_ms,fix_stale"
    }
}
