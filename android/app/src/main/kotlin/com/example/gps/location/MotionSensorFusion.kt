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
    val maneuverEvent: String? = null,
    val maneuverEventSequence: Long = 0L,
    val rejectedSpikeCount: Int = 0,
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

    private var straightCalibrationSinceElapsed = 0L
    private var calibrationSamples = 0

    private var motionCandidate = ""
    private var motionCandidateSinceElapsed = 0L
    private var confirmedMotion = ""
    private var lastConfirmedEventElapsed = 0L

    private var lastAcceptedRawLateral: Float? = null
    private var lastAcceptedRawYaw: Float? = null

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
        updateMotionState()
        publish()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                hasRotation = true
                SensorManager.getOrientation(rotationMatrix, orientation)
                val heading = normalize360(orientation[0] * 180f / PI.toFloat())
                state = state.copy(sensorHeadingDegrees = heading)
                calibrateMountingOffsetIfPossible()
                updateDerivedHeading()
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> updateLateralAcceleration(event.values)
            Sensor.TYPE_GYROSCOPE -> updateYawRate(event.values)
        }
        updateMotionState()
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun calibrateMountingOffsetIfPossible() {
        val deviceHeading = state.sensorHeadingDegrees ?: return
        val gnssHeading = gnssBearingDegrees ?: return
        val yaw = abs(state.yawRateDegS ?: 0f)
        val lateral = abs(state.lateralAccelerationMps2 ?: 0f)
        val now = SystemClock.elapsedRealtime()

        val straightEnough = speedMps >= 5f && yaw <= 4f && lateral <= 0.45f
        if (!straightEnough) {
            straightCalibrationSinceElapsed = 0L
            if (!state.sensorFrameCalibrated) calibrationSamples = 0
            return
        }

        if (straightCalibrationSinceElapsed == 0L) {
            straightCalibrationSinceElapsed = now
            return
        }
        if (now - straightCalibrationSinceElapsed < 1500L) return

        val targetOffset = normalizeSigned(gnssHeading - deviceHeading)
        val alpha = if (state.sensorFrameCalibrated) 0.02f else 0.12f
        mountingOffsetDegrees = blendSignedAngle(mountingOffsetDegrees, targetOffset, alpha)

        if (!state.sensorFrameCalibrated) {
            calibrationSamples++
            if (calibrationSamples >= 5) {
                state = state.copy(sensorFrameCalibrated = true)
            }
        }
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
        val raw = world[0] * cos(radians) - world[1] * sin(radians)

        if (abs(raw) > 7.0f || lastAcceptedRawLateral?.let { abs(raw - it) > 5.0f } == true) {
            state = state.copy(rejectedSpikeCount = state.rejectedSpikeCount + 1)
            return
        }

        lastAcceptedRawLateral = raw
        val bounded = raw.coerceIn(-4.0f, 4.0f)
        val filtered = ema(state.lateralAccelerationMps2, bounded, 0.08f)
        state = state.copy(lateralAccelerationMps2 = filtered)
    }

    private fun updateYawRate(values: FloatArray) {
        if (!hasRotation || values.size < 3) return
        val world = toWorld(values)
        val raw = world[2] * 180f / PI.toFloat()

        if (abs(raw) > 120f || lastAcceptedRawYaw?.let { abs(raw - it) > 90f } == true) {
            state = state.copy(rejectedSpikeCount = state.rejectedSpikeCount + 1)
            return
        }

        lastAcceptedRawYaw = raw
        val bounded = raw.coerceIn(-75f, 75f)
        val filtered = ema(state.yawRateDegS, bounded, 0.10f)
        state = state.copy(yawRateDegS = filtered)
    }

    private fun updateMotionState() {
        val now = SystemClock.elapsedRealtime()
        val instant = classifyInstantMotion(state.lateralAccelerationMps2, state.yawRateDegS)

        if (instant == "LOW SPEED" || instant == "STABLE" || instant == "MINOR MOTION") {
            motionCandidate = ""
            motionCandidateSinceElapsed = 0L
            confirmedMotion = ""
            state = state.copy(motionHint = instant)
            return
        }

        if (motionCandidate != instant) {
            motionCandidate = instant
            motionCandidateSinceElapsed = now
            state = state.copy(motionHint = "VERIFYING")
            return
        }

        val requiredDuration = when (instant) {
            "TURN / CURVE" -> 500L
            "LEFT LATERAL", "RIGHT LATERAL" -> 400L
            else -> 500L
        }
        if (now - motionCandidateSinceElapsed < requiredDuration) {
            state = state.copy(motionHint = "VERIFYING")
            return
        }

        if (confirmedMotion != instant) {
            confirmedMotion = instant
            val eventAllowed = now - lastConfirmedEventElapsed >= 1800L
            if (eventAllowed) {
                lastConfirmedEventElapsed = now
                state = state.copy(
                    maneuverEvent = instant,
                    maneuverEventSequence = state.maneuverEventSequence + 1L,
                    motionHint = instant,
                )
                return
            }
        }
        state = state.copy(motionHint = instant)
    }

    private fun classifyInstantMotion(lateral: Float?, yaw: Float?): String {
        if (speedMps < 4.5f) return "LOW SPEED"
        val lat = lateral ?: 0f
        val yawRate = yaw ?: 0f
        return when {
            abs(yawRate) >= 8f -> "TURN / CURVE"
            lat >= 0.55f && abs(yawRate) <= 8f -> "RIGHT LATERAL"
            lat <= -0.55f && abs(yawRate) <= 8f -> "LEFT LATERAL"
            abs(lat) <= 0.28f && abs(yawRate) <= 3.5f -> "STABLE"
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
