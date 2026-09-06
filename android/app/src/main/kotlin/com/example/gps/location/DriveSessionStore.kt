package com.example.gps.location

import android.content.Context

class DriveSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val telemetry = DriveTelemetryRecorder(context)

    fun isActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    fun setActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    fun clearSession() {
        prefs.edit().clear().commit()
        telemetry.startNew()
    }

    fun save(state: GnssUiState) {
        prefs.edit()
            .putInt(KEY_SAMPLES, state.sessionSamples)
            .apply {
                if (state.sessionBestAccuracyMeters != null) {
                    putFloat(KEY_BEST_ACCURACY, state.sessionBestAccuracyMeters)
                } else {
                    remove(KEY_BEST_ACCURACY)
                }
                if (state.sessionWorstAccuracyMeters != null) {
                    putFloat(KEY_WORST_ACCURACY, state.sessionWorstAccuracyMeters)
                } else {
                    remove(KEY_WORST_ACCURACY)
                }
            }
            .putInt(KEY_AVG_QUALITY, state.sessionAverageQuality)
            .putFloat(KEY_PEAK_LATERAL, state.sessionPeakLateralAccelerationMps2)
            .putFloat(KEY_PEAK_YAW, state.sessionPeakYawRateDegS)
            .putInt(KEY_LEFT_EVENTS, state.sessionLeftLateralEvents)
            .putInt(KEY_RIGHT_EVENTS, state.sessionRightLateralEvents)
            .putInt(KEY_TURN_EVENTS, state.sessionTurnEvents)
            .putBoolean(KEY_CALIBRATED, state.sessionCalibrationReached)
            .putInt(KEY_REJECTED_SPIKES, state.sessionRejectedMotionSpikes)
            .putInt(KEY_HEADING_SAMPLES, state.sessionHeadingSamples)
            .putFloat(KEY_AVG_HEADING_ERROR, state.sessionAverageHeadingErrorDeg)
            .putFloat(KEY_PEAK_HEADING_ERROR, state.sessionPeakHeadingErrorDeg)
            .putInt(KEY_LAST_QUALITY, state.qualityScore)
            .putString(KEY_LAST_QUALITY_LABEL, state.qualityLabel)
            .commit()
        telemetry.append(state)
    }

    fun load(): GnssUiState {
        val active = isActive()
        val hasSummary = prefs.contains(KEY_SAMPLES)
        if (!hasSummary) {
            return GnssUiState(
                message = if (active) "Drive test active · reconnecting…" else "Drive test not started",
            )
        }

        return GnssUiState(
            qualityScore = prefs.getInt(KEY_LAST_QUALITY, 0),
            qualityLabel = prefs.getString(KEY_LAST_QUALITY_LABEL, "UNAVAILABLE") ?: "UNAVAILABLE",
            message = if (active) "Drive test active · reconnecting…" else "Drive test stopped · summary saved",
            sessionSamples = prefs.getInt(KEY_SAMPLES, 0),
            sessionBestAccuracyMeters = if (prefs.contains(KEY_BEST_ACCURACY)) {
                prefs.getFloat(KEY_BEST_ACCURACY, 0f)
            } else {
                null
            },
            sessionWorstAccuracyMeters = if (prefs.contains(KEY_WORST_ACCURACY)) {
                prefs.getFloat(KEY_WORST_ACCURACY, 0f)
            } else {
                null
            },
            sessionAverageQuality = prefs.getInt(KEY_AVG_QUALITY, 0),
            sessionPeakLateralAccelerationMps2 = prefs.getFloat(KEY_PEAK_LATERAL, 0f),
            sessionPeakYawRateDegS = prefs.getFloat(KEY_PEAK_YAW, 0f),
            sessionLeftLateralEvents = prefs.getInt(KEY_LEFT_EVENTS, 0),
            sessionRightLateralEvents = prefs.getInt(KEY_RIGHT_EVENTS, 0),
            sessionTurnEvents = prefs.getInt(KEY_TURN_EVENTS, 0),
            sessionCalibrationReached = prefs.getBoolean(KEY_CALIBRATED, false),
            sessionRejectedMotionSpikes = prefs.getInt(KEY_REJECTED_SPIKES, 0),
            sessionHeadingSamples = prefs.getInt(KEY_HEADING_SAMPLES, 0),
            sessionAverageHeadingErrorDeg = prefs.getFloat(KEY_AVG_HEADING_ERROR, 0f),
            sessionPeakHeadingErrorDeg = prefs.getFloat(KEY_PEAK_HEADING_ERROR, 0f),
        )
    }

    private companion object {
        const val PREFS_NAME = "lane_gps_drive_session"
        const val KEY_ACTIVE = "active"
        const val KEY_SAMPLES = "samples"
        const val KEY_BEST_ACCURACY = "best_accuracy"
        const val KEY_WORST_ACCURACY = "worst_accuracy"
        const val KEY_AVG_QUALITY = "avg_quality"
        const val KEY_PEAK_LATERAL = "peak_lateral"
        const val KEY_PEAK_YAW = "peak_yaw"
        const val KEY_LEFT_EVENTS = "left_events"
        const val KEY_RIGHT_EVENTS = "right_events"
        const val KEY_TURN_EVENTS = "turn_events"
        const val KEY_CALIBRATED = "calibrated"
        const val KEY_REJECTED_SPIKES = "rejected_spikes"
        const val KEY_HEADING_SAMPLES = "heading_samples"
        const val KEY_AVG_HEADING_ERROR = "avg_heading_error"
        const val KEY_PEAK_HEADING_ERROR = "peak_heading_error"
        const val KEY_LAST_QUALITY = "last_quality"
        const val KEY_LAST_QUALITY_LABEL = "last_quality_label"
    }
}
