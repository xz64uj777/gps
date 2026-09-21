package com.example.gps.route

/** Once shown, keep an approach visible until its maneuver ends, even if speed drops. */
internal class LaneApproachVisibility {
    private var key: String? = null
    private var shown = false

    fun update(maneuverKey: String?, eligible: Boolean): Boolean {
        if (maneuverKey != key) {
            key = maneuverKey
            shown = false
        }
        shown = maneuverKey != null && (shown || eligible)
        return shown
    }
}
