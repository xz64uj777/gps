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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.gps.lane.LaneApiSettings
import com.example.gps.location.DriveSessionRuntime
import com.example.gps.location.DriveSessionStore
import com.example.gps.location.DriveTrackingService
import com.example.gps.location.GnssUiState
import kotlin.math.roundToInt

private val AppBg = Color(0xFF090D15)
private val CardBg = Color(0xFF121927)
private val CardBgStrong = Color(0xFF172238)
private val Blue = Color(0xFF5BA8FF)
private val BlueSoft = Color(0xFF9CCBFF)
private val Green = Color(0xFF57D37D)
private val Amber = Color(0xFFFFB648)
private val Red = Color(0xFFFF6B6B)
private val Muted = Color(0xFF95A1B5)
private val Line = Color(0xFF2A3446)

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue,
                    secondary = Green,
                    background = AppBg,
                    surface = CardBg,
                )
            ) {
                DriverScreen(
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
private fun DriverScreen(
    state: GnssUiState,
    sessionActive: Boolean,
    laneApiUrl: String,
    onLaneApiUrlChange: (String) -> Unit,
    saveLaneApiUrl: () -> Unit,
    requestPermission: () -> Unit,
    startDrive: () -> Unit,
    stopDrive: () -> Unit,
) {
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    val laneNumber = state.likelyLaneNumberFromLeft
    val laneCount = state.likelyLaneCount?.coerceIn(1, 8)
    val laneKnown = laneNumber != null && laneCount != null
    val confidencePct = (state.laneConfidence.coerceIn(0f, 1f) * 100f).roundToInt()
    val speedMph = state.speedMps?.times(2.23694f)

    Column(
        Modifier
            .fillMaxSize()
            .background(AppBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("LaneGPS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                Text("Live lane guidance", color = Muted, fontSize = 13.sp)
            }
            StatusPill(
                text = if (sessionActive) "RECORDING" else "READY",
                color = if (sessionActive) Green else Blue,
            )
        }

        Spacer(Modifier.height(14.dp))

        LaneGuidanceCard(state = state, laneNumber = laneNumber, laneCount = laneCount)

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricCard(
                label = "GPS",
                value = state.qualityLabel,
                detail = "${state.qualityScore}/100",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "ACCURACY",
                value = state.accuracyMeters?.let { "%.1f m".format(it) } ?: "—",
                detail = if ((state.accuracyMeters ?: 999f) <= 5f) "lane-grade" else "estimate",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "SPEED",
                value = speedMph?.let { "%.0f".format(it) } ?: "—",
                detail = "mph",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lane confidence", color = Muted, fontSize = 12.sp)
                        Text(
                            when {
                                state.laneExactClaim -> "LOCKED"
                                laneKnown && state.laneConfidence >= 0.75f -> "STRONG LIKELY"
                                laneKnown -> "LIKELY"
                                state.fixReceived -> "SEARCHING"
                                else -> "WAITING FOR GPS"
                            },
                            color = when {
                                state.laneExactClaim -> Green
                                laneKnown -> Amber
                                else -> BlueSoft
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                    }
                    Text(
                        if (laneKnown) "$confidencePct%" else "—",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp,
                    )
                }
                Spacer(Modifier.height(9.dp))
                ConfidenceBar(if (laneKnown) state.laneConfidence else 0f, state.laneExactClaim)
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        state.laneExactClaim -> "Exact lane confirmed. Strong highlight is allowed."
                        laneKnown -> "Likely lane shown softly. The app will not pretend it knows more than the sensors and map support."
                        state.fixReceived -> "Road position is live; waiting for a reliable lane match."
                        else -> "Waiting for a location fix."
                    },
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Road tracking", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                MiniRow("Lane candidates", state.laneCandidateCount.toString())
                MiniRow("Motion", state.motionHint)
                MiniRow("Lane sensors", if (state.sensorLaneReady) "READY" else "LEARNING / LIMITED")
                if (!state.sensorLaneReady) {
                    Spacer(Modifier.height(6.dp))
                    Text(state.qualityReason, color = Muted, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = if (sessionActive) stopDrive else startDrive,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (sessionActive) Red else Blue,
                contentColor = Color(0xFF07101E),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                if (sessionActive) "STOP & SAVE DRIVE" else "START DRIVE",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
            )
        }

        if (!state.permissionFine && !sessionActive) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = requestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Enable precise location")
            }
        }

        if (!sessionActive && state.sessionSamples > 0) {
            Spacer(Modifier.height(14.dp))
            SessionSummaryCard(state)
        }

        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = { diagnosticsExpanded = !diagnosticsExpanded },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                if (diagnosticsExpanded) "Hide diagnostics" else "Diagnostics & developer settings",
                color = BlueSoft,
            )
        }

        if (diagnosticsExpanded) {
            DiagnosticsPanel(
                state = state,
                laneApiUrl = laneApiUrl,
                onLaneApiUrlChange = onLaneApiUrlChange,
                saveLaneApiUrl = saveLaneApiUrl,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LaneGuidanceCard(state: GnssUiState, laneNumber: Int?, laneCount: Int?) {
    val laneKnown = laneNumber != null && laneCount != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBgStrong),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier.padding(top = 18.dp, start = 14.dp, end = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    state.laneExactClaim && laneKnown -> "LANE $laneNumber OF $laneCount"
                    laneKnown -> "LIKELY LANE $laneNumber OF $laneCount"
                    state.fixReceived -> "FINDING YOUR LANE"
                    else -> "WAITING FOR GPS"
                },
                color = when {
                    state.laneExactClaim -> Green
                    laneKnown -> Amber
                    else -> BlueSoft
                },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 23.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.laneExactClaim -> "Exact lane confirmed"
                    laneKnown -> "Probable position — uncertainty shown on purpose"
                    else -> state.message
                },
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                ForwardLaneView(
                    laneCount = laneCount ?: 4,
                    likelyLaneNumberFromLeft = laneNumber,
                    exactLane = state.laneExactClaim,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(30.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun MetricCard(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1)
            Text(detail, color = BlueSoft, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ConfidenceBar(value: Float, exact: Boolean) {
    val progress = value.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Line, RoundedCornerShape(8.dp))
    ) {
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(if (exact) Green else Amber, RoundedCornerShape(8.dp))
            )
        }
    }
}

@Composable
private fun SessionSummaryCard(state: GnssUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Last drive", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            MiniRow("GPS samples", state.sessionSamples.toString())
            MiniRow("Best accuracy", state.sessionBestAccuracyMeters?.let { "%.1f m".format(it) } ?: "—")
            MiniRow("Average GPS", "${state.sessionAverageQuality}/100")
            MiniRow("Heading average", if (state.sessionHeadingSamples > 0) "%.1f°".format(state.sessionAverageHeadingErrorDeg) else "—")
            MiniRow("Lane-change events", (state.sessionLeftLateralEvents + state.sessionRightLateralEvents).toString())
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    state: GnssUiState,
    laneApiUrl: String,
    onLaneApiUrlChange: (String) -> Unit,
    saveLaneApiUrl: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1420)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("Lane matching")
            DebugRow("Map status", state.laneDataStatus)
            DebugRow("Candidates", state.laneCandidateCount.toString())
            DebugRow("Lane", if (state.likelyLaneNumberFromLeft != null && state.likelyLaneCount != null) "${state.likelyLaneNumberFromLeft} of ${state.likelyLaneCount}" else "—")
            DebugRow("Confidence", if (state.likelyLaneNumberFromLeft != null) "${(state.laneConfidence * 100f).roundToInt()}%" else "—")
            DebugRow("Exact claim", if (state.laneExactClaim) "YES" else "NO")

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
            SectionTitle("GNSS")
            DebugRow("Latitude", state.latitude?.let { "%.7f".format(it) } ?: "—")
            DebugRow("Longitude", state.longitude?.let { "%.7f".format(it) } ?: "—")
            DebugRow("Accuracy", state.accuracyMeters?.let { "%.1f m".format(it) } ?: "—")
            DebugRow("Quality", "${state.qualityScore}/100 · ${state.qualityLabel}")
            DebugRow("Satellites", "${state.satellitesUsedInFix}/${state.satellitesVisible}")
            DebugRow("C/N0", state.averageUsedCn0DbHz?.let { "%.1f dB-Hz".format(it) } ?: "—")
            DebugRow("Speed", state.speedMps?.let { "%.1f m/s".format(it) } ?: "—")
            DebugRow("Bearing", state.bearingDegrees?.let { "%.0f°".format(it) } ?: "—")
            DebugRow("Provider", state.provider ?: "—")

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
            SectionTitle("Motion")
            DebugRow("Sensor heading", state.sensorHeadingDegrees?.let { "%.0f°".format(it) } ?: "—")
            DebugRow("Fused heading", state.fusedHeadingDegrees?.let { "%.0f°".format(it) } ?: "—")
            DebugRow("Lateral", state.lateralAccelerationMps2?.let { "%+.2f m/s²".format(it) } ?: "—")
            DebugRow("Yaw", state.yawRateDegS?.let { "%+.1f°/s".format(it) } ?: "—")
            DebugRow("Motion", state.motionHint)
            DebugRow("Calibration", if (state.sensorFrameCalibrated) "LEARNED" else "LEARNING")

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
            SectionTitle("Developer backend")
            Text(
                "Direct OSM matching is the default. This URL is only for the optional development backend.",
                color = Muted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = laneApiUrl,
                onValueChange = onLaneApiUrlChange,
                label = { Text("Backend URL") },
                placeholder = { Text("http://192.168.1.50:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = saveLaneApiUrl, modifier = Modifier.fillMaxWidth()) {
                Text("Save backend URL")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = BlueSoft, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun MiniRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, modifier = Modifier.width(112.dp), fontSize = 12.sp)
        Text(value, color = Color.White, modifier = Modifier.weight(1f), fontSize = 12.sp)
    }
}

@Composable
private fun ForwardLaneView(
    laneCount: Int,
    likelyLaneNumberFromLeft: Int?,
    exactLane: Boolean,
) {
    Canvas(Modifier.fillMaxSize()) {
        val safeLaneCount = laneCount.coerceIn(1, 8)
        val horizonY = size.height * 0.10f
        val bottomY = size.height * 0.94f
        val horizonHalfWidth = size.width * 0.15f
        val bottomHalfWidth = size.width * 0.48f
        val centerX = size.width / 2f

        val road = Path().apply {
            moveTo(centerX - horizonHalfWidth, horizonY)
            lineTo(centerX + horizonHalfWidth, horizonY)
            lineTo(centerX + bottomHalfWidth, bottomY)
            lineTo(centerX - bottomHalfWidth, bottomY)
            close()
        }
        drawPath(road, Color(0xFF202837))

        val likelyIndex = likelyLaneNumberFromLeft?.minus(1)
        if (likelyIndex != null && likelyIndex in 0 until safeLaneCount) {
            val leftT = likelyIndex.toFloat() / safeLaneCount
            val rightT = (likelyIndex + 1).toFloat() / safeLaneCount
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
            drawPath(
                band,
                if (exactLane) Green.copy(alpha = 0.38f) else Amber.copy(alpha = 0.24f),
            )

            val markerX = (bottomLeft + bottomRight) / 2f
            val markerY = bottomY - size.height * 0.06f
            val marker = Path().apply {
                moveTo(markerX, markerY - 18f)
                lineTo(markerX - 15f, markerY + 12f)
                lineTo(markerX + 15f, markerY + 12f)
                close()
            }
            drawPath(marker, if (exactLane) Green else Amber)
        }

        for (i in 0..safeLaneCount) {
            val t = i.toFloat() / safeLaneCount
            val topX = centerX - horizonHalfWidth + 2 * horizonHalfWidth * t
            val bottomX = centerX - bottomHalfWidth + 2 * bottomHalfWidth * t
            drawLine(
                color = if (i == 0 || i == safeLaneCount) Color(0xFFBEC8D8) else Color(0xFF7B8799),
                start = Offset(topX, horizonY),
                end = Offset(bottomX, bottomY),
                strokeWidth = if (i == 0 || i == safeLaneCount) 6f else 3f,
                cap = StrokeCap.Round,
            )
        }
    }
}
