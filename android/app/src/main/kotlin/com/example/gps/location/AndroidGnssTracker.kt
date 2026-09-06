package com.example.gps.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

data class GnssUiState(
    val permissionFine: Boolean = false,
    val permissionCoarse: Boolean = false,
    val providerEnabled: Boolean = false,
    val fixReceived: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val altitudeMeters: Double? = null,
    val satellitesVisible: Int = 0,
    val satellitesUsedInFix: Int = 0,
    val provider: String? = null,
    val lastUpdateMillis: Long? = null,
    val message: String = "GNSS fix: waiting…",
)

class AndroidGnssTracker(
    context: Context,
    private val onState: (GnssUiState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var state = GnssUiState()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            state = state.copy(
                providerEnabled = true,
                fixReceived = true,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                provider = location.provider,
                lastUpdateMillis = location.time,
                message = "GNSS fix: OK",
            )
            publish()
        }

        override fun onProviderEnabled(provider: String) {
            state = state.copy(providerEnabled = true, message = "GNSS provider enabled; waiting for fix…")
            publish()
        }

        override fun onProviderDisabled(provider: String) {
            state = state.copy(providerEnabled = false, fixReceived = false, message = "Location/GNSS is disabled")
            publish()
        }

        @Deprecated("Deprecated in Android API; kept for compatibility.")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            state = state.copy(message = if (state.fixReceived) state.message else "GNSS started; acquiring satellites…")
            publish()
        }

        override fun onStopped() {
            state = state.copy(message = "GNSS stopped")
            publish()
        }

        override fun onFirstFix(ttffMillis: Int) {
            state = state.copy(message = "GNSS first fix in ${ttffMillis} ms")
            publish()
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            state = state.copy(
                satellitesVisible = status.satelliteCount,
                satellitesUsedInFix = used,
            )
            publish()
        }
    }

    fun refreshPermissionState() {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        state = state.copy(
            permissionFine = fine,
            permissionCoarse = coarse,
            message = when {
                fine -> state.message
                coarse -> "Approximate location granted — Precise is required for lane mode"
                else -> "Location permission required"
            }
        )
        publish()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        refreshPermissionState()
        if (!state.permissionFine && !state.permissionCoarse) return

        val gpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        val networkEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }

        state = state.copy(
            providerEnabled = gpsEnabled || networkEnabled,
            message = when {
                !state.permissionFine -> "Approximate location granted — enable Precise for lane mode"
                gpsEnabled -> "GNSS fix: waiting…"
                networkEnabled -> "GPS disabled; using network location"
                else -> "Location services are disabled"
            }
        )
        publish()

        if (gpsEnabled) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                locationListener,
            )
        }

        if (networkEnabled) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                locationListener,
            )
        }

        if (state.permissionFine) {
            try {
                locationManager.registerGnssStatusCallback(
                    gnssCallback,
                    android.os.Handler(appContext.mainLooper)
                )
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        try {
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
    }

    private fun publish() = onState(state)
}
