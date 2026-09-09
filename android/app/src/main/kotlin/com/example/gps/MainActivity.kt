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
import com.example.gps.route.OpenRouteClient
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
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

private data class RouteUiState(
    val busy: Boolean = false,
    val waitingForGps: Boolean = false,
    val error: String? = null,
    val summary: OpenRouteClient.RouteSummary? = null,
)

class MainActivity : ComponentActivity() {
    private lateinit var store: DriveSessionStore
    private lateinit var laneApiSettings: LaneApiSettings
    private val routeExecutor = Executors.newSingleThreadExecutor()
    private val routeClient = OpenRouteClient()

    private var uiState by mutableStateOf(GnssUiState())
    private var sessionActive by mutableStateOf(false)
    private var laneApiUrl by mutableStateOf("")
    private var routeQuery by mutableStateOf("")
    private var routeState by mutableStateOf(RouteUiState())
    private var exportedLogName by mutableStateOf<String?>(null)
    private var pendingStart = false
    private var pendingRouteQuery: String? = null

    private val runtimeListener: (GnssUiState) -> Unit = { state ->
        runOnUiThread {
            uiState = state
            sessionActive = store.isActive()
            exportedLogName = store.lastExportedLogName()

            val pending = pendingRouteQuery
            if (pending != null && isFreshRouteOrigin(state)) {
                pendingRouteQuery = null
                launchRoutePlan(pending, state.latitude!!, state.longitude!!)
            }
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
        exportedLogName = store.lastExportedLogName()

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
                    routeQuery = routeQuery,
                    routeState = routeState,
                    exportedLogName = exportedLogName,
                    laneApiUrl = laneApiUrl,
                    onRouteQueryChange = { routeQuery = it },
                    onPlanRoute = { requestRoutePlan() },
                    onClearRoute = { clearRoute() },
                    onLaneApiUrlChange = { laneApiUrl = it },
                    saveLaneApiUrl = { saveLaneApiUrl() },
                    requestPermission = { requestPermissionsForDrive(startAfterGrant = false) },
                    startDrive = { startDriveTest() },
                    stopDrive = { stopDriveTest() },
                    openTripLab = { startActivity(Intent(this, ReplayActivity::class.java)) },
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
        exportedLogName = store.lastExportedLogName()
        DriveSessionRuntime.addListener(runtimeListener)
    }

    override fun onStop() {
        DriveSessionRuntime.removeListener(runtimeListener)
        super.onStop()
    }

    override fun onDestroy() {
        routeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requestRoutePlan() {
        val query = routeQuery.trim()
        if (query.isBlank()) {
            routeState = RouteUiState(error = "Enter a destination first.")
            return
        }

        if (isFreshRouteOrigin(uiState)) {
            pendingRouteQuery = null
            launchRoutePlan(query, uiState.latitude!!, uiState.longitude!!)
        } else {
            pendingRouteQuery = query
            routeState = RouteUiState(
                waitingForGps = true,
                error = if (sessionActive) {
                    "Waiting for a fresh GPS fix. Route will plan automatically."
                } else {
                    "Destination saved. Start Drive while parked and the route will plan when GPS locks."
                },
            )
        }
    }

    private fun launchRoutePlan(query: String, lat: Double, lon: Double) {
        routeState = RouteUiState(busy = true)
        routeExecutor.execute {
            val result = runCatching { routeClient.plan(query, lat, lon) }
            runOnUiThread {
                routeState = result.fold(
                    onSuccess = { RouteUiState(summary = it) },
                    onFailure = {
                        RouteUiState(
                            error = it.message ?: "Could not plan this route.",
                        )
                    },
                )
            }
        }
    }

    private fun clearRoute() {
        pendingRouteQuery = null
        routeState = RouteUiState()
        routeQuery = ""
    }

    private fun isFreshRouteOrigin(state: GnssUiState): Boolean {
        val timestamp = state.lastUpdateMillis ?: return false
        if (state.latitude == null || state.longitude == null || !state.fixReceived) return false
        return System.currentTimeMillis() - timestamp <= 10_000L
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
    routeQuery: String,
    routeState: RouteUiState,
    exportedLogName: String?,
    laneApiUrl: String,
    onRouteQueryChange: (String) -> Unit,
    onPlanRoute: () -> Unit,
    onClearRoute: () -> Unit,
    onLaneApiUrlChange: (String) -> Unit,
    saveLaneApiUrl: () -> Unit,
    requestPermission: () -> Unit,
    startDrive: () -> Unit,
    stopDrive: () -> Unit,
    openTripLab: () -> Unit,
) {
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(sessionActive, state.lastUpdateMillis) {
        nowMillis = System.currentTimeMillis()
        while (sessionActive) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val fixAgeMs = state.lastUpdateMillis?.let { (nowMillis - it).coerceAtLeast(0L) }
    val gpsStale = sessionActive && state.fixReceived && fixAgeMs != null && fixAgeMs > 3_000L
    val laneNumber = if (gpsStale) null else state.likelyLaneNumberFromLeft
    val laneCount = if (gpsStale) null else state.likelyLaneCount?.coerceIn(1, 8)
    val laneKnown = laneNumber != null && laneCount != null
    val exactLane = state.laneExactClaim && !gpsStale
    val speedMph = if (gpsStale) null else state.speedMps?.times(2.23694f)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(AppBg)
    ) {
        val wide = maxWidth >= 700.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 22.dp else 16.dp, vertical = 14.dp)
        ) {
            DriverHeader(sessionActive = sessionActive, gpsStale = gpsStale)

            if (gpsStale) {
                Spacer(Modifier.height(12.dp))
                GpsLostBanner(fixAgeMs ?: 0L)
            }

            Spacer(Modifier.height(14.dp))

            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1.18f)) {
                        LaneGuidanceCard(
                            state = state,
                            laneNumber = laneNumber,
                            laneCount = laneCount,
                            exactLane = exactLane,
                            gpsStale = gpsStale,
                            height = 360.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        LaneConfidenceCard(
                            state = state,
                            laneKnown = laneKnown,
                            exactLane = exactLane,
                            gpsStale = gpsStale,
                        )
                    }

                    Column(Modifier.weight(0.82f)) {
                        RoutePlannerCard(
                            query = routeQuery,
                            routeState = routeState,
                            onQueryChange = onRouteQueryChange,
                            onPlan = onPlanRoute,
                            onClear = onClearRoute,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricStrip(state = state, speedMph = speedMph, gpsStale = gpsStale)
                        Spacer(Modifier.height(12.dp))
                        RoadTrackingCard(state = state, gpsStale = gpsStale)
                    }
                }
            } else {
                LaneGuidanceCard(
                    state = state,
                    laneNumber = laneNumber,
                    laneCount = laneCount,
                    exactLane = exactLane,
                    gpsStale = gpsStale,
                    height = 280.dp,
                )
                Spacer(Modifier.height(12.dp))
                RoutePlannerCard(
                    query = routeQuery,
                    routeState = routeState,
                    onQueryChange = onRouteQueryChange,
                    onPlan = onPlanRoute,
                    onClear = onClearRoute,
                )
                Spacer(Modifier.height(12.dp))
                MetricStrip(state = state, speedMph = speedMph, gpsStale = gpsStale)
                Spacer(Modifier.height(12.dp))
                LaneConfidenceCard(
                    state = state,
                    laneKnown = laneKnown,
                    exactLane = exactLane,
                    gpsStale = gpsStale,
                )
                Spacer(Modifier.height(12.dp))
                RoadTrackingCard(state = state, gpsStale = gpsStale)
            }

            Spacer(Modifier.height(14.dp))

            if (!sessionActive) {
                PreDriveCard(
                    permissionFine = state.permissionFine,
                    routeReady = routeState.summary != null,
                    routeWaiting = routeState.waitingForGps,
                )
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = if (sessionActive) stopDrive else startDrive,
                modifier = Modifier.fillMaxWidth().height(58.dp),
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
                SessionSummaryCard(state = state, exportedLogName = exportedLogName)
            }

            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    if (diagnosticsExpanded) "Hide tools & diagnostics" else "Tools & diagnostics",
                    color = BlueSoft,
                )
            }

            if (diagnosticsExpanded) {
                DiagnosticsPanel(
                    state = state,
                    fixAgeMs = fixAgeMs,
                    laneApiUrl = laneApiUrl,
                    onLaneApiUrlChange = onLaneApiUrlChange,
                    saveLaneApiUrl = saveLaneApiUrl,
                    openTripLab = openTripLab,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DriverHeader(sessionActive: Boolean, gpsStale: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("LaneGPS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            Text("Lane-aware navigation", color = Muted, fontSize = 13.sp)
        }
        StatusPill(
            text = when {
                gpsStale -> "GPS LOST"
                sessionActive -> "RECORDING"
                else -> "READY"
            },
            color = when {
                gpsStale -> Red
                sessionActive -> Green
                else -> Blue
            },
        )
    }
}

@Composable
private fun GpsLostBanner(fixAgeMs: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.13f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("GPS SIGNAL LOST", color = Red, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(
                "Last fresh fix was ${formatAge(fixAgeMs)} ago. Lane guidance is hidden until fresh GPS returns.",
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun RoutePlannerCard(
    query: String,
    routeState: RouteUiState,
    onQueryChange: (String) -> Unit,
    onPlan: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Destination", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Real route foundation", color = Muted, fontSize = 11.sp)
                }
                if (routeState.summary != null) {
                    StatusPill("ROUTE READY", Green)
                }
            }

            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Where are you going?") },
                placeholder = { Text("Address, place or business") },
                enabled = !routeState.busy,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPlan,
                    enabled = !routeState.busy && query.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (routeState.busy) "PLANNING…" else "PLAN ROUTE")
                }
                if (routeState.summary != null || routeState.waitingForGps) {
                    OutlinedButton(onClick = onClear) {
                        Text("CLEAR")
                    }
                }
            }

            routeState.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = if (routeState.waitingForGps) Amber else Red,
                    fontSize = 12.sp,
                )
            }

            routeState.summary?.let { route ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(10.dp))
                Text(
                    route.destinationName,
                    color = BlueSoft,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RouteMetric("ROUTE", formatDistance(route.distanceMeters), Modifier.weight(1f))
                    RouteMetric("ETA", formatDuration(route.durationSeconds), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text("NEXT MANEUVER", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    route.nextManeuver,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 21.sp,
                )
                if (route.nextRoad.isNotBlank()) {
                    Text(route.nextRoad, color = BlueSoft, fontSize = 13.sp, maxLines = 1)
                }
                Text(
                    "${formatDistance(route.nextManeuverDistanceMeters)} ahead",
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "Target-lane guidance will attach to this maneuver next. LaneGPS will not invent a target lane before the map supports it.",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun RouteMetric(label: String, value: String, modifier: Modifier) {
    Box(
        modifier
            .background(CardBgStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Column {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun MetricStrip(state: GnssUiState, speedMph: Float?, gpsStale: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricCard(
            label = "GPS",
            value = if (gpsStale) "LOST" else state.qualityLabel,
            detail = if (gpsStale) "stale fix" else "${state.qualityScore}/100",
            modifier = Modifier.weight(1f),
            warning = gpsStale,
        )
        MetricCard(
            label = "ACCURACY",
            value = if (gpsStale) "—" else state.accuracyMeters?.let { "%.1f m".format(it) } ?: "—",
            detail = if (!gpsStale && (state.accuracyMeters ?: 999f) <= 5f) "lane-grade" else "estimate",
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            label = "SPEED",
            value = speedMph?.let { "%.0f".format(it) } ?: "—",
            detail = "mph",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LaneConfidenceCard(
    state: GnssUiState,
    laneKnown: Boolean,
    exactLane: Boolean,
    gpsStale: Boolean,
) {
    val confidencePct = (state.laneConfidence.coerceIn(0f, 1f) * 100f).roundToInt()
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
                            gpsStale -> "GPS LOST"
                            exactLane -> "LOCKED"
                            laneKnown && state.laneConfidence >= 0.75f -> "STRONG LIKELY"
                            laneKnown -> "LIKELY"
                            state.fixReceived -> "SEARCHING"
                            else -> "WAITING FOR GPS"
                        },
                        color = when {
                            gpsStale -> Red
                            exactLane -> Green
                            laneKnown -> Amber
                            else -> BlueSoft
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
                Text(
                    if (laneKnown && !gpsStale) "$confidencePct%" else "—",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                )
            }
            Spacer(Modifier.height(9.dp))
            ConfidenceBar(if (laneKnown && !gpsStale) state.laneConfidence else 0f, exactLane)
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    gpsStale -> "Old location data is never allowed to masquerade as live lane guidance."
                    exactLane -> "Exact lane confirmed."
                    laneKnown -> "Probable lane shown softly. Uncertainty stays visible."
                    state.fixReceived -> "Road position is live; waiting for a reliable lane match."
                    else -> "Waiting for a location fix."
                },
                color = Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun RoadTrackingCard(state: GnssUiState, gpsStale: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Road tracking", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            MiniRow("Lane candidates", if (gpsStale) "—" else state.laneCandidateCount.toString())
            MiniRow("Motion", if (gpsStale) "WAITING FOR GPS" else state.motionHint)
            MiniRow("Lane sensors", if (!gpsStale && state.sensorLaneReady) "READY" else "LEARNING / LIMITED")
            if (!state.sensorLaneReady || gpsStale) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (gpsStale) "Fresh GNSS required before lane guidance resumes." else state.qualityReason,
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PreDriveCard(permissionFine: Boolean, routeReady: Boolean, routeWaiting: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Before you drive", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            ChecklistRow("Precise location", permissionFine)
            ChecklistRow(
                "Destination",
                routeReady,
                pending = routeWaiting,
                pendingText = "queued for GPS",
            )
            Text(
                "Set the destination and press Start Drive while parked. If the route is waiting for GPS, it will calculate automatically after the first fresh fix.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

@Composable
private fun ChecklistRow(label: String, ready: Boolean, pending: Boolean = false, pendingText: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text(
            when {
                ready -> "READY"
                pending -> pendingText.uppercase()
                else -> "NEEDED"
            },
            color = when {
                ready -> Green
                pending -> Amber
                else -> Muted
            },
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LaneGuidanceCard(
    state: GnssUiState,
    laneNumber: Int?,
    laneCount: Int?,
    exactLane: Boolean,
    gpsStale: Boolean,
    height: androidx.compose.ui.unit.Dp,
) {
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
                    gpsStale -> "GPS SIGNAL LOST"
                    exactLane && laneKnown -> "LANE $laneNumber OF $laneCount"
                    laneKnown -> "LIKELY LANE $laneNumber OF $laneCount"
                    state.fixReceived -> "FINDING YOUR LANE"
                    else -> "WAITING FOR GPS"
                },
                color = when {
                    gpsStale -> Red
                    exactLane -> Green
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
                    gpsStale -> "Guidance paused until fresh location returns"
                    exactLane -> "Exact lane confirmed"
                    laneKnown -> "Probable position — uncertainty shown on purpose"
                    else -> state.message
                },
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(height)) {
                ForwardLaneView(
                    laneCount = laneCount ?: 4,
                    likelyLaneNumberFromLeft = laneNumber,
                    exactLane = exactLane,
                    disabled = gpsStale,
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
private fun MetricCard(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = if (warning) Red.copy(alpha = 0.12f) else CardBg),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                color = if (warning) Red else Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                maxLines = 1,
            )
            Text(detail, color = if (warning) Red else BlueSoft, fontSize = 10.sp, maxLines = 1)
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
private fun SessionSummaryCard(state: GnssUiState, exportedLogName: String?) {
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
            MiniRow(
                "Heading average",
                if (state.sessionHeadingSamples > 0) "%.1f°".format(state.sessionAverageHeadingErrorDeg) else "—",
            )
            MiniRow(
                "Lane-change events",
                (state.sessionLeftLateralEvents + state.sessionRightLateralEvents).toString(),
            )
            if (exportedLogName != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
                Text("Saved to Download/LaneGPS", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(exportedLogName, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    state: GnssUiState,
    fixAgeMs: Long?,
    laneApiUrl: String,
    onLaneApiUrlChange: (String) -> Unit,
    saveLaneApiUrl: () -> Unit,
    openTripLab: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1420)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("Trip tools")
            OutlinedButton(onClick = openTripLab, modifier = Modifier.fillMaxWidth()) {
                Text("OPEN TRIP LAB")
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
            SectionTitle("Lane matching")
            DebugRow("Map status", state.laneDataStatus)
            DebugRow("Candidates", state.laneCandidateCount.toString())
            DebugRow(
                "Lane",
                if (state.likelyLaneNumberFromLeft != null && state.likelyLaneCount != null) {
                    "${state.likelyLaneNumberFromLeft} of ${state.likelyLaneCount}"
                } else {
                    "—"
                },
            )
            DebugRow(
                "Confidence",
                if (state.likelyLaneNumberFromLeft != null) {
                    "${(state.laneConfidence * 100f).roundToInt()}%"
                } else {
                    "—"
                },
            )
            DebugRow("Exact claim", if (state.laneExactClaim) "YES" else "NO")

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
            SectionTitle("GNSS")
            DebugRow("Latitude", state.latitude?.let { "%.7f".format(it) } ?: "—")
            DebugRow("Longitude", state.longitude?.let { "%.7f".format(it) } ?: "—")
            DebugRow("Fix age", fixAgeMs?.let(::formatAge) ?: "—")
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
        Text(
            value,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, modifier = Modifier.width(112.dp), fontSize = 12.sp)
        Text(value, color = Color.White, modifier = Modifier.weight(1f), fontSize = 12.sp)
    }
}

private fun formatAge(ageMs: Long): String =
    if (ageMs < 1_000L) "${ageMs} ms" else "%.1f s".format(ageMs / 1_000f)

private fun formatDistance(meters: Double): String =
    if (meters >= 1_609.344) {
        "%.1f mi".format(meters / 1_609.344)
    } else {
        "${meters.roundToInt()} m"
    }

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remainder = minutes % 60
        "${hours}h ${remainder}m"
    } else {
        "$minutes min"
    }
}

@Composable
private fun ForwardLaneView(
    laneCount: Int,
    likelyLaneNumberFromLeft: Int?,
    exactLane: Boolean,
    disabled: Boolean,
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
        drawPath(road, if (disabled) Color(0xFF181D27) else Color(0xFF202837))

        val likelyIndex = likelyLaneNumberFromLeft?.minus(1)
        if (!disabled && likelyIndex != null && likelyIndex in 0 until safeLaneCount) {
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
                color = if (disabled) {
                    Color(0xFF4A5260)
                } else if (i == 0 || i == safeLaneCount) {
                    Color(0xFFBEC8D8)
                } else {
                    Color(0xFF7B8799)
                },
                start = Offset(topX, horizonY),
                end = Offset(bottomX, bottomY),
                strokeWidth = if (i == 0 || i == safeLaneCount) 6f else 3f,
                cap = StrokeCap.Round,
            )
        }
    }
}
