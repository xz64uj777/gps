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
import com.example.gps.lane.DirectOsmLaneClient
import com.example.gps.laneengine.GeoPoint
import com.example.gps.laneengine.Lane
import com.example.gps.laneengine.LaneMatcher
import com.example.gps.laneengine.Observation
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class DriveTrackingService : Service() {
    private lateinit var store: DriveSessionStore
    private var tracker: AndroidGnssTracker? = null
    @Volatile private var latestState: GnssUiState? = null
    private var lastPersistElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val laneExecutor = Executors.newSingleThreadExecutor()
    private val laneLock = Any()
    private var laneMatcher = LaneMatcher()
    @Volatile private var cachedLanes: List<Lane> = emptyList()
    @Volatile private var laneFetchInFlight = false
    private var lastLaneFetchSuccessElapsed = 0L
    private var lastLaneFetchAttemptElapsed = 0L
    private var lastLaneFetchPoint: GeoPoint? = null
    @Volatile private var laneFetchStatus = "WAITING"
    @Volatile private var laneOverlay = LaneOverlay(status = "OSM DIRECT · WAITING FOR LOCATION")

    override fun onCreate() {
        super.onCreate()
        store = DriveSessionStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndSave()
                return START_NOT_STICKY
            }
            ACTION_START -> startRecording(resetSession = intent.getBooleanExtra(EXTRA_RESET, true))
            ACTION_RESUME -> startRecording(resetSession = false)
            else -> {
                if (store.isActive()) startRecording(resetSession = false)
                else {
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
                laneOverlay = LaneOverlay(status = "OSM DIRECT · WAITING FOR LOCATION")
                laneFetchStatus = "WAITING"
                lastLaneFetchPoint = null
                lastLaneFetchSuccessElapsed = 0L
                lastLaneFetchAttemptElapsed = 0L
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

        val current = GeoPoint(lat, lon)
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaneFetchAttemptElapsed < LANE_FETCH_ATTEMPT_BACKOFF_MS) return

        val movedMeters = lastLaneFetchPoint?.let { distanceMeters(it, current) } ?: Double.POSITIVE_INFINITY
        val hasCache = cachedLanes.isNotEmpty()
        val minimumAge = if (hasCache) LANE_CACHE_MIN_AGE_MS else LANE_RETRY_MIN_AGE_MS
        val minimumMove = if (hasCache) LANE_CACHE_REFRESH_DISTANCE_M else 0.0
        if (
            lastLaneFetchSuccessElapsed > 0L &&
            now - lastLaneFetchSuccessElapsed < minimumAge &&
            movedMeters < minimumMove
        ) return

        lastLaneFetchAttemptElapsed = now
        laneFetchInFlight = true
        laneFetchStatus = "LOADING"
        laneOverlay = laneOverlay.copy(status = "LOADING OSM LANES DIRECTLY…")

        laneExecutor.execute {
            try {
                val corridor = DirectOsmLaneClient().fetchCorridor(lat, lon, LANE_FETCH_RADIUS_M)
                val endpointHost = corridor.endpoint
                    .substringAfter("://")
                    .substringBefore('/')
                    .take(30)
                synchronized(laneLock) {
                    val merged = mergeLaneCache(
                        existing = cachedLanes,
                        incoming = corridor.lanes,
                        anchor = current,
                    )
                    cachedLanes = merged
                    laneFetchStatus = "OK $endpointHost +${corridor.lanes.size} cache${merged.size}"
                    lastLaneFetchSuccessElapsed = SystemClock.elapsedRealtime()
                    lastLaneFetchPoint = current
                    laneOverlay = if (merged.isEmpty()) {
                        LaneOverlay(status = "NO USABLE OSM ROAD/LANE DATA NEARBY · FETCH $laneFetchStatus")
                    } else {
                        LaneOverlay(
                            status = "OSM LANE DATA LOADED · FETCH $laneFetchStatus",
                            candidateCount = merged.size,
                        )
                    }
                }
            } catch (exc: Exception) {
                val detail = exc.message?.take(90)?.replace('\n', ' ') ?: exc.javaClass.simpleName
                laneFetchStatus = "ERROR $detail"
                laneOverlay = laneOverlay.copy(status = "OSM DATA ERROR · $detail")
            } finally {
                laneFetchInFlight = false
            }

            val currentState = latestState ?: return@execute
            val mergedState = applyLaneMatch(currentState)
            latestState = mergedState
            DriveSessionRuntime.publish(mergedState)
        }
    }

    private fun mergeLaneCache(
        existing: List<Lane>,
        incoming: List<Lane>,
        anchor: GeoPoint,
    ): List<Lane> {
        val merged = LinkedHashMap<String, Lane>()
        existing.forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        return merged.values.filter { lane ->
            pointToPolylineMeters(anchor, lane.centerline) <= LANE_CACHE_RETAIN_DISTANCE_M
        }
    }

    private fun applyLaneMatch(state: GnssUiState): GnssUiState {
        val allLanes = cachedLanes
        val lat = state.latitude
        val lon = state.longitude
        val accuracy = state.accuracyMeters
        if (allLanes.isEmpty() || lat == null || lon == null || accuracy == null) {
            return mergeLaneOverlay(state)
        }

        val position = GeoPoint(lat, lon)
        val heading = (state.fusedHeadingDegrees ?: state.bearingDegrees)?.toDouble()
        val measured = allLanes.map { lane ->
            val distance = pointToPolylineMeters(position, lane.centerline)
            val laneHeading = laneBearingDegreesNearPoint(lane, position)
            val headingDiff =
                if (heading != null && laneHeading != null) angleDifferenceDegrees(heading, laneHeading)
                else 0.0
            Candidate(lane, distance, headingDiff)
        }
        val withinDistance = measured.filter { it.distanceMeters <= CANDIDATE_MAX_DISTANCE_M }
        val nearby = withinDistance
            .filter { candidate ->
                heading == null || candidate.headingErrorDegrees <= CANDIDATE_MAX_HEADING_ERROR_DEG
            }
            .sortedWith(compareBy<Candidate> { it.distanceMeters }.thenBy { it.headingErrorDegrees })
            .take(CANDIDATE_LIMIT)

        if (nearby.isEmpty()) {
            val rejection = if (withinDistance.isEmpty()) {
                val nearest = measured.minOfOrNull { it.distanceMeters }
                if (nearest == null || !nearest.isFinite()) "DISTANCE_UNKNOWN"
                else "DISTANCE_${String.format(java.util.Locale.US, "%.0f", nearest)}m"
            } else {
                val smallestHeadingError = withinDistance.minOfOrNull { it.headingErrorDegrees } ?: 999.0
                "HEADING_${String.format(java.util.Locale.US, "%.0f", smallestHeadingError)}deg"
            }
            laneOverlay = LaneOverlay(
                status = "OSM LOADED · NO PLAUSIBLE LANE · BLOCK $rejection · FETCH $laneFetchStatus",
                candidateCount = 0,
            )
            return mergeLaneOverlay(state)
        }

        val lanes = nearby.map { it.lane }
        val estimate = synchronized(laneLock) {
            laneMatcher.update(
                observation = Observation(
                    timestampMillis = state.lastUpdateMillis ?: System.currentTimeMillis(),
                    position = position,
                    horizontalAccuracyMeters = accuracy.toDouble(),
                    speedMps = (state.speedMps ?: 0f).toDouble(),
                    bearingDegrees = heading,
                    lateralAccelerationMps2 = state.lateralAccelerationMps2?.toDouble(),
                    sensorHeadingDegrees = state.sensorHeadingDegrees?.toDouble(),
                ),
                candidates = lanes,
            )
        }

        val topLane = lanes.firstOrNull { it.id == estimate.mostLikelyLaneId }
        if (topLane == null) {
            laneOverlay = LaneOverlay(
                status = "LANE CANDIDATES AVAILABLE · NO MATCH · BLOCK NO_MATCH · FETCH $laneFetchStatus",
                candidateCount = lanes.size,
            )
            return mergeLaneOverlay(state)
        }

        val sameSegment = allLanes.filter { it.segmentId == topLane.segmentId }
        val exact = estimate.claimExactLane && state.sensorLaneReady && accuracy <= 5f
        val exactBlocker = when {
            exact -> "READY"
            accuracy > 5f -> "GNSS_ACCURACY"
            !state.sensorLaneReady -> "SENSOR_GATE"
            topLane.sourceConfidence < EXACT_SOURCE_CONFIDENCE_MIN -> "MAP_SOURCE"
            estimate.confidence < EXACT_MATCH_CONFIDENCE_MIN -> "MATCH_CONFIDENCE"
            else -> "STABILITY_OR_TRANSITION"
        }
        val statusCore = when {
            accuracy > 5f -> "UNCERTAIN · GNSS ${"%.1f".format(accuracy)} m"
            exact -> "EXACT-LANE CLAIM READY"
            else -> "LIKELY LANE · UNCERTAIN"
        }
        val source = String.format(java.util.Locale.US, "%.2f", topLane.sourceConfidence)
        val status =
            "$statusCore · SRC $source · ID ${topLane.id} · BLOCK $exactBlocker · FETCH $laneFetchStatus"
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

    private fun pointToPolylineMeters(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        val lat0 = point.lat * PI / 180.0
        fun xy(p: GeoPoint): Pair<Double, Double> {
            val x = (p.lon - point.lon) * PI / 180.0 * EARTH_RADIUS_M * cos(lat0)
            val y = (p.lat - point.lat) * PI / 180.0 * EARTH_RADIUS_M
            return x to y
        }
        if (line.size == 1) {
            val (x, y) = xy(line.first())
            return hypot(x, y)
        }
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.lastIndex) {
            val (ax, ay) = xy(line[i])
            val (bx, by) = xy(line[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val denom = dx * dx + dy * dy
            val t = if (denom <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / denom).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    private fun laneBearingDegreesNearPoint(lane: Lane, point: GeoPoint): Double? {
        if (lane.centerline.size < 2) return null
        var bestIndex = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in 0 until lane.centerline.lastIndex) {
            val segment = listOf(lane.centerline[i], lane.centerline[i + 1])
            val d = pointToPolylineMeters(point, segment)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        return initialBearingDegrees(lane.centerline[bestIndex], lane.centerline[bestIndex + 1])
    }

    private fun initialBearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dl = (b.lon - a.lon) * PI / 180.0
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
    }

    private fun angleDifferenceDegrees(a: Double, b: Double): Double {
        val raw = abs((a - b + 180.0) % 360.0 - 180.0)
        return if (raw > 180.0) 360.0 - raw else raw
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dp = (b.lat - a.lat) * PI / 180.0
        val dl = (b.lon - a.lon) * PI / 180.0
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1.0 - h))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Lane GPS drive recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps GNSS, motion sensors, and lane matching active with the screen off."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Lane GPS drive test recording")
            .setContentText("GNSS + motion + direct OSM lane matching. Screen can be off.")
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
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private data class Candidate(
        val lane: Lane,
        val distanceMeters: Double,
        val headingErrorDegrees: Double,
    )

    private data class LaneOverlay(
        val status: String = "OSM DIRECT · WAITING FOR LOCATION",
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
        private const val LANE_FETCH_RADIUS_M = 1600
        private const val LANE_CACHE_MIN_AGE_MS = 120_000L
        private const val LANE_RETRY_MIN_AGE_MS = 30_000L
        private const val LANE_FETCH_ATTEMPT_BACKOFF_MS = 10_000L
        private const val LANE_CACHE_REFRESH_DISTANCE_M = 850.0
        private const val LANE_CACHE_RETAIN_DISTANCE_M = 4_500.0
        private const val CANDIDATE_MAX_DISTANCE_M = 45.0
        private const val CANDIDATE_MAX_HEADING_ERROR_DEG = 35.0
        private const val CANDIDATE_LIMIT = 20
        private const val EXACT_SOURCE_CONFIDENCE_MIN = 0.75
        private const val EXACT_MATCH_CONFIDENCE_MIN = 0.70
        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
