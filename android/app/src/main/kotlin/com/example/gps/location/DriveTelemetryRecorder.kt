package com.example.gps.location

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
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

    fun buildShareIntent(): Intent? {
        if (!hasLog()) return null
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            logFile,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Lane GPS drive telemetry")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildRow(state: GnssUiState): String = listOf(
        (state.lastUpdateMillis ?: System.currentTimeMillis()).toString(),
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
    ).joinToString(",")

    private fun number(value: Number?): String = value?.let {
        String.format(Locale.US, "%.6f", it.toDouble())
    } ?: ""

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        private const val HEADER =
            "timestamp_ms,lat,lon,accuracy_m,quality_score,quality_label,sat_used,sat_visible,avg_cn0_dbhz," +
                "speed_mps,gnss_bearing_deg,sensor_heading_deg,fused_heading_deg,lateral_mps2,yaw_deg_s," +
                "motion_hint,calibrated,sensor_lane_ready,lane_status,lane_candidates,lane_from_left," +
                "lane_count,lane_confidence,exact_lane_claim"
    }
}
