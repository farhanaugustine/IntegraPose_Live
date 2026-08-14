package com.integrapose.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypointSkeletonTest {
    @Test
    fun parsesCommaAndLineSeparatedConnections() {
        assertEquals(
            listOf(
                KeypointConnection(0, 1),
                KeypointConnection(1, 2),
                KeypointConnection(2, 4)
            ),
            parseKeypointConnections("0-1, 1 -> 2\n2:4")
        )
    }

    @Test
    fun emptyTextMeansKeypointsOnly() {
        assertTrue(parseKeypointConnections("  ").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateUndirectedConnections() {
        parseKeypointConnections("0-1, 1-0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSelfConnections() {
        parseKeypointConnections("3-3")
    }
}
