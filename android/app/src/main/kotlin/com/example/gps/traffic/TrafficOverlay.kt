package com.example.gps.traffic

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.*

/** Optional user-owned traffic credentials stay on this device, outside backups and logs. */
class TrafficSettings(context: Context) {
    private val file = File(context.noBackupFilesDir, "traffic-key")
    private val prefs = context.getSharedPreferences("traffic", Context.MODE_PRIVATE)
    fun key(): String = runCatching { file.readText().trim() }.getOrDefault("")
    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun save(key: String, enabled: Boolean) {
        if (key.isBlank()) file.delete() else file.writeText(key.trim())
        prefs.edit().putBoolean("enabled", enabled && key.isNotBlank()).apply()
    }
}

object TrafficTiles {
    fun template(key: String): String = "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?tileSize=256&key=" +
        URLEncoder.encode(key.trim(), "UTF-8")
    fun probeUrl(key: String, lat: Double, lon: Double): String {
        val z = 10
        val n = 1 shl z
        val x = floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt().coerceIn(0, n-1)
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n-1)
        return template(key).replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
    }
    fun failure(code: Int): String = when (code) {
        401, 403 -> "Traffic key rejected · check Options"
        429 -> "Traffic usage limit reached"
        else -> "Traffic unavailable · retrying"
    }
}

class TrafficOverlay(context: Context, private val status: (String) -> Unit) {
    private val settings = TrafficSettings(context)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var map: MapLibreMap? = null
    private var active = false
    private var generation = 0
    private val refresh = Runnable { reload() }
    fun attach(map: MapLibreMap) { this.map = map; reload() }
    fun resume() { active = true; reload() }
    fun pause() { active = false; generation++; handler.removeCallbacks(refresh); remove() }
    fun destroy() { pause(); executor.shutdownNow(); map = null }
    private fun remove() { map?.style?.let { it.removeLayer(LAYER); it.removeSource(SOURCE) } }
    fun reload() {
        handler.removeCallbacks(refresh)
        val ticket = ++generation
        remove()
        val key = settings.key()
        if (!settings.enabled() || key.isBlank()) {
            status(if (key.isBlank()) "Traffic · setup in Options" else "Traffic off")
            return
        }
        if (!active) return
        val current = map ?: return
        if (current.style == null) return
        status("Traffic · connecting…")
        val target = current.cameraPosition.target ?: return
        executor.execute {
            val result = runCatching {
                val connection = URL(TrafficTiles.probeUrl(key, target.latitude, target.longitude)).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8000; connection.readTimeout = 8000
                    connection.useCaches = false
                    val code = connection.responseCode
                    if (code != 200) TrafficTiles.failure(code)
                    else {
                        val bytes = ByteArray(8)
                        java.io.DataInputStream(connection.inputStream).use { it.readFully(bytes) }
                        if (bytes.contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) null
                        else "Traffic response unavailable"
                    }
                } finally { connection.disconnect() }
            }.getOrElse { "Traffic offline · retrying" }
            handler.post {
                if (ticket != generation || !active) return@post
                if (result == null) {
                    current.style?.let { style ->
                        val tiles = TileSet("2.2.0", TrafficTiles.template(key))
                        tiles.attribution = "© TomTom"
                        style.addSource(RasterSource(SOURCE, tiles, 256))
                        // Congestion sits below navigation so route and position stay legible.
                        style.addLayerBelow(RasterLayer(LAYER, SOURCE), "lanegps-route-layer")
                    }
                    status("Traffic on · © TomTom")
                } else status(result)
                handler.postDelayed(refresh, 120_000L)
            }
        }
    }
    private companion object { const val SOURCE = "traffic-flow"; const val LAYER = "traffic-flow-layer" }
}
