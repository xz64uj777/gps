package com.example.gps.laneengine

/** Road navigation threshold, not evidence of an exact lane. */
object NavigationFixQuality {
    fun isUsable(timestampMillis: Long?, nowMillis: Long, accuracyMeters: Double?): Boolean =
        FixFreshness.isFresh(timestampMillis, nowMillis) && accuracyMeters != null &&
            accuracyMeters.isFinite() && accuracyMeters > 0.0 && accuracyMeters <= 25.0
}
