package com.example.gps.laneengine

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalCarriagewayResolverTest {
    private val origin = GeoPoint(41.75, -72.66)

    @Test
    fun sustainedTwoPlusThreeParallelSegmentsBecomeFivePhysicalLanes() {
        val resolver = PhysicalCarriagewayResolver()
        val lanes = listOf(
            lane("A0", "A", 0, -7.2),
            lane("A1", "A", 1, -3.6),
            lane("B0", "B", 0, 0.0),
            lane("B1", "B", 1, 3.6),
            lane("B2", "B", 2, 7.2),
        )

        listOf(0.0, 12.0, 24.0, 36.0, 48.0, 60.0).forEach { northMeters ->
            val result = resolver.resolve(positionNorthMeters(northMeters), 0.0, lanes[3], lanes)
            assertEquals(3, result.laneCount)
            assertFalse(result.mergedSegments)
        }

        val confirmed = resolver.resolve(positionNorthMeters(72.0), 0.0, lanes[3], lanes)
        assertEquals(5, confirmed.laneCount)
        assertEquals(4, confirmed.laneNumberFromLeft)
        assertTrue(confirmed.mergedSegments)
        assertEquals(2, confirmed.segmentCount)
    }

    @Test
    fun junctionLikeTwoPlusThreeAlignmentUnderSixtyMetersNeverBecomesFive() {
        val resolver = PhysicalCarriagewayResolver()
        val lanes = listOf(
            lane("A0", "A", 0, -7.2),
            lane("A1", "A", 1, -3.6),
            lane("B0", "B", 0, 0.0),
            lane("B1", "B", 1, 3.6),
            lane("B2", "B", 2, 7.2),
        )

        listOf(0.0, 8.0, 16.0, 24.0, 32.0, 40.0, 48.0, 56.0).forEach { northMeters ->
            val result = resolver.resolve(positionNorthMeters(northMeters), 0.0, lanes[3], lanes)
            assertEquals(3, result.laneCount)
            assertFalse(result.mergedSegments)
        }
    }

    @Test
    fun sustainedThreeWayBundleDoesNotBecomeSevenLaneCarriageway() {
        val resolver = PhysicalCarriagewayResolver()
        val lanes = listOf(
            lane("A0", "A", 0, -10.8),
            lane("A1", "A", 1, -7.2),
            lane("B0", "B", 0, -3.6),
            lane("B1", "B", 1, 0.0),
            lane("B2", "B", 2, 3.6),
            lane("C0", "C", 0, 7.2),
            lane("C1", "C", 1, 10.8),
        )

        listOf(0.0, 12.0, 24.0, 36.0, 48.0, 60.0, 72.0).forEach { northMeters ->
            val result = resolver.resolve(positionNorthMeters(northMeters), 0.0, lanes[3], lanes)
            assertEquals(3, result.laneCount)
            assertFalse(result.mergedSegments)
        }
    }

    @Test
    fun overlappingSequentialWaysDoNotDoubleLaneCount() {
        val resolver = PhysicalCarriagewayResolver()
        val lanes = listOf(
            lane("A0", "A", 0, 3.6),
            lane("A1", "A", 1, 0.0),
            lane("A2", "A", 2, -3.6),
            lane("B0", "B", 0, 3.7),
            lane("B1", "B", 1, 0.1),
            lane("B2", "B", 2, -3.5),
        )

        val result = resolver.resolve(origin, 0.0, lanes[1], lanes)

        assertEquals(3, result.laneCount)
        assertEquals(2, result.laneNumberFromLeft)
        assertFalse(result.mergedSegments)
    }

    @Test
    fun separatedParallelRoadDoesNotGetMerged() {
        val resolver = PhysicalCarriagewayResolver()
        val main = listOf(
            lane("M0", "MAIN", 0, 3.6),
            lane("M1", "MAIN", 1, 0.0),
            lane("M2", "MAIN", 2, -3.6),
        )
        val separated = listOf(
            lane("S0", "SIDE", 0, 18.0),
            lane("S1", "SIDE", 1, 14.4),
        )
        val lanes = main + separated

        val result = resolver.resolve(origin, 0.0, main[1], lanes)

        assertEquals(3, result.laneCount)
        assertEquals(2, result.laneNumberFromLeft)
        assertFalse(result.mergedSegments)
    }

    @Test
    fun oppositeDirectionParallelRoadDoesNotGetMerged() {
        val resolver = PhysicalCarriagewayResolver()
        val northbound = listOf(
            lane("N0", "N", 0, 1.8, northbound = true),
            lane("N1", "N", 1, -1.8, northbound = true),
        )
        val southbound = listOf(
            lane("S0", "S", 0, 5.4, northbound = false),
            lane("S1", "S", 1, 9.0, northbound = false),
        )
        val lanes = northbound + southbound

        val result = resolver.resolve(origin, 0.0, northbound[0], lanes)

        assertEquals(2, result.laneCount)
        assertFalse(result.mergedSegments)
    }

    private fun lane(
        id: String,
        segment: String,
        index: Int,
        eastMeters: Double,
        northbound: Boolean = true,
    ): Lane {
        val lonOffset = eastMeters / (111_320.0 * cos(origin.lat * Math.PI / 180.0))
        val start = GeoPoint(origin.lat - 0.001, origin.lon + lonOffset)
        val end = GeoPoint(origin.lat + 0.001, origin.lon + lonOffset)
        val line = if (northbound) listOf(start, end) else listOf(end, start)
        return Lane(id, segment, index, line)
    }

    private fun positionNorthMeters(northMeters: Double): GeoPoint =
        GeoPoint(origin.lat + northMeters / 111_320.0, origin.lon)
}
