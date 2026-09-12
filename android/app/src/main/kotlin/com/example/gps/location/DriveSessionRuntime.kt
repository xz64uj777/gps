package com.example.gps.location

import java.util.concurrent.CopyOnWriteArraySet

object DriveSessionRuntime {
    private val listeners = CopyOnWriteArraySet<(GnssUiState) -> Unit>()

    @Volatile
    private var latestState: GnssUiState? = null

    fun publish(state: GnssUiState) {
        // Preserve the full matcher result internally so drive telemetry still
        // records the raw likely-lane estimate for debugging and replay.
        latestState = state

        // Driver-facing screens must never turn a low-confidence matcher guess
        // into a visually authoritative current-lane highlight. LaneGPS can
        // still show the road's lane count, but the current lane is exposed to
        // live UI only after the exact-lane safety gates have passed.
        val displayState = driverFacing(state)
        listeners.forEach { listener -> listener(displayState) }
    }

    fun latest(): GnssUiState? = latestState?.let(::driverFacing)

    fun addListener(listener: (GnssUiState) -> Unit) {
        listeners.add(listener)
        latestState?.let { listener(driverFacing(it)) }
    }

    fun removeListener(listener: (GnssUiState) -> Unit) {
        listeners.remove(listener)
    }

    private fun driverFacing(state: GnssUiState): GnssUiState =
        if (state.laneExactClaim) {
            state
        } else {
            state.copy(likelyLaneNumberFromLeft = null)
        }
}
