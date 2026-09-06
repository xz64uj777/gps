package com.example.gps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.gps.laneengine.Lane
import com.example.gps.laneengine.LaneEstimate
import com.example.gps.laneengine.LaneMatcher
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var telemetry by mutableStateOf(LiveTelemetry())
    private var locationPermissionVersion by mutableIntStateOf(0)

    private val laneMatcher = LaneMatcher()

    // This intentionally remains empty until the route-corridor cache supplies real OSM lanes.
    // Real phone observations are already passed through LaneMatcher below.
    private var activeLaneCandidates: List<Lane> = emptyList()

    private lateinit var observationSource: AndroidObservationSource

    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            locationPermissionVersion++
            observationSource.stop()
            if (hasAnyLocationPermission()) observationSource.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        observationSource = AndroidObservationSource(this) { sample ->
            telemetry = sample
        }

        setContent {
            MaterialTheme {
                val permissionVersion = locationPermissionVersion
                val permissionState = remember(permissionVersion) { currentPermissionState() }
                val estimate = remember(telemetry.observation, activeLaneCandidates) {
                    telemetry.observation?.let { observation ->
                        laneMatcher.update(observation, activeLaneCandidates)
                    } ?: LaneEstimate(null, 0.0, emptyList(), false)
                }

                LiveNavigationScreen(
                    telemetry = telemetry,
                    estimate = estimate,
                    permissionState = permissionState,
                    requestLocation = {
                        locationPermissionRequest.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        locationPermissionVersion++
        if (hasAnyLocationPermission()) observationSource.start()
    }

    override fun onStop() {
        observationSource.stop()
        super.onStop()
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun currentPermissionState(): LocationPermissionState = when {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED -> LocationPermissionState.PRECISE
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED -> LocationPermissionState.APPROXIMATE
        else -> LocationPermissionState.NONE
    }
}

private enum class LocationPermissionState {
    NONE,
    APPROXIMATE,
    PRECISE,
}

@Composable
private fun LiveNavigationScreen(
    telemetry: LiveTelemetry,
    estimate: LaneEstimate,
    permissionState: LocationPermissionState,
    requestLocation: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(18.dp)
    ) {
        Text("Lane GPS · live sensors", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(8.dp))

        when (permissionState) {
            LocationPermissionState.NONE -> PermissionPrompt(
                title = "Precise location is required for lane-level navigation.",
                detail = "Android can still grant approximate location; the app will degrade safely and will not claim an exact lane.",
                buttonText = "Enable location",
                onClick = requestLocation,
            )
            LocationPermissionState.APPROXIMATE -> PermissionPrompt(
                title = "Approximate location enabled",
                detail = "Lane matching will remain uncertainty-first. Grant precise location for GNSS fixes and satellite status.",
                buttonText = "Upgrade to precise",
                onClick = requestLocation,
            )
            LocationPermissionState.PRECISE -> {
                LiveStatus(telemetry)
                Spacer(Modifier.height(16.dp))

                Text(
                    "Lane corridor not loaded yet",
                    color = Color(0xFFFFD180),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    "Real observations are flowing into LaneMatcher, but no OSM route-lane candidates exist on-device yet.",
                    color = Color(0xFFB8C1CC),
                )

                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ForwardLaneView(
                        laneCount = 5,
                        currentLaneIndex = null,
                        recommendedLaneIndices = emptySet(),
                    )
                }

                Text(
                    if (estimate.claimExactLane) {
                        "Lane ${estimate.mostLikelyLaneId} · confidence ${(estimate.confidence * 100).toInt()}%"
                    } else {
                        "Current lane: uncertain (no real lane corridor candidates)"
                    },
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PermissionPrompt(
    title: String,
    detail: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(detail, color = Color(0xFFB8C1CC))
    Spacer(Modifier.height(16.dp))
    Button(onClick = onClick) {
        Text(buttonText)
    }
}

@Composable
private fun LiveStatus(telemetry: LiveTelemetry) {
    val observation = telemetry.observation
    val accel = telemetry.accelerometerMps2
    val gyro = telemetry.gyroscopeRadS

    Text(
        when {
            telemetry.error != null -> "Location error: ${telemetry.error}"
            !telemetry.locationEnabled -> "Device location is disabled"
            observation == null -> "Waiting for GNSS fix…"
            else -> "Live fix · ${telemetry.provider ?: "location"}"
        },
        color = if (telemetry.error == null) Color(0xFF8ED7FF) else Color(0xFFFF8A80),
        fontSize = 18.sp,
    )

    if (observation != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Accuracy ${format1(observation.horizontalAccuracyMeters)} m · " +
                "speed ${format1(observation.speedMps)} m/s · " +
                "heading ${formatHeading(observation.bearingDegrees)}",
            color = Color.White,
        )
        Text(
            "GNSS satellites used: ${telemetry.satellitesUsedInFix?.toString() ?: "—"}",
            color = Color(0xFFB8C1CC),
        )
    }

    if (accel != null || gyro != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Accel xyz ${formatTriple(accel)} m/s²",
            color = Color(0xFFB8C1CC),
            fontSize = 13.sp,
        )
        Text(
            "Gyro xyz ${formatTriple(gyro)} rad/s · sensor heading ${formatHeading(telemetry.sensorHeadingDegrees)}",
            color = Color(0xFFB8C1CC),
            fontSize = 13.sp,
        )
    }
}

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatHeading(value: Double?): String =
    value?.let { "${String.format(Locale.US, "%.0f", it)}°" } ?: "—"

private fun formatTriple(value: Triple<Double, Double, Double>?): String =
    value?.let { "(${format1(it.first)}, ${format1(it.second)}, ${format1(it.third)})" } ?: "—"

@Composable
private fun ForwardLaneView(
    laneCount: Int,
    currentLaneIndex: Int?,
    recommendedLaneIndices: Set<Int>,
) {
    Canvas(Modifier.fillMaxSize()) {
        val hy = size.height * 0.16f
        val by = size.height * 0.92f
        val hh = size.width * 0.12f
        val bh = size.width * 0.48f
        val cx = size.width / 2f

        for (i in 0..laneCount) {
            val t = i.toFloat() / laneCount
            val tx = cx - hh + 2 * hh * t
            val bx = cx - bh + 2 * bh * t
            drawLine(
                Color(0xFF6C737F),
                Offset(tx, hy),
                Offset(bx, by),
                strokeWidth = if (i == 0 || i == laneCount) 7f else 3f,
                cap = StrokeCap.Round,
            )
        }

        for (lane in recommendedLaneIndices) {
            val l = lane.toFloat() / laneCount
            val r = (lane + 1).toFloat() / laneCount
            val p = Path().apply {
                moveTo(cx - hh + 2 * hh * l, hy)
                lineTo(cx - hh + 2 * hh * r, hy)
                lineTo(cx - bh + 2 * bh * r, by)
                lineTo(cx - bh + 2 * bh * l, by)
                close()
            }
            drawPath(p, Color(0x4433B5E5))
        }

        if (currentLaneIndex != null) {
            val t = (currentLaneIndex + 0.5f) / laneCount
            val x = cx - bh + 2 * bh * t
            val y = by - 30f
            drawCircle(Color.White, 14f, Offset(x, y))
            drawCircle(Color(0xFF33B5E5), 20f, Offset(x, y), style = Stroke(5f))
        }
    }
}
