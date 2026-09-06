package com.example.gps.lane

import com.example.gps.laneengine.GeoPoint
import com.example.gps.laneengine.Lane
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LaneApiClient(private val baseUrl: String) {
    data class Corridor(val lanes: List<Lane>)

    fun fetchOrImport(lat: Double, lon: Double, radiusMeters: Int = 900): Corridor {
        val first = fetchCorridor(lat, lon, radiusMeters)
        if (first.lanes.isNotEmpty()) return first

        try {
            triggerDevImport(lat, lon, radiusMeters)
        } catch (_: Exception) {
            // A production server may intentionally disable the dev import endpoint.
        }
        return fetchCorridor(lat, lon, radiusMeters)
    }

    fun fetchCorridor(lat: Double, lon: Double, radiusMeters: Int = 900): Corridor {
        val url = URL(
            "$baseUrl/v1/corridor?lat=$lat&lon=$lon&radius_m=$radiusMeters"
        )
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseCorridor(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun triggerDevImport(lat: Double, lon: Double, radiusMeters: Int) {
        val url = URL(
            "$baseUrl/v1/dev/import-corridor?lat=$lat&lon=$lon&radius_m=$radiusMeters"
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 35_000
            connection.doOutput = false
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCorridor(body: String): Corridor {
        val root = JSONObject(body)
        val laneArray = root.optJSONArray("lanes") ?: return Corridor(emptyList())
        val lanes = buildList {
            for (i in 0 until laneArray.length()) {
                val item = laneArray.getJSONObject(i)
                val line = item.optJSONArray("centerline") ?: continue
                val points = buildList {
                    for (j in 0 until line.length()) {
                        val point = line.getJSONObject(j)
                        add(
                            GeoPoint(
                                lat = point.getDouble("lat"),
                                lon = point.getDouble("lon"),
                            )
                        )
                    }
                }
                if (points.size < 2) continue
                add(
                    Lane(
                        id = item.getString("id"),
                        segmentId = item.getString("road_segment_id"),
                        index = item.getInt("lane_index"),
                        centerline = points,
                        widthMeters = item.optDouble("estimated_width_m", 3.6),
                        changeLeft = item.optBoolean("change_left", true),
                        changeRight = item.optBoolean("change_right", true),
                        sourceConfidence = item.optDouble("source_confidence", 0.5),
                    )
                )
            }
        }
        return Corridor(lanes)
    }
}
