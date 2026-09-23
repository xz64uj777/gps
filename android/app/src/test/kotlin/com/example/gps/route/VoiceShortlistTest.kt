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
    @Test fun knownGoogleEnglishVoiceIdsGetGenderLabels() {
        assertEquals("Male", VoiceShortlist.gender("en-us-x-iol-local"))
        assertEquals("Male", VoiceShortlist.gender("en-us-x-iom-network"))
        assertEquals("Female", VoiceShortlist.gender("en-us-x-iog-local"))
        assertEquals("Female", VoiceShortlist.gender("en-gb-x-gba-network"))
        assertEquals("Male", VoiceShortlist.gender("en-gb-x-gbd-local"))
        assertEquals("Male", VoiceShortlist.gender("en-au-x-aub-local"))
    }

    @Test fun explicitEngineGenderTokenOverridesOpaqueFamilyMapping() {
        assertEquals("Male", VoiceShortlist.gender("en-us-x-sfg#male_1-local"))
        assertEquals("Female", VoiceShortlist.gender("en-us-x-iol#female_2-local"))
    }

}
