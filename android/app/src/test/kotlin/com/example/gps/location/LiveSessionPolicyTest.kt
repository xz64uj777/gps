package com.example.gps.location
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSessionPolicyTest {
    @Test fun foregroundEntryDoesNotTrustStaleActivePreference() {
        assertEquals(LiveSessionPolicy.Action.NEW, LiveSessionPolicy.onOpen(false, true, false))
        assertEquals(LiveSessionPolicy.Action.NEW, LiveSessionPolicy.onOpen(false, false, false))
    }
    @Test fun screenRecreationPreservesRunningTrackerAndRecording() {
        assertEquals(LiveSessionPolicy.Action.KEEP, LiveSessionPolicy.onOpen(true, true, false))
        assertEquals(LiveSessionPolicy.Action.KEEP, LiveSessionPolicy.onOpen(true, true, true))
    }
    @Test fun onlyActiveSavedRoutesResume() {
        assertEquals(LiveSessionPolicy.Action.RESUME, LiveSessionPolicy.onOpen(false, true, true))
        assertEquals(LiveSessionPolicy.Action.NEW, LiveSessionPolicy.onOpen(false, false, true))
    }
}
