package com.example.gps.location

import org.junit.Assert.*
import org.junit.Test

class DriveIdlePolicyTest {
    @Test fun unattendedDriveStopsAtFiveMinutes() {
        val policy = DriveIdlePolicy(1_000)
        assertFalse(policy.shouldStop(300_999, false, false, false))
        assertTrue(policy.shouldStop(301_000, false, false, false))
    }
    @Test fun activeNavigationSurvivesMissingGpsInBackground() {
        val policy = DriveIdlePolicy(0)
        assertFalse(policy.shouldStop(3_600_000, false, true, false))
        assertFalse(policy.shouldStop(3_600_001, false, false, false))
    }
    @Test fun screenOffMovementKeepsDriveAlive() {
        val policy = DriveIdlePolicy(0)
        assertFalse(policy.shouldStop(600_000, false, false, true))
        assertFalse(policy.shouldStop(899_999, false, false, false))
        assertTrue(policy.shouldStop(900_000, false, false, false))
    }
    @Test fun visibleDiagnosticsAndBriefStopsArePreserved() {
        val policy = DriveIdlePolicy(0)
        assertFalse(policy.shouldStop(600_000, true, false, false))
        assertFalse(policy.shouldStop(620_000, false, false, false))
    }
}
