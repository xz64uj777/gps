package com.example.gps.location

/** Trim parked tails, retaining each lifecycle occurrence only once beyond the cutoff. */
internal object DriveLogExport {
    fun trim(lines: List<String>): List<String> {
        if (lines.size <= 2) return lines
        var lastUsefulRecordedAt: Long? = null
        for (line in lines.drop(1)) {
            val fields = parseCsvLine(line)
            if (fields.size <= FIX_STALE_INDEX) continue
            val recordedAt = fields.getOrNull(RECORDED_AT_INDEX)?.toLongOrNull() ?: continue
            val fixAge = fields.getOrNull(FIX_AGE_INDEX)?.toLongOrNull() ?: Long.MAX_VALUE
            val accuracy = fields.getOrNull(ACCURACY_INDEX)?.toDoubleOrNull() ?: Double.POSITIVE_INFINITY
            val speed = fields.getOrNull(SPEED_INDEX)?.toDoubleOrNull() ?: 0.0
            val stale = fields.getOrNull(FIX_STALE_INDEX)?.toBooleanStrictOrNull() ?: true

            if (!stale && fixAge <= STALE_FIX_MS && accuracy <= USEFUL_ACCURACY_M && speed >= MOVING_SPEED_MPS) {
                lastUsefulRecordedAt = recordedAt
            }
        }

        val lastUseful = lastUsefulRecordedAt
        if (lastUseful == null) {
            return lines
        }

        val cutoff = lastUseful + EXPORT_TAIL_GRACE_MS
        val header = parseCsvLine(lines.first())
        val navEventIndex = header.indexOf("nav_event")
        val navUpdatedIndex = header.indexOf("nav_updated_at_ms")
        var previousLifecycle: Pair<String, String>? = null
        return listOf(lines.first()) + lines.drop(1).filter { line ->
            val fields = parseCsvLine(line)
            val recordedAt = fields.getOrNull(RECORDED_AT_INDEX)?.toLongOrNull()
            val event = fields.getOrNull(navEventIndex).orEmpty()
            val lifecycle = event.startsWith("ROUTE_") || event.startsWith("DRIVE_") || event == "NAVIGATION_STOPPED"
            val key = event to fields.getOrNull(navUpdatedIndex).orEmpty()
            val newLifecycle = lifecycle && key != previousLifecycle
            previousLifecycle = key
            recordedAt == null || recordedAt <= cutoff || newLifecycle
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
    private const val ACCURACY_INDEX = 3
    private const val SPEED_INDEX = 9
    private const val RECORDED_AT_INDEX = 24
    private const val FIX_AGE_INDEX = 26
    private const val FIX_STALE_INDEX = 27
}
