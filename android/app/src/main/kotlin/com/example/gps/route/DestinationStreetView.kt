package com.example.gps.route

import java.util.Locale

internal object DestinationStreetView {
    fun nearArrival(distanceMeters: Double): Boolean = distanceMeters.isFinite() && distanceMeters in 0.0..250.0

    fun url(lat: Double, lon: Double): String {
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        val position = String.format(Locale.US, "%.7f%%2C%.7f", lat, lon)
        return "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$position"
    }
}
