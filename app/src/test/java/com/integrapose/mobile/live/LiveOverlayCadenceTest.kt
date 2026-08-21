package com.integrapose.mobile.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOverlayCadenceTest {
    @Test
    fun fifteenHzPublishesHalfOfThirtyHzCameraFrames() {
        val cadence = LiveOverlayCadence()
        val published = (0 until 30).count { frame ->
            cadence.shouldPublish(frame * 33_333L, maximumFps = 15)
        }

        assertEquals(15, published)
    }

    @Test
    fun twentyHzUsesFractionalCadenceInsteadOfFifteenHzThresholding() {
        val cadence = LiveOverlayCadence()
        val published = (0 until 30).count { frame ->
            cadence.shouldPublish(frame * 33_333L, maximumFps = 20)
        }

        assertTrue(published in 19..20)
    }

    @Test
    fun unrestrictedCadencePublishesEveryFrame() {
        val cadence = LiveOverlayCadence()
        assertEquals(30, (0 until 30).count { cadence.shouldPublish(it * 33_333L, null) })
    }
}
