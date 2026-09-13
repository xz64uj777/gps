package com.example.gps.laneengine

import kotlin.test.Test
import kotlin.test.assertEquals

class ManeuverInstructionTest {
    @Test fun exitsKeepTheirMeaningWithSlightModifiers() {
        assertEquals("Take the exit on the right", ManeuverInstruction.text("off ramp", "slight right"))
        assertEquals("Take the exit 29A on the left", ManeuverInstruction.text("off ramp", "slight left", "29A"))
        assertEquals("Take the exit", ManeuverInstruction.text("off ramp", "straight"))
    }
    @Test fun forksAndMergesAreNotOrdinaryTurns() {
        assertEquals("Keep left at the fork", ManeuverInstruction.text("fork", "slight left"))
        assertEquals("Merge right", ManeuverInstruction.text("merge", "slight right"))
        assertEquals("Take the ramp on the right", ManeuverInstruction.text("on ramp", "right"))
        assertEquals("Enter the roundabout", ManeuverInstruction.text("roundabout", "right"))
    }
    @Test fun normalTurnsRemainTurns() {
        assertEquals("Slight right", ManeuverInstruction.text("turn", "slight right"))
        assertEquals("Turn left", ManeuverInstruction.text("turn", "left"))
    }
}
