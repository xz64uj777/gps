package com.example.gps.laneengine
import kotlin.math.*

internal object Geo {
    private const val R = 6_371_000.0

    fun initialBearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDifferenceDegrees(a: Double, b: Double): Double {
        val d = abs((a - b + 180.0) % 360.0 - 180.0)
        return if (d > 180.0) 360.0 - d else d
    }

    fun pointToPolylineMeters(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        val lat0 = Math.toRadians(point.lat)
        fun xy(p: GeoPoint): Pair<Double, Double> {
            val x = Math.toRadians(p.lon - point.lon) * R * cos(lat0)
            val y = Math.toRadians(p.lat - point.lat) * R
            return x to y
        }
        if (line.size == 1) {
            val (x, y) = xy(line.first())
            return hypot(x, y)
        }
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.lastIndex) {
            val (ax, ay) = xy(line[i]); val (bx, by) = xy(line[i + 1])
            val dx = bx - ax; val dy = by - ay
            val denom = dx * dx + dy * dy
            val t = if (denom == 0.0) 0.0 else ((-ax * dx - ay * dy) / denom).coerceIn(0.0, 1.0)
            best = min(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    fun laneBearingDegrees(lane: Lane): Double? =
        if (lane.centerline.size >= 2)
            initialBearingDegrees(lane.centerline.first(), lane.centerline.last())
        else null
}
