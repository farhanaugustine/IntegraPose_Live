package com.integrapose.mobile.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayCalibrationTest {
    @Test
    fun defaultCalibrationPreservesFitCenteredCoordinates() {
        val transform = OverlayCoordinateTransform(
            100, 50, 200f, 200f, false, OverlayCalibration.Default
        )

        val topLeft = transform.map(0f, 0f)
        val bottomRight = transform.map(100f, 50f)
        assertEquals(0f, topLeft.x, 0.001f)
        assertEquals(50f, topLeft.y, 0.001f)
        assertEquals(200f, bottomRight.x, 0.001f)
        assertEquals(150f, bottomRight.y, 0.001f)
    }

    @Test
    fun horizontalAndVerticalFlipsCanBeCombined() {
        val transform = OverlayCoordinateTransform(
            100,
            100,
            200f,
            200f,
            false,
            OverlayCalibration(flipHorizontal = true, flipVertical = true)
        )

        val first = transform.map(0f, 0f)
        val second = transform.map(100f, 100f)
        assertEquals(200f, first.x, 0.001f)
        assertEquals(200f, first.y, 0.001f)
        assertEquals(0f, second.x, 0.001f)
        assertEquals(0f, second.y, 0.001f)
    }

    @Test
    fun customRotationAppliesAfterFlipsAndScale() {
        val transform = OverlayCoordinateTransform(
            100,
            100,
            200f,
            200f,
            false,
            OverlayCalibration(rotationDegrees = 90f)
        )

        val point = transform.map(100f, 50f)
        assertEquals(100f, point.x, 0.001f)
        assertEquals(200f, point.y, 0.001f)
    }

    @Test
    fun offsetsAndIndependentScaleApplyAroundPreviewCenter() {
        val transform = OverlayCoordinateTransform(
            100,
            100,
            200f,
            200f,
            false,
            OverlayCalibration(
                offsetXFraction = 0.10f,
                offsetYFraction = -0.10f,
                scaleX = 0.50f,
                scaleY = 1.50f
            )
        )

        val first = transform.map(0f, 0f)
        val second = transform.map(100f, 100f)
        assertEquals(70f, first.x, 0.001f)
        assertEquals(-70f, first.y, 0.001f)
        assertEquals(170f, second.x, 0.001f)
        assertEquals(230f, second.y, 0.001f)
    }
}
