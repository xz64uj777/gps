package com.example.gps.route

/**
 * Android's TextToSpeech Voice API does not expose a standardized gender field.
 * Prefer an explicit gender token when an engine provides one, then fall back to
 * known Google TTS voice IDs that are otherwise opaque.
 */
internal object VoiceShortlist {
    private val knownGenderById = mapOf(
        // English · Australia
        "en-au-language" to "Female",
        "en-au-x-afh" to "Female",
        "en-au-x-aua" to "Female",
        "en-au-x-aub" to "Male",
        "en-au-x-auc" to "Female",
        "en-au-x-aud" to "Male",

        // English · United Kingdom
        "en-gb-language" to "Female",
        "en-gb-x-fis" to "Female",
        "en-gb-x-gba" to "Female",
        "en-gb-x-gbb" to "Male",
        "en-gb-x-gbc" to "Female",
        "en-gb-x-gbd" to "Male",
        "en-gb-x-gbg" to "Female",
        "en-gb-x-rjs" to "Male",

        // English · India
        "en-in-language" to "Female",
        "en-in-x-ahp" to "Female",
        "en-in-x-cxx" to "Female",
        "en-in-x-ena" to "Female",
        "en-in-x-enc" to "Female",
        "en-in-x-end" to "Male",
        "en-in-x-ene" to "Male",

        // English · Nigeria
        "en-ng-language" to "Female",
        "en-ng-x-tfn" to "Female",

        // English · United States
        "en-us-language" to "Female",
        "en-us-x-iob" to "Female",
        "en-us-x-iog" to "Female",
        "en-us-x-iol" to "Male",
        "en-us-x-iom" to "Male",
        "en-us-x-sfg" to "Female",
        "en-us-x-tpc" to "Female",
        "en-us-x-tpd" to "Male",
        "en-us-x-tpf" to "Female",
    )

    fun gender(name: String): String {
        val lower = name.lowercase(java.util.Locale.ROOT)
        val tokens = lower.split(Regex("[^a-z]+"))
        // Some newer engine IDs include explicit fragments such as #male_1.
        if ("female" in tokens && "male" !in tokens) return "Female"
        if ("male" in tokens && "female" !in tokens) return "Male"

        val canonical = lower
            .substringBefore('#')
            .removeSuffix("-local")
            .removeSuffix("-network")
        return knownGenderById[canonical] ?: "Unknown"
    }

    fun <T> select(items: List<T>, id: (T) -> String, selectedId: String?): List<T> {
        val preferred = items.sortedBy { if (id(it) == selectedId) 0 else 1 }
        val female = preferred.filter { gender(id(it)) == "Female" }.take(4)
        val male = preferred.filter { gender(id(it)) == "Male" }.take(4)
        val known = female + male
        val unknown = preferred.filter { gender(id(it)) == "Unknown" }.take(8 - known.size)
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
