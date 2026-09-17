package com.example.gps.laneengine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePromptGateTest {
    @Test fun highwayExitHasThreeUsefulCues() {
        val gate = VoicePromptGate()
        assertFalse(gate.shouldSpeak("exit", 5000.0, 0, speedMps = 30.0, highwayExit = true))
        assertTrue(gate.shouldSpeak("exit", 1200.0, 0, speedMps = 30.0, highwayExit = true))
        assertFalse(gate.shouldSpeak("exit", 780.0, 24_000, speedMps = 30.0, highwayExit = true))
        assertTrue(gate.shouldSpeak("exit", 590.0, 40_000, speedMps = 30.0, highwayExit = true))
        assertTrue(gate.shouldSpeak("exit", 140.0, 55_000, speedMps = 30.0, highwayExit = true))
        assertFalse(gate.shouldSpeak("exit", 49.0, 70_000))
    }
    @Test fun ordinaryTurnOnlyGetsTwoCues() {
        val gate = VoicePromptGate()
        assertFalse(gate.shouldSpeak("turn", 1600.0, 0))
        assertFalse(gate.shouldSpeak("turn", 780.0, 10_000))
        assertTrue(gate.shouldSpeak("turn", 290.0, 30_000))
        assertFalse(gate.shouldSpeak("turn", 150.0, 45_000))
        assertTrue(gate.shouldSpeak("turn", 50.0, 55_000))
        assertFalse(gate.shouldSpeak("turn", 70.0, 60_000))
        assertFalse(gate.shouldSpeak("turn", 49.0, 70_000))
    }
    @Test fun restoredScreenDoesNotRepeatApproachCue() {
        val first = VoicePromptGate()
        assertTrue(first.shouldSpeak("turn", 290.0, 0))
        val restored = VoicePromptGate()
        restored.restore(first.snapshot())
        assertFalse(restored.shouldSpeak("turn", 280.0, 20_000))
        assertTrue(restored.shouldSpeak("turn", 50.0, 30_000))
    }
    @Test fun closeTurnsAndInvalidFixes() {
        val gate = VoicePromptGate()
        assertFalse(gate.shouldSpeak("a", Double.NaN, 0))
        assertTrue(gate.shouldSpeak("a", 50.0, 0))
        assertFalse(gate.shouldSpeak("b", 50.0, 1000))
        assertTrue(gate.shouldSpeak("b", 45.0, 3000))
        gate.reset()
        assertTrue(gate.shouldSpeak("a", 50.0, 4000))
    }
}
