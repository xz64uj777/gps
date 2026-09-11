package com.example.gps.route

/**
 * Process-local voice diagnostics sampled into drive CSV rows.
 * This does not contain microphone/audio data; it only records TTS state and
 * whether LaneGPS attempted to speak an instruction.
 */
object NavigationVoiceTelemetryRuntime {
    data class Snapshot(
        val ready: Boolean = false,
        val muted: Boolean = false,
        val selectedVoiceId: String = "",
        val selectedVoiceLabel: String = "",
        val speakAttempts: Int = 0,
        val lastUtterance: String = "",
        val lastSpeakResult: Int? = null,
        val lastAttemptAtMillis: Long = 0L,
        val updatedAtMillis: Long = 0L,
    )

    @Volatile
    private var latest = Snapshot()

    fun snapshot(): Snapshot = latest

    fun resetForDrive() {
        val previous = latest
        latest = previous.copy(
            speakAttempts = 0,
            lastUtterance = "",
            lastSpeakResult = null,
            lastAttemptAtMillis = 0L,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun publishState(
        ready: Boolean,
        muted: Boolean,
        selectedVoiceId: String?,
        selectedVoiceLabel: String?,
    ) {
        latest = latest.copy(
            ready = ready,
            muted = muted,
            selectedVoiceId = selectedVoiceId.orEmpty(),
            selectedVoiceLabel = selectedVoiceLabel.orEmpty(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun publishSpeakAttempt(text: String, result: Int) {
        latest = latest.copy(
            speakAttempts = latest.speakAttempts + 1,
            lastUtterance = text,
            lastSpeakResult = result,
            lastAttemptAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }
}
