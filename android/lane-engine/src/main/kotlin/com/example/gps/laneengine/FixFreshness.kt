package com.example.gps.laneengine

/** One freshness boundary for live map, navigation and lane matching. */
object FixFreshness {
    const val MAX_AGE_MS = 3_000L

    fun isFresh(timestampMillis: Long?, nowMillis: Long): Boolean =
        timestampMillis != null && timestampMillis <= nowMillis &&
            nowMillis - timestampMillis <= MAX_AGE_MS
}
