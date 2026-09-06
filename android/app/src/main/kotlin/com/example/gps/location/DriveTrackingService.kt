package com.example.gps.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.example.gps.MainActivity
import com.example.gps.lane.LaneApiClient
import com.example.gps.lane.LaneApiSettings
import com.example.gps.laneengine.GeoPoint
import com.example.gps.laneengine.Lane
import com.example.gps.laneengine.LaneMatcher
import com.example.gps.laneengine.Observation
import java.util.concurrent.Executors

class DriveTrackingService : Service() {
    private lateinit var store: DriveSessionStore
    private lateinit var laneApiSettings: LaneApiSettings
    private var tracker: AndroidGnssTracker? = null
    @Volatile private var latestState: GnssUiState? = null
    private var lastPersistElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val laneExecutor = Executors.newSingleThreadExecutor()
    private val laneLock = Any()
    private var laneMatcher = LaneMatcher()
    @Volatile private var cachedLanes: List<Lane> = emptyList()
    @Volatile private var laneFetchInFlight = false
    private var lastLaneFetchElapsed = 0L
    private var cachedBaseUrl = ""
    @Volatile private var laneOverlay = LaneOverlay()

    override fun onCreate() {
        super.onCreate()
        store = DriveSessionStore(this)
        laneApiSettings = LaneApiSettings(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndSave()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startRecording(resetSession = intent.getBooleanExtra(EXTRA_RESET, true))
            }

            ACTION_RESUME -> {
                startRecording(resetSession = false)
            }

            else -> {
                if (store.isActive()) {
                    startRecording(resetSession = false)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tracker?.stop()
        tracker = null
        latestState?.let(store::save)
        releaseWakeLock()
        laneExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(resetSession: Boolean) {
        if (resetSession) {
            tracker?.stop()
            tracker = null
            latestState = null
            store.clearSession()
            synchronized(laneLock) {
                laneMatcher = LaneMatcher()
                cachedLanes = emptyList()
                laneOverlay = LaneOverlay()
            }
        }

        if (tracker != null) return

        store.setActive(true)
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        val initialState = mergeLaneOverlay(store.load())
        latestState = initialState
        DriveSessionRuntime.publish(initialState)

        tracker = AndroidGnssTracker(
            context = this,
            initialState = initialState,
            onState = { rawState ->
                val matchedState = applyLaneMatch(rawState)
                latestState = matchedState
                DriveSessionRuntime.publish(matchedState)
                maybeFetchLaneCorridor(matchedState)

                val now = SystemClock.elapsedRealtime()
                if (now - lastPersistElapsed >= PERSIST_INTERVAL_MS) {
                    store.save(matchedState)
                    lastPersistElapsed = now
                }
            },
        ).also { it.start() }
    }

    private fun stopAndSave() {
        tracker?.stop()
        tracker = null

        val finalState = mergeLaneOverlay(latestState ?: store.load()).copy(
            sensorLaneReady = false,
            laneExactClaim = false,
            message = "Drive test stopped · summary saved",
        )
        latestState = finalState
        store.save(finalState)
        store.setActive(false)
        DriveSessionRuntime.publish(finalState)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun maybeFetchLaneCorridor(state: GnssUiState) {
        val lat = state.latitude ?: return
        val lon = state.longitude ?: return
        if (!state.fixReceived || laneFetchInFlight) return

        val baseUrl = laneApiSettings.getBaseUrl()
        if (baseUrl.isBlank()) {
            if (laneOverlay.status != "API NOT CONFIGURED") {
                laneOverlay = LaneOverlay(status = "API NOT CONFIGURED")
            }
            return
        }

        if (baseUrl != cachedBaseUrl) {
            synchronized(laneLock) {
                cachedBaseUrl = baseUrl
                cachedLanes = emptyList()
                laneMatcher = LaneMatcher()
                laneOverlay = LaneOverlay(status = "LANE API CONNECTING…")
            }
            lastLaneFetchElapsed = 0L
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaneFetchElapsed < LANE_FETCH_INTERVAL_MS) return
        lastLaneFetchElapsed = now
        laneFetchInFlight = true
        laneOverlay = laneOverlay.copy(status = "LOADING OSM LANE CORRIDOR…")

        laneExecutor.execute {
            try {
                val corridor = LaneApiClient(baseUrl).fetchOrImport(lat, lon, LANE_FETCH_RADIUS_M)
                synchronized(laneLock) {
                    cachedLanes = corridor.lanes
                    laneOverlay = if (corridor.lanes.isEmpty()) {
                        LaneOverlay(status = "NO USABLE OSM LANES NEARBY")
                    } else {
                        LaneOverlay(
                            status = "LANE DATA LOADED",
                            candidateCount = corridor.lanes.size,
                        )
                    }
                }
            } catch (exc: Exception) {
                val detail = exc.message?.take(48)?.replace('\n', ' ') ?: exc.javaClass.simpleName
                laneOverlay = LaneOverlay(status = "LANE API ERROR · $detail")
            } finally {
                laneFetchInFlight = false
            }

            val current = latestState ?: return@execute
            val merged = applyLaneMatch(current)
            latestState = merged
            DriveSessionRuntime.publish(merged)
        }
    }

    private fun applyLaneMatch(state: GnssUiState): GnssUiState {
        val lanes = cachedLanes
        val lat = state.latitude
        val lon = state.longitude
        val accuracy = state.accuracyMeters
        if (lanes.isEmpty() || lat == null || lon == null || accuracy == null) {
            return mergeLaneOverlay(state)
        }

        val estimate = synchronized(laneLock) {
            laneMatcher.update(
                observation = Observation(
                    timestampMillis = state.lastUpdateMillis ?: System.currentTimeMillis(),
                    position = GeoPoint(lat, lon),
                    horizontalAccuracyMeters = accuracy.toDouble(),
                    speedMps = (state.speedMps ?: 0f).toDouble(),
                    bearingDegrees = (state.fusedHeadingDegrees ?: state.bearingDegrees)?.toDouble(),
                    lateralAccelerationMps2 = state.lateralAccelerationMps2?.toDouble(),
                    sensorHeadingDegrees = state.sensorHeadingDegrees?.toDouble(),
                ),
                candidates = lanes,
            )
        }

        val topLane = lanes.firstOrNull { it.id == estimate.mostLikelyLaneId }
        if (topLane == null) {
            laneOverlay = laneOverlay.copy(
                status = "LANE CANDIDATES AVAILABLE · NO MATCH",
                candidateCount = lanes.size,
            )
            return mergeLaneOverlay(state)
        }

        val sameSegment = lanes.filter { it.segmentId == topLane.segmentId }
        val exact = estimate.claimExactLane && state.sensorLaneReady
        val status = when {
            accuracy > 5f -> "UNCERTAIN · GNSS ${"%.1f".format(accuracy)} m"
            exact -> "EXACT-LANE CLAIM READY"
            else -> "LIKELY LANE · UNCERTAIN"
        }
        laneOverlay = LaneOverlay(
            status = status,
            candidateCount = lanes.size,
            laneNumberFromLeft = topLane.index + 1,
            laneCount = sameSegment.size.coerceAtLeast(1),
            confidence = estimate.confidence.toFloat(),
            exactClaim = exact,
        )
        return mergeLaneOverlay(state)
    }

    private fun mergeLaneOverlay(state: GnssUiState): GnssUiState {
        val overlay = laneOverlay
        return state.copy(
            laneDataStatus = overlay.status,
            laneCandidateCount = overlay.candidateCount,
            likelyLaneNumberFromLeft = overlay.laneNumberFromLeft,
            likelyLaneCount = overlay.laneCount,
            laneConfidence = overlay.confidence,
            laneExactClaim = overlay.exactClaim,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Lane GPS drive recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps GNSS and motion-sensor drive tests recording with the screen off."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Lane GPS drive test recording")
            .setContentText("GNSS + motion + lane matching. Screen can be off.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LaneGPS:DriveTest",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private data class LaneOverlay(
        val status: String = "API NOT CONFIGURED",
        val candidateCount: Int = 0,
        val laneNumberFromLeft: Int? = null,
        val laneCount: Int? = null,
        val confidence: Float = 0f,
        val exactClaim: Boolean = false,
    )

    companion object {
        const val ACTION_START = "com.example.gps.action.START_DRIVE_TEST"
        const val ACTION_RESUME = "com.example.gps.action.RESUME_DRIVE_TEST"
        const val ACTION_STOP = "com.example.gps.action.STOP_DRIVE_TEST"
        const val EXTRA_RESET = "reset_session"

        private const val CHANNEL_ID = "lane_gps_drive_test"
        private const val NOTIFICATION_ID = 4107
        private const val PERSIST_INTERVAL_MS = 1000L
        private const val LANE_FETCH_INTERVAL_MS = 15_000L
        private const val LANE_FETCH_RADIUS_M = 900
    }
}
