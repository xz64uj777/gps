package com.example.gps.route

/**
 * Process-local snapshot of navigation state for drive CSV diagnostics.
 *
 * The foreground drive recorder reads this independently from the navigation UI,
 * so route progress remains observable in telemetry without coupling lane matching
 * to routing decisions.
 */
object NavigationTelemetryRuntime {
    data class Snapshot(
        val event: String = "IDLE",
        val destination: String = "",
        val nextManeuver: String = "",
        val nextRoad: String = "",
        val nextManeuverDistanceMeters: Double? = null,
        val remainingDistanceMeters: Double? = null,
        val remainingSeconds: Double? = null,
        val offRouteDistanceMeters: Double? = null,
        val routePointIndex: Int? = null,
        val maneuverIndex: Int? = null,
        val arrived: Boolean = false,
        val rerouteCount: Int = 0,
        val updatedAtMillis: Long = 0L,
        val routeLaneCount: Int = 0,
        val routeLaneChoices: String = "",
        val routeLaneIndications: String = "",
    )

    @Volatile
    private var latest = Snapshot()

    @Volatile private var lanePanelStatus = "INACTIVE"
    @Volatile private var streetViewStatus = "INACTIVE"
    fun lanePanel(status: String) { lanePanelStatus = status }
    fun lanePanelStatus(): String = lanePanelStatus
    fun streetView(status: String) { streetViewStatus = status }
    fun streetViewStatus(): String = streetViewStatus

    fun snapshot(): Snapshot = latest

    fun lifecycle(event: String, destination: String = latest.destination) {
        latest = latest.copy(event = event, destination = destination, updatedAtMillis = System.currentTimeMillis())
    }

    fun resetForDrive() {
        latest = Snapshot()
        lanePanelStatus = "INACTIVE"
        streetViewStatus = "INACTIVE"
    }

    fun publish(
        route: OpenRouteClient.RouteSummary,
        event: String,
        routePointIndex: Int?,
        maneuverIndex: Int?,
        incrementReroute: Boolean = false,
    ) {
        val previous = latest
        latest = Snapshot(
            event = event,
            destination = route.destinationName,
            nextManeuver = route.nextManeuver,
            nextRoad = route.nextRoad,
            routeLaneCount = route.nextLanes.size,
            routeLaneChoices = route.nextLanes.mapIndexedNotNull { i, lane -> (i + 1).takeIf { lane.valid } }.joinToString("|"),
            routeLaneIndications = RouteLaneGuidance.hints(route.nextLanes).joinToString("|"),
            nextManeuverDistanceMeters = route.nextManeuverDistanceMeters,
            remainingDistanceMeters = route.distanceMeters,
            remainingSeconds = route.durationSeconds,
            offRouteDistanceMeters = route.offRouteDistanceMeters,
            routePointIndex = routePointIndex,
            maneuverIndex = maneuverIndex,
            arrived = route.arrived,
            rerouteCount = previous.rerouteCount + if (incrementReroute) 1 else 0,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }
}
