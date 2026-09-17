package com.example.gps.location

/** Opening a visible map is distinct from an unattended Android service restart. */
internal object LiveSessionPolicy {
    enum class Action { KEEP, RESUME, NEW }
    fun onOpen(trackerRunning: Boolean, savedActive: Boolean, savedRoute: Boolean): Action = when {
        trackerRunning -> Action.KEEP
        savedActive && savedRoute -> Action.RESUME
        else -> Action.NEW
    }
}
