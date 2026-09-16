package com.example.gps.route

enum class VoiceMode(val label: String) {
    NORMAL("Normal"), ALERTS_ONLY("Alerts only"), MUTE("Mute");

    fun allows(event: String): Boolean = when (this) {
        NORMAL -> true
        ALERTS_ONLY -> event == "rerouting" || event == "arrival" || event == "voice-preview"
        MUTE -> false
    }
}
