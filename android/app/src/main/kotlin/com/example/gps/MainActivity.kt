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
import com.example.gps.location.AndroidGnssTracker
import com.example.gps.location.GnssUiState

class MainActivity : ComponentActivity() {
    private var tracker: AndroidGnssTracker? = null
    private var uiState by mutableStateOf(GnssUiState())

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            tracker?.refreshPermissionState()
            tracker?.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tracker = AndroidGnssTracker(this) { state ->
            runOnUiThread { uiState = state }
        }

        setContent {
            MaterialTheme {
                NavigationDebugScreen(
                    state = uiState,
                    requestPermission = { requestLocationPermission() }
                )
            }
        }

        if (hasAnyLocationPermission()) {
            tracker?.start()
        } else {
            requestLocationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        tracker?.start()
    }

    override fun onPause() {
        tracker?.stop()
        super.onPause()
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }
}

@Composable
private fun NavigationDebugScreen(
    state: GnssUiState,
    requestPermission: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
    ) {
        Text(
            "Lane GPS · Live GNSS",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(state.message, color = statusColor(state), fontSize = 18.sp)

        if (!state.permissionFine) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.permissionCoarse)
                    "Approximate permission is active. Android Settings → Apps → Lane GPS → Permissions → Location → Precise."
                else
                    "Location permission has not been granted.",
                color = Color(0xFFFFCC80)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = requestPermission) {
                Text("Request location permission")
            }
        }

        Spacer(Modifier.height(14.dp))
        DebugRow("Latitude", state.latitude?.let { "%.7f".format(it) } ?: "—")
        DebugRow("Longitude", state.longitude?.let { "%.7f".format(it) } ?: "—")
        DebugRow("Accuracy", state.accuracyMeters?.let { "%.1f m".format(it) } ?: "—")
        DebugRow("Speed", state.speedMps?.let { "%.1f m/s".format(it) } ?: "—")
        DebugRow("Bearing", state.bearingDegrees?.let { "%.0f°".format(it) } ?: "—")
        DebugRow("Provider", state.provider ?: "—")
        DebugRow("Satellites", "${state.satellitesUsedInFix}/${state.satellitesVisible} used")
        DebugRow("Precise", if (state.permissionFine) "YES" else "NO")

        Spacer(Modifier.height(18.dp))

        Text(
            "Lane data: NOT LOADED",
            color = Color(0xFFFFCC80),
            fontWeight = FontWeight.Bold
        )
        Text(
            "The lane renderer below is still a visualization placeholder until the OSM lane graph is connected.",
            color = Color(0xFFB8C1CC),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.BottomCenter
        ) {
            ForwardLaneView(5, 2, setOf(1, 2))
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Color(0xFF8B949E), modifier = Modifier.width(110.dp))
        Text(value, color = Color.White)
    }
}

private fun statusColor(state: GnssUiState): Color =
    when {
        state.fixReceived -> Color(0xFF7EE787)
        !state.permissionFine -> Color(0xFFFFCC80)
        else -> Color(0xFF8ED7FF)
    }

@Composable
private fun ForwardLaneView(
    laneCount: Int,
    currentLaneIndex: Int,
    recommendedLaneIndices: Set<Int>,
) {
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

        for (lane in recommendedLaneIndices) {
            val left = lane.toFloat() / laneCount
            val right = (lane + 1).toFloat() / laneCount
            val path = Path().apply {
                moveTo(centerX - horizonHalfWidth + 2 * horizonHalfWidth * left, horizonY)
                lineTo(centerX - horizonHalfWidth + 2 * horizonHalfWidth * right, horizonY)
                lineTo(centerX - bottomHalfWidth + 2 * bottomHalfWidth * right, bottomY)
                lineTo(centerX - bottomHalfWidth + 2 * bottomHalfWidth * left, bottomY)
                close()
            }
            drawPath(path, Color(0x4433B5E5))
        }

        val laneCenter = (currentLaneIndex + 0.5f) / laneCount
        val carX = centerX - bottomHalfWidth + 2 * bottomHalfWidth * laneCenter
        val carY = bottomY - 30f
        drawCircle(Color.White, 14f, Offset(carX, carY))
        drawCircle(
            Color(0xFF33B5E5),
            20f,
            Offset(carX, carY),
            style = Stroke(5f)
        )
    }
}
