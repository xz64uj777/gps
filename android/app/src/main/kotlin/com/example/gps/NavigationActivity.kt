package com.example.gps

import com.example.gps.laneengine.NavigationFixQuality
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import com.example.gps.laneengine.MapFollowInterpolation
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gps.route.ActiveNavigationStore
import com.example.gps.route.NavigationTelemetryRuntime
import com.example.gps.location.DriveTelemetryRecorder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.gps.location.DriveSessionRuntime
import com.example.gps.location.DriveSessionStore
import com.example.gps.location.DriveTrackingService
import com.example.gps.location.GnssUiState
import com.example.gps.route.DestinationStore
import com.example.gps.route.NavigationVoiceController
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

class NavigationActivity : ComponentActivity() {
    private lateinit var navigationStore: ActiveNavigationStore
    private var routeGeneration = 0L
    private var lastRouteCheckpoint = 0L
    private lateinit var store: DriveSessionStore
    private val routeClient = OpenRouteClient()
    private val routeExecutor = Executors.newSingleThreadExecutor()

    private lateinit var mapView: MapView
    private var trafficStatus by mutableStateOf("Traffic · setup in Options")
    private val trafficOverlay by lazy { com.example.gps.traffic.TrafficOverlay(this) { trafficStatus = it } }
    private var map: MapLibreMap? = null
    private var mapStyleReady = false
    private var lastMapFixTimestamp: Long? = null
    private var renderedGeometry: List<OpenRouteClient.RoutePoint>? = null
    private var routeLayersInitialized = false
    private var followAnimator: ValueAnimator? = null
    private var renderedPosition: LatLng? = null
    private var mapResumed = false
    private var followingLocation by mutableStateOf(true)

    private var uiState by mutableStateOf(GnssUiState())
    private var sessionActive by mutableStateOf(false)
    private var recordingActive by mutableStateOf(false)
    private var autoRecordingForRoute = false
    private var routeQuery by mutableStateOf("")
    private var routeUi by mutableStateOf(NavigationRouteUi())

    private var pendingStart = false
    private var liveViewStoppedByUser = false
    private var locationPromptedThisVisit = false
    private var pendingRouteQuery: String? = null
    private var lastProgressFixTimestamp: Long? = null
    private var offRouteFixStreak = 0
    private var lastRerouteElapsed = 0L

    private val runtimeListener: (GnssUiState) -> Unit = { state ->
        runOnUiThread {
            uiState = state
            sessionActive = store.isActive()
            recordingActive = store.isRecording()
            if (!sessionActive && (routeUi.summary != null || pendingRouteQuery != null || routeUi.planning)) clearRoute()

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
        enableEdgeToEdge()
        followingLocation = savedInstanceState?.getBoolean("following_location", true) ?: true
        liveViewStoppedByUser = savedInstanceState?.getBoolean("live_view_stopped", false) ?: false
        locationPromptedThisVisit = savedInstanceState?.getBoolean("location_prompted", false) ?: false
        navigationStore = ActiveNavigationStore(this)
        store = DriveSessionStore(this)
        uiState = DriveSessionRuntime.latest() ?: store.load()
        sessionActive = store.isActive()
        recordingActive = store.isRecording()
        val recovered = if (sessionActive) navigationStore.load() else null
        if (!sessionActive) navigationStore.clear()
        routeQuery = recovered?.destinationName.orEmpty()
        routeUi = NavigationRouteUi(summary = recovered)
        autoRecordingForRoute = recovered != null && recordingActive
        if (recovered != null) logRouteEvent("ROUTE_RECOVERED")

        MapLibre.getInstance(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(mapView) { _, insets ->
            positionMapControls(insets)
            insets
        }
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followingLocation = false
                    followAnimator?.cancel()
                }
            }
            readyMap.uiSettings.isCompassEnabled = true
            positionMapControls(ViewCompat.getRootWindowInsets(mapView))
            ViewCompat.requestApplyInsets(mapView)
            readyMap.uiSettings.isLogoEnabled = true
            readyMap.uiSettings.isAttributionEnabled = true
            readyMap.uiSettings.isRotateGesturesEnabled = true
            readyMap.uiSettings.isTiltGesturesEnabled = true
            // Keep attribution above the floating bottom controls.
            val density = resources.displayMetrics.density
            readyMap.uiSettings.setAttributionMargins((8 * density).toInt(), 0, 0, (196 * density).toInt())
            readyMap.uiSettings.setLogoMargins((8 * density).toInt(), 0, 0, (220 * density).toInt())
            readyMap.setStyle(MAP_STYLE_URI) { style ->
                installNavigationLayers(style)
                trafficOverlay.attach(readyMap)
                mapStyleReady = true
                routeLayersInitialized = false
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
                    recordingActive = recordingActive,
                    trafficStatus = trafficStatus,
                    onTrafficChanged = { trafficOverlay.reload() },
                    followingLocation = followingLocation,
                    onRecenter = {
                        followingLocation = true
                        updateMapFromState(uiState, routeUi.summary, forceCamera = true)
                    },
                    query = routeQuery,
                    routeUi = routeUi,
                    mapView = mapView,
                    onQueryChange = {
                        routeQuery = it
                    },
                    onQuickRoute = { quick ->
                        routeQuery = quick
                        if (sessionActive) {
                            requestRoutePlan(quick)
                        } else {
                            startDrive()
                        }
                    },
                    onClearRoute = { clearRoute() },
                    onPrimaryAction = {
                        if (sessionActive) {
                            if (routeQuery.isNotBlank()) requestRoutePlan()
                        } else {
                            startDrive()
                        }
                    },
                    onStartLiveView = {
                        // Live View is sensing-only; trip recording is always explicit.
                        routeQuery = ""
                        startDrive()
                    },
                    onStartTrip = { startTripRecording() },
                    onStopTrip = { stopTripRecording() },
                    onStopNavigation = { clearRoute() },
                    onEnableLocation = { requestDrivePermissions(true) },
                    onOpenDiagnostics = { startActivity(Intent(this, MainActivity::class.java)) },
                )
            }
        }

        // onStart explicitly ensures a running live session, including after process death.
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        uiState = DriveSessionRuntime.latest() ?: store.load()
        sessionActive = store.isActive()
        recordingActive = store.isRecording()
        DriveSessionRuntime.addListener(runtimeListener)
        if (!liveViewStoppedByUser && !pendingStart) {
            if (hasFineLocationPermission()) {
                // Keep Live View automatic, but sensing-only. A selected route
                // promotes the session to recording; standalone Live View does not.
                ensureLiveView()
            } else if (!locationPromptedThisVisit) {
                locationPromptedThisVisit = true
                requestDrivePermissions(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        trafficOverlay.resume()
        mapResumed = true
        updateMapFromState(uiState, routeUi.summary, forceCamera = true)
    }

    override fun onPause() {
        mapResumed = false
        followAnimator?.cancel()
        trafficOverlay.pause()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Standalone live view belongs to this visible screen. A saved route keeps
        // its existing background/recovery behavior; folding must not end a session.
        if (!isChangingConfigurations && sessionActive && !recordingActive && routeUi.summary == null &&
            pendingRouteQuery == null && !routeUi.planning && !routeUi.waitingForGps) {
            stopDrive()
        }
        if (store.isActive()) routeUi.summary?.let { navigationStore.save(it) }
        DriveSessionRuntime.removeListener(runtimeListener)
        mapView.onStop()
        if (!isChangingConfigurations) liveViewStoppedByUser = false
        super.onStop()
    }

    private fun positionMapControls(insets: WindowInsetsCompat?) {
        val safe = insets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val gap = (12 * resources.displayMetrics.density).toInt()
        map?.uiSettings?.setCompassMargins(
            (safe?.left ?: 0) + gap,
            (safe?.top ?: 0) + gap,
            (safe?.right ?: 0) + gap,
            (safe?.bottom ?: 0) + gap,
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("following_location", followingLocation)
        outState.putBoolean("live_view_stopped", liveViewStoppedByUser)
        outState.putBoolean("location_prompted", locationPromptedThisVisit)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        routeGeneration++
        routeExecutor.shutdownNow()
        trafficOverlay.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }

    private fun requestRoutePlan(queryOverride: String? = null) {
        val query = (queryOverride ?: routeQuery).trim()
        if (query.isBlank()) {
            routeUi = routeUi.copy(error = "Enter a destination, or leave it blank for Live View.")
            return
        }
        routeQuery = query
        ensureRouteRecording()

        if (freshLocation(uiState)) {
            pendingRouteQuery = null
            planNewRoute(query, uiState.latitude!!, uiState.longitude!!)
        } else {
            pendingRouteQuery = query
            routeUi = routeUi.copy(
                waitingForGps = true,
                error = "Waiting for an accurate GPS fix. Navigation will start automatically.",
            )
        }
    }

    private fun planNewRoute(query: String, lat: Double, lon: Double) {
        if (routeUi.planning) return
        routeUi = routeUi.copy(planning = true, waitingForGps = false, error = null)
        val generation = ++routeGeneration
        routeExecutor.execute {
            val result = runCatching { routeClient.plan(query, lat, lon) }
            runOnUiThread {
                if (isDestroyed || generation != routeGeneration || !store.isActive()) return@runOnUiThread
                routeUi = result.fold(
                    onSuccess = { planned ->
                        val summary = if (freshLocation(uiState)) {
                            routeClient.updateProgress(planned, uiState.latitude!!, uiState.longitude!!)
                        } else planned
                        navigationStore.save(summary)
                        DestinationStore(this).addRecent(summary.destinationName)
                        offRouteFixStreak = 0
                        NavigationRouteUi(summary = summary)
                    },
                    onFailure = { error ->
                        if (recordingActive) {
                            stopTripRecording()
                            autoRecordingForRoute = false
                        }
                        routeUi.copy(
                            planning = false,
                            error = error.message ?: "Could not build this route.",
                        )
                    },
                )
                logRouteEvent(if (result.isSuccess) "ROUTE_STARTED" else "ROUTE_PLAN_FAILED")
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
        if (SystemClock.elapsedRealtime() - lastRouteCheckpoint >= 5_000L || progressed.arrived) {
            navigationStore.save(progressed)
            lastRouteCheckpoint = SystemClock.elapsedRealtime()
        }
        routeUi = routeUi.copy(summary = progressed, error = null)

        if (progressed.arrived && recordingActive) {
            stopTripRecording()
            autoRecordingForRoute = false
        }

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
        val generation = ++routeGeneration
        routeExecutor.execute {
            val result = runCatching { routeClient.reroute(previous, lat, lon) }
            runOnUiThread {
                if (isDestroyed || generation != routeGeneration || !store.isActive()) return@runOnUiThread
                routeUi = result.fold(
                    onSuccess = { planned ->
                        val summary = if (freshLocation(uiState)) {
                            routeClient.updateProgress(planned, uiState.latitude!!, uiState.longitude!!)
                        } else planned
                        navigationStore.save(summary)
                        DestinationStore(this).addRecent(summary.destinationName)
                        routeUi.copy(rerouting = false, summary = summary, error = null)
                    },
                    onFailure = { error ->
                        routeUi.copy(
                            rerouting = false,
                            error = "Reroute delayed: ${error.message ?: "routing service unavailable"}",
                        )
                    },
                )
                logRouteEvent(if (result.isSuccess) "ROUTE_REROUTED" else "ROUTE_REROUTE_FAILED")
                updateMapFromState(uiState, routeUi.summary, forceCamera = true)
            }
        }
    }

    private fun clearRoute() {
        val endingRoute =
            routeUi.summary != null || pendingRouteQuery != null || routeUi.planning || routeUi.waitingForGps
        routeGeneration++
        logRouteEvent("ROUTE_STOPPED")
        if (endingRoute && recordingActive) {
            // Destination navigation owns its recording lifecycle. Stopping/clearing
            // navigation must save the trip immediately instead of leaving a red
            // recorder running in Live View.
            stopTripRecording()
            autoRecordingForRoute = false
        }
        pendingRouteQuery = null
        navigationStore.clear()
        routeQuery = ""
        routeUi = NavigationRouteUi()
        offRouteFixStreak = 0
        updateMapFromState(uiState, null, forceCamera = true)
    }

    private fun clearRouteForFreeDrive() {
        routeGeneration++
        pendingRouteQuery = null
        navigationStore.clear()
        routeUi = NavigationRouteUi()
        offRouteFixStreak = 0
        updateMapFromState(uiState, null, forceCamera = true)
    }

    private fun freshLocation(state: GnssUiState): Boolean {
        val timestamp = state.lastUpdateMillis ?: return false
        if (!state.fixReceived || state.latitude == null || state.longitude == null) return false
        return NavigationFixQuality.isUsable(timestamp, System.currentTimeMillis(), state.accuracyMeters?.toDouble())
    }

    private fun startDrive() {
        if (!hasFineLocationPermission() || needsNotificationPermission()) {
            requestDrivePermissions(true)
        } else {
            startDriveInternal()
        }
    }

    private fun startDriveInternal() {
        liveViewStoppedByUser = false
        val destination = routeQuery.trim()
        if (destination.isBlank()) {
            clearRouteForFreeDrive()
        } else {
            navigationStore.clear()
            pendingRouteQuery = destination
            routeUi = NavigationRouteUi(
                waitingForGps = true,
                error = "Starting GPS and building your route…",
            )
        }

        sessionActive = true
        uiState = GnssUiState(message = if (destination.isBlank()) "Starting Live View…" else "Starting navigation + trip recording…")
        lastProgressFixTimestamp = null
        lastMapFixTimestamp = null
        if (destination.isBlank()) {
            autoRecordingForRoute = false
            ContextCompat.startForegroundService(
                this,
                Intent(this, DriveTrackingService::class.java)
                    .setAction(DriveTrackingService.ACTION_OPEN_LIVE_VIEW)
            )
        } else {
            recordingActive = true
            autoRecordingForRoute = true
            ContextCompat.startForegroundService(
                this,
                Intent(this, DriveTrackingService::class.java)
                    .setAction(DriveTrackingService.ACTION_START)
                    .putExtra(DriveTrackingService.EXTRA_RESET, true)
            )
        }
    }

    private fun ensureRouteRecording() {
        if (recordingActive || store.isRecording()) {
            recordingActive = true
            return
        }
        recordingActive = true
        autoRecordingForRoute = true
        ContextCompat.startForegroundService(
            this,
            Intent(this, DriveTrackingService::class.java)
                .setAction(DriveTrackingService.ACTION_START)
                .putExtra(DriveTrackingService.EXTRA_RESET, true)
        )
    }

    private fun startTripRecording() {
        if (recordingActive) return
        autoRecordingForRoute = false
        recordingActive = true
        ContextCompat.startForegroundService(
            this,
            Intent(this, DriveTrackingService::class.java)
                .setAction(DriveTrackingService.ACTION_START)
                .putExtra(DriveTrackingService.EXTRA_RESET, true)
        )
    }

    private fun stopTripRecording() {
        if (!recordingActive) return
        recordingActive = false
        startService(
            Intent(this, DriveTrackingService::class.java)
                .setAction(DriveTrackingService.ACTION_STOP_RECORDING)
        )
    }

    private fun ensureLiveView() {
        sessionActive = true
        ContextCompat.startForegroundService(this,
            Intent(this, DriveTrackingService::class.java)
                .setAction(DriveTrackingService.ACTION_OPEN_LIVE_VIEW))
    }

    private fun resumeDrive() {
        val intent = Intent(this, DriveTrackingService::class.java)
            .setAction(DriveTrackingService.ACTION_RESUME)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopDrive() {
        clearRoute()
        sessionActive = false
        startService(
            Intent(this, DriveTrackingService::class.java)
                .setAction(DriveTrackingService.ACTION_STOP)
        )
    }

    private fun logRouteEvent(event: String) {
        if (!store.isActive()) return
        val route = routeUi.summary
        if (route != null) {
            NavigationTelemetryRuntime.publish(route, event, route.progressIndex, null, event == "ROUTE_REROUTED")
        } else NavigationTelemetryRuntime.lifecycle(event, routeQuery)
        DriveTelemetryRecorder(this).append(uiState)
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

        // Progress copies retain geometry identity. Upload only on plan/reroute/clear,
        // not on every motion-sensor callback or position update.
        if (!routeLayersInitialized || renderedGeometry !== route?.geometry) {
            (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
                route?.geometry?.let(::routeFeatureCollection) ?: emptyFeatureCollection())
            (style.getSource(DESTINATION_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
                route?.let { pointFeatureCollection(it.destinationLat, it.destinationLon) } ?: emptyFeatureCollection())
            renderedGeometry = route?.geometry
            routeLayersInitialized = true
        }
        val positionSource = style.getSource(POSITION_SOURCE_ID) as? GeoJsonSource
        if (!freshLocation(state)) {
            followAnimator?.cancel()
            renderedPosition = null
            positionSource?.setGeoJson(emptyFeatureCollection())
            return
        }
        if (!mapResumed) return
        val timestamp = state.lastUpdateMillis ?: return
        if (!forceCamera && timestamp == lastMapFixTimestamp) return
        val previousTimestamp = lastMapFixTimestamp
        lastMapFixTimestamp = timestamp
        val rawTarget = LatLng(state.latitude!!, state.longitude!!)
        val target = route?.let { snapDisplayPointToRoute(rawTarget, state.accuracyMeters, it) } ?: rawTarget
        // Browsing is sticky: fresh fixes, route changes and resume can update
        // overlays and the position marker, but only Recenter re-enables follow.
        if (!followingLocation) {
            followAnimator?.cancel()
            renderedPosition = target
            positionSource?.setGeoJson(pointFeatureCollection(target.latitude, target.longitude))
            return
        }
        val moving = (state.speedMps ?: 0f) >= 1.5f
        val previousCamera = map?.cameraPosition ?: return
        // While driving, GPS course follows the car rather than a loose phone's rotation.
        val heading = if (moving) (state.bearingDegrees ?: state.fusedHeadingDegrees)?.toDouble()
            ?: previousCamera.bearing else previousCamera.bearing
        val destinationCamera = CameraPosition.Builder()
            .target(target)
            .zoom(if (moving) 17.3 else if (sessionActive) 16.9 else 16.2)
            .bearing(if (sessionActive || moving) heading else 0.0)
            .tilt(if (sessionActive) 58.0 else if (moving) 48.0 else 0.0)
            .build()
        followAnimator?.cancel()
        val startPosition = renderedPosition
        val gap = previousTimestamp?.let { timestamp - it } ?: Long.MAX_VALUE
        val startTarget = previousCamera.target
        if (forceCamera || startPosition == null || startTarget == null || gap !in 1..3_000 ||
            startPosition.distanceTo(target) > 150.0) {
            renderedPosition = target
            positionSource?.setGeoJson(pointFeatureCollection(target.latitude, target.longitude))
            map?.moveCamera(CameraUpdateFactory.newCameraPosition(destinationCamera))
            return
        }
        // Interpolate observed fixes only: no invented future GPS or lane evidence.
        // Marker and camera share a frame clock, avoiding marker/camera disagreement.
        followAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (gap * 0.75).toLong().coerceIn(150L, 650L)
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val t = (animation.animatedValue as Float).toDouble()
                val marker = LatLng(
                    MapFollowInterpolation.linear(startPosition.latitude, target.latitude, t),
                    MapFollowInterpolation.linear(startPosition.longitude, target.longitude, t))
                renderedPosition = marker
                positionSource?.setGeoJson(pointFeatureCollection(marker.latitude, marker.longitude))
                if (!followingLocation) return@addUpdateListener
                val cameraTarget = LatLng(
                    MapFollowInterpolation.linear(startTarget.latitude, target.latitude, t),
                    MapFollowInterpolation.linear(startTarget.longitude, target.longitude, t))
                map?.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                    .target(cameraTarget)
                    .bearing(MapFollowInterpolation.bearing(previousCamera.bearing, destinationCamera.bearing, t))
                    .zoom(MapFollowInterpolation.linear(previousCamera.zoom, destinationCamera.zoom, t))
                    .tilt(MapFollowInterpolation.linear(previousCamera.tilt, destinationCamera.tilt, t))
                    .build()))
            }
            start()
        }
    }

    /**
     * Visual map matching only. Navigation/lane evidence still uses the raw GNSS fix.
     * When a route is active and the raw fix is reasonably close to it, project the
     * displayed marker/camera onto the local route segment so a 8–15 m phone GPS
     * offset does not make the car appear beside the road.
     */
    private fun snapDisplayPointToRoute(
        raw: LatLng,
        accuracyMeters: Float?,
        route: OpenRouteClient.RouteSummary,
    ): LatLng {
        val points = route.geometry
        if (points.size < 2) return raw

        val start = (route.progressIndex - 6).coerceAtLeast(0).coerceAtMost(points.lastIndex - 1)
        val end = (route.progressIndex + 80).coerceAtMost(points.lastIndex)
        if (end <= start) return raw

        val earth = 6_371_000.0
        val lat0 = raw.latitude * kotlin.math.PI / 180.0
        val cosLat = kotlin.math.cos(lat0).coerceAtLeast(0.01)

        fun xy(point: OpenRouteClient.RoutePoint): Pair<Double, Double> {
            val east = (point.lon - raw.longitude) * kotlin.math.PI / 180.0 * earth * cosLat
            val north = (point.lat - raw.latitude) * kotlin.math.PI / 180.0 * earth
            return east to north
        }

        var bestDistance = Double.POSITIVE_INFINITY
        var bestEast = 0.0
        var bestNorth = 0.0
        for (i in start until end) {
            val (ax, ay) = xy(points[i])
            val (bx, by) = xy(points[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val denom = dx * dx + dy * dy
            val t = if (denom <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / denom).coerceIn(0.0, 1.0)
            val east = ax + t * dx
            val north = ay + t * dy
            val distance = kotlin.math.hypot(east, north)
            if (distance < bestDistance) {
                bestDistance = distance
                bestEast = east
                bestNorth = north
            }
        }

        val maxSnapMeters = maxOf(22.0, (accuracyMeters?.toDouble() ?: 8.0) * 2.2)
            .coerceAtMost(45.0)
        if (!bestDistance.isFinite() || bestDistance > maxSnapMeters) return raw

        val snappedLat = raw.latitude + (bestNorth / earth) * 180.0 / kotlin.math.PI
        val snappedLon = raw.longitude + (bestEast / (earth * cosLat)) * 180.0 / kotlin.math.PI
        return LatLng(snappedLat, snappedLon)
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
        const val OFF_ROUTE_REROUTE_METERS = 75.0
        const val OFF_ROUTE_FIXES_REQUIRED = 5
        const val REROUTE_COOLDOWN_MS = 30_000L
    }
}

@Composable
private fun NavigationScreen(
    state: GnssUiState,
    sessionActive: Boolean,
    recordingActive: Boolean,
    trafficStatus: String,
    onTrafficChanged: () -> Unit,
    followingLocation: Boolean,
    onRecenter: () -> Unit,
    query: String,
    routeUi: NavigationRouteUi,
    mapView: MapView,
    onQueryChange: (String) -> Unit,
    onQuickRoute: (String) -> Unit,
    onClearRoute: () -> Unit,
    onPrimaryAction: () -> Unit,
    onStartLiveView: () -> Unit,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onStopNavigation: () -> Unit,
    onEnableLocation: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var editingDestination by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var voiceState by remember {
        mutableStateOf(
            NavigationVoiceController.State(
                ready = false,
                muted = false,
                voices = emptyList(),
                selectedVoiceId = NavigationVoiceController.DEFAULT_VOICE_ID,
            )
        )
    }
    val voiceController = remember {
        NavigationVoiceController(context.applicationContext) { state ->
            mainHandler.post { voiceState = state }
        }
    }

    DisposableEffect(voiceController) {
        onDispose { voiceController.shutdown() }
    }

    LaunchedEffect(Unit) {
        voiceState = voiceController.state()
    }

    var announcedRoute by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(
        routeUi.summary?.routeStartedAtMillis,
        routeUi.summary?.progressIndex,
        routeUi.summary?.nextManeuver,
        routeUi.summary?.nextManeuverDistanceMeters?.roundToInt(),
        routeUi.summary?.arrived,
        voiceState.ready,
        state.lastUpdateMillis,
        sessionActive,
    ) {
        val route = routeUi.summary
        if (route == null || !sessionActive) {
            voiceController.stopSpeaking()
            announcedRoute = null
        } else if (voiceState.ready && state.lastUpdateMillis?.let {
                NavigationFixQuality.isUsable(it, System.currentTimeMillis(), state.accuracyMeters?.toDouble())
            } == true) {
            if (announcedRoute != route.routeStartedAtMillis) {
                voiceController.onRouteStarted(route)
                announcedRoute = route.routeStartedAtMillis
            } else voiceController.onProgress(route, (state.speedMps ?: 0f).toDouble())
        }
    }

    LaunchedEffect(routeUi.rerouting) {
        if (routeUi.rerouting) voiceController.announceRerouting()
    }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionActive, state.lastUpdateMillis) {
        nowMillis = System.currentTimeMillis()
        while (sessionActive) {
            kotlinx.coroutines.delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val fixAge = state.lastUpdateMillis?.let { (nowMillis - it).coerceAtLeast(0L) }
    val gpsStale = sessionActive && (!state.fixReceived || (fixAge != null && fixAge > 3_000L))
    val gpsWeak = sessionActive && !gpsStale && state.fixReceived && (
        (state.accuracyMeters ?: Float.MAX_VALUE) > 10f ||
            state.qualityScore < 70 ||
            (state.satellitesUsedInFix in 1..4)
    )
    val rawLaneCount = if (gpsStale) null else state.likelyLaneCount?.takeIf { it in 1..8 }
    var laneCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(rawLaneCount, gpsStale) {
        if (!gpsStale && rawLaneCount != laneCount) {
            // Require a short stable layout before changing the road graphic.
            kotlinx.coroutines.delay(1_500L)
            laneCount = rawLaneCount
        }
    }
    val layoutSettling = !gpsStale && laneCount != rawLaneCount
    val liveLaneNumber = if (gpsStale || layoutSettling) null
        else state.likelyLaneNumberFromLeft
    val route = routeUi.summary
    val liveTargetLanes = remember(state.laneTurnHints, route?.nextManeuver, laneCount) {
        recommendedLaneNumbers(
            turnHints = state.laneTurnHints,
            maneuver = route?.nextManeuver,
            laneCount = laneCount,
        )
    }

    // OSM turn:lanes can appear only on the final tagged approach segment. Hold a
    // valid recommendation through short gaps on the same maneuver/road layout so
    // the target lane does not flash on for one or two fixes and disappear again.
    var rememberedTargetManeuver by remember { mutableStateOf<String?>(null) }
    var rememberedTargetLaneCount by remember { mutableStateOf<Int?>(null) }
    var rememberedTargetLanes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(route?.routeStartedAtMillis, route?.nextManeuver, laneCount, liveTargetLanes) {
        val maneuverKey = route?.let { "${it.routeStartedAtMillis}|${it.nextManeuver}" }
        if (maneuverKey != rememberedTargetManeuver) {
            rememberedTargetManeuver = maneuverKey
            rememberedTargetLaneCount = null
            rememberedTargetLanes = emptySet()
        }
        if (liveTargetLanes.isNotEmpty() && laneCount != null) {
            rememberedTargetLaneCount = laneCount
            rememberedTargetLanes = liveTargetLanes
        } else if (laneCount != null && rememberedTargetLaneCount != null &&
            laneCount != rememberedTargetLaneCount) {
            rememberedTargetLaneCount = null
            rememberedTargetLanes = emptySet()
        }
    }
    val stableTargetLanes = when {
        liveTargetLanes.isNotEmpty() -> liveTargetLanes
        laneCount != null && laneCount == rememberedTargetLaneCount -> rememberedTargetLanes
        else -> emptySet()
    }

    // Preserve the last trustworthy lane picture through weak/lost GPS instead of
    // letting one noisy fix jump the driver across lanes.
    var frozenLaneCount by remember { mutableStateOf<Int?>(null) }
    var frozenLaneNumber by remember { mutableStateOf<Int?>(null) }
    var frozenTurnHints by remember { mutableStateOf<List<String>>(emptyList()) }
    var frozenTargetLanes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(
        gpsWeak, gpsStale, layoutSettling, laneCount, liveLaneNumber,
        state.laneTurnHints, stableTargetLanes,
    ) {
        val stableLaneCount = laneCount
        if (!gpsWeak && !gpsStale && !layoutSettling && stableLaneCount != null) {
            frozenLaneCount = stableLaneCount
            if (liveLaneNumber != null && liveLaneNumber in 1..stableLaneCount) {
                frozenLaneNumber = liveLaneNumber
            }
            if (state.laneTurnHints.size == stableLaneCount) frozenTurnHints = state.laneTurnHints
            frozenTargetLanes = stableTargetLanes
        }
    }
    val laneUncertain = gpsWeak || gpsStale
    val displayLaneCount = if (laneUncertain) frozenLaneCount ?: laneCount else laneCount
    val displayLaneNumber = if (laneUncertain) frozenLaneNumber else liveLaneNumber
    val displayTurnHints = if (laneUncertain && frozenTurnHints.isNotEmpty()) frozenTurnHints else state.laneTurnHints
    val displayTargetLanes = if (laneUncertain) frozenTargetLanes else stableTargetLanes

    // Keep the most recent exact confirmation visible briefly as historical
    // evidence. It is never treated as a current exact claim after the gate drops.
    var lastConfirmedLane by remember { mutableStateOf<Int?>(null) }
    var lastConfirmedLaneCount by remember { mutableStateOf<Int?>(null) }
    var lastConfirmedAtMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(
        state.laneExactClaim,
        state.likelyLaneNumberFromLeft,
        state.likelyLaneCount,
        state.lastUpdateMillis,
    ) {
        val confirmedLane = state.likelyLaneNumberFromLeft
        val confirmedCount = state.likelyLaneCount
        if (state.laneExactClaim && confirmedLane != null && confirmedCount != null &&
            confirmedLane in 1..confirmedCount) {
            lastConfirmedLane = confirmedLane
            lastConfirmedLaneCount = confirmedCount
            lastConfirmedAtMillis = System.currentTimeMillis()
        }
    }
    val lastConfirmedAgeMillis = (nowMillis - lastConfirmedAtMillis)
        .takeIf { lastConfirmedAtMillis > 0L && it in 0..15_000L }
    val recentConfirmedLane = lastConfirmedLane.takeIf {
        lastConfirmedAgeMillis != null &&
            lastConfirmedLaneCount != null &&
            lastConfirmedLaneCount == displayLaneCount
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(NavBg)) {
        val wide = maxWidth >= 600.dp
        val foldedCompact = maxWidth < 480.dp
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!editingDestination) {
        Column(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(
                    start = if (foldedCompact) 5.dp else 8.dp,
                    top = if (foldedCompact) 5.dp else 8.dp,
                    end = if (foldedCompact) 52.dp else 64.dp,
                    bottom = 6.dp,
                )
                .widthIn(max = if (wide) 390.dp else 540.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(color = NavCardStrong, shape = RoundedCornerShape(if (foldedCompact) 12.dp else 16.dp)) {
                Column(Modifier.padding(if (foldedCompact) 6.dp else 10.dp)) {
                    Text(
                        when {
                            gpsStale -> "GPS LOST · guidance paused"
                            routeUi.rerouting -> "REROUTING…"
                            routeUi.planning -> "BUILDING ROUTE…"
                            routeUi.waitingForGps -> "WAITING FOR ACCURATE GPS…"
                            route?.arrived == true -> "ARRIVED"
                            route != null && recordingActive -> "NAVIGATION · TRIP RECORDING"
                            route != null -> "NAVIGATION · NOT RECORDING"
                            recordingActive -> "TRIP RECORDING"
                            sessionActive -> "LIVE VIEW · NOT RECORDING"
                            else -> "LaneGPS · READY"
                        }, color = if (gpsStale) NavRed else NavBlueSoft,
                        fontWeight = FontWeight.Bold, fontSize = if (foldedCompact) 9.sp else 12.sp,
                    )
                    route?.let {
                        Text(com.example.gps.laneengine.ManeuverInstruction.symbol(it.nextManeuver),
                            color = Color.White, fontSize = if (foldedCompact) 24.sp else 36.sp, fontWeight = FontWeight.Bold)
                        if (!it.arrived && it.distanceMeters <= 500.0) {
                            Text(it.destinationSide?.let { side -> "Destination on the $side" }
                                ?: "Destination side unavailable", color = NavBlueSoft, fontSize = 13.sp)
                        }
                    }
                    Text(route?.nextManeuver ?: if (sessionActive) "Live road follow" else "Live view · no destination needed",
                        color = Color.White, fontSize = if (foldedCompact) 15.sp else 21.sp, fontWeight = FontWeight.Bold,
                        maxLines = if (foldedCompact) 1 else 2)
                    route?.let {
                        Text("${formatNavDistance(it.nextManeuverDistanceMeters)} · ${it.nextRoad}",
                            color = Color.White, fontSize = if (foldedCompact) 11.sp else 14.sp, maxLines = 1)
                    }
                    routeUi.error?.let { Text(it, color = NavAmber, fontSize = 12.sp, maxLines = 2) }
                }
            }
            val showLaneOverlay = !foldedCompact || shouldShowCompactLaneOverlay(
                laneCount = displayLaneCount,
                targetLanes = displayTargetLanes,
                maneuver = route?.nextManeuver,
                maneuverDistanceMeters = route?.nextManeuverDistanceMeters,
                arrived = route?.arrived == true,
            )
            if (showLaneOverlay) {
                CompactLaneOverlay(
                    state = state,
                    laneNumber = displayLaneNumber,
                    laneCount = displayLaneCount,
                    stale = gpsStale,
                    weak = gpsWeak,
                    settling = layoutSettling,
                    active = sessionActive,
                    route = route,
                    targetLanes = displayTargetLanes,
                    turnHints = displayTurnHints,
                    recentConfirmedLane = recentConfirmedLane,
                    recentConfirmedAgeMillis = lastConfirmedAgeMillis,
                    compact = foldedCompact,
                )
            }
        }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(8.dp)
                .widthIn(max = if (wide) 390.dp else 540.dp).fillMaxWidth(),
            color = NavCard, shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                Modifier
                    .heightIn(max = maxHeight * if (foldedCompact) 0.22f else 0.52f)
                    .verticalScroll(rememberScrollState())
                    .padding(if (foldedCompact) 5.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (foldedCompact) 2.dp else 4.dp),
            ) {
                DestinationCard(
                    voiceController, voiceState, query, routeUi, sessionActive,
                    onQueryChange, onQuickRoute, onClearRoute, onPrimaryAction,
                    { editingDestination = it },
                    compact = foldedCompact,
                )
                if (!foldedCompact || trafficStatus.startsWith("Traffic on")) {
                    Text(trafficStatus, color = NavMuted, fontSize = if (foldedCompact) 9.sp else 11.sp, maxLines = 1)
                }

                if (!followingLocation) {
                    Button(onClick = onRecenter, modifier = Modifier.fillMaxWidth()) {
                        Text("RECENTER · FOLLOW ME", fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "${if (gpsStale || !sessionActive) "—" else ((state.speedMps ?: 0f) * 2.23694f).roundToInt().toString()} mph" +
                        (route?.let { " · ${formatNavDistance(it.distanceMeters)} · ${formatNavDuration(it.durationSeconds)}" } ?: ""),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (foldedCompact) 13.sp else 17.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showOptions = true }, modifier = Modifier.weight(1f)) {
                        Text("Options", maxLines = 1)
                    }
                    OutlinedButton(onClick = { voiceController.cycleMode() }) {
                        Text(voiceState.mode.label)
                    }
                }
                if (!sessionActive) {
                    Button(onClick = onStartLiveView, modifier = Modifier.fillMaxWidth().heightIn(min = if (foldedCompact) 38.dp else 48.dp)) {
                        Text("START LIVE VIEW", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        if (recordingActive) "TRIP RECORDING ON" else "LIVE VIEW ON · TRIP RECORDING OFF",
                        color = if (recordingActive) NavRed else NavGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    if (recordingActive) {
                        Button(
                            onClick = onStopTrip,
                            modifier = Modifier.fillMaxWidth().heightIn(min = if (foldedCompact) 38.dp else 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NavRed, contentColor = NavBg),
                        ) {
                            Text("STOP & SAVE TRIP", fontWeight = FontWeight.ExtraBold)
                        }
                    } else {
                        Button(onClick = onStartTrip, modifier = Modifier.fillMaxWidth().heightIn(min = if (foldedCompact) 38.dp else 48.dp)) {
                            Text("START TRIP RECORDING", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    if (route != null || routeUi.planning || routeUi.waitingForGps) {
                        OutlinedButton(onClick = onStopNavigation, modifier = Modifier.fillMaxWidth()) {
                            Text("STOP NAVIGATION", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    if (showOptions) {
        Dialog(onDismissRequest = { showOptions = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = NavBg) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    TextButton(onClick = { showOptions = false }) { Text("BACK TO MAP") }
                    Text("Options", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    VoiceOptions(voiceController, voiceState)
                    TrafficOptions(onTrafficChanged)
                    if (!state.permissionFine && !sessionActive) {
                        TextButton(onClick = onEnableLocation) { Text("Enable precise location") }
                    }
                    Text("Normal voice: fewer turn cues, with an extra early highway-exit warning.", color = NavMuted, fontSize = 13.sp)

                    HorizontalDivider()
                    Text("GPS diagnostics", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Accuracy: ${state.accuracyMeters?.roundToInt()?.let { "±$it m" } ?: "waiting"} · Satellites: ${state.satellitesUsedInFix}", color = NavMuted)
                    Text(state.laneDataStatus, color = NavMuted, fontSize = 12.sp)
                    Text("Trip logs are saved in Downloads/LaneGPS.", color = NavMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CompactLaneOverlay(
    state: GnssUiState,
    laneNumber: Int?,
    laneCount: Int?,
    stale: Boolean,
    weak: Boolean,
    settling: Boolean,
    active: Boolean,
    route: OpenRouteClient.RouteSummary?,
    targetLanes: Set<Int>,
    turnHints: List<String>,
    recentConfirmedLane: Int?,
    recentConfirmedAgeMillis: Long?,
    compact: Boolean = false,
) {
    val uncertain = stale || weak
    val laneKnown = active &&
        laneNumber != null && laneCount != null && laneNumber in 1..laneCount
    val exact = laneKnown && !uncertain && !settling && state.laneExactClaim
    val likely = laneKnown && !exact && !uncertain && !settling
    val confidence = (state.laneConfidence.coerceIn(0f, 1f) * 100).roundToInt()
    val targetTitle = if (!uncertain && laneCount != null && targetLanes.isNotEmpty()) {
        targetLaneLabel(targetLanes, laneCount)
    } else null
    val qualityText = when {
        stale -> "GPS LOST"
        weak -> "GPS WEAK"
        else -> "GPS GOOD"
    }
    val qualityColor = when {
        stale -> NavRed
        weak -> NavAmber
        else -> NavGreen
    }

    Surface(color = NavCard, shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)) {
        Column(
            Modifier.padding(if (compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        !active -> "LANE GUIDANCE · waiting for live view"
                        uncertain -> "LANE UNCERTAIN · holding last good view"
                        settling -> "UPDATING ROAD LANES"
                        targetTitle != null -> targetTitle
                        exact -> "LANE $laneNumber OF $laneCount · CONFIRMED"
                        recentConfirmedLane != null && laneCount != null ->
                            "LAST CONFIRMED LANE $recentConfirmedLane OF $laneCount · ${(recentConfirmedAgeMillis ?: 0L) / 1000}s AGO"
                        likely -> "LIKELY LANE $laneNumber OF $laneCount · $confidence%"
                        laneCount != null -> "$laneCount LANES · POSITION UNCERTAIN"
                        state.laneCandidateCount > 0 -> "ROAD FOUND · RESOLVING LANES"
                        else -> "LANES UNKNOWN · scanning road"
                    },
                    modifier = Modifier.weight(1f),
                    color = when {
                        uncertain -> NavAmber
                        targetTitle != null -> NavBlueSoft
                        exact -> NavGreen
                        else -> NavAmber
                    },
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 12.sp else 15.sp,
                )
                Surface(
                    color = qualityColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(99.dp),
                ) {
                    Text(
                        qualityText,
                        color = qualityColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(
                            horizontal = if (compact) 6.dp else 8.dp,
                            vertical = if (compact) 3.dp else 5.dp,
                        ),
                    )
                }
            }

            if (active) {
                LaneRoadDiagram(
                    laneCount = laneCount,
                    currentLane = if (laneKnown) laneNumber else null,
                    exact = exact,
                    targetLanes = targetLanes,
                    turnHints = turnHints,
                    dimmed = uncertain,
                    compact = compact,
                )

                route?.takeIf { !it.arrived }?.let {
                    NextMoveStrip(
                        route = it,
                        currentLane = laneNumber,
                        targetLanes = targetLanes,
                        exact = exact,
                        uncertain = uncertain,
                        compact = compact,
                    )
                }

                if (!compact) Text(
                    when {
                        !state.fixReceived -> "Waiting for a GPS fix. Live View is already running."
                        stale -> "GPS lost. The last trustworthy lane picture is frozen instead of jumping."
                        weak -> "GPS is weak. LaneGPS is holding the last good lane picture until readings settle."
                        recentConfirmedLane != null && !exact ->
                            "Lane $recentConfirmedLane was recently confirmed; current GPS no longer meets the exact-lane gate."
                        targetLanes.isNotEmpty() && exact && laneNumber != null ->
                            laneMoveAdvice(laneNumber, targetLanes) + " · Blue = route lane; green = confirmed car."
                        targetLanes.isNotEmpty() ->
                            "Blue = lane(s) mapped for ${route?.nextManeuver ?: "the next maneuver"}. Current lane is not confirmed yet."
                        !exact && state.laneCandidateCount == 0 ->
                            "No matching lane map here yet. GPS position alone cannot identify your lane."
                        !exact && "BLOCK MAP_SOURCE" in state.laneDataStatus ->
                            "This road's lane layout is inferred or incomplete. Current lane cannot be confirmed."
                        !exact && !state.sensorFrameCalibrated ->
                            "Current lane unconfirmed while motion calibration settles."
                        exact -> "Green car = confirmed current lane."
                        likely -> "Amber car = likely current lane; LaneGPS is not claiming it as exact yet."
                        settling -> "Road layout is changing; LaneGPS waits for a stable layout before switching."
                        else -> "LaneGPS is waiting for enough road/GPS evidence to place your car."
                    },
                    color = NavMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun NextMoveStrip(
    route: OpenRouteClient.RouteSummary,
    currentLane: Int?,
    targetLanes: Set<Int>,
    exact: Boolean,
    uncertain: Boolean,
    compact: Boolean = false,
) {
    val nearest = currentLane?.let { current ->
        targetLanes.minByOrNull { kotlin.math.abs(it - current) }
    }
    val delta = if (currentLane != null && nearest != null) kotlin.math.abs(nearest - currentLane) else 0
    val alreadyCorrect = exact && currentLane != null && currentLane in targetLanes
    val text = when {
        uncertain -> "LANE UNCERTAIN · hold current position"
        alreadyCorrect -> "STAY · current lane"
        exact && currentLane != null && nearest != null && nearest < currentLane ->
            "← LEFT ${delta.coerceAtLeast(1)} · in ${formatNavDistance(route.nextManeuverDistanceMeters)}"
        exact && currentLane != null && nearest != null && nearest > currentLane ->
            "RIGHT ${delta.coerceAtLeast(1)} → · in ${formatNavDistance(route.nextManeuverDistanceMeters)}"
        else ->
            "${com.example.gps.laneengine.ManeuverInstruction.symbol(route.nextManeuver)} ${route.nextManeuver} · in ${formatNavDistance(route.nextManeuverDistanceMeters)}"
    }
    Surface(
        color = when {
            uncertain -> NavAmber.copy(alpha = 0.13f)
            alreadyCorrect -> NavGreen.copy(alpha = 0.10f)
            else -> NavBlue.copy(alpha = 0.16f)
        },
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = if (compact) 7.dp else 10.dp,
                vertical = if (compact) 5.dp else if (alreadyCorrect) 6.dp else 9.dp,
            ),
            color = when {
                uncertain -> NavAmber
                alreadyCorrect -> NavGreen
                else -> Color.White
            },
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 12.sp else if (alreadyCorrect) 12.sp else 16.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun LaneRoadDiagram(
    laneCount: Int?,
    currentLane: Int?,
    exact: Boolean,
    targetLanes: Set<Int>,
    turnHints: List<String>,
    dimmed: Boolean,
    compact: Boolean = false,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(if (compact) 72.dp else 118.dp)
            .padding(vertical = if (compact) 1.dp else 4.dp)
    ) {
        val nearLeft = size.width * 0.04f
        val nearRight = size.width * 0.96f
        val farLeft = size.width * 0.32f
        val farRight = size.width * 0.68f
        val top = size.height * 0.06f
        val bottom = size.height
        val road = Path().apply {
            moveTo(nearLeft, bottom); lineTo(farLeft, top)
            lineTo(farRight, top); lineTo(nearRight, bottom); close()
        }
        drawPath(road, Color(0xFF263344))

        if (laneCount != null) {
            // Highlight mapped target lane(s) as perspective ribbons. These are derived
            // only from explicit OSM turn:lanes tags matching the next route maneuver.
            targetLanes.filter { it in 1..laneCount }.forEach { lane ->
                val leftFraction = (lane - 1).toFloat() / laneCount
                val rightFraction = lane.toFloat() / laneCount
                val targetPath = Path().apply {
                    moveTo(nearLeft + (nearRight - nearLeft) * leftFraction, bottom)
                    lineTo(farLeft + (farRight - farLeft) * leftFraction, top)
                    lineTo(farLeft + (farRight - farLeft) * rightFraction, top)
                    lineTo(nearLeft + (nearRight - nearLeft) * rightFraction, bottom)
                    close()
                }
                drawPath(targetPath, NavBlue.copy(alpha = if (dimmed) 0.10f else 0.34f))
            }
        }

        drawLine(Color.White, Offset(nearLeft, bottom), Offset(farLeft, top), 2.dp.toPx())
        drawLine(Color.White, Offset(nearRight, bottom), Offset(farRight, top), 2.dp.toPx())
        if (laneCount != null) {
            for (boundary in 1 until laneCount) {
                val fraction = boundary.toFloat() / laneCount
                drawLine(
                    Color(0xFFD9E2EF),
                    Offset(nearLeft + (nearRight - nearLeft) * fraction, bottom),
                    Offset(farLeft + (farRight - farLeft) * fraction, top),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx())),
                )
            }

            // Small lane arrows expose the actual OSM turn-lane data instead of just
            // showing lane numbers. A lane can legitimately carry multiple arrows.
            for (lane in 1..laneCount) {
                val tokens = turnHints.getOrNull(lane - 1).orEmpty()
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val fraction = (lane - 0.5f) / laneCount
                val x = farLeft + (farRight - farLeft) * fraction
                val shaftBottom = top + 34.dp.toPx()
                val shaftTop = top + 12.dp.toPx()
                val baseColor = if (lane in targetLanes) Color.White else NavBlueSoft.copy(alpha = 0.75f)
                val c = if (dimmed) baseColor.copy(alpha = 0.28f) else baseColor
                val stroke = if (lane in targetLanes) 2.4.dp.toPx() else 1.6.dp.toPx()
                val through = tokens.any { it == "through" }
                val left = tokens.any { "left" in it || it == "reverse" }
                val right = tokens.any { "right" in it }
                if (through || (!left && !right)) {
                    drawLine(c, Offset(x, shaftBottom), Offset(x, shaftTop), stroke)
                    drawLine(c, Offset(x, shaftTop), Offset(x - 4.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x, shaftTop), Offset(x + 4.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                }
                if (left) {
                    drawLine(c, Offset(x, shaftBottom), Offset(x, shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x, shaftTop + 5.dp.toPx()), Offset(x - 7.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x - 7.dp.toPx(), shaftTop + 5.dp.toPx()), Offset(x - 3.dp.toPx(), shaftTop + 1.dp.toPx()), stroke)
                }
                if (right) {
                    drawLine(c, Offset(x, shaftBottom), Offset(x, shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x, shaftTop + 5.dp.toPx()), Offset(x + 7.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x + 7.dp.toPx(), shaftTop + 5.dp.toPx()), Offset(x + 3.dp.toPx(), shaftTop + 1.dp.toPx()), stroke)
                }
            }

            if (currentLane != null && currentLane in 1..laneCount) {
                val depth = 0.76f
                val left = farLeft + (nearLeft - farLeft) * depth
                val right = farRight + (nearRight - farRight) * depth
                val x = left + (right - left) * (currentLane - 0.5f) / laneCount
                val width = minOf(25.dp.toPx(), (right - left) / laneCount * 0.68f)
                val y = top + (bottom - top) * depth
                val carColor = (if (exact) NavGreen else NavAmber).copy(alpha = if (dimmed) 0.45f else 1f)
                drawRoundRect(
                    carColor,
                    Offset(x - width / 2, y - 17.dp.toPx()),
                    Size(width, 33.dp.toPx()),
                    CornerRadius(5.dp.toPx()),
                )
                drawRoundRect(
                    NavBg,
                    Offset(x - width * 0.32f, y - 11.dp.toPx()),
                    Size(width * 0.64f, 8.dp.toPx()),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

internal fun shouldShowCompactLaneOverlay(
    laneCount: Int?,
    targetLanes: Set<Int>,
    maneuver: String?,
    maneuverDistanceMeters: Double?,
    arrived: Boolean,
): Boolean {
    if (arrived || laneCount == null || laneCount < 2 || maneuverDistanceMeters == null) return false
    val intent = routeLaneIntent(maneuver)
    val mappedLaneChange = targetLanes.isNotEmpty() && maneuverDistanceMeters <= 1_600.0
    val multiLaneTurn =
        intent in setOf(RouteLaneIntent.LEFT, RouteLaneIntent.RIGHT, RouteLaneIntent.UTURN) &&
            maneuverDistanceMeters <= 700.0
    return mappedLaneChange || multiLaneTurn
}

private enum class RouteLaneIntent { LEFT, RIGHT, THROUGH, UTURN }

private fun recommendedLaneNumbers(
    turnHints: List<String>,
    maneuver: String?,
    laneCount: Int?,
): Set<Int> {
    if (laneCount == null || laneCount !in 1..8 || turnHints.size != laneCount) return emptySet()
    val intent = routeLaneIntent(maneuver) ?: return emptySet()
    return turnHints.mapIndexedNotNull { index, raw ->
        val tokens = raw.split(';').map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }
        val match = when (intent) {
            RouteLaneIntent.LEFT -> tokens.any { "left" in it }
            RouteLaneIntent.RIGHT -> tokens.any { "right" in it }
            RouteLaneIntent.THROUGH -> tokens.any { it == "through" }
            RouteLaneIntent.UTURN -> tokens.any { it == "reverse" || "uturn" in it || "u_turn" in it }
        }
        (index + 1).takeIf { match }
    }.toSet()
}

private fun routeLaneIntent(maneuver: String?): RouteLaneIntent? {
    val text = maneuver?.lowercase(java.util.Locale.ROOT)?.trim().orEmpty()
    if (text.isEmpty() || "destination" in text || "arriv" in text || "roundabout" in text) return null
    return when {
        "u-turn" in text || "uturn" in text -> RouteLaneIntent.UTURN
        "left" in text -> RouteLaneIntent.LEFT
        "right" in text -> RouteLaneIntent.RIGHT
        "continue" in text || "straight" in text -> RouteLaneIntent.THROUGH
        else -> null
    }
}

private fun targetLaneLabel(targetLanes: Set<Int>, laneCount: Int): String {
    val sorted = targetLanes.filter { it in 1..laneCount }.sorted()
    if (sorted.isEmpty()) return "ROUTE LANES UNKNOWN"
    return when {
        sorted.size == 1 -> "USE LANE ${sorted.first()} OF $laneCount"
        sorted.zipWithNext().all { (a, b) -> b == a + 1 } ->
            "USE LANES ${sorted.first()}–${sorted.last()} OF $laneCount"
        else -> "USE LANES ${sorted.joinToString(", ")} OF $laneCount"
    }
}

private fun laneMoveAdvice(currentLane: Int, targets: Set<Int>): String {
    if (currentLane in targets) return "KEEP THIS LANE"
    val nearest = targets.minByOrNull { kotlin.math.abs(it - currentLane) } ?: return "TARGET LANE MAPPED"
    return if (nearest < currentLane) "MOVE LEFT WHEN SAFE" else "MOVE RIGHT WHEN SAFE"
}

@Composable
private fun NavigationHeader(
    sessionActive: Boolean,
    gpsStale: Boolean,
    rerouting: Boolean,
    routed: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("LaneGPS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                when {
                    sessionActive && routed -> "3D lane-first navigation"
                    sessionActive -> "3D Live View · live lane sensing"
                    else -> "Lane-first navigation"
                },
                color = NavMuted,
                fontSize = 12.sp,
            )
        }
        val text = when {
            gpsStale -> "GPS LOST"
            rerouting -> "REROUTING"
            sessionActive && routed -> "NAV"
            sessionActive -> "LIVE VIEW"
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
                fontSize = 10.sp,
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
private fun ManeuverCard(
    route: OpenRouteClient.RouteSummary?,
    routeUi: NavigationRouteUi,
    sessionActive: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavCardStrong),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (route == null) {
                Text(
                    if (sessionActive) "LIVE VIEW" else "READY",
                    color = if (sessionActive) NavGreen else NavMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (sessionActive) "Live road follow" else "Destination optional",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                )
                Text(
                    if (sessionActive) {
                        "3D map follow and lane sensing are running without a destination."
                    } else {
                        "Enter a destination and tap Start Navigation, or leave it blank for Live View."
                    },
                    color = NavMuted,
                    fontSize = 12.sp,
                )
                routeUi.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = NavAmber, fontSize = 11.sp)
                }
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
                    if (routeUi.rerouting) "REROUTING…" else "3D LIVE ROUTE",
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
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                color = Color(0xCC080D15),
                shape = RoundedCornerShape(99.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            ) {
                Text(
                    "3D FOLLOW",
                    color = NavGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun LaneCard(
    state: GnssUiState,
    laneNumber: Int?,
    laneCount: Int?,
    gpsStale: Boolean,
    layoutSettling: Boolean,
) {
    val countKnown = laneCount != null && laneCount in 1..8
    val laneKnown = countKnown && laneNumber != null && laneNumber in 1..laneCount!!
    val exact = laneKnown && state.laneExactClaim && !gpsStale
    val accent = if (exact) NavGreen else NavAmber

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavCard),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("ROAD LANES", color = NavBlue, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        when {
                            gpsStale -> "GPS LOST"
                            layoutSettling -> "UPDATING ROAD LANES"
                            exact -> "LANE $laneNumber OF $laneCount"
                            laneKnown -> "LIKELY $laneNumber OF $laneCount"
                            countKnown -> "$laneCount LANES · POSITION UNCERTAIN"
                            else -> "SCANNING ROAD LANES"
                        },
                        color = when {
                            gpsStale -> NavRed
                            laneKnown -> accent
                            countKnown -> NavBlueSoft
                            else -> NavMuted
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (countKnown) 20.sp else 18.sp,
                    )
                }
                if (laneKnown) {
                    Text(
                        "${(state.laneConfidence.coerceIn(0f, 1f) * 100).roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (countKnown) {
                Row(
                    Modifier.fillMaxWidth().height(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(laneCount!!) { index ->
                        val current = laneKnown && index + 1 == laneNumber
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    when {
                                        current -> accent.copy(alpha = 0.30f)
                                        else -> Color(0xFF0C1423)
                                    },
                                    RoundedCornerShape(11.dp),
                                )
                                .border(
                                    width = if (current) 2.dp else 1.dp,
                                    color = if (current) accent else NavBlue.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(11.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "↑",
                                    color = if (current) Color.White else NavBlueSoft,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 26.sp,
                                )
                                Text(
                                    "${index + 1}",
                                    color = if (current) Color.White else NavMuted,
                                    fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    when {
                        layoutSettling -> "Previous road layout · checking new lane data"
                        exact -> "Green = LaneGPS has enough evidence for an exact current-lane claim."
                        laneKnown -> "Amber = likely current lane; GPS uncertainty is still being respected."
                        else -> "Road lane count is known; LaneGPS is still deciding which lane you occupy."
                    },
                    color = NavMuted,
                    fontSize = 10.sp,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(Color(0xFF0C1220), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            gpsStale -> "Waiting for fresh GPS"
                            state.laneCandidateCount > 0 -> "${state.laneCandidateCount} road candidate(s) nearby · collecting lane-count evidence"
                            else -> "Waiting for nearby OSM lane geometry"
                        },
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
                Text("route-to-lane guidance still in development", color = NavMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun DestinationCard(
    voiceController: NavigationVoiceController,
    voiceState: NavigationVoiceController.State,
    query: String,
    routeUi: NavigationRouteUi,
    sessionActive: Boolean,
    onQueryChange: (String) -> Unit,
    onQuickRoute: (String) -> Unit,
    onClearRoute: () -> Unit,
    onPrimaryAction: () -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    compact: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = LocalFocusManager.current
    val store = remember { DestinationStore(context.applicationContext) }
    val photon = remember { PhotonSearchClient() }
    var saved by remember { mutableStateOf(store.saved()) }
    var recent by remember { mutableStateOf(store.recent()) }
    var home by remember { mutableStateOf(store.home()) }
    var work by remember { mutableStateOf(store.work()) }
    var remoteSuggestions by remember { mutableStateOf<List<PhotonSearchClient.Suggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var queryFocused by remember { mutableStateOf(false) }

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

    val localMatches = remember(query, saved, recent, home, work) { store.localMatches(query) }
    val showSuggestions = queryFocused &&
        (localMatches.isNotEmpty() || remoteSuggestions.isNotEmpty() || searching)
    val quickCandidate = routeUi.summary?.destinationName?.trim()?.takeIf { it.isNotBlank() }
        ?: query.trim().takeIf { it.isNotBlank() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavCard),
        shape = RoundedCornerShape(if (compact) 12.dp else 18.dp),
    ) {
        Column(Modifier.padding(if (compact) 5.dp else 13.dp)) {
            if (compact) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    color = Color(0xFF0D1422),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NavLine),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { queryFocused = it.isFocused; onEditingChanged(it.isFocused) },
                            singleLine = true,
                            enabled = !routeUi.planning,
                            textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
                            cursorBrush = SolidColor(NavBlueSoft),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isBlank()) {
                                        Text("Search destination", color = NavMuted, fontSize = 12.sp, maxLines = 1)
                                    }
                                    inner()
                                }
                            },
                        )
                        TextButton(
                            onClick = { focusManager.clearFocus(); onPrimaryAction() },
                            enabled = query.isNotBlank() && !routeUi.planning,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(if (routeUi.planning) "…" else "GO", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { queryFocused = it.isFocused; onEditingChanged(it.isFocused) },
                    singleLine = true,
                    placeholder = { Text("Address, place or business") },
                    trailingIcon = { TextButton(onClick = { focusManager.clearFocus(); onPrimaryAction() },
                        enabled = query.isNotBlank() && !routeUi.planning) { Text(if (routeUi.planning) "…" else "GO") } },
                    enabled = !routeUi.planning,
                )
            }

            if (queryFocused) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (home != null) {
                            onQuickRoute(home!!)
                        } else if (quickCandidate != null) {
                            store.setHome(quickCandidate)
                            home = store.home()
                            recent = store.recent()
                        }
                    },
                    enabled = !routeUi.planning && (home != null || quickCandidate != null),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (home == null) "SET HOME" else "⌂ HOME", maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (work != null) {
                            onQuickRoute(work!!)
                        } else if (quickCandidate != null) {
                            store.setWork(quickCandidate)
                            work = store.work()
                            recent = store.recent()
                        }
                    },
                    enabled = !routeUi.planning && (work != null || quickCandidate != null),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (work == null) "SET WORK" else "▣ WORK", maxLines = 1)
                }
            }

            Spacer(Modifier.height(7.dp))

            }

            if (showSuggestions && !routeUi.planning) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xFF0D1422),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = if (compact) 120.dp else 180.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = if (compact) 2.dp else 4.dp)
                    ) {
                        if (query.isBlank() && localMatches.isNotEmpty()) {
                            Text(
                                "HOME · WORK · SAVED · RECENT",
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
                                    onClick = {
                                        onQueryChange(item.label)
                                        focusManager.clearFocus()
                                        onQuickRoute(item.label)
                                    },
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
                                            item.quickLabel ?: when {
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
                                if (item.quickLabel == null) {
                                    TextButton(onClick = {
                                        store.toggleSaved(item.label)
                                        saved = store.saved()
                                    }) {
                                        Text(if (store.isSaved(item.label)) "★" else "☆", color = NavBlueSoft)
                                    }
                                }
                            }
                        }

                        remoteSuggestions
                            .filterNot { remote ->
                                localMatches.any { local ->
                                    remote.fullLabel().equals(local.label, ignoreCase = true)
                                }
                            }
                            .take(5)
                            .forEach { suggestion ->
                                val fullLabel = suggestion.fullLabel()
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = {
                                            onQueryChange(fullLabel)
                                            focusManager.clearFocus()
                                            onQuickRoute(fullLabel)
                                        },
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
                                                    maxLines = 2,
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
                                "Searching nearby addresses…",
                                color = NavMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            if (!compact) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onPrimaryAction()
                    },
                    enabled = query.isNotBlank() && !routeUi.planning,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        when {
                            routeUi.planning -> "BUILDING ROUTE…"
                            routeUi.summary != null -> "ROUTE TO THIS"
                            else -> "START NAVIGATION"
                        },
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }

            routeUi.error?.takeIf { routeUi.summary == null }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = if (routeUi.waitingForGps) NavAmber else NavRed, fontSize = 11.sp)
            }
            if (queryFocused) routeUi.summary?.let { route ->
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        store.setHome(route.destinationName)
                        home = store.home()
                        recent = store.recent()
                    }) {
                        Text("USE AS HOME", color = NavMuted, fontSize = 9.sp)
                    }
                    TextButton(onClick = {
                        store.setWork(route.destinationName)
                        work = store.work()
                        recent = store.recent()
                    }) {
                        Text("USE AS WORK", color = NavMuted, fontSize = 9.sp)
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
            "road candidates",
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

@Composable
private fun VoiceOptions(voiceController: NavigationVoiceController, voiceState: NavigationVoiceController.State) {
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var voiceGroup by remember { mutableStateOf<String?>(null) }
    val selectedVoiceLabel = voiceState.voices
        .firstOrNull { it.id == voiceState.selectedVoiceId }
        ?.let { if (it.group == "Defaults & effects") it.label else "${it.group} · ${it.label}" }
        ?: "System default"

    Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(
                    onClick = { voiceController.cycleMode() },
                    enabled = voiceState.ready,
                    modifier = Modifier.weight(0.8f),
                ) {
                    Text(
                        when {
                            !voiceState.ready -> "VOICE…"
                            else -> voiceState.mode.label
                        },
                        maxLines = 1,
                    )
                }
                Box(Modifier.weight(1.2f)) {
                    OutlinedButton(
                        onClick = { voiceGroup = null; voiceMenuExpanded = true },
                        enabled = voiceState.ready && voiceState.voices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selectedVoiceLabel, maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = voiceMenuExpanded,
                        onDismissRequest = { voiceMenuExpanded = false },
                    ) {
                        if (voiceGroup == null) {
                            voiceState.voices.map { it.group }.distinct().forEach { group ->
                                DropdownMenuItem(text = { Text(group) }, onClick = { voiceGroup = group })
                            }
                        } else {
                            DropdownMenuItem(text = { Text("‹ All languages / regions") }, onClick = { voiceGroup = null })
                            voiceState.voices.filter { it.group == voiceGroup }.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        voiceController.selectVoice(option.id)
                                        voiceMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            TextButton(onClick = { voiceController.previewSelectedVoice() },
                enabled = voiceState.ready && !voiceState.muted) {
                Text(if (voiceState.muted) "Unmute to test voice" else "TEST SELECTED VOICE")
            }

            Text("Alerts only: rerouting and arrival. No turn-by-turn speech.",
                color = NavMuted, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))

    }
}

@Composable
private fun TrafficOptions(onChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { com.example.gps.traffic.TrafficSettings(context.applicationContext) }
    var key by remember { mutableStateOf(settings.key()) }
    var enabled by remember { mutableStateOf(settings.enabled()) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live traffic", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("TomTom congestion overlay: green = flowing, yellow/orange = slower, red = heavy traffic. Coverage varies. Route times and rerouting still use the standard route service.", color = NavMuted, fontSize = 13.sp)
        OutlinedTextField(value = key, onValueChange = { key = it; message = "" },
            label = { Text("TomTom Traffic API key") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Text("Show traffic on the map", color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
        Button(onClick = {
            if (enabled && key.isBlank()) message = "Enter your TomTom Traffic key first."
            else {
                settings.save(key, enabled)
                onChanged()
                message = if (enabled) "Saved. Connection status appears on the map." else "Traffic disabled."
            }
        }) { Text("SAVE TRAFFIC SETTINGS") }
        TextButton(onClick = {
            key = ""; enabled = false; settings.save("", false); onChanged(); message = "Key removed."
        }) { Text("REMOVE KEY") }
        if (message.isNotBlank()) Text(message, color = NavBlueSoft, fontSize = 13.sp)
        Text("Your key stays on this phone. Traffic refreshes every two minutes while this screen is active. Provider usage limits apply.", color = NavMuted, fontSize = 12.sp)
    }
}
