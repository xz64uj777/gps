package com.example.gps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.gps.location.DriveSessionRuntime
import com.example.gps.location.DriveSessionStore
import com.example.gps.location.DriveTrackingService
import com.example.gps.location.GnssUiState

class MainActivity : ComponentActivity() {
    private lateinit var store: DriveSessionStore
    private var uiState by mutableStateOf(GnssUiState())
    private var sessionActive by mutableStateOf(false)
    private var pendingStart = false

    private val runtimeListener: (GnssUiState) -> Unit = { state ->
        runOnUiThread {
            uiState = state
            sessionActive = store.isActive()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val shouldStart = pendingStart && hasFineLocationPermission()
            pendingStart = false
            if (shouldStart) startDriveTestInternal()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DriveSessionStore(this)
        uiState = DriveSessionRuntime.latest() ?: store.load()
        sessionActive = store.isActive()

        setContent {
            MaterialTheme {
                NavigationDebugScreen(
                    state = uiState,
                    sessionActive = sessionActive,
                    requestPermission = { requestPermissionsForDrive(startAfterGrant = false) },
                    startDrive = { startDriveTest() },
                    stopDrive = { stopDriveTest() },
                )
            }
        }

        if (sessionActive && hasFineLocationPermission()) {
            resumeActiveDriveTest()
        }
    }

    override fun onStart() {
        super.onStart()
        uiState = DriveSessionRuntime.latest() ?: store.load()
        sessionActive = store.isActive()
        DriveSessionRuntime.addListener(runtimeListener)
    }

    override fun onStop() {
        DriveSessionRuntime.removeListener(runtimeListener)
        super.onStop()
    }

    private fun startDriveTest() {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

        if (!hasFineLocationPermission() || needsNotificationPermission) {
            requestPermissionsForDrive(startAfterGrant = true)
        } else {
            startDriveTestInternal()
        }
    }

    private fun startDriveTestInternal() {
        sessionActive = true
        uiState = GnssUiState(message = "Starting new drive test…")
        val intent = Intent(this, DriveTrackingService::class.java)
            .setAction(DriveTrackingService.ACTION_START)
            .putExtra(DriveTrackingService.EXTRA_RESET, true)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun resumeActiveDriveTest() {
        val intent = Intent(this, DriveTrackingService::class.java)
            .setAction(DriveTrackingService.ACTION_RESUME)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopDriveTest() {
        sessionActive = false
        val intent = Intent(this, DriveTrackingService::class.java)
            .setAction(DriveTrackingService.ACTION_STOP)
        startService(intent)
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsForDrive(startAfterGrant: Boolean) {
        pendingStart = startAfterGrant
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
private fun NavigationDebugScreen(
    state: GnssUiState,
    sessionActive: Boolean,
    requestPermission: () -> Unit,
    startDrive: () -> Unit,
    stopDrive: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Lane GPS · Durable Drive Test", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(8.dp))
        Text(state.message, color = statusColor(state), fontSize = 18.sp)

        Spacer(Modifier.height(14.dp))
        SectionTitle("Drive session")
        Text(
            if (sessionActive) "RECORDING · SAFE TO TURN SCREEN OFF" else "STOPPED · SUMMARY SAVED LOCALLY",
            color = if (sessionActive) Color(0xFF7EE787) else Color(0xFF8ED7FF),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = if (sessionActive) stopDrive else startDrive) {
            Text(if (sessionActive) "STOP & SAVE DRIVE TEST" else "START NEW DRIVE TEST")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (sessionActive)
                "Recording runs as a foreground service, persists every second, and continues when the screen turns off or the app is backgrounded."
            else if (state.sessionSamples > 0)
                "This summary stays saved until you start a new drive test. Starting a new test clears the saved totals."
            else
                "Start a drive test while parked, then leave the phone mounted and drive normally.",
            color = Color(0xFFB8C1CC),
            fontSize = 12.sp,
        )

        if (!state.permissionFine && !sessionActive) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.permissionCoarse)
                    "Approximate permission is active. Precise location is required for a drive test."
                else "Precise location permission is required.",
                color = Color(0xFFFFCC80)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = requestPermission) { Text("Request permissions") }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("GNSS")
        DebugRow("Latitude", state.latitude?.let { "%.7f".format(it) } ?: "—")
        DebugRow("Longitude", state.longitude?.let { "%.7f".format(it) } ?: "—")
        DebugRow("Accuracy", state.accuracyMeters?.let { "%.1f m".format(it) } ?: "—")
        DebugRow("Quality", "${state.qualityScore}/100 · ${state.qualityLabel}")
        DebugRow("Satellites", "${state.satellitesUsedInFix}/${state.satellitesVisible} used")
        DebugRow("Signal", state.averageUsedCn0DbHz?.let { "%.1f dB-Hz avg".format(it) } ?: "—")
        DebugRow("Speed", state.speedMps?.let { "%.1f m/s".format(it) } ?: "—")
        DebugRow("Bearing", state.bearingDegrees?.let { "%.0f°".format(it) } ?: "—")
        DebugRow("Provider", state.provider ?: "—")
        DebugRow("Precise", if (state.permissionFine) "YES" else "NO")

        Spacer(Modifier.height(14.dp))
        SectionTitle("Motion fusion")
        DebugRow("Sensor heading", state.sensorHeadingDegrees?.let { "%.0f°".format(it) } ?: "—")
        DebugRow("Fused heading", state.fusedHeadingDegrees?.let { "%.0f°".format(it) } ?: "—")
        DebugRow("Lateral accel", state.lateralAccelerationMps2?.let { "%+.2f m/s²".format(it) } ?: "—")
        DebugRow("Yaw rate", state.yawRateDegS?.let { "%+.1f°/s".format(it) } ?: "—")
        DebugRow("Motion", state.motionHint)
        DebugRow("Frame calib.", if (state.sensorFrameCalibrated) "LEARNED" else "DRIVE STRAIGHT > 11 mph")
        DebugRow(
            "Sensors",
            listOf(
                if (state.rotationSensorAvailable) "ROT" else "no ROT",
                if (state.linearAccelerationAvailable) "LIN" else "no LIN",
                if (state.gyroscopeAvailable) "GYRO" else "no GYRO",
            ).joinToString(" · ")
        )

        Spacer(Modifier.height(16.dp))
        Text(
            if (state.sensorLaneReady) "SENSOR GATE: READY" else "SENSOR GATE: NOT READY",
            color = if (state.sensorLaneReady) Color(0xFF7EE787) else Color(0xFFFFCC80),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Text(state.qualityReason, color = Color(0xFFB8C1CC), fontSize = 13.sp)

        Spacer(Modifier.height(18.dp))
        SectionTitle("Drive test summary")
        DebugRow("GPS samples", state.sessionSamples.toString())
        DebugRow("Best accuracy", state.sessionBestAccuracyMeters?.let { "%.1f m".format(it) } ?: "—")
        DebugRow("Worst accuracy", state.sessionWorstAccuracyMeters?.let { "%.1f m".format(it) } ?: "—")
        DebugRow("Avg quality", if (state.sessionSamples > 0) "${state.sessionAverageQuality}/100" else "—")
        DebugRow("Peak lateral", "%.2f m/s²".format(state.sessionPeakLateralAccelerationMps2))
        DebugRow("Peak yaw", "%.1f°/s".format(state.sessionPeakYawRateDegS))
        DebugRow(
            "Heading avg",
            if (state.sessionHeadingSamples > 0) "%.1f°".format(state.sessionAverageHeadingErrorDeg) else "—",
        )
        DebugRow(
            "Heading peak",
            if (state.sessionHeadingSamples > 0) "%.1f°".format(state.sessionPeakHeadingErrorDeg) else "—",
        )
        DebugRow("Heading pts", state.sessionHeadingSamples.toString())
        DebugRow("Lane-like L", state.sessionLeftLateralEvents.toString())
        DebugRow("Lane-like R", state.sessionRightLateralEvents.toString())
        DebugRow("Turns/curves", state.sessionTurnEvents.toString())
        DebugRow("Spikes reject", state.sessionRejectedMotionSpikes.toString())
        DebugRow("Calibrated", if (state.sessionCalibrationReached) "YES" else "NO")
        Text(
            "Once stopped, these totals are stored on the phone and survive app restarts. You can come back later and send the summary.",
            color = Color(0xFF7EE787),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(18.dp))
        Text("Lane data: NOT LOADED", color = Color(0xFFFFCC80), fontWeight = FontWeight.Bold)
        Text(
            "No exact current-lane marker is drawn until the OSM lane graph is connected.",
            color = Color(0xFFB8C1CC),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(320.dp)) { ForwardLaneView(laneCount = 5) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color(0xFF8ED7FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Color(0xFF8B949E), modifier = Modifier.width(118.dp))
        Text(value, color = Color.White)
    }
}

private fun statusColor(state: GnssUiState): Color = when {
    state.sensorLaneReady -> Color(0xFF7EE787)
    state.fixReceived -> Color(0xFF8ED7FF)
    !state.permissionFine -> Color(0xFFFFCC80)
    else -> Color(0xFF8ED7FF)
}

@Composable
private fun ForwardLaneView(laneCount: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val horizonY = size.height * 0.16f
        val bottomY = size.height * 0.92f
        val horizonHalfWidth = size.width * 0.12f
        val bottomHalfWidth = size.width * 0.48f
        val centerX = size.width / 2f

        for (i in 0..laneCount) {
            val t = i.toFloat() / laneCount
            val topX = centerX - horizonHalfWidth + 2 * horizonHalfWidth * t
            val bottomX = centerX - bottomHalfWidth + 2 * bottomHalfWidth * t
            drawLine(
                color = Color(0xFF6C737F),
                start = Offset(topX, horizonY),
                end = Offset(bottomX, bottomY),
                strokeWidth = if (i == 0 || i == laneCount) 7f else 3f,
                cap = StrokeCap.Round,
            )
        }

        val unknownBand = Path().apply {
            moveTo(centerX - horizonHalfWidth, horizonY)
            lineTo(centerX + horizonHalfWidth, horizonY)
            lineTo(centerX + bottomHalfWidth, bottomY)
            lineTo(centerX - bottomHalfWidth, bottomY)
            close()
        }
        drawPath(unknownBand, Color(0x111F6FEB))
    }
}
