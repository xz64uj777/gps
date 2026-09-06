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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.gps.laneengine.GnssQualityEvaluator
import com.example.gps.laneengine.GnssQualityInput

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
    val averageUsedCn0DbHz: Float? = null,
    val provider: String? = null,
    val lastUpdateMillis: Long? = null,
    val sensorHeadingDegrees: Float? = null,
    val fusedHeadingDegrees: Float? = null,
    val lateralAccelerationMps2: Float? = null,
    val yawRateDegS: Float? = null,
    val motionHint: String = "WAITING",
    val sensorFrameCalibrated: Boolean = false,
    val rotationSensorAvailable: Boolean = false,
    val linearAccelerationAvailable: Boolean = false,
    val gyroscopeAvailable: Boolean = false,
    val qualityScore: Int = 0,
    val qualityLabel: String = "UNAVAILABLE",
    val sensorLaneReady: Boolean = false,
    val qualityReason: String = "Waiting for GNSS fix",
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
    private var started = false
    private var gpsProviderEnabled = false
    private var lastGpsFixElapsed = 0L

    private val motionFusion = MotionSensorFusion(appContext) { motion ->
        state = state.copy(
            sensorHeadingDegrees = motion.sensorHeadingDegrees,
            fusedHeadingDegrees = motion.fusedHeadingDegrees,
            lateralAccelerationMps2 = motion.lateralAccelerationMps2,
            yawRateDegS = motion.yawRateDegS,
            motionHint = motion.motionHint,
            sensorFrameCalibrated = motion.sensorFrameCalibrated,
            rotationSensorAvailable = motion.rotationSensorAvailable,
            linearAccelerationAvailable = motion.linearAccelerationAvailable,
            gyroscopeAvailable = motion.gyroscopeAvailable,
        )
        publish()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: "unknown"
            val nowElapsed = SystemClock.elapsedRealtime()

            if (
                provider == LocationManager.NETWORK_PROVIDER &&
                state.permissionFine &&
                gpsProviderEnabled &&
                nowElapsed - lastGpsFixElapsed < 5000L
            ) {
                return
            }

            if (provider == LocationManager.GPS_PROVIDER) {
                lastGpsFixElapsed = nowElapsed
            }

            val bearing = if (location.hasBearing()) location.bearing else null
            val speed = if (location.hasSpeed()) location.speed else null
            motionFusion.updateGnss(bearing, speed)

            state = state.copy(
                providerEnabled = true,
                fixReceived = true,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                speedMps = speed,
                bearingDegrees = bearing,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                provider = provider,
                lastUpdateMillis = location.time,
                message = if (provider == LocationManager.GPS_PROVIDER) "GNSS fix: OK" else "Network location fallback",
            )
            publish()
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) gpsProviderEnabled = true
            state = state.copy(
                providerEnabled = anyProviderEnabled(),
                message = if (state.fixReceived) state.message else "Location provider enabled; waiting for fix…",
            )
            publish()
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) gpsProviderEnabled = false
            state = state.copy(
                providerEnabled = anyProviderEnabled(),
                sensorLaneReady = false,
                message = if (anyProviderEnabled()) "GPS disabled; using fallback location" else "Location/GNSS is disabled",
            )
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
            state = state.copy(message = "GNSS stopped", sensorLaneReady = false)
            publish()
        }

        override fun onFirstFix(ttffMillis: Int) {
            state = state.copy(message = "GNSS first fix in ${ttffMillis} ms")
            publish()
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var usedCn0Total = 0f
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    usedCn0Total += status.getCn0DbHz(i)
                }
            }
            state = state.copy(
                satellitesVisible = status.satelliteCount,
                satellitesUsedInFix = used,
                averageUsedCn0DbHz = if (used > 0) usedCn0Total / used else null,
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
        motionFusion.start()
        if (!state.permissionFine && !state.permissionCoarse) return
        if (started) return
        started = true

        gpsProviderEnabled = try {
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
            providerEnabled = gpsProviderEnabled || networkEnabled,
            message = when {
                !state.permissionFine -> "Approximate location granted — enable Precise for lane mode"
                gpsProviderEnabled -> "GNSS fix: waiting…"
                networkEnabled -> "GPS disabled; using network location"
                else -> "Location services are disabled"
            }
        )
        publish()

        if (gpsProviderEnabled) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                250L,
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
        if (!started) {
            motionFusion.stop()
            return
        }
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        try {
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
        motionFusion.stop()
        started = false
    }

    private fun anyProviderEnabled(): Boolean {
        val gps = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        val network = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
        return gps || network
    }

    private fun publish() {
        val fixAge = state.lastUpdateMillis?.let {
            (System.currentTimeMillis() - it).coerceAtLeast(0L)
        }
        val quality = GnssQualityEvaluator.evaluate(
            GnssQualityInput(
                hasFix = state.fixReceived,
                finePermission = state.permissionFine,
                gpsProvider = state.provider == LocationManager.GPS_PROVIDER,
                accuracyMeters = state.accuracyMeters?.toDouble(),
                satellitesUsed = state.satellitesUsedInFix,
                satellitesVisible = state.satellitesVisible,
                averageUsedCn0DbHz = state.averageUsedCn0DbHz?.toDouble(),
                fixAgeMillis = fixAge,
            )
        )
        state = state.copy(
            qualityScore = quality.score,
            qualityLabel = quality.grade.name,
            sensorLaneReady = quality.laneSensorsReady,
            qualityReason = quality.reason,
        )
        onState(state)
    }
}
