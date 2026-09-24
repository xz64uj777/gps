package com.example.gps.location

import org.junit.Assert.*
import org.junit.Test

class DriveLogExportTest {
    private val headerFields = listOf(
        "timestamp_ms", "lat", "lon", "accuracy_m", "quality_score", "quality_label",
        "sat_used", "sat_visible", "avg_cn0_dbhz", "speed_mps", "gnss_bearing_deg",
        "sensor_heading_deg", "fused_heading_deg", "lateral_mps2", "yaw_deg_s",
        "motion_hint", "calibrated", "sensor_lane_ready", "lane_status", "lane_candidates",
        "lane_from_left", "lane_count", "lane_confidence", "exact_lane_claim", "lane_turn_hints",
        "recorded_at_ms", "fix_timestamp_ms", "fix_age_ms", "fix_stale", "nav_event",
        "nav_destination", "nav_next_maneuver", "nav_next_road", "nav_maneuver_distance_m",
        "nav_remaining_distance_m", "nav_remaining_seconds", "nav_off_route_m",
        "nav_route_point_index", "nav_maneuver_index", "nav_arrived", "nav_reroute_count",
        "nav_updated_at_ms"
    )
    private val header = headerFields.joinToString(",")

    private fun row(
        time: Long,
        speed: Double = 0.0,
        stale: Boolean = false,
        event: String = "DRIVE_STARTED",
        eventTime: Long = 1L,
    ): String {
        val values = MutableList(headerFields.size) { "" }
        fun put(name: String, value: String) {
            values[headerFields.indexOf(name)] = value
        }
        put("accuracy_m", "10.0")
        put("speed_mps", speed.toString())
        put("recorded_at_ms", time.toString())
        put("fix_age_ms", if (stale) "500000" else "100")
        put("fix_stale", stale.toString())
        put("nav_event", event)
        put("nav_destination", "\"Park St, Hartford\"")
        put("nav_updated_at_ms", eventTime.toString())
        return values.joinToString(",")
    }

    @Test fun repeatedStartLabelDoesNotPreserveAnHourOfStaleRows() {
        val moving = row(1_000, speed = 10.0)
        val grace = row(91_000)
        val stop = row(3_600_000, event = "DRIVE_AUTO_STOPPED_IDLE", eventTime = 3_600_000)
        val input = listOf(
            header,
            moving,
            grace,
            row(91_001, stale = true),
            row(3_000_000, stale = true),
            stop,
            row(3_601_000, event = "DRIVE_AUTO_STOPPED_IDLE", eventTime = 3_600_000),
        )
        assertEquals(listOf(header, moving, grace, stop), DriveLogExport.trim(input))
    }

    @Test fun distinctLifecycleEventsSurviveButRepeatedSnapshotsDoNot() {
        val moving = row(1_000, speed = 10.0)
        val first = row(200_000, event = "ROUTE_RECOVERED", eventTime = 200_000)
        val second = row(300_000, event = "ROUTE_RECOVERED", eventTime = 300_000)
        assertEquals(
            listOf(header, moving, first, second),
            DriveLogExport.trim(
                listOf(
                    header,
                    moving,
                    first,
                    row(210_000, event = "ROUTE_RECOVERED", eventTime = 200_000),
                    second,
                )
            )
        )
    }

    @Test fun laterRealMovementPreservesTheTripAndIntermediateStops() {
        val input = listOf(header, row(1_000, speed = 10.0), row(200_000), row(500_000, speed = 10.0))
        assertEquals(input, DriveLogExport.trim(input))
    }

    @Test fun noMovementDoesNotDiscardDiagnosticSessions() {
        val input = listOf(header, row(1_000), row(600_000, stale = true))
        assertEquals(input, DriveLogExport.trim(input))
    }

    @Test fun telemetryColumnInsertionDoesNotBreakTrimming() {
        val shiftedHeader = headerFields.toMutableList().apply {
            add(indexOf("recorded_at_ms"), "future_lane_debug_field")
        }
        fun shiftedRow(time: Long, speed: Double, stale: Boolean): String {
            val values = MutableList(shiftedHeader.size) { "" }
            values[shiftedHeader.indexOf("accuracy_m")] = "10.0"
            values[shiftedHeader.indexOf("speed_mps")] = speed.toString()
            values[shiftedHeader.indexOf("recorded_at_ms")] = time.toString()
            values[shiftedHeader.indexOf("fix_age_ms")] = if (stale) "500000" else "100"
            values[shiftedHeader.indexOf("fix_stale")] = stale.toString()
            values[shiftedHeader.indexOf("nav_event")] = "PROGRESS"
            return values.joinToString(",")
        }
        val shiftedHeaderLine = shiftedHeader.joinToString(",")
        val moving = shiftedRow(1_000, 10.0, false)
        val staleTail = shiftedRow(200_000, 0.0, true)
        assertEquals(
            listOf(shiftedHeaderLine, moving),
            DriveLogExport.trim(listOf(shiftedHeaderLine, moving, staleTail))
        )
    }
    @Test fun routePayloadSurvivesParkedTailTrimming() {
        val extendedHeader = header + ",nav_route_id,nav_route_geometry_lat_lon"
        val moving = row(1_000, speed = 10.0, event = "PROGRESS") + ",route-a,1.0:2.0|1.001:2.002"
        val routeChange = row(200_000, event = "PROGRESS") + ",route-b,1.0:2.0|1.002:2.002"
        val repeated = row(201_000, event = "PROGRESS") + ",route-b,"
        assertEquals(
            listOf(extendedHeader, moving, routeChange),
            DriveLogExport.trim(listOf(extendedHeader, moving, routeChange, repeated)),
        )
    }

}
