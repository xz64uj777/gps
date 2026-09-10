package com.example.gps.laneengine

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalCarriagewayResolverTest {
    private val resolver = PhysicalCarriagewayResolver()
    private val origin = GeoPoint(41.75, -72.66)

    @Test
    fun combinesTwoPlusThreeParallelSegmentsIntoFivePhysicalLanes() {
        val lanes = listOf(
            lane("A0", "A", 0, -7.2),
            lane("A1", "A", 1, -3.6),
            lane("B0", "B", 0, 0.0),
            lane("B1", "B", 1, 3.6),
            lane("B2", "B", 2, 7.2),
        )

        val result = resolver.resolve(
            position = origin,
            travelHeadingDegrees = 0.0,
            matchedLane = lanes[3],
            lanes = lanes,
        )

        assertEquals(5, result.laneCount)
        assertEquals(4, result.laneNumberFromLeft)
        assertTrue(result.mergedSegments)
        assertEquals(2, result.segmentCount)
    }

    @Test
    fun overlappingSequentialWaysDoNotDoubleLaneCount() {
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
    }

    @Test
    fun separatedParallelRoadDoesNotGetMerged() {
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
}
