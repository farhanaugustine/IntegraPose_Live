package com.integrapose.mobile.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientedCropGeometryTest {
    @Test
    fun mapsCropCornersAtZeroDegrees() {
        val geometry = geometry(0)

        assertDimensions(geometry, 100, 50)
        assertPoint(geometry, 10f, 20f, 0f, 0f)
        assertPoint(geometry, 110f, 70f, 100f, 50f)
    }

    @Test
    fun mapsCropCornersAtNinetyDegrees() {
        val geometry = geometry(90)

        assertDimensions(geometry, 50, 100)
        assertPoint(geometry, 10f, 20f, 50f, 0f)
        assertPoint(geometry, 110f, 20f, 50f, 100f)
        assertPoint(geometry, 10f, 70f, 0f, 0f)
    }

    @Test
    fun mapsCropCornersAtOneHundredEightyDegrees() {
        val geometry = geometry(180)

        assertDimensions(geometry, 100, 50)
        assertPoint(geometry, 10f, 20f, 100f, 50f)
        assertPoint(geometry, 110f, 70f, 0f, 0f)
    }

    @Test
    fun mapsCropCornersAtTwoHundredSeventyDegrees() {
        val geometry = geometry(270)

        assertDimensions(geometry, 50, 100)
        assertPoint(geometry, 10f, 20f, 0f, 100f)
        assertPoint(geometry, 110f, 20f, 0f, 0f)
        assertPoint(geometry, 10f, 70f, 50f, 100f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRightAngleRotation() {
        geometry(45)
    }

    @Test
    fun mirrorsDisplayCoordinatesAfterRotation() {
        val zero = geometry(0, mirrorHorizontally = true)
        assertPoint(zero, 10f, 20f, 100f, 0f)
        assertPoint(zero, 110f, 70f, 0f, 50f)

        val ninety = geometry(90, mirrorHorizontally = true)
        assertPoint(ninety, 10f, 20f, 0f, 0f)
        assertPoint(ninety, 10f, 70f, 50f, 0f)
    }

    private fun geometry(
        rotationDegrees: Int,
        mirrorHorizontally: Boolean = false
    ) = OrientedCropGeometry(
        left = 10,
        top = 20,
        right = 110,
        bottom = 70,
        rotationDegrees = rotationDegrees,
        mirrorHorizontally = mirrorHorizontally
    )

    private fun assertDimensions(
        geometry: OrientedCropGeometry,
        width: Int,
        height: Int
    ) {
        assertEquals(width, geometry.outputWidth)
        assertEquals(height, geometry.outputHeight)
    }

    private fun assertPoint(
        geometry: OrientedCropGeometry,
        sourceX: Float,
        sourceY: Float,
        expectedX: Float,
        expectedY: Float
    ) {
        val mapped = geometry.map(sourceX, sourceY)
        assertEquals(expectedX, mapped.x, 0.0001f)
        assertEquals(expectedY, mapped.y, 0.0001f)
    }
}
