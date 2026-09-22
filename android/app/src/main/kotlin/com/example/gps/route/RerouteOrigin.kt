package com.example.gps.route

import kotlin.math.roundToInt

/** Direction constraint for a moving reroute origin, not the destination. */
internal object RerouteOrigin {
    fun usableBearing(bearing: Double?, speedMps: Double?, accuracyMeters: Double?): Double? {
        if (bearing == null || !bearing.isFinite() || bearing < 0.0 || bearing >= 360.0) return null
        if (speedMps == null || !speedMps.isFinite() || speedMps < 3.0) return null
        if (accuracyMeters == null || !accuracyMeters.isFinite() ||
            accuracyMeters <= 0.0 || accuracyMeters > 25.0) return null
        return bearing
    }

    fun bearingQuery(bearing: Double?): String {
        if (bearing == null || !bearing.isFinite() || bearing < 0.0 || bearing >= 360.0) return ""
        // Empty second entry leaves arrival direction unrestricted. OSRM's
        // tolerance accommodates curves without accepting the opposite direction.
        return "&bearings=${bearing.roundToInt() % 360},45;"
    }
}
