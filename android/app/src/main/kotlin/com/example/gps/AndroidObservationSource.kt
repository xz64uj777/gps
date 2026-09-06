package com.example.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.gps.laneengine.GeoPoint
import com.example.gps.laneengine.Observation
import kotlin.math.PI

data class LiveTelemetry(
    val observation: Observation? = null,
    val provider: String? = null,
    val satellitesUsedInFix: Int? = null,
    val accelerometerMps2: Triple<Double, Double, Double>? = null,
    val gyroscopeRadS: Triple<Double, Double, Double>? = null,
    val sensorHeadingDegrees: Double? = null,
    val locationEnabled: Boolean = true,
    val error: String? = null,
)

class AndroidObservationSource(
    context: Context,
    private val onTelemetry: (LiveTelemetry) -> Unit,
) : LocationListener, SensorEventListener {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var latest = LiveTelemetry()
    private var started = false

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            publish(latest.copy(satellitesUsedInFix = used))
        }
    }

    fun start() {
        if (started) return
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            publish(latest.copy(error = "Location permission not granted"))
            return
        }

        started = true
        registerSensors()

        try {
            val providerStarted = when {
                fine && locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) -> {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        0f,
                        appContext.mainExecutor,
                        this,
                    )
                    locationManager.registerGnssStatusCallback(appContext.mainExecutor, gnssCallback)
                    true
                }
                locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        0f,
                        appContext.mainExecutor,
                        this,
                    )
                    true
                }
                else -> false
            }
            publish(
                latest.copy(
                    locationEnabled = locationManager.isLocationEnabled,
                    error = if (providerStarted) null else "No usable location provider is available",
                )
            )
        } catch (security: SecurityException) {
            started = false
            unregisterSensors()
            publish(latest.copy(error = "Location permission changed while starting"))
        }
    }

    fun stop() {
        if (!started) return
        started = false
        locationManager.removeUpdates(this)
        try {
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
            // Callback may not have been registered when only coarse location is available.
        }
        unregisterSensors()
    }

    override fun onLocationChanged(location: Location) {
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val gpsBearing =
            if (location.hasBearing() && speed >= MIN_GPS_BEARING_SPEED_MPS) {
                location.bearing.toDouble()
            } else {
                null
            }
        // Rotation-vector azimuth is magnetic-north referenced on typical devices.
        // Keep it as telemetry until geomagnetic-declination/device-frame correction exists.
        val heading = gpsBearing

        // Raw phone-frame acceleration is recorded, but is not treated as vehicle-lateral
        // acceleration until device-to-vehicle frame calibration is implemented.
        val lateralAcceleration: Double? = null

        val gyro = latest.gyroscopeRadS
        val observation = Observation(
            timestampMillis = location.time,
            position = GeoPoint(location.latitude, location.longitude),
            horizontalAccuracyMeters =
                if (location.hasAccuracy()) location.accuracy.toDouble() else 100.0,
            speedMps = speed,
            bearingDegrees = heading,
            lateralAccelerationMps2 = lateralAcceleration,
            sensorHeadingDegrees = latest.sensorHeadingDegrees,
            gyroscopeZRadS = gyro?.third,
            verticalAccuracyMeters =
                if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
                    location.verticalAccuracyMeters.toDouble()
                } else null,
        )
        publish(
            latest.copy(
                observation = observation,
                provider = location.provider,
                locationEnabled = locationManager.isLocationEnabled,
                error = null,
            )
        )
    }

    override fun onProviderDisabled(provider: String) {
        publish(latest.copy(locationEnabled = locationManager.isLocationEnabled))
    }

    override fun onProviderEnabled(provider: String) {
        publish(latest.copy(locationEnabled = locationManager.isLocationEnabled))
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                publish(
                    latest.copy(
                        accelerometerMps2 = Triple(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble(),
                        )
                    )
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                publish(
                    latest.copy(
                        gyroscopeRadS = Triple(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble(),
                        )
                    )
                )
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val azimuthDegrees = ((orientation[0] * 180.0 / PI) + 360.0) % 360.0
                publish(latest.copy(sensorHeadingDegrees = azimuthDegrees))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerSensors() {
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun publish(value: LiveTelemetry) {
        latest = value
        onTelemetry(value)
    }

    private companion object {
        const val LOCATION_INTERVAL_MS = 500L
        const val MIN_GPS_BEARING_SPEED_MPS = 1.5
    }
}
