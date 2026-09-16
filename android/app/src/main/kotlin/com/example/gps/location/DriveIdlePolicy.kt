package com.example.gps.location

/** Uses elapsed time so clock changes cannot finish a drive early. */
internal class DriveIdlePolicy(private var lastActivityElapsed: Long) {
    fun shouldStop(now: Long, screenVisible: Boolean, activeRoute: Boolean, moving: Boolean): Boolean {
        if (screenVisible || activeRoute || moving) {
            lastActivityElapsed = now
            return false
        }
        return now - lastActivityElapsed >= 300_000L
    }
}
