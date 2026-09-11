package com.example.gps.route

import android.content.Context
import org.json.JSONArray

/** Small local destination history/favorites store. */
class DestinationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recent(): List<String> = readList(KEY_RECENT)

    fun saved(): List<String> = readList(KEY_SAVED)

    fun addRecent(label: String) {
        val clean = label.trim()
        if (clean.isBlank()) return
        val next = (listOf(clean) + recent().filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_RECENTS)
        writeList(KEY_RECENT, next)
    }

    fun toggleSaved(label: String): Boolean {
        val clean = label.trim()
        if (clean.isBlank()) return false
        val current = saved().toMutableList()
        val existing = current.indexOfFirst { it.equals(clean, ignoreCase = true) }
        val nowSaved = if (existing >= 0) {
            current.removeAt(existing)
            false
        } else {
            current.add(0, clean)
            while (current.size > MAX_SAVED) current.removeLast()
            true
        }
        writeList(KEY_SAVED, current)
        return nowSaved
    }

    fun isSaved(label: String): Boolean =
        saved().any { it.equals(label.trim(), ignoreCase = true) }

    fun localMatches(query: String): List<LocalDestination> {
        val q = query.trim()
        val saved = saved()
        val recent = recent()
        val ordered = buildList {
            saved.forEach { add(LocalDestination(it, true, false)) }
            recent.forEach { label ->
                if (none { it.label.equals(label, ignoreCase = true) }) {
                    add(LocalDestination(label, false, true))
                }
            }
        }
        if (q.isBlank()) return ordered.take(MAX_LOCAL_RESULTS)
        return ordered
            .filter { it.label.contains(q, ignoreCase = true) }
            .take(MAX_LOCAL_RESULTS)
    }

    private fun readList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeList(key: String, values: List<String>) {
        val array = JSONArray()
        values.forEach(array::put)
        prefs.edit().putString(key, array.toString()).apply()
    }

    data class LocalDestination(
        val label: String,
        val saved: Boolean,
        val recent: Boolean,
    )

    private companion object {
        const val PREFS_NAME = "lane_gps_destinations"
        const val KEY_RECENT = "recent"
        const val KEY_SAVED = "saved"
        const val MAX_RECENTS = 10
        const val MAX_SAVED = 12
        const val MAX_LOCAL_RESULTS = 8
    }
}
