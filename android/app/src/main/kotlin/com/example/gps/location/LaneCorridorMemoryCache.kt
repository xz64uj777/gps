package com.example.gps.location

import com.example.gps.laneengine.GeoPoint
import com.example.gps.laneengine.Lane

/**
 * Process-local warm cache for the most recently fetched OSM lane corridor.
 *
 * A new drive often begins before Overpass can return a fresh corridor. Reusing
 * the last successful lane graph gives the matcher immediate nearby geometry;
 * normal distance/heading gates still reject it if the new drive starts
 * somewhere else. The service always schedules a fresh fetch after seeding.
 */
object LaneCorridorMemoryCache {
    data class Snapshot(
        val lanes: List<Lane>,
        val anchor: GeoPoint?,
    )

    @Volatile private var snapshot: Snapshot? = null

    fun read(): Snapshot? = snapshot

    fun write(lanes: List<Lane>, anchor: GeoPoint?) {
        if (lanes.isEmpty()) return
        snapshot = Snapshot(lanes = lanes.toList(), anchor = anchor)
    }
}
