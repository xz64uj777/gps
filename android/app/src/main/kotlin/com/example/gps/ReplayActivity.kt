package com.example.gps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
import com.example.gps.location.DriveReplayCsv
import com.example.gps.location.DriveReplaySample
import com.example.gps.replay.ReplayLaneAnalysis
import com.example.gps.replay.ReplayLaneAnalyzer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class ReplayActivity : ComponentActivity() {
    private var samples by mutableStateOf<List<DriveReplaySample>>(emptyList())
    private var sampleIndex by mutableStateOf(0)
    private var playing by mutableStateOf(false)
    private var fileName by mutableStateOf<String?>(null)
    private var loadError by mutableStateOf<String?>(null)
    private var currentAnalysis by mutableStateOf<ReplayLaneAnalysis?>(null)
    private var analysisBusy by mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private val analyzer = ReplayLaneAnalyzer()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val analysisGeneration = AtomicInteger(0)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadReplay(uri)
    }

    private val replayTick = object : Runnable {
        override fun run() {
            if (!playing || samples.isEmpty()) return
            if (sampleIndex >= samples.lastIndex) {
                playing = false
                return
            }
            val current = samples[sampleIndex]
            val next = samples[sampleIndex + 1]
            sampleIndex++
            scheduleLaneAnalysis()
            val sourceDelay = (next.recordedAtMillis - current.recordedAtMillis).coerceAtLeast(1L)
            val replayDelay = (sourceDelay / REPLAY_SPEED).coerceIn(MIN_TICK_MS, MAX_TICK_MS)
            handler.postDelayed(this, replayDelay)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ReplayScreen(
                    samples = samples,
                    index = sampleIndex,
                    playing = playing,
                    fileName = fileName,
                    error = loadError,
                    analysis = currentAnalysis,
                    analysisBusy = analysisBusy,
                    onLoad = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                    onPrevious = { step(-1) },
                    onNext = { step(1) },
                    onPlayPause = { togglePlayback() },
                    onRestart = { restartReplay() },
                )
            }
        }

        intent?.data?.let(::loadReplay)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::loadReplay)
    }

    override fun onStop() {
        playing = false
        handler.removeCallbacks(replayTick)
        super.onStop()
    }

    override fun onDestroy() {
        analysisGeneration.incrementAndGet()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadReplay(uri: Uri) {
        playing = false
        handler.removeCallbacks(replayTick)
        loadError = null
        currentAnalysis = null
        analysisBusy = false
        try {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Unable to open this CSV")
            val parsed = DriveReplayCsv.parse(text)
            if (parsed.isEmpty()) error("No Lane GPS samples found in this CSV")
            samples = parsed
            sampleIndex = 0
            fileName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Drive replay"
            analyzer.reset(clearMapCache = true)
            scheduleLaneAnalysis()
        } catch (exc: Exception) {
            samples = emptyList()
            sampleIndex = 0
            fileName = null
            loadError = exc.message ?: exc.javaClass.simpleName
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun togglePlayback() {
        if (samples.isEmpty()) return
        if (sampleIndex >= samples.lastIndex) {
            sampleIndex = 0
            analyzer.reset(clearMapCache = false)
            scheduleLaneAnalysis()
        }
        playing = !playing
        handler.removeCallbacks(replayTick)
        if (playing) handler.post(replayTick)
    }

    private fun step(delta: Int) {
        playing = false
        handler.removeCallbacks(replayTick)
        if (samples.isEmpty()) return
        val next = (sampleIndex + delta).coerceIn(0, samples.lastIndex)
        if (next < sampleIndex) analyzer.reset(clearMapCache = false)
        sampleIndex = next
        scheduleLaneAnalysis()
    }

    private fun restartReplay() {
        playing = false
        handler.removeCallbacks(replayTick)
        sampleIndex = 0
        analyzer.reset(clearMapCache = false)
        scheduleLaneAnalysis()
    }

    private fun scheduleLaneAnalysis() {
        val sample = samples.getOrNull(sampleIndex) ?: run {
            currentAnalysis = null
            analysisBusy = false
            return
        }
        val generation = analysisGeneration.incrementAndGet()
        analysisBusy = true
        analysisExecutor.execute {
            if (generation != analysisGeneration.get()) return@execute
            val result = try {
                analyzer.analyze(sample)
            } catch (exc: Exception) {
                ReplayLaneAnalysis(
                    status = "CURRENT ENGINE ERROR · ${exc.message ?: exc.javaClass.simpleName}",
                )
            }
            if (generation != analysisGeneration.get()) return@execute
            runOnUiThread {
                if (generation == analysisGeneration.get()) {
                    currentAnalysis = result
                    analysisBusy = false
                }
            }
        }
    }

    private companion object {
        const val REPLAY_SPEED = 4L
        const val MIN_TICK_MS = 50L
        const val MAX_TICK_MS = 1000L
    }
}

@Composable
private fun ReplayScreen(
    samples: List<DriveReplaySample>,
    index: Int,
    playing: Boolean,
    fileName: String?,
    error: String?,
    analysis: ReplayLaneAnalysis?,
    analysisBusy: Boolean,
    onLoad: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
) {
    val sample = samples.getOrNull(index)
    val state = sample?.state
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF090E14))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Lane GPS · Trip Lab", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 25.sp)
        Text(
            "Recorded drive vs current lane engine · 4× replay",
            color = Color(0xFF9DA7B3),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onLoad) { Text(if (samples.isEmpty()) "LOAD DRIVE CSV" else "LOAD DIFFERENT CSV") }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text("Could not load replay: $error", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
        }

        if (sample == null || state == null) {
            Spacer(Modifier.height(18.dp))
            Text(
                "Choose any LaneGPS-drive CSV. The Trip Lab replays the recorded telemetry and also asks the current LaneMatcher + current OpenStreetMap data what lane it would choose now.",
                color = Color(0xFFB8C1CC),
            )
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        Text(fileName ?: "Drive replay", color = Color(0xFF8ED7FF), fontWeight = FontWeight.Bold)
        Text(
            "Sample ${index + 1} / ${samples.size} · ${((index + 1) * 100f / samples.size).roundToInt()}%",
            color = Color(0xFFB8C1CC),
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPrevious, enabled = index > 0, modifier = Modifier.weight(1f)) { Text("◀") }
            Button(onClick = onPlayPause, modifier = Modifier.weight(1.6f)) { Text(if (playing) "PAUSE" else "PLAY") }
            Button(onClick = onNext, enabled = index < samples.lastIndex, modifier = Modifier.weight(1f)) { Text("▶") }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onRestart, enabled = index > 0) { Text("RESTART") }

        Spacer(Modifier.height(18.dp))
        ReplaySectionTitle("GNSS health")
        val age = sample.fixAgeMillis
        val health = when {
            age != null && age > 3000L -> "STALE"
            (state.accuracyMeters ?: Float.POSITIVE_INFINITY) > 20f -> "DEGRADED"
            else -> "GOOD"
        }
        val healthColor = when (health) {
            "GOOD" -> Color(0xFF7EE787)
            "DEGRADED" -> Color(0xFFFFCC80)
            else -> Color(0xFFFF8A80)
        }
        Text(health, color = healthColor, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        ReplayRow("Recorded", formatClock(sample.recordedAtMillis))
        ReplayRow("Fix time", sample.fixTimestampMillis?.let(::formatClock) ?: "—")
        ReplayRow("Fix age", age?.let { if (it < 1000L) "${it} ms" else "%.1f s".format(it / 1000f) } ?: "—")
        ReplayRow("Accuracy", state.accuracyMeters?.let { "%.1f m".format(it) } ?: "—")
        ReplayRow("Quality", "${state.qualityScore}/100 · ${state.qualityLabel}")
        ReplayRow("Satellites", "${state.satellitesUsedInFix}/${state.satellitesVisible}")
        ReplayRow("Speed", state.speedMps?.let { "%.1f mph".format(it * 2.236936f) } ?: "—")
        ReplayRow("GNSS bearing", state.bearingDegrees?.let { "%.0f°".format(it) } ?: "—")

        Spacer(Modifier.height(16.dp))
        ReplaySectionTitle("Motion")
        ReplayRow("Sensor heading", state.sensorHeadingDegrees?.let { "%.0f°".format(it) } ?: "—")
        ReplayRow("Fused heading", state.fusedHeadingDegrees?.let { "%.0f°".format(it) } ?: "—")
        ReplayRow("Lateral", state.lateralAccelerationMps2?.let { "%+.2f m/s²".format(it) } ?: "—")
        ReplayRow("Yaw", state.yawRateDegS?.let { "%+.1f°/s".format(it) } ?: "—")
        ReplayRow("Motion", state.motionHint)

        Spacer(Modifier.height(18.dp))
        ReplaySectionTitle("Lane decision · recorded vs current")
        val recordedLane = laneText(state.likelyLaneNumberFromLeft, state.likelyLaneCount)
        ReplayRow("Recorded lane", recordedLane)
        ReplayRow("Recorded conf.", "${(state.laneConfidence * 100f).roundToInt()}%")
        ReplayRow("Recorded exact", if (state.laneExactClaim) "YES" else "NO")

        if (analysisBusy) {
            Spacer(Modifier.height(8.dp))
            Text("CURRENT ENGINE · ANALYZING…", color = Color(0xFF8ED7FF), fontWeight = FontWeight.Bold)
        }
        if (analysis != null) {
            val currentLane = laneText(analysis.laneNumberFromLeft, analysis.laneCount)
            ReplayRow("Current lane", currentLane)
            ReplayRow("Current conf.", "${(analysis.confidence * 100f).roundToInt()}%")
            ReplayRow("Current exact", if (analysis.exactClaim) "YES" else "NO")
            ReplayRow(
                "Heading used",
                analysis.headingDegrees?.let { "${"%.0f".format(it)}° · ${analysis.headingSource}" } ?: analysis.headingSource,
            )
            ReplayRow(
                "Phone/GPS Δ",
                analysis.headingDisagreementDegrees?.let { "%.0f°".format(it) } ?: "—",
            )
            ReplayRow("Map source", if (analysis.sourceConfidence > 0f) "${(analysis.sourceConfidence * 100f).roundToInt()}%" else "—")

            val comparison = when {
                analysis.laneNumberFromLeft == null -> "CURRENT ENGINE HAS NO LANE CLAIM"
                state.likelyLaneNumberFromLeft == null -> "CURRENT ENGINE FOUND A LANE"
                analysis.laneNumberFromLeft == state.likelyLaneNumberFromLeft -> "CURRENT ENGINE AGREES WITH RECORDED LANE"
                else -> "LANE DECISION CHANGED · REVIEW THIS POINT"
            }
            val comparisonColor = when {
                analysis.laneNumberFromLeft == state.likelyLaneNumberFromLeft && analysis.laneNumberFromLeft != null -> Color(0xFF7EE787)
                analysis.laneNumberFromLeft == null -> Color(0xFFFFCC80)
                else -> Color(0xFF8ED7FF)
            }
            Spacer(Modifier.height(7.dp))
            Text(comparison, color = comparisonColor, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Text(analysis.status, color = Color(0xFFB8C1CC), fontSize = 12.sp)
            Text(analysis.fetchStatus, color = Color(0xFF7D8895), fontSize = 11.sp)
        }

        Spacer(Modifier.height(18.dp))
        val displayLaneCount = (analysis?.laneCount ?: state.likelyLaneCount)?.coerceIn(1, 8) ?: 5
        val displayLikely = analysis?.laneNumberFromLeft ?: state.likelyLaneNumberFromLeft
        val displayExact = if (analysis != null) {
            if (analysis.exactClaim) analysis.laneNumberFromLeft else null
        } else {
            if (state.laneExactClaim) state.likelyLaneNumberFromLeft else null
        }
        Box(Modifier.fillMaxWidth().height(330.dp)) {
            ReplayLaneView(
                laneCount = displayLaneCount,
                exactLaneNumberFromLeft = displayExact,
                likelyLaneNumberFromLeft = displayLikely,
            )
        }
    }
}

private fun laneText(lane: Int?, count: Int?): String =
    if (lane != null && count != null) "$lane of $count" else "—"

@Composable
private fun ReplaySectionTitle(text: String) {
    Text(text, color = Color(0xFF8ED7FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun ReplayRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Color(0xFF7D8895), modifier = Modifier.width(118.dp))
        Text(value, color = Color.White)
    }
}

private fun formatClock(timestampMillis: Long): String =
    SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(timestampMillis))

@Composable
private fun ReplayLaneView(
    laneCount: Int,
    exactLaneNumberFromLeft: Int?,
    likelyLaneNumberFromLeft: Int?,
) {
    Canvas(Modifier.fillMaxSize()) {
        val horizonY = size.height * 0.13f
        val bottomY = size.height * 0.94f
        val horizonHalfWidth = size.width * 0.11f
        val bottomHalfWidth = size.width * 0.49f
        val centerX = size.width / 2f
        val highlightedLane = exactLaneNumberFromLeft ?: likelyLaneNumberFromLeft
        val highlightedIndex = highlightedLane?.minus(1)

        if (highlightedIndex != null && highlightedIndex in 0 until laneCount) {
            val leftT = highlightedIndex.toFloat() / laneCount
            val rightT = (highlightedIndex + 1).toFloat() / laneCount
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
            val bandColor = if (exactLaneNumberFromLeft != null) Color(0x663FB950) else Color(0x554C9AFF)
            drawPath(band, bandColor)
        }

        for (i in 0..laneCount) {
            val t = i.toFloat() / laneCount
            val topX = centerX - horizonHalfWidth + 2 * horizonHalfWidth * t
            val bottomX = centerX - bottomHalfWidth + 2 * bottomHalfWidth * t
            drawLine(
                color = Color(0xFF66717D),
                start = Offset(topX, horizonY),
                end = Offset(bottomX, bottomY),
                strokeWidth = if (i == 0 || i == laneCount) 7f else 3f,
                cap = StrokeCap.Round,
            )
        }
    }
}
