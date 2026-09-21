package com.example.gps.streetview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** User-owned Google Street View credentials stay on-device and outside backups. */
class StreetViewSettings(context: Context) {
    private val file = File(context.noBackupFilesDir, "streetview-key")
    private val prefs = context.getSharedPreferences("streetview", Context.MODE_PRIVATE)

    fun key(): String = runCatching { file.readText().trim() }.getOrDefault("")
    fun enabled(): Boolean = prefs.getBoolean("enabled", false)

    fun save(key: String, enabled: Boolean) {
        if (key.isBlank()) file.delete() else file.writeText(key.trim())
        prefs.edit().putBoolean("enabled", enabled && key.isNotBlank()).apply()
    }
}

object StreetViewStaticUrls {
    fun metadataUrl(key: String, lat: Double, lon: Double): String =
        "https://maps.googleapis.com/maps/api/streetview/metadata?location=" +
            String.format(java.util.Locale.US, "%.7f,%.7f", lat, lon) +
            "&key=" + URLEncoder.encode(key.trim(), "UTF-8")

    fun imageUrl(
        key: String,
        panoId: String,
        heading: Double,
        width: Int = 640,
        height: Int = 360,
    ): String =
        "https://maps.googleapis.com/maps/api/streetview?size=" + width + "x" + height +
            "&pano=" + URLEncoder.encode(panoId, "UTF-8") +
            "&heading=" + String.format(java.util.Locale.US, "%.1f", heading) +
            "&fov=80&pitch=0&return_error_code=true&key=" +
            URLEncoder.encode(key.trim(), "UTF-8")

    fun bearingDegrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val phi1 = Math.toRadians(fromLat)
        val phi2 = Math.toRadians(toLat)
        val deltaLon = Math.toRadians(toLon - fromLon)
        val y = sin(deltaLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

data class StreetViewPreview(
    val bitmap: Bitmap,
    val copyright: String,
)

class StreetViewStaticClient {
    sealed interface Result {
        data class Success(val preview: StreetViewPreview) : Result
        data object NoImagery : Result
        data class Error(val message: String) : Result
    }

    fun load(key: String, destinationLat: Double, destinationLon: Double): Result {
        if (key.isBlank()) return Result.Error("Street View key missing")
        val metadataConnection = open(StreetViewStaticUrls.metadataUrl(key, destinationLat, destinationLon))
        val metadataText = try {
            val code = metadataConnection.responseCode
            if (code != 200) return Result.Error(httpMessage(code))
            metadataConnection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return Result.Error("Street View unavailable")
        } finally {
            metadataConnection.disconnect()
        }

        val metadata = runCatching { JSONObject(metadataText) }.getOrNull()
            ?: return Result.Error("Street View metadata unavailable")
        return when (metadata.optString("status")) {
            "ZERO_RESULTS", "NOT_FOUND" -> Result.NoImagery
            "OK" -> {
                val panoId = metadata.optString("pano_id")
                val location = metadata.optJSONObject("location")
                if (panoId.isBlank() || location == null) return Result.NoImagery
                val panoLat = location.optDouble("lat", Double.NaN)
                val panoLon = location.optDouble("lng", Double.NaN)
                if (!panoLat.isFinite() || !panoLon.isFinite()) return Result.NoImagery
                val heading = StreetViewStaticUrls.bearingDegrees(
                    panoLat, panoLon, destinationLat, destinationLon
                )
                val imageConnection = open(StreetViewStaticUrls.imageUrl(key, panoId, heading))
                try {
                    val code = imageConnection.responseCode
                    if (code != 200) return Result.Error(httpMessage(code))
                    val bitmap = BitmapFactory.decodeStream(imageConnection.inputStream)
                        ?: return Result.Error("Street View image unavailable")
                    Result.Success(
                        StreetViewPreview(
                            bitmap = bitmap,
                            copyright = metadata.optString("copyright").ifBlank { "© Google" },
                        )
                    )
                } catch (_: Exception) {
                    Result.Error("Street View unavailable")
                } finally {
                    imageConnection.disconnect()
                }
            }
            "REQUEST_DENIED" -> Result.Error("Street View key/API denied")
            "OVER_QUERY_LIMIT" -> Result.Error("Street View usage limit reached")
            else -> Result.Error("Street View unavailable")
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
        }

    private fun httpMessage(code: Int): String = when (code) {
        401, 403 -> "Street View key/API denied"
        429 -> "Street View usage limit reached"
        else -> "Street View unavailable"
    }
}
