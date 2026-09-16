package com.example.gps.route

/** Unknown provider IDs must not be assigned an invented gender. */
internal object VoiceShortlist {
    fun gender(name: String): String {
        val tokens = name.lowercase(java.util.Locale.ROOT).split(Regex("[^a-z]+"))
        return when {
            "female" in tokens && "male" !in tokens -> "Female"
            "male" in tokens && "female" !in tokens -> "Male"
            else -> "Voice"
        }
    }

    fun <T> select(items: List<T>, id: (T) -> String, selectedId: String?): List<T> {
        val preferred = items.sortedBy { if (id(it) == selectedId) 0 else 1 }
        val female = preferred.filter { gender(id(it)) == "Female" }.take(4)
        val male = preferred.filter { gender(id(it)) == "Male" }.take(4)
        val known = female + male
        val unknown = preferred.filter { gender(id(it)) == "Voice" }.take(8 - known.size)
        val result = (known + unknown).toMutableList()
        // Keep a previously chosen opaque voice accessible even when known slots fill up.
        val selected = preferred.firstOrNull { id(it) == selectedId }
        if (selected != null && selected !in result) {
            if (result.size == 8) result.removeAt(result.lastIndex)
            result.add(selected)
        }
        return result
    }
}
