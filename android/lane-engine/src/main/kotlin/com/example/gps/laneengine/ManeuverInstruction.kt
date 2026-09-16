package com.example.gps.laneengine

/** Preserve OSRM maneuver type before applying its direction modifier. */
object ManeuverInstruction {
    fun symbol(instruction: String): String {
        val label = instruction.lowercase(java.util.Locale.ROOT)
        return when {
            "arriv" in label || "destination" in label -> "⚑"
            "u-turn" in label -> "↶"
            "roundabout" in label -> "◎"
            "slight left" in label || ("left" in label && ("fork" in label || "merge" in label || "ramp" in label || "exit" in label)) -> "↖"
            "slight right" in label || ("right" in label && ("fork" in label || "merge" in label || "ramp" in label || "exit" in label)) -> "↗"
            "left" in label -> "↰"
            "right" in label -> "↱"
            else -> "↑"
        }
    }

    fun text(type: String, modifier: String, exits: String = ""): String {
        val side = when (modifier) {
            "slight left", "left", "sharp left" -> "left"
            "slight right", "right", "sharp right" -> "right"
            else -> ""
        }
        val sideSuffix = if (side.isEmpty()) "" else " on the $side"
        return when (type) {
            "off ramp" -> "Take the exit" + exits.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty() + sideSuffix
            "on ramp" -> "Take the ramp$sideSuffix"
            "merge" -> if (side.isEmpty()) "Merge" else "Merge $side"
            "fork" -> if (side.isEmpty()) "Continue at the fork" else "Keep $side at the fork"
            "roundabout", "rotary" -> "Enter the roundabout"
            "arrive" -> "Arrive at destination"
            "new name", "notification" -> "Continue"
            else -> when (modifier) {
                "slight left" -> "Slight left"
                "left" -> "Turn left"
                "sharp left" -> "Sharp left"
                "slight right" -> "Slight right"
                "right" -> "Turn right"
                "sharp right" -> "Sharp right"
                "straight" -> "Continue straight"
                "uturn" -> "Make a U-turn"
                else -> "Continue"
            }
        }
    }
}
