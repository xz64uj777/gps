package com.example.gps.route

import org.junit.Assert.*
import org.junit.Test

class VoiceShortlistTest {
    @Test fun capsEachKnownGenderAtFour() {
        val all = (1..12).map { "female-$it" } + (1..12).map { "male-$it" }
        val result = VoiceShortlist.select(all, { it }, null)
        assertEquals(4, result.count { VoiceShortlist.gender(it) == "Female" })
        assertEquals(4, result.count { VoiceShortlist.gender(it) == "Male" })
    }

    @Test fun engineFeaturesCanSupplyGenderWhenVoiceIdIsOpaque() {
        assertEquals(
            "Female",
            VoiceShortlist.gender("en-us-x-iom-local", setOf("com.vendor.gender=female")),
        )
        assertEquals(
            "Male",
            VoiceShortlist.gender("en-us-x-iom-local", setOf("voice masculine")),
        )
    }

    @Test fun opaqueEngineIdsAreExplicitlyUnknownAndBounded() {
        assertEquals("Unknown", VoiceShortlist.gender("en-us-x-iom-local"))
        assertEquals(8, VoiceShortlist.select((1..30).map { "opaque-$it" }, { it }, null).size)
    }

    @Test fun selectedVoiceIsNotLostAtTheLimit() {
        val all = (1..8).map { "female-$it" } + (1..8).map { "male-$it" } + "opaque-saved"
        val result = VoiceShortlist.select(all, { it }, "opaque-saved")
        assertEquals(8, result.size)
        assertTrue("opaque-saved" in result)
    }
}
