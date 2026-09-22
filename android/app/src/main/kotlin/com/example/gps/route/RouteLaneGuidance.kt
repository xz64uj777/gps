package com.example.gps.route

import org.json.JSONArray
import org.json.JSONObject

/** Lane order is left-to-right in the direction of travel, including invalid lanes. */
data class RouteLane(val indications: List<String>, val valid: Boolean)

internal object RouteLaneGuidance {
    fun decode(array: JSONArray?): List<RouteLane> {
        if (array == null || array.length() !in 1..12) return emptyList()
        return List(array.length()) { i ->
            val lane = array.optJSONObject(i) ?: return emptyList()
            // Missing validity must not silently turn an unknown layout into guidance.
            if (!lane.has("valid")) return emptyList()
            val arrows = lane.optJSONArray("indications") ?: return emptyList()
            RouteLane(List(arrows.length()) { arrows.optString(it) }, lane.optBoolean("valid", false))
        }
    }

    fun encode(lanes: List<RouteLane>) = JSONArray().apply {
        lanes.forEach { put(JSONObject().put("valid", it.valid).put("indications", JSONArray(it.indications))) }
    }

    fun forStep(step: JSONObject): List<RouteLane> {
        val maneuver = step.optJSONObject("maneuver")?.optJSONArray("location") ?: return emptyList()
        val first = step.optJSONArray("intersections")?.optJSONObject(0) ?: return emptyList()
        val at = first.optJSONArray("location") ?: return emptyList()
        // Never borrow lanes from a later intersection on the outgoing road.
        if (kotlin.math.abs(at.optDouble(0) - maneuver.optDouble(0)) > 0.00001 ||
            kotlin.math.abs(at.optDouble(1) - maneuver.optDouble(1)) > 0.00001) return emptyList()
        return decode(first.optJSONArray("lanes"))
    }

    fun visible(distance: Double, speedMps: Float?, arrived: Boolean): Boolean =
        !arrived && distance.isFinite() && distance >= 0 &&
            distance <= maxOf(700.0, ((speedMps ?: 0f).coerceIn(0f, 55f) * 65.0)).coerceAtMost(2500.0)

    fun actionable(lanes: List<RouteLane>, maneuver: String?): Boolean {
        if (lanes.size < 2) return false
        val validCount = lanes.count { it.valid }
        if (validCount == 0) return false
        // A restricted set of valid lanes is always actionable guidance.
        if (validCount < lanes.size) return true
        // If every lane is valid, only keep the panel for an actual directional maneuver.
        val text = maneuver.orEmpty().lowercase()
        return listOf("turn", "exit", "merge", "keep", "fork", "slight", "u-turn", "uturn")
            .any { it in text }
    }

    fun hints(lanes: List<RouteLane>): List<String> = lanes.map { lane ->
        lane.indications.joinToString(";") { when (it) { "straight" -> "through"; "uturn" -> "reverse"; "none" -> ""; else -> it.replace(' ', '_') } }
    }

    fun currentLane(lanes: List<RouteLane>, currentCount: Int?, currentHints: List<String>,
        lane: Int?, exact: Boolean, distance: Double): Int? {
        // The approach may gain/drop lanes. Count alone does not establish correspondence.
        if (!exact || distance !in 0.0..200.0 || lanes.size != currentCount || lane == null || lane !in 1..lanes.size) return null
        fun normalized(hints: List<String>) = hints.map { it.split(';').filter { v -> v.isNotBlank() }.toSet() }
        return lane.takeIf { normalized(hints(lanes)) == normalized(currentHints) && currentHints.any { it.isNotBlank() } }
    }
}
