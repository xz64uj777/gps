package com.example.gps.route

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Private, atomic route checkpoint. Recovery never performs another destination search. */
class ActiveNavigationStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "active-navigation.json"))

    fun save(route: OpenRouteClient.RouteSummary) {
        val stream = file.startWrite()
        try {
            stream.write(RouteCheckpoint.encode(route).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun load(): OpenRouteClient.RouteSummary? = runCatching {
        RouteCheckpoint.decode(file.openRead().bufferedReader().use { it.readText() })
    }.getOrNull()

    fun clear() = file.delete()
}

internal object RouteCheckpoint {
    fun encode(r: OpenRouteClient.RouteSummary): String = JSONObject().apply {
        put("version", 1)
        put("destinationName", r.destinationName)
        put("destinationSide", r.destinationSide ?: "")
        put("destinationLat", r.destinationLat)
        put("destinationLon", r.destinationLon)
        put("distanceMeters", r.distanceMeters)
        put("durationSeconds", r.durationSeconds)
        put("nextManeuver", r.nextManeuver)
        put("nextRoad", r.nextRoad)
        put("nextManeuverDistanceMeters", r.nextManeuverDistanceMeters)
        put("totalRouteMeters", r.totalRouteMeters)
        put("totalRouteSeconds", r.totalRouteSeconds)
        put("progressIndex", r.progressIndex)
        put("routeStartedAtMillis", r.routeStartedAtMillis)
        put("offRouteDistanceMeters", r.offRouteDistanceMeters)
        put("arrived", r.arrived)
        put("source", r.source)
        put("geometry", JSONArray().apply {
            r.geometry.forEach { put(JSONArray().put(it.lat).put(it.lon)) }
        })
        put("maneuvers", JSONArray().apply {
            r.maneuvers.forEach {
                put(JSONObject().put("label", it.label).put("road", it.road)
                    .put("lat", it.lat).put("lon", it.lon).put("routeIndex", it.routeIndex))
            }
        })
    }.toString()

    fun decode(value: String): OpenRouteClient.RouteSummary {
        val j = JSONObject(value)
        require(j.getInt("version") == 1)
        val geometry = j.getJSONArray("geometry").let { a ->
            List(a.length()) { i -> a.getJSONArray(i).let {
                OpenRouteClient.RoutePoint(it.getDouble(0), it.getDouble(1))
            } }
        }
        require(geometry.size >= 2)
        require(geometry.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
        val maneuvers = j.getJSONArray("maneuvers").let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let {
                OpenRouteClient.RouteManeuver(it.getString("label"), it.getString("road"),
                    it.getDouble("lat"), it.getDouble("lon"), it.getInt("routeIndex"))
            } }
        }
        require(j.getInt("progressIndex") in geometry.indices)
        require(maneuvers.all { it.routeIndex in geometry.indices })
        return OpenRouteClient.RouteSummary(
            destinationSide = j.optString("destinationSide", "").takeIf { it == "left" || it == "right" },
            destinationName = j.getString("destinationName"), destinationLat = j.getDouble("destinationLat"),
            destinationLon = j.getDouble("destinationLon"), distanceMeters = j.getDouble("distanceMeters"),
            durationSeconds = j.getDouble("durationSeconds"), nextManeuver = j.getString("nextManeuver"),
            nextRoad = j.getString("nextRoad"), nextManeuverDistanceMeters = j.getDouble("nextManeuverDistanceMeters"),
            geometry = geometry, maneuvers = maneuvers, totalRouteMeters = j.getDouble("totalRouteMeters"),
            totalRouteSeconds = j.getDouble("totalRouteSeconds"), progressIndex = j.getInt("progressIndex"),
            routeStartedAtMillis = j.getLong("routeStartedAtMillis"), offRouteDistanceMeters = j.getDouble("offRouteDistanceMeters"),
            arrived = j.getBoolean("arrived"), source = j.getString("source"),
        )
    }
}
