package com.example.gps.route

/** Unknown provider IDs must not be assigned an invented gender. */
internal object VoiceShortlist {
    fun gender(name: String, features: Set<String> = emptySet()): String {
        val text = buildString {
            append(name)
            features.forEach {
                append(' ')
                append(it)
            }
        }.lowercase(java.util.Locale.ROOT)

        val tokens = text.split(Regex("[^a-z]+")).filter { it.isNotBlank() }.toSet()
        return when {
            "female" in tokens || "woman" in tokens || "feminine" in tokens -> "Female"
            "male" in tokens || "man" in tokens || "masculine" in tokens -> "Male"
            else -> "Unknown"
        }
    }

    fun <T> select(
        items: List<T>,
        id: (T) -> String,
        selectedId: String?,
        features: (T) -> Set<String> = { emptySet() },
    ): List<T> {
        val preferred = items.sortedBy { if (id(it) == selectedId) 0 else 1 }
        val female = preferred.filter { gender(id(it), features(it)) == "Female" }.take(4)
        val male = preferred.filter { gender(id(it), features(it)) == "Male" }.take(4)
        val known = female + male
        val unknown = preferred.filter { gender(id(it), features(it)) == "Unknown" }.take(8 - known.size)
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
