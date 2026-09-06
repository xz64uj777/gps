package com.example.gps.location

import java.util.concurrent.CopyOnWriteArraySet

object DriveSessionRuntime {
    private val listeners = CopyOnWriteArraySet<(GnssUiState) -> Unit>()

    @Volatile
    private var latestState: GnssUiState? = null

    fun publish(state: GnssUiState) {
        latestState = state
        listeners.forEach { listener -> listener(state) }
    }

    fun latest(): GnssUiState? = latestState

    fun addListener(listener: (GnssUiState) -> Unit) {
        listeners.add(listener)
        latestState?.let(listener)
    }

    fun removeListener(listener: (GnssUiState) -> Unit) {
        listeners.remove(listener)
    }
}
