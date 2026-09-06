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
import androidx.compose.material3.OutlinedTextField
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
import com.example.gps.lane.LaneApiSettings
import com.example.gps.location.DriveSessionRuntime
import com.example.gps.location.DriveSessionStore
import com.example.gps.location.DriveTrackingService
import com.example.gps.location.GnssUiState
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var store: DriveSessionStore
    private lateinit var laneApiSettings: LaneApiSettings
    private var uiState by mutableStateOf(GnssUiState())
    private var sessionActive by mutableStateOf(false)
    private var laneApiUrl by mutableStateOf("")
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
        laneApiSettings = LaneApiSettings(this)
        laneApiUrl = laneApiSettings.getBaseUrl()
        uiState = DriveSessionRuntime.latest() ?: store.load()
        sessionActive = store.isActive()

        setContent {
            MaterialTheme {
                NavigationDebugScreen(
                    state = uiState,
                    sessionActive = sessionActive,
                    laneApiUrl = laneApiUrl,
                    onLaneApiUrlChange = { laneApiUrl = it },
                    saveLaneApiUrl = { saveLaneApiUrl() },
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

    private fun saveLaneApiUrl() {
        laneApiSettings.setBaseUrl(laneApiUrl)
        laneApiUrl = laneApiSettings.getBaseUrl()
    }

    private fun startDriveTest() {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        val needsLocalNetworkPermission =
            Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED

        if (!hasFineLocationPermission() || needsNotificationPermission || needsLocalNetworkPermission) {
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
        if (Build.VERSION.SDK_INT >= 37) {
            permissions += Manifest.permission.ACCESS_LOCAL_NETWORK
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
private fun NavigationDebugScreen(
    state: GnssUiState,
    sessionActive: Boolean,
    laneApiUrl: String,
    onLaneApiUrlChange: (String) -> Unit,
    saveLaneApiUrl: () -> Unit,
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
        Text("Lane GPS · Live Lane Match", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
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

        Spacer(Modifier.height(16.dp))
        SectionTitle("Lane API")
        OutlinedTextField(
            value = laneApiUrl,
            onValueChange = onLaneApiUrlChange,
            label = { Text("Backend URL") },
            placeholder = { Text("http://192.168.1.50:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = saveLaneApiUrl) { Text("SAVE LANE API URL") }
        Text(
            "Development mode: enter the Lane GPS backend address on the same Wi-Fi network. The app will fetch a nearby OSM corridor and ask the local dev API to populate it when empty.",
            color = Color(0xFFB8C1CC),
            fontSize = 12.sp,
        )

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
        SectionTitle("Lane matching")
        DebugRow("Map status", state.laneDataStatus)
        DebugRow("Candidates", state.laneCandidateCount.toString())
        val likelyLane = if (state.likelyLaneNumberFromLeft != null && state.likelyLaneCount != null) {
            "${state.likelyLaneNumberFromLeft} of ${state.likelyLaneCount} from left"
        } else {
            "—"
        }
        DebugRow("Likely lane", likelyLane)
        DebugRow(
            "Confidence",
            if (state.likelyLaneNumberFromLeft != null) "${(state.laneConfidence * 100f).roundToInt()}%" else "—",
        )
        Text(
            if (state.laneExactClaim)
                "EXACT-LANE CLAIM ACTIVE · lane highlight is allowed"
            else
                "UNCERTAIN · no exact lane highlight until GNSS, sensors, map data, and matcher confidence all agree",
            color = if (state.laneExactClaim) Color(0xFF7EE787) else Color(0xFFFFCC80),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )

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
            "Once stopped, these totals are stored on the phone and survive app restarts.",
            color = Color(0xFF7EE787),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(18.dp))
        val laneCount = state.likelyLaneCount?.coerceIn(1, 8) ?: 5
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            ForwardLaneView(
                laneCount = laneCount,
                exactLaneNumberFromLeft = if (state.laneExactClaim) state.likelyLaneNumberFromLeft else null,
            )
        }
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
    state.laneExactClaim -> Color(0xFF7EE787)
    state.sensorLaneReady -> Color(0xFF7EE787)
    state.fixReceived -> Color(0xFF8ED7FF)
    !state.permissionFine -> Color(0xFFFFCC80)
    else -> Color(0xFF8ED7FF)
}

@Composable
private fun ForwardLaneView(laneCount: Int, exactLaneNumberFromLeft: Int?) {
    Canvas(Modifier.fillMaxSize()) {
        val horizonY = size.height * 0.16f
        val bottomY = size.height * 0.92f
        val horizonHalfWidth = size.width * 0.12f
        val bottomHalfWidth = size.width * 0.48f
        val centerX = size.width / 2f

        val exactIndex = exactLaneNumberFromLeft?.minus(1)
        if (exactIndex != null && exactIndex in 0 until laneCount) {
            val leftT = exactIndex.toFloat() / laneCount
            val rightT = (exactIndex + 1).toFloat() / laneCount
            val topLeft = centerX - horizonHalfWidth + 2 * horizonHalfWidth * leftT
            val topRight = centerX - horizonHalfWidth + 2 * horizonHalfWidth * rightT
            val bottomLeft = centerX - bottomHalfWidth + 2 * bottomHalfWidth * leftT
            val bottomRight = centerX - bottomHalfWidth + 2 * bottomHalfWidth * rightT
            val band = Path().apply {
                moveTo(topLeft, horizonY)
                lineTo(topRight, horizonY)
                lineTo(bottomRight, bottomY)
                lineTo(bottomLeft, bottomY)
                close()
            }
            drawPath(band, Color(0x553FB950))
        }

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
    }
}
