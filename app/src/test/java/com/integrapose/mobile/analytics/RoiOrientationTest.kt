package com.integrapose.mobile.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class RoiOrientationTest {
    @Test
    fun uprightRoiMapsBackToNinetyDegreeEncodedFrame() {
        val upright = BehaviorRoi(
            id = "arena",
            name = "Arena",
            left = 0.65f,
            top = 0.15f,
            right = 0.75f,
            bottom = 0.25f
        )

        val source = upright.toSourceOrientation(90)

        assertEquals(0.15f, source.left, 0.0001f)
        assertEquals(0.25f, source.top, 0.0001f)
        assertEquals(0.25f, source.right, 0.0001f)
        assertEquals(0.35f, source.bottom, 0.0001f)
    }

    @Test
    fun noRotationPreservesRoiCoordinates() {
        val roi = BehaviorRoi("arena", "Arena", 0.1f, 0.2f, 0.8f, 0.9f)

        assertEquals(roi, roi.toSourceOrientation(0))
    }
}
