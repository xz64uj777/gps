package com.example.gps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.gps.location.DriveSessionRuntime
import com.example.gps.location.DriveSessionStore
import com.example.gps.location.DriveTrackingService
import com.example.gps.location.GnssUiState
import com.example.gps.route.DestinationStore
import com.example.gps.route.OpenRouteClient
import com.example.gps.route.PhotonSearchClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private val NavBg = Color(0xFF080D15)
private val NavCard = Color(0xFF121A28)
private val NavCardStrong = Color(0xFF18243A)
private val NavBlue = Color(0xFF5BA8FF)
private val NavBlueSoft = Color(0xFFA7D2FF)
private val NavGreen = Color(0xFF58D47F)
private val NavAmber = Color(0xFFFFB648)
private val NavRed = Color(0xFFFF6B6B)
private val NavMuted = Color(0xFF9AA6BA)
private val NavLine = Color(0xFF2A3548)

private data class NavigationRouteUi(
    val planning: Boolean = false,
    val rerouting: Boolean = false,
    val waitingForGps: Boolean = false,
    val error: String? = null,
    val summary: OpenRouteClient.RouteSummary? = null,
)

/**
 * Keeps the active route across Fold open/close Activity recreation and ordinary
 * foreground interruptions such as a phone call. Process-death persistence can
 * be added later once the navigation model settles.
 */
private object NavigationRuntimeCache {
    @Volatile var query: String = ""
    @Volatile var route: OpenRouteClient.RouteSummary? = null
}

class NavigationActivity : ComponentActivity() {
    private lateinit var store: DriveSessionStore
    private val routeClient = OpenRouteClient()
    private val routeExecutor = Executors.newSingleThreadExecutor()

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var mapStyleReady = false
    private var lastMapFixTimestamp: Long? = null

    private var uiState by mutableStateOf(GnssUiState())
    private var sessionActive by mutableStateOf(false)
    private var routeQuery by mutableStateOf("")
    private var routeUi by mutableStateOf(NavigationRouteUi())

    private var pendingStart = false
    private var pendingRouteQuery: String? = null
    private var lastProgressFixTimestamp: Long? = null
    private var offRouteFixStreak = 0
    private var lastRerouteElapsed = 0L

    private val runtimeListener: (GnssUiState) -> Unit = { state ->
        runOnUiThread {
            uiState = state
            sessionActive = store.isActive()

            val pending = pendingRouteQuery
            if (pending != null && freshLocation(state)) {
                pendingRouteQuery = null
                planNewRoute(pending, state.latitude!!, state.longitude!!)
            }

            updateLiveRouteFromFix(state)
            updateMapFromState(state, routeUi.summary)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val shouldStart = pendingStart && hasFineLocationPermission()
            pendingStart = false
            if (shouldStart) startDriveInternal()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DriveSessionStore(this)
        uiState = DriveSessionRuntime.latest() ?: store.load()
        sessionActive = store.isActive()
        routeQuery = NavigationRuntimeCache.query
        routeUi = NavigationRouteUi(summary = NavigationRuntimeCache.route)

        MapLibre.getInstance(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.uiSettings.isCompassEnabled = true
            readyMap.uiSettings.isLogoEnabled = true
            readyMap.uiSettings.isAttributionEnabled = true
            readyMap.setStyle(MAP_STYLE_URI) { style ->
                installNavigationLayers(style)
                mapStyleReady = true
                updateMapFromState(uiState, routeUi.summary, forceCamera = true)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NavBlue,
                    secondary = NavGreen,
                    background = NavBg,
                    surface = NavCard,
                )
            ) {
                NavigationScreen(
                    state = uiState,
                    sessionActive = sessionActive,
                    query = routeQuery,
                    routeUi = routeUi,
                    mapView = mapView,
                    onQueryChange = {
                        routeQuery = it
                        NavigationRuntimeCache.query = it
                    },
                    onFindRoute = { requestRoutePlan() },
                    onClearRoute = { clearRoute() },
                    onStartDrive = { startDrive() },
                    onStopDrive = { stopDrive() },
                    onEnableLocation = { requestDrivePermissions(false) },
                    onOpenDiagnostics = { startActivity(Intent(this, MainActivity::class.java)) },
                )
            }
        }

        if (sessionActive && hasFineLocationPermission()) resumeDrive()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        uiState = DriveSessionRuntime.latest() ?: store.load()
        sessionActive = store.isActive()
        DriveSessionRuntime.addListener(runtimeListener)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        DriveSessionRuntime.removeListener(runtimeListener)
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        routeExecutor.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    private fun requestRoutePlan() {
        val query = routeQuery.trim()
        if (query.isBlank()) {
            routeUi = routeUi.copy(error = "Enter where you're going first.")
            return
        }
        NavigationRuntimeCache.query = query

        if (freshLocation(uiState)) {
            pendingRouteQuery = null
            planNewRoute(query, uiState.latitude!!, uiState.longitude!!)
        } else {
            pendingRouteQuery = query
            routeUi = routeUi.copy(
                waitingForGps = true,
                error = if (sessionActive) {
                    "Waiting for a fresh GPS fix. Route will start automatically."
                } else {
                    "Destination saved. Start Drive while parked; the route will start when GPS locks."
                },
            )
        }
    }

    private fun planNewRoute(query: String, lat: Double, lon: Double) {
        if (routeUi.planning) return
        routeUi = routeUi.copy(planning = true, waitingForGps = false, error = null)
        routeExecutor.execute {
            val result = runCatching { routeClient.plan(query, lat, lon) }
            runOnUiThread {
                routeUi = result.fold(
                    onSuccess = { summary ->
                        NavigationRuntimeCache.route = summary
                        offRouteFixStreak = 0
                        NavigationRouteUi(summary = summary)
                    },
                    onFailure = { error ->
                        routeUi.copy(
                            planning = false,
                            error = error.message ?: "Could not build this route.",
                        )
                    },
                )
                updateMapFromState(uiState, routeUi.summary, forceCamera = true)
            }
        }
    }

    private fun updateLiveRouteFromFix(state: GnssUiState) {
        val summary = routeUi.summary ?: return
        if (!freshLocation(state)) return
        val fixTimestamp = state.lastUpdateMillis ?: return
        if (fixTimestamp == lastProgressFixTimestamp) return
        lastProgressFixTimestamp = fixTimestamp

        val progressed = routeClient.updateProgress(summary, state.latitude!!, state.longitude!!)
        NavigationRuntimeCache.route = progressed
        routeUi = routeUi.copy(summary = progressed, error = null)

        if (!sessionActive || progressed.arrived || routeUi.planning || routeUi.rerouting) {
            offRouteFixStreak = 0
            return
        }

        val accuracyGoodEnough = (state.accuracyMeters ?: 999f) <= 25f
        val moving = (state.speedMps ?: 0f) >= 2f
        if (progressed.offRouteDistanceMeters > OFF_ROUTE_REROUTE_METERS && accuracyGoodEnough && moving) {
            offRouteFixStreak++
        } else {
            offRouteFixStreak = 0
        }

        val now = SystemClock.elapsedRealtime()
        if (offRouteFixStreak >= OFF_ROUTE_FIXES_REQUIRED && now - lastRerouteElapsed >= REROUTE_COOLDOWN_MS) {
            offRouteFixStreak = 0
            lastRerouteElapsed = now
            reroute(progressed, state.latitude!!, state.longitude!!)
        }
    }

    private fun reroute(previous: OpenRouteClient.RouteSummary, lat: Double, lon: Double) {
        if (routeUi.rerouting) return
        routeUi = routeUi.copy(rerouting = true, error = null)
        routeExecutor.execute {
            val result = runCatching { routeClient.reroute(previous, lat, lon) }
            runOnUiThread {
                routeUi = result.fold(
                    onSuccess = { summary ->
                        NavigationRuntimeCache.route = summary
                        routeUi.copy(rerouting = false, summary = summary, error = null)
                    },
                    onFailure = { error ->
                        routeUi.copy(
                            rerouting = false,
                            error = "Reroute delayed: ${error.message ?: "routing service unavailable"}",
                        )
                    },
                )
                updateMapFromState(uiState, routeUi.summary, forceCamera = true)
            }
        }
    }

    private fun clearRoute() {
        pendingRouteQuery = null
        NavigationRuntimeCache.query = ""
        NavigationRuntimeCache.route = null
        routeQuery = ""
        routeUi = NavigationRouteUi()
        offRouteFixStreak = 0
        updateMapFromState(uiState, null, forceCamera = true)
    }

    private fun freshLocation(state: GnssUiState): Boolean {
        val timestamp = state.lastUpdateMillis ?: return false
        if (!state.fixReceived || state.latitude == null || state.longitude == null) return false
        return System.currentTimeMillis() - timestamp <= 10_000L
    }

    private fun startDrive() {
        if (!hasFineLocationPermission() || needsNotificationPermission()) {
            requestDrivePermissions(true)
        } else {
            startDriveInternal()
        }
    }

    private fun startDriveInternal() {
        sessionActive = true
        uiState = GnssUiState(message = "Starting drive…")
        lastProgressFixTimestamp = null
        val intent = Intent(this, DriveTrackingService::class.java)
            .setAction(DriveTrackingService.ACTION_START)
            .putExtra(DriveTrackingService.EXTRA_RESET, true)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun resumeDrive() {
        val intent = Intent(this, DriveTrackingService::class.java)
            .setAction(DriveTrackingService.ACTION_RESUME)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopDrive() {
        sessionActive = false
        startService(
            Intent(this, DriveTrackingService::class.java)
                .setAction(DriveTrackingService.ACTION_STOP)
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun requestDrivePermissions(startAfterGrant: Boolean) {
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

    private fun installNavigationLayers(style: Style) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, emptyFeatureCollection()))
        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor("#5BA8FF"),
                lineWidth(7f),
                lineOpacity(0.92f),
            )
        )

        style.addSource(GeoJsonSource(DESTINATION_SOURCE_ID, emptyFeatureCollection()))
        style.addLayer(
            CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID).withProperties(
                circleColor("#5BA8FF"),
                circleRadius(8f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            )
        )

        style.addSource(GeoJsonSource(POSITION_SOURCE_ID, emptyFeatureCollection()))
        style.addLayer(
            CircleLayer(POSITION_LAYER_ID, POSITION_SOURCE_ID).withProperties(
                circleColor("#57D37D"),
                circleRadius(9f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            )
        )
    }

    private fun updateMapFromState(
        state: GnssUiState,
        route: OpenRouteClient.RouteSummary?,
        forceCamera: Boolean = false,
    ) {
        if (!mapStyleReady) return
        val style = map?.style ?: return

        val routeSource = style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource
        routeSource?.setGeoJson(route?.geometry?.let(::routeFeatureCollection) ?: emptyFeatureCollection())

        val destinationSource = style.getSource(DESTINATION_SOURCE_ID) as? GeoJsonSource
        destinationSource?.setGeoJson(
            route?.let { pointFeatureCollection(it.destinationLat, it.destinationLon) }
                ?: emptyFeatureCollection()
        )

        val lat = state.latitude
        val lon = state.longitude
        val positionSource = style.getSource(POSITION_SOURCE_ID) as? GeoJsonSource
        if (lat != null && lon != null && state.fixReceived) {
            positionSource?.setGeoJson(pointFeatureCollection(lat, lon))
        } else {
            positionSource?.setGeoJson(emptyFeatureCollection())
        }

        val fixTimestamp = state.lastUpdateMillis
        val shouldMoveCamera = forceCamera || (fixTimestamp != null && fixTimestamp != lastMapFixTimestamp)
        if (lat != null && lon != null && shouldMoveCamera) {
            lastMapFixTimestamp = fixTimestamp
            val heading = (state.fusedHeadingDegrees ?: state.bearingDegrees ?: 0f).toDouble()
            val moving = (state.speedMps ?: 0f) >= 3f
            val camera = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(if (moving) 16.7 else 16.2)
                .bearing(if (moving) heading else 0.0)
                .tilt(if (moving) 48.0 else 0.0)
                .build()
            map?.easeCamera(CameraUpdateFactory.newCameraPosition(camera), 500)
        }
    }

    private fun routeFeatureCollection(points: List<OpenRouteClient.RoutePoint>): String {
        if (points.size < 2) return emptyFeatureCollection()
        val coordinates = JSONArray()
        points.forEach { point ->
            coordinates.put(JSONArray().put(point.lon).put(point.lat))
        }
        val geometry = JSONObject()
            .put("type", "LineString")
            .put("coordinates", coordinates)
        return featureCollection(geometry)
    }

    private fun pointFeatureCollection(lat: Double, lon: Double): String {
        val geometry = JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(lon).put(lat))
        return featureCollection(geometry)
    }

    private fun featureCollection(geometry: JSONObject): String {
        val feature = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", geometry)
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", JSONArray().put(feature))
            .toString()
    }

    private fun emptyFeatureCollection(): String =
        "{\"type\":\"FeatureCollection\",\"features\":[]}"

    private companion object {
        const val MAP_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
        const val ROUTE_SOURCE_ID = "lanegps-route-source"
        const val ROUTE_LAYER_ID = "lanegps-route-layer"
        const val POSITION_SOURCE_ID = "lanegps-position-source"
        const val POSITION_LAYER_ID = "lanegps-position-layer"
        const val DESTINATION_SOURCE_ID = "lanegps-destination-source"
        const val DESTINATION_LAYER_ID = "lanegps-destination-layer"
        const val OFF_ROUTE_REROUTE_METERS = 55.0
        const val OFF_ROUTE_FIXES_REQUIRED = 3
        const val REROUTE_COOLDOWN_MS = 12_000L
    }
}

@Composable
private fun NavigationScreen(
    state: GnssUiState,
    sessionActive: Boolean,
    query: String,
    routeUi: NavigationRouteUi,
    mapView: MapView,
    onQueryChange: (String) -> Unit,
    onFindRoute: () -> Unit,
    onClearRoute: () -> Unit,
    onStartDrive: () -> Unit,
    onStopDrive: () -> Unit,
    onEnableLocation: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionActive, state.lastUpdateMillis) {
        nowMillis = System.currentTimeMillis()
        while (sessionActive) {
            kotlinx.coroutines.delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val fixAge = state.lastUpdateMillis?.let { (nowMillis - it).coerceAtLeast(0L) }
    val gpsStale = sessionActive && state.fixReceived && fixAge != null && fixAge > 3_000L
    val laneCount = if (gpsStale) null else state.likelyLaneCount?.coerceIn(1, 8)
    val laneNumber = if (gpsStale) null else state.likelyLaneNumberFromLeft
    val route = routeUi.summary

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(NavBg)
    ) {
        val wide = maxWidth >= 700.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 20.dp else 14.dp, vertical = 12.dp)
        ) {
            NavigationHeader(sessionActive, gpsStale, routeUi.rerouting)
            Spacer(Modifier.height(10.dp))

            if (gpsStale) {
                StatusBanner(
                    title = "GPS SIGNAL LOST",
                    text = "Lane guidance and route progress are paused until a fresh fix returns.",
                    color = NavRed,
                )
                Spacer(Modifier.height(10.dp))
            }

            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1.25f)) {
                        ManeuverCard(route, routeUi)
                        Spacer(Modifier.height(10.dp))
                        NavigationMapHost(mapView, Modifier.fillMaxWidth().height(430.dp))
                    }
                    Column(Modifier.weight(0.75f)) {
                        LaneCard(state, laneNumber, laneCount, gpsStale)
                        Spacer(Modifier.height(10.dp))
                        DestinationCard(query, routeUi, onQueryChange, onFindRoute, onClearRoute)
                        Spacer(Modifier.height(10.dp))
                        CompactMetrics(state, gpsStale)
                    }
                }
            } else {
                ManeuverCard(route, routeUi)
                Spacer(Modifier.height(10.dp))
                NavigationMapHost(mapView, Modifier.fillMaxWidth().height(285.dp))
                Spacer(Modifier.height(10.dp))
                LaneCard(state, laneNumber, laneCount, gpsStale)
                Spacer(Modifier.height(10.dp))
                DestinationCard(query, routeUi, onQueryChange, onFindRoute, onClearRoute)
                Spacer(Modifier.height(10.dp))
                CompactMetrics(state, gpsStale)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = if (sessionActive) onStopDrive else onStartDrive,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (sessionActive) NavRed else NavBlue,
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
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onEnableLocation, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable precise location")
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenDiagnostics, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Detailed diagnostics & Trip Lab", color = NavBlueSoft)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun NavigationHeader(sessionActive: Boolean, gpsStale: Boolean, rerouting: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("LaneGPS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Lane-first navigation", color = NavMuted, fontSize = 12.sp)
        }
        val text = when {
            gpsStale -> "GPS LOST"
            rerouting -> "REROUTING"
            sessionActive -> "LIVE"
            else -> "READY"
        }
        val color = when {
            gpsStale -> NavRed
            rerouting -> NavAmber
            sessionActive -> NavGreen
            else -> NavBlue
        }
        Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
            Text(
                text,
                color = color,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun StatusBanner(title: String, text: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.13f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = color, fontWeight = FontWeight.ExtraBold)
            Text(text, color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ManeuverCard(route: OpenRouteClient.RouteSummary?, routeUi: NavigationRouteUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavCardStrong),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (route == null) {
                Text("NO ACTIVE ROUTE", color = NavMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Choose a destination", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                Text("The map and live maneuver guidance will start after a route is found.", color = NavMuted, fontSize = 12.sp)
                return@Column
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (route.arrived) "ARRIVAL" else "NEXT MANEUVER",
                    color = if (route.arrived) NavGreen else NavMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    when {
                        routeUi.rerouting -> "REROUTING…"
                        else -> "LIVE ROUTE"
                    },
                    color = if (routeUi.rerouting) NavAmber else NavGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                route.nextManeuver,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                lineHeight = 29.sp,
            )
            if (route.nextRoad.isNotBlank()) {
                Text(route.nextRoad, color = NavBlueSoft, fontSize = 14.sp, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavMetric(
                    if (route.arrived) "HERE" else formatNavDistance(route.nextManeuverDistanceMeters),
                    "to maneuver",
                    Modifier.weight(1f),
                )
                NavMetric(formatNavDistance(route.distanceMeters), "remaining", Modifier.weight(1f))
                NavMetric(formatNavDuration(route.durationSeconds), "ETA", Modifier.weight(1f))
            }
            routeUi.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = NavAmber, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun NavigationMapHost(mapView: MapView, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = NavCard),
        shape = RoundedCornerShape(20.dp),
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LaneCard(
    state: GnssUiState,
    laneNumber: Int?,
    laneCount: Int?,
    gpsStale: Boolean,
) {
    val known = laneNumber != null && laneCount != null && laneNumber in 1..laneCount
    val exact = known && state.laneExactClaim && !gpsStale
    val accent = if (exact) NavGreen else NavAmber

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavCard),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("CURRENT LANE", color = NavMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            gpsStale -> "GPS LOST"
                            exact -> "LANE $laneNumber OF $laneCount"
                            known -> "LIKELY $laneNumber OF $laneCount"
                            else -> "FINDING LANE"
                        },
                        color = when {
                            gpsStale -> NavRed
                            known -> accent
                            else -> NavBlueSoft
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 21.sp,
                    )
                }
                if (known) {
                    Text(
                        "${(state.laneConfidence.coerceIn(0f, 1f) * 100).roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (known) {
                Row(
                    Modifier.fillMaxWidth().height(82.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(laneCount!!) { index ->
                        val current = index + 1 == laneNumber
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    if (current) accent.copy(alpha = 0.28f) else Color(0xFF0C1220),
                                    RoundedCornerShape(10.dp),
                                )
                                .border(
                                    width = if (current) 2.dp else 1.dp,
                                    color = if (current) accent else NavLine,
                                    shape = RoundedCornerShape(10.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                color = if (current) Color.White else NavMuted,
                                fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Normal,
                                fontSize = 17.sp,
                            )
                        }
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(Color(0xFF0C1220), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (gpsStale) "Waiting for fresh GPS" else "Road geometry is live; lane position needs more evidence",
                        color = NavMuted,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("TARGET LANES", color = NavBlue, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(8.dp))
                Text("route-to-lane connection next", color = NavMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun DestinationCard(
    query: String,
    routeUi: NavigationRouteUi,
    onQueryChange: (String) -> Unit,
    onFindRoute: () -> Unit,
    onClearRoute: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { DestinationStore(context.applicationContext) }
    val photon = remember { PhotonSearchClient() }
    var saved by remember { mutableStateOf(store.saved()) }
    var recent by remember { mutableStateOf(store.recent()) }
    var remoteSuggestions by remember { mutableStateOf<List<PhotonSearchClient.Suggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(routeUi.summary?.destinationName) {
        val destination = routeUi.summary?.destinationName?.trim().orEmpty()
        if (destination.isNotBlank()) {
            store.addRecent(destination)
            recent = store.recent()
        }
    }

    LaunchedEffect(query) {
        remoteSuggestions = emptyList()
        searching = false
        val clean = query.trim()
        if (clean.length < 3) return@LaunchedEffect
        kotlinx.coroutines.delay(450L)
        searching = true
        remoteSuggestions = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                photon.search(clean, limit = 5)
            }
        }.getOrDefault(emptyList())
        searching = false
    }

    val localMatches = remember(query, saved, recent) { store.localMatches(query) }
    val showSuggestions = localMatches.isNotEmpty() || remoteSuggestions.isNotEmpty() || searching

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavCard),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("Where are you going?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Address, place or business") },
                enabled = !routeUi.planning,
            )

            if (showSuggestions && !routeUi.planning) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xFF0D1422),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 2.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        if (query.isBlank() && localMatches.isNotEmpty()) {
                            Text(
                                "SAVED & RECENT",
                                color = NavMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }

                        localMatches.take(5).forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { onQueryChange(item.label) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            item.label,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            when {
                                                item.saved -> "Saved"
                                                item.recent -> "Recent"
                                                else -> ""
                                            },
                                            color = if (item.saved) NavBlueSoft else NavMuted,
                                            fontSize = 9.sp,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                TextButton(onClick = {
                                    store.toggleSaved(item.label)
                                    saved = store.saved()
                                }) {
                                    Text(if (store.isSaved(item.label)) "★" else "☆", color = NavBlueSoft)
                                }
                            }
                        }

                        remoteSuggestions
                            .filterNot { remote ->
                                localMatches.any { local ->
                                    remote.label.equals(local.label, ignoreCase = true)
                                }
                            }
                            .take(5)
                            .forEach { suggestion ->
                                val fullLabel = listOf(suggestion.label, suggestion.subtitle)
                                    .filter { it.isNotBlank() }
                                    .joinToString(", ")
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { onQueryChange(fullLabel) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                suggestion.label,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            if (suggestion.subtitle.isNotBlank()) {
                                                Text(
                                                    suggestion.subtitle,
                                                    color = NavMuted,
                                                    fontSize = 9.sp,
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }
                                    TextButton(onClick = {
                                        store.toggleSaved(fullLabel)
                                        saved = store.saved()
                                    }) {
                                        Text(if (store.isSaved(fullLabel)) "★" else "☆", color = NavBlueSoft)
                                    }
                                }
                            }

                        if (searching) {
                            Text(
                                "Searching addresses…",
                                color = NavMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onFindRoute,
                    enabled = query.isNotBlank() && !routeUi.planning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (routeUi.planning) "FINDING…" else "FIND ROUTE")
                }
                if (routeUi.summary != null || routeUi.waitingForGps) {
                    OutlinedButton(onClick = onClearRoute) { Text("CLEAR") }
                }
            }
            routeUi.error?.takeIf { routeUi.summary == null }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = if (routeUi.waitingForGps) NavAmber else NavRed, fontSize = 11.sp)
            }
            routeUi.summary?.let { route ->
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        route.destinationName,
                        color = NavBlueSoft,
                        fontSize = 11.sp,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        store.toggleSaved(route.destinationName)
                        saved = store.saved()
                    }) {
                        Text(
                            if (store.isSaved(route.destinationName)) "★ SAVED" else "☆ SAVE",
                            color = NavBlueSoft,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMetrics(state: GnssUiState, gpsStale: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        NavMetric(
            if (gpsStale) "LOST" else state.accuracyMeters?.let { "%.1fm".format(it) } ?: "—",
            "GPS accuracy",
            Modifier.weight(1f),
        )
        NavMetric(
            if (gpsStale) "—" else state.speedMps?.let { "%.0f".format(it * 2.23694f) } ?: "—",
            "mph",
            Modifier.weight(1f),
        )
        NavMetric(
            if (gpsStale) "—" else state.laneCandidateCount.toString(),
            "lane candidates",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun NavMetric(value: String, label: String, modifier: Modifier) {
    Column(
        modifier
            .background(Color(0xFF0D1422), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = NavMuted, fontSize = 9.sp, maxLines = 1)
    }
}

private fun formatNavDistance(meters: Double): String {
    if (!meters.isFinite()) return "—"
    val feet = meters * 3.28084
    return if (meters < 402.0) {
        "${(feet / 25.0).roundToInt() * 25} ft"
    } else {
        "%.1f mi".format(meters / 1609.344)
    }
}

private fun formatNavDuration(seconds: Double): String {
    if (!seconds.isFinite()) return "—"
    val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(0)
    return when {
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
