package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixFreshnessTest {
    @Test fun rejectsMissingFutureAndExpiredFixes() {
        assertFalse(FixFreshness.isFresh(null, 100_000L))
        assertFalse(FixFreshness.isFresh(100_001L, 100_000L))
        assertTrue(FixFreshness.isFresh(97_000L, 100_000L))
        assertFalse(FixFreshness.isFresh(96_999L, 100_000L))
        // Regression: a 24-second-old drive fix must not drive the live marker.
        assertFalse(FixFreshness.isFresh(76_000L, 100_000L))
    }
}
