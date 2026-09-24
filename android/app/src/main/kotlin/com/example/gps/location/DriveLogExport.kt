package com.example.gps.location

/** Trim parked tails, retaining each lifecycle occurrence only once beyond the cutoff. */
internal object DriveLogExport {
    fun trim(lines: List<String>): List<String> {
        if (lines.size <= 2) return lines

        val header = parseCsvLine(lines.first())
        fun index(name: String): Int = header.indexOf(name)

        val accuracyIndex = index("accuracy_m")
        val speedIndex = index("speed_mps")
        val recordedAtIndex = index("recorded_at_ms")
        val fixAgeIndex = index("fix_age_ms")
        val fixStaleIndex = index("fix_stale")
        val navEventIndex = index("nav_event")
        val navUpdatedIndex = index("nav_updated_at_ms")
        val routeGeometryIndex = index("nav_route_geometry_lat_lon")

        // A malformed or old header should never make export destructive.
        if (listOf(accuracyIndex, speedIndex, recordedAtIndex, fixAgeIndex, fixStaleIndex).any { it < 0 }) {
            return lines
        }

        var lastUsefulRecordedAt: Long? = null
        for (line in lines.drop(1)) {
            val fields = parseCsvLine(line)
            val recordedAt = fields.getOrNull(recordedAtIndex)?.toLongOrNull() ?: continue
            val fixAge = fields.getOrNull(fixAgeIndex)?.toLongOrNull() ?: Long.MAX_VALUE
            val accuracy = fields.getOrNull(accuracyIndex)?.toDoubleOrNull() ?: Double.POSITIVE_INFINITY
            val speed = fields.getOrNull(speedIndex)?.toDoubleOrNull() ?: 0.0
            val stale = fields.getOrNull(fixStaleIndex)?.toBooleanStrictOrNull() ?: true

            if (!stale && fixAge <= STALE_FIX_MS && accuracy <= USEFUL_ACCURACY_M && speed >= MOVING_SPEED_MPS) {
                lastUsefulRecordedAt = recordedAt
            }
        }

        val lastUseful = lastUsefulRecordedAt ?: return lines
        val cutoff = lastUseful + EXPORT_TAIL_GRACE_MS
        var previousLifecycle: Pair<String, String>? = null

        return listOf(lines.first()) + lines.drop(1).filter { line ->
            val fields = parseCsvLine(line)
            val recordedAt = fields.getOrNull(recordedAtIndex)?.toLongOrNull()
            val event = fields.getOrNull(navEventIndex).orEmpty()
            val lifecycle = event.startsWith("ROUTE_") || event.startsWith("DRIVE_") || event == "NAVIGATION_STOPPED"
            val key = event to fields.getOrNull(navUpdatedIndex).orEmpty()
            val newLifecycle = lifecycle && key != previousLifecycle
            previousLifecycle = key
            recordedAt == null || recordedAt <= cutoff || newLifecycle ||
                !fields.getOrNull(routeGeometryIndex).isNullOrBlank()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        values += current.toString()
        return values
    }

    private const val STALE_FIX_MS = 3000L
    private const val USEFUL_ACCURACY_M = 50.0
    private const val MOVING_SPEED_MPS = 1.0
    private const val EXPORT_TAIL_GRACE_MS = 90_000L
}
