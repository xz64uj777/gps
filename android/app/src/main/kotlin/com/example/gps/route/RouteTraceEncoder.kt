package com.example.gps.route

import java.security.MessageDigest

/** CSV replay payload: lat:lon pairs separated by |, emitted once per route change. */
internal class RouteTraceEncoder {
    data class Trace(val routeId: String = "", val geometry: String = "")
    private var previous: List<OpenRouteClient.RoutePoint> = emptyList()
    private var routeId = ""

    fun reset() {
        previous = emptyList()
        routeId = ""
    }

    fun next(geometry: List<OpenRouteClient.RoutePoint>): Trace {
        if (geometry.isEmpty()) {
            reset()
            return Trace()
        }
        if (geometry === previous || geometry == previous) return Trace(routeId)
        val encoded = geometry.joinToString("|") { "${it.lat}:${it.lon}" }
        routeId = MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        previous = geometry
        return Trace(routeId, encoded)
    }
}
