package com.example.gps.route
import org.junit.Assert.*
import org.junit.Test
class VoiceModeTest {
    @Test fun alertsOnlySuppressesRoutineTurnsButKeepsArrivalAndReroute() {
        assertFalse(VoiceMode.ALERTS_ONLY.allows("maneuver-123-1"))
        assertTrue(VoiceMode.ALERTS_ONLY.allows("arrival"))
        assertTrue(VoiceMode.ALERTS_ONLY.allows("rerouting"))
    }
    @Test fun muteSuppressesEverySpeechCategory() {
        listOf("arrival", "rerouting", "maneuver-123-1", "voice-preview").forEach {
            assertFalse(VoiceMode.MUTE.allows(it))
        }
    }
    @Test fun normalKeepsTurnDirections() {
        assertTrue(VoiceMode.NORMAL.allows("maneuver-123-1"))
    }
}
