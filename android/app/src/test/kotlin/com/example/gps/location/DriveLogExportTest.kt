package com.example.gps.location

import org.junit.Assert.*
import org.junit.Test

class DriveLogExportTest {
    private val header = MutableList(41) { "field$it" }.apply {
        this[28] = "nav_event"
        this[40] = "nav_updated_at_ms"
    }.joinToString(",")

    private fun row(time: Long, speed: Double = 0.0, stale: Boolean = false,
                    event: String = "DRIVE_STARTED", eventTime: Long = 1L): String =
        MutableList(41) { "" }.apply {
            this[3] = "10.0"
            this[9] = speed.toString()
            this[24] = time.toString()
            this[26] = if (stale) "500000" else "100"
            this[27] = stale.toString()
            this[28] = event
            this[29] = "\"Park St, Hartford\""
            this[40] = eventTime.toString()
        }.joinToString(",")

    @Test fun repeatedStartLabelDoesNotPreserveAnHourOfStaleRows() {
        val moving = row(1_000, speed = 10.0)
        val grace = row(91_000)
        val stop = row(3_600_000, event = "DRIVE_AUTO_STOPPED_IDLE", eventTime = 3_600_000)
        val input = listOf(header, moving, grace, row(91_001, stale = true),
            row(3_000_000, stale = true), stop, row(3_601_000, event = "DRIVE_AUTO_STOPPED_IDLE", eventTime = 3_600_000))
        assertEquals(listOf(header, moving, grace, stop), DriveLogExport.trim(input))
    }

    @Test fun distinctLifecycleEventsSurviveButRepeatedSnapshotsDoNot() {
        val moving = row(1_000, speed = 10.0)
        val first = row(200_000, event = "ROUTE_RECOVERED", eventTime = 200_000)
        val second = row(300_000, event = "ROUTE_RECOVERED", eventTime = 300_000)
        assertEquals(listOf(header, moving, first, second), DriveLogExport.trim(listOf(header,
            moving, first, row(210_000, event = "ROUTE_RECOVERED", eventTime = 200_000), second)))
    }

    @Test fun laterRealMovementPreservesTheTripAndIntermediateStops() {
        val input = listOf(header, row(1_000, speed = 10.0), row(200_000), row(500_000, speed = 10.0))
        assertEquals(input, DriveLogExport.trim(input))
    }

    @Test fun noMovementDoesNotDiscardDiagnosticSessions() {
        val input = listOf(header, row(1_000), row(600_000, stale = true))
        assertEquals(input, DriveLogExport.trim(input))
    }
}
