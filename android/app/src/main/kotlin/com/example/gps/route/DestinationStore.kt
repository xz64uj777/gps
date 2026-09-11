package com.example.gps.route

import android.content.Context
import org.json.JSONArray

/** Small local destination history/favorites store. */
class DestinationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recent(): List<String> = readList(KEY_RECENT)

    fun saved(): List<String> = readList(KEY_SAVED)

    fun home(): String? = prefs.getString(KEY_HOME, null)?.trim()?.takeIf { it.isNotBlank() }

    fun work(): String? = prefs.getString(KEY_WORK, null)?.trim()?.takeIf { it.isNotBlank() }

    fun setHome(label: String) {
        writeQuick(KEY_HOME, label)
    }

    fun setWork(label: String) {
        writeQuick(KEY_WORK, label)
    }

    fun clearHome() {
        prefs.edit().remove(KEY_HOME).apply()
    }

    fun clearWork() {
        prefs.edit().remove(KEY_WORK).apply()
    }

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
        val home = home()
        val work = work()
        val saved = saved()
        val recent = recent()
        val ordered = buildList {
            if (home != null) add(LocalDestination(home, saved = true, recent = false, quickLabel = "Home"))
            if (work != null && !work.equals(home, ignoreCase = true)) {
                add(LocalDestination(work, saved = true, recent = false, quickLabel = "Work"))
            }
            saved.forEach { label ->
                if (none { it.label.equals(label, ignoreCase = true) }) {
                    add(LocalDestination(label, true, false))
                }
            }
            recent.forEach { label ->
                if (none { it.label.equals(label, ignoreCase = true) }) {
                    add(LocalDestination(label, false, true))
                }
            }
        }
        if (q.isBlank()) return ordered.take(MAX_LOCAL_RESULTS)
        return ordered
            .filter { it.label.contains(q, ignoreCase = true) || it.quickLabel?.contains(q, ignoreCase = true) == true }
            .take(MAX_LOCAL_RESULTS)
    }

    private fun writeQuick(key: String, label: String) {
        val clean = label.trim()
        if (clean.isBlank()) return
        prefs.edit().putString(key, clean).apply()
        addRecent(clean)
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
        val quickLabel: String? = null,
    )

    private companion object {
        const val PREFS_NAME = "lane_gps_destinations"
        const val KEY_RECENT = "recent"
        const val KEY_SAVED = "saved"
        const val KEY_HOME = "home"
        const val KEY_WORK = "work"
        const val MAX_RECENTS = 10
        const val MAX_SAVED = 12
        const val MAX_LOCAL_RESULTS = 8
    }
}
