package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePromptGateTest {
    @Test fun highwayExitHasStagedAdvanceCues() {
        val gate = VoicePromptGate()
        assertTrue(gate.shouldSpeak("exit", 1500.0, 0, speedMps = 30.0))
        assertTrue(gate.shouldSpeak("exit", 780.0, 24_000, speedMps = 30.0))
        assertTrue(gate.shouldSpeak("exit", 290.0, 40_000, speedMps = 30.0))
        assertTrue(gate.shouldSpeak("exit", 50.0, 48_000, speedMps = 30.0))
        assertFalse(gate.shouldSpeak("exit", 70.0, 60_000))
        assertFalse(gate.shouldSpeak("exit", 49.0, 70_000))
    }
    @Test fun previewDoesNotSuppressLaterAdvanceWarning() {
        val gate = VoicePromptGate()
        assertTrue(gate.shouldSpeak("exit", 5000.0, 0, force = true))
        assertTrue(gate.shouldSpeak("exit", 1500.0, 100_000))
    }
    @Test fun cooldownRetriesRatherThanConsumesPhase() {
        val gate = VoicePromptGate()
        assertTrue(gate.shouldSpeak("turn", 700.0, 0))
        assertFalse(gate.shouldSpeak("turn", 290.0, 5_000))
        assertTrue(gate.shouldSpeak("turn", 250.0, 8_000))
        assertTrue(gate.shouldSpeak("next exit", 50.0, 11_000))
    }
    @Test fun invalidCuesWaitAndResetAllowsNewTrip() {
        val gate = VoicePromptGate()
        assertFalse(gate.shouldSpeak("a", Double.NaN, 0))
        assertFalse(gate.shouldSpeak("a", 2000.0, 0))
        assertTrue(gate.shouldSpeak("a", 500.0, 0))
        gate.reset()
        assertTrue(gate.shouldSpeak("a", 500.0, 1))
    }
}
