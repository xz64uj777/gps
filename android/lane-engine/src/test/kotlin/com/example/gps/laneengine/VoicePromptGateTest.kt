package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePromptGateTest {
    @Test fun twoCuesAndNoReplayAfterManeuverJitter() {
        val gate = VoicePromptGate()
        assertTrue(gate.shouldSpeak("left", 700.0, 0))
        assertFalse(gate.shouldSpeak("left", 300.0, 10_000))
        assertFalse(gate.shouldSpeak("left", 100.0, 20_000))
        assertTrue(gate.shouldSpeak("left", 50.0, 30_000))
        assertFalse(gate.shouldSpeak("right", 500.0, 32_000))
        assertTrue(gate.shouldSpeak("right", 500.0, 40_000))
        assertFalse(gate.shouldSpeak("left", 50.0, 50_000))
    }
    @Test fun distantAndInvalidCuesWaitAndResetAllowsNewTrip() {
        val gate = VoicePromptGate()
        assertFalse(gate.shouldSpeak("a", Double.NaN, 0))
        assertFalse(gate.shouldSpeak("a", 2000.0, 0))
        assertTrue(gate.shouldSpeak("a", 2000.0, 0, force = true))
        gate.reset()
        assertTrue(gate.shouldSpeak("a", 500.0, 1))
    }
}
