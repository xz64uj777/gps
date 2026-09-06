package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GnssQualityTest {
    @Test
    fun threeMeterFixWithManySatellitesIsSensorReady() {
        val result = GnssQualityEvaluator.evaluate(
            GnssQualityInput(
                hasFix = true,
                finePermission = true,
                gpsProvider = true,
                accuracyMeters = 3.0,
                satellitesUsed = 12,
                satellitesVisible = 15,
                averageUsedCn0DbHz = 31.0,
                fixAgeMillis = 600,
            )
        )

        assertTrue(result.laneSensorsReady)
        assertTrue(result.score >= 90)
        assertEquals(GnssQualityGrade.EXCELLENT, result.grade)
    }

    @Test
    fun poorHorizontalAccuracyBlocksLaneSensorGate() {
        val result = GnssQualityEvaluator.evaluate(
            GnssQualityInput(
                hasFix = true,
                finePermission = true,
                gpsProvider = true,
                accuracyMeters = 9.0,
                satellitesUsed = 14,
                satellitesVisible = 16,
                averageUsedCn0DbHz = 34.0,
                fixAgeMillis = 500,
            )
        )

        assertFalse(result.laneSensorsReady)
        assertTrue(result.reason.contains("accuracy", ignoreCase = true))
    }

    @Test
    fun staleFixBlocksLaneSensorGate() {
        val result = GnssQualityEvaluator.evaluate(
            GnssQualityInput(
                hasFix = true,
                finePermission = true,
                gpsProvider = true,
                accuracyMeters = 2.0,
                satellitesUsed = 15,
                satellitesVisible = 16,
                averageUsedCn0DbHz = 36.0,
                fixAgeMillis = 6000,
            )
        )

        assertFalse(result.laneSensorsReady)
        assertTrue(result.reason.contains("stale", ignoreCase = true))
    }
}
