package com.example.gps.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class MotionFusionState(
    val sensorHeadingDegrees: Float? = null,
    val fusedHeadingDegrees: Float? = null,
    val lateralAccelerationMps2: Float? = null,
    val yawRateDegS: Float? = null,
    val motionHint: String = "WAITING",
    val sensorFrameCalibrated: Boolean = false,
    val rotationSensorAvailable: Boolean = false,
    val linearAccelerationAvailable: Boolean = false,
    val gyroscopeAvailable: Boolean = false,
)

class MotionSensorFusion(
    context: Context,
    private val onState: (MotionFusionState) -> Unit,
) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var state = MotionFusionState(
        rotationSensorAvailable = rotationSensor != null,
        linearAccelerationAvailable = linearAccelerationSensor != null,
        gyroscopeAvailable = gyroscopeSensor != null,
    )

    private var started = false
    private var hasRotation = false
    private var gnssBearingDegrees: Float? = null
    private var speedMps: Float = 0f
    private var mountingOffsetDegrees: Float? = null
    private var lastPublishElapsed = 0L

    fun start() {
        if (started) return
        started = true
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelerationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscopeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        publish(force = true)
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
    }

    fun updateGnss(bearingDegrees: Float?, speedMps: Float?) {
        this.gnssBearingDegrees = bearingDegrees
        this.speedMps = speedMps ?: 0f
        calibrateMountingOffsetIfPossible()
        updateDerivedHeading()
        publish()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                hasRotation = true
                SensorManager.getOrientation(rotationMatrix, orientation)
                val heading = normalize360((orientation[0] * 180f / PI.toFloat()))
                state = state.copy(sensorHeadingDegrees = heading)
                calibrateMountingOffsetIfPossible()
                updateDerivedHeading()
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> updateLateralAcceleration(event.values)
            Sensor.TYPE_GYROSCOPE -> updateYawRate(event.values)
        }
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun calibrateMountingOffsetIfPossible() {
        val deviceHeading = state.sensorHeadingDegrees ?: return
        val gnssHeading = gnssBearingDegrees ?: return
        if (speedMps < 4f) return

        val targetOffset = normalizeSigned(gnssHeading - deviceHeading)
        mountingOffsetDegrees = blendSignedAngle(mountingOffsetDegrees, targetOffset, 0.06f)
        state = state.copy(sensorFrameCalibrated = true)
    }

    private fun updateDerivedHeading() {
        val deviceHeading = state.sensorHeadingDegrees
        val fused = when {
            deviceHeading != null && mountingOffsetDegrees != null ->
                normalize360(deviceHeading + mountingOffsetDegrees!!)
            gnssBearingDegrees != null -> gnssBearingDegrees
            else -> deviceHeading
        }
        state = state.copy(fusedHeadingDegrees = fused)
    }

    private fun updateLateralAcceleration(values: FloatArray) {
        if (!hasRotation || values.size < 3) return
        val world = toWorld(values)
        val heading = state.fusedHeadingDegrees ?: gnssBearingDegrees ?: return
        val radians = heading * PI.toFloat() / 180f

        // Android world frame: X roughly east, Y roughly north. Project acceleration
        // onto the vehicle's right-hand axis using the best heading currently available.
        val lateralRight = world[0] * cos(radians) - world[1] * sin(radians)
        val filtered = ema(state.lateralAccelerationMps2, lateralRight, 0.18f)
        state = state.copy(
            lateralAccelerationMps2 = filtered,
            motionHint = classifyMotion(filtered, state.yawRateDegS),
        )
    }

    private fun updateYawRate(values: FloatArray) {
        if (!hasRotation || values.size < 3) return
        val world = toWorld(values)
        val yawDegS = world[2] * 180f / PI.toFloat()
        val filtered = ema(state.yawRateDegS, yawDegS, 0.20f)
        state = state.copy(
            yawRateDegS = filtered,
            motionHint = classifyMotion(state.lateralAccelerationMps2, filtered),
        )
    }

    private fun classifyMotion(lateral: Float?, yaw: Float?): String {
        if (speedMps < 2.5f) return "LOW SPEED"
        val lat = lateral ?: 0f
        val yawRate = yaw ?: 0f
        return when {
            abs(yawRate) >= 12f -> "TURN / CURVE"
            lat >= 0.65f -> "RIGHT LATERAL"
            lat <= -0.65f -> "LEFT LATERAL"
            abs(lat) <= 0.35f && abs(yawRate) <= 5f -> "STABLE"
            else -> "MINOR MOTION"
        }
    }

    private fun toWorld(values: FloatArray): FloatArray {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        return floatArrayOf(
            rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z,
            rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z,
            rotationMatrix[6] * x + rotationMatrix[7] * y + rotationMatrix[8] * z,
        )
    }

    private fun publish(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishElapsed < 100L) return
        lastPublishElapsed = now
        onState(state)
    }

    private fun ema(current: Float?, next: Float, alpha: Float): Float =
        current?.let { it + alpha * (next - it) } ?: next

    private fun blendSignedAngle(current: Float?, target: Float, alpha: Float): Float {
        if (current == null) return normalizeSigned(target)
        val delta = normalizeSigned(target - current)
        return normalizeSigned(current + delta * alpha)
    }

    private fun normalize360(value: Float): Float {
        var normalized = value % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    private fun normalizeSigned(value: Float): Float {
        var normalized = value % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }
}
