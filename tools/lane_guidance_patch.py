from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}: {old[:80]!r}")
    file.write_text(text.replace(old, new, 1))


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    file = ROOT / path
    text = file.read_text()
    a = text.find(start)
    b = text.find(end, a + len(start))
    if a < 0 or b < 0:
        raise RuntimeError(f"{path}: block markers not found")
    file.write_text(text[:a] + replacement + text[b:])


# Preserve explicit OSM turn:lanes tokens all the way into the runtime lane model.
replace_once(
    "android/lane-engine/src/main/kotlin/com/example/gps/laneengine/Models.kt",
    "    val changeRight: Boolean = true,\n    val sourceConfidence: Double = 1.0,\n",
    "    val changeRight: Boolean = true,\n    val turns: Set<String> = emptySet(),\n    val sourceConfidence: Double = 1.0,\n",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/lane/DirectOsmLaneClient.kt",
    """        return List(count) { index ->
            val change = changeValues.getOrNull(index)?.trim().orEmpty()
            LaneSpec(
                index = index,
                changeLeft = change !in setOf(\"no\", \"not_left\"),
                changeRight = change !in setOf(\"no\", \"not_right\"),
                confidence = confidence,
            )
        }
""",
    """        return List(count) { index ->
            val change = changeValues.getOrNull(index)?.trim().orEmpty()
            val turns = if (explicitTurns) {
                turnValues.getOrNull(index).orEmpty()
                    .split(';')
                    .map { it.trim().lowercase(java.util.Locale.ROOT) }
                    .filter { it.isNotEmpty() && it != \"none\" }
                    .toSet()
            } else {
                emptySet()
            }
            LaneSpec(
                index = index,
                changeLeft = change !in setOf(\"no\", \"not_left\"),
                changeRight = change !in setOf(\"no\", \"not_right\"),
                turns = turns,
                confidence = confidence,
            )
        }
""",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/lane/DirectOsmLaneClient.kt",
    """                changeLeft = spec.changeLeft,
                changeRight = spec.changeRight,
                sourceConfidence = spec.confidence,
""",
    """                changeLeft = spec.changeLeft,
                changeRight = spec.changeRight,
                turns = spec.turns,
                sourceConfidence = spec.confidence,
""",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/lane/DirectOsmLaneClient.kt",
    """        val changeLeft: Boolean,
        val changeRight: Boolean,
        val confidence: Double,
""",
    """        val changeLeft: Boolean,
        val changeRight: Boolean,
        val turns: Set<String>,
        val confidence: Double,
""",
)

# Runtime UI state carries a conservative per-lane turn hint list. Empty means unknown.
replace_once(
    "android/app/src/main/kotlin/com/example/gps/location/AndroidGnssTracker.kt",
    "    val likelyLaneCount: Int? = null,\n    val laneConfidence: Float = 0f,\n",
    "    val likelyLaneCount: Int? = null,\n    val laneTurnHints: List<String> = emptyList(),\n    val laneConfidence: Float = 0f,\n",
)

# Only publish turn hints when one unambiguous carriageway segment accounts for the
# displayed physical lanes. If parallel OSM ways had to be merged, target-lane output
# stays unavailable rather than guessing lane order.
replace_once(
    "android/app/src/main/kotlin/com/example/gps/location/DriveTrackingService.kt",
    """        val exactBlocker = when {
            exact -> \"READY\"
            accuracy > 5f -> \"GNSS_ACCURACY\"
            !state.sensorLaneReady -> \"SENSOR_GATE\"
            topLane.sourceConfidence < EXACT_SOURCE_CONFIDENCE_MIN -> \"MAP_SOURCE\"
            estimate.confidence < EXACT_MATCH_CONFIDENCE_MIN -> \"MATCH_CONFIDENCE\"
            physical.mergedSegments -> \"CARRIAGEWAY_GROUP\"
            else -> \"STABILITY_OR_TRANSITION\"
        }
        val statusCore = when {
""",
    """        val exactBlocker = when {
            exact -> \"READY\"
            accuracy > 5f -> \"GNSS_ACCURACY\"
            !state.sensorLaneReady -> \"SENSOR_GATE\"
            topLane.sourceConfidence < EXACT_SOURCE_CONFIDENCE_MIN -> \"MAP_SOURCE\"
            estimate.confidence < EXACT_MATCH_CONFIDENCE_MIN -> \"MATCH_CONFIDENCE\"
            physical.mergedSegments -> \"CARRIAGEWAY_GROUP\"
            else -> \"STABILITY_OR_TRANSITION\"
        }
        val turnHints = if (!physical.mergedSegments) {
            allLanes
                .filter { it.segmentId == topLane.segmentId }
                .sortedBy { it.index }
                .takeIf { it.size == physical.laneCount }
                ?.map { lane -> lane.turns.sorted().joinToString(\";\") }
                .orEmpty()
        } else {
            emptyList()
        }
        val statusCore = when {
""",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/location/DriveTrackingService.kt",
    """            laneCount = physical.laneCount,
            confidence = estimate.confidence.toFloat(),
            exactClaim = exact,
""",
    """            laneCount = physical.laneCount,
            turnHints = turnHints,
            confidence = estimate.confidence.toFloat(),
            exactClaim = exact,
""",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/location/DriveTrackingService.kt",
    """                likelyLaneCount = null,
                laneConfidence = 0f,
""",
    """                likelyLaneCount = null,
                laneTurnHints = emptyList(),
                laneConfidence = 0f,
""",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/location/DriveTrackingService.kt",
    """            likelyLaneCount = overlay.laneCount,
            laneConfidence = overlay.confidence,
""",
    """            likelyLaneCount = overlay.laneCount,
            laneTurnHints = overlay.turnHints,
            laneConfidence = overlay.confidence,
""",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/location/DriveTrackingService.kt",
    """        val laneCount: Int? = null,
        val confidence: Float = 0f,
""",
    """        val laneCount: Int? = null,
        val turnHints: List<String> = emptyList(),
        val confidence: Float = 0f,
""",
)

# Route-aware target lanes: match the next OSRM maneuver to explicit OSM turn:lanes
# tags on the current carriageway. No tags or ambiguous carriageway => no target claim.
replace_once(
    "android/app/src/main/kotlin/com/example/gps/NavigationActivity.kt",
    """    val laneNumber = if (gpsStale || layoutSettling) null
        else state.likelyLaneNumberFromLeft
    val route = routeUi.summary

    BoxWithConstraints""",
    """    val laneNumber = if (gpsStale || layoutSettling) null
        else state.likelyLaneNumberFromLeft
    val route = routeUi.summary
    val targetLanes = remember(state.laneTurnHints, route?.nextManeuver, laneCount) {
        recommendedLaneNumbers(
            turnHints = state.laneTurnHints,
            maneuver = route?.nextManeuver,
            laneCount = laneCount,
        )
    }

    BoxWithConstraints""",
)

replace_once(
    "android/app/src/main/kotlin/com/example/gps/NavigationActivity.kt",
    "            CompactLaneOverlay(state, laneNumber, laneCount, gpsStale, layoutSettling, sessionActive)\n",
    "            CompactLaneOverlay(state, laneNumber, laneCount, gpsStale, layoutSettling, sessionActive, route, targetLanes)\n",
)

new_lane_ui = r'''@Composable
private fun CompactLaneOverlay(
    state: GnssUiState,
    laneNumber: Int?,
    laneCount: Int?,
    stale: Boolean,
    settling: Boolean,
    active: Boolean,
    route: OpenRouteClient.RouteSummary?,
    targetLanes: Set<Int>,
) {
    val laneKnown = active && !stale && !settling &&
        laneNumber != null && laneCount != null && laneNumber in 1..laneCount
    val exact = laneKnown && state.laneExactClaim
    val likely = laneKnown && !exact
    val confidence = (state.laneConfidence.coerceIn(0f, 1f) * 100).roundToInt()
    val targetTitle = if (laneCount != null && targetLanes.isNotEmpty()) {
        targetLaneLabel(targetLanes, laneCount)
    } else null

    Surface(color = NavCard, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(
                when {
                    !active -> "LANE GUIDANCE · waiting for live view"
                    stale -> "LANES UNKNOWN · GPS lost"
                    settling -> "UPDATING ROAD LANES"
                    targetTitle != null -> targetTitle
                    exact -> "LANE $laneNumber OF $laneCount · CONFIRMED"
                    likely -> "LIKELY LANE $laneNumber OF $laneCount · $confidence%"
                    laneCount != null -> "$laneCount LANES · POSITION UNCERTAIN"
                    state.laneCandidateCount > 0 -> "ROAD FOUND · RESOLVING LANES"
                    else -> "LANES UNKNOWN · scanning road"
                },
                color = when {
                    targetTitle != null -> NavBlueSoft
                    exact -> NavGreen
                    else -> NavAmber
                },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
            )
            if (active) {
                LaneRoadDiagram(
                    laneCount = if (stale || settling) null else laneCount,
                    currentLane = if (laneKnown) laneNumber else null,
                    exact = exact,
                    targetLanes = targetLanes,
                    turnHints = state.laneTurnHints,
                )
                Text(
                    when {
                        targetLanes.isNotEmpty() && exact && laneNumber != null ->
                            laneMoveAdvice(laneNumber, targetLanes) + " · Blue = route lane; green = confirmed car."
                        targetLanes.isNotEmpty() ->
                            "Blue = lane(s) mapped for ${route?.nextManeuver ?: "the next maneuver"}. Current lane is not confirmed yet."
                        route != null ->
                            "Route is active, but this road has no unambiguous OSM turn-lane data here yet."
                        exact -> "Green car = confirmed current lane."
                        likely -> "Amber car = likely current lane; LaneGPS is not claiming it as exact yet."
                        settling -> "Road layout is changing; current-lane marker is paused until it settles."
                        else -> "LaneGPS is waiting for enough road/GPS evidence to place your car."
                    },
                    color = NavMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun LaneRoadDiagram(
    laneCount: Int?,
    currentLane: Int?,
    exact: Boolean,
    targetLanes: Set<Int>,
    turnHints: List<String>,
) {
    Canvas(Modifier.fillMaxWidth().height(118.dp).padding(vertical = 4.dp)) {
        val nearLeft = size.width * 0.04f
        val nearRight = size.width * 0.96f
        val farLeft = size.width * 0.32f
        val farRight = size.width * 0.68f
        val top = size.height * 0.06f
        val bottom = size.height
        val road = Path().apply {
            moveTo(nearLeft, bottom); lineTo(farLeft, top)
            lineTo(farRight, top); lineTo(nearRight, bottom); close()
        }
        drawPath(road, Color(0xFF263344))

        if (laneCount != null) {
            // Highlight mapped target lane(s) as perspective ribbons. These are derived
            // only from explicit OSM turn:lanes tags matching the next route maneuver.
            targetLanes.filter { it in 1..laneCount }.forEach { lane ->
                val leftFraction = (lane - 1).toFloat() / laneCount
                val rightFraction = lane.toFloat() / laneCount
                val targetPath = Path().apply {
                    moveTo(nearLeft + (nearRight - nearLeft) * leftFraction, bottom)
                    lineTo(farLeft + (farRight - farLeft) * leftFraction, top)
                    lineTo(farLeft + (farRight - farLeft) * rightFraction, top)
                    lineTo(nearLeft + (nearRight - nearLeft) * rightFraction, bottom)
                    close()
                }
                drawPath(targetPath, NavBlue.copy(alpha = 0.34f))
            }
        }

        drawLine(Color.White, Offset(nearLeft, bottom), Offset(farLeft, top), 2.dp.toPx())
        drawLine(Color.White, Offset(nearRight, bottom), Offset(farRight, top), 2.dp.toPx())
        if (laneCount != null) {
            for (boundary in 1 until laneCount) {
                val fraction = boundary.toFloat() / laneCount
                drawLine(
                    Color(0xFFD9E2EF),
                    Offset(nearLeft + (nearRight - nearLeft) * fraction, bottom),
                    Offset(farLeft + (farRight - farLeft) * fraction, top),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx())),
                )
            }

            // Small lane arrows expose the actual OSM turn-lane data instead of just
            // showing lane numbers. A lane can legitimately carry multiple arrows.
            for (lane in 1..laneCount) {
                val tokens = turnHints.getOrNull(lane - 1).orEmpty()
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val fraction = (lane - 0.5f) / laneCount
                val x = farLeft + (farRight - farLeft) * fraction
                val shaftBottom = top + 34.dp.toPx()
                val shaftTop = top + 12.dp.toPx()
                val c = if (lane in targetLanes) Color.White else NavBlueSoft.copy(alpha = 0.75f)
                val stroke = if (lane in targetLanes) 2.4.dp.toPx() else 1.6.dp.toPx()
                val through = tokens.any { it == "through" }
                val left = tokens.any { "left" in it || it == "reverse" }
                val right = tokens.any { "right" in it }
                if (through || (!left && !right)) {
                    drawLine(c, Offset(x, shaftBottom), Offset(x, shaftTop), stroke)
                    drawLine(c, Offset(x, shaftTop), Offset(x - 4.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x, shaftTop), Offset(x + 4.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                }
                if (left) {
                    drawLine(c, Offset(x, shaftBottom), Offset(x, shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x, shaftTop + 5.dp.toPx()), Offset(x - 7.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x - 7.dp.toPx(), shaftTop + 5.dp.toPx()), Offset(x - 3.dp.toPx(), shaftTop + 1.dp.toPx()), stroke)
                }
                if (right) {
                    drawLine(c, Offset(x, shaftBottom), Offset(x, shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x, shaftTop + 5.dp.toPx()), Offset(x + 7.dp.toPx(), shaftTop + 5.dp.toPx()), stroke)
                    drawLine(c, Offset(x + 7.dp.toPx(), shaftTop + 5.dp.toPx()), Offset(x + 3.dp.toPx(), shaftTop + 1.dp.toPx()), stroke)
                }
            }

            if (currentLane != null && currentLane in 1..laneCount) {
                val depth = 0.76f
                val left = farLeft + (nearLeft - farLeft) * depth
                val right = farRight + (nearRight - farRight) * depth
                val x = left + (right - left) * (currentLane - 0.5f) / laneCount
                val width = minOf(25.dp.toPx(), (right - left) / laneCount * 0.68f)
                val y = top + (bottom - top) * depth
                val carColor = if (exact) NavGreen else NavAmber
                drawRoundRect(
                    carColor,
                    Offset(x - width / 2, y - 17.dp.toPx()),
                    Size(width, 33.dp.toPx()),
                    CornerRadius(5.dp.toPx()),
                )
                drawRoundRect(
                    NavBg,
                    Offset(x - width * 0.32f, y - 11.dp.toPx()),
                    Size(width * 0.64f, 8.dp.toPx()),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

private enum class RouteLaneIntent { LEFT, RIGHT, THROUGH, UTURN }

private fun recommendedLaneNumbers(
    turnHints: List<String>,
    maneuver: String?,
    laneCount: Int?,
): Set<Int> {
    if (laneCount == null || laneCount !in 1..8 || turnHints.size != laneCount) return emptySet()
    val intent = routeLaneIntent(maneuver) ?: return emptySet()
    return turnHints.mapIndexedNotNull { index, raw ->
        val tokens = raw.split(';').map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }
        val match = when (intent) {
            RouteLaneIntent.LEFT -> tokens.any { "left" in it }
            RouteLaneIntent.RIGHT -> tokens.any { "right" in it }
            RouteLaneIntent.THROUGH -> tokens.any { it == "through" }
            RouteLaneIntent.UTURN -> tokens.any { it == "reverse" || "uturn" in it || "u_turn" in it }
        }
        (index + 1).takeIf { match }
    }.toSet()
}

private fun routeLaneIntent(maneuver: String?): RouteLaneIntent? {
    val text = maneuver?.lowercase(java.util.Locale.ROOT)?.trim().orEmpty()
    if (text.isEmpty() || "destination" in text || "arriv" in text || "roundabout" in text) return null
    return when {
        "u-turn" in text || "uturn" in text -> RouteLaneIntent.UTURN
        "left" in text -> RouteLaneIntent.LEFT
        "right" in text -> RouteLaneIntent.RIGHT
        "continue" in text || "straight" in text -> RouteLaneIntent.THROUGH
        else -> null
    }
}

private fun targetLaneLabel(targetLanes: Set<Int>, laneCount: Int): String {
    val sorted = targetLanes.filter { it in 1..laneCount }.sorted()
    if (sorted.isEmpty()) return "ROUTE LANES UNKNOWN"
    return when {
        sorted.size == 1 -> "USE LANE ${sorted.first()} OF $laneCount"
        sorted.zipWithNext().all { (a, b) -> b == a + 1 } ->
            "USE LANES ${sorted.first()}–${sorted.last()} OF $laneCount"
        else -> "USE LANES ${sorted.joinToString(", ")} OF $laneCount"
    }
}

private fun laneMoveAdvice(currentLane: Int, targets: Set<Int>): String {
    if (currentLane in targets) return "KEEP THIS LANE"
    val nearest = targets.minByOrNull { kotlin.math.abs(it - currentLane) } ?: return "TARGET LANE MAPPED"
    return if (nearest < currentLane) "MOVE LEFT WHEN SAFE" else "MOVE RIGHT WHEN SAFE"
}

'''

replace_between(
    "android/app/src/main/kotlin/com/example/gps/NavigationActivity.kt",
    "@Composable\nprivate fun CompactLaneOverlay",
    "@Composable\nprivate fun NavigationHeader",
    new_lane_ui,
)

print("Lane guidance patch applied")
