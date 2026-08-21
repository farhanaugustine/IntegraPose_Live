package com.integrapose.mobile.live

import com.integrapose.mobile.inference.OrientedCropGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRecordingPreviewTransformTest {
    @Test
    fun fitCenterMapsTheSharedCropWithoutRecordingRebindOffsets() {
        val geometry = OrientedCropGeometry(
            left = 10,
            top = 20,
            right = 110,
            bottom = 70,
            rotationDegrees = 0
        )

        val matrix = recordingPreviewMatrixValues(
            geometry = geometry,
            targetWidth = 200,
            targetHeight = 200,
            fillTarget = false
        )

        assertPoint(matrix, 10f, 20f, 0f, 50f)
        assertPoint(matrix, 110f, 70f, 200f, 150f)
    }

    @Test
    fun fillCenterUsesTheSameCropButClipsSymmetrically() {
        val geometry = OrientedCropGeometry(
            left = 10,
            top = 20,
            right = 110,
            bottom = 70,
            rotationDegrees = 0
        )

        val matrix = recordingPreviewMatrixValues(
            geometry = geometry,
            targetWidth = 200,
            targetHeight = 200,
            fillTarget = true
        )

        assertPoint(matrix, 10f, 20f, -100f, 0f)
        assertPoint(matrix, 110f, 70f, 300f, 200f)
    }

    @Test
    fun portraitRotationAndFrontMirrorMatchRecordedGeometry() {
        val geometry = OrientedCropGeometry(
            left = 0,
            top = 0,
            right = 100,
            bottom = 50,
            rotationDegrees = 90,
            mirrorHorizontally = true
        )

        val matrix = recordingPreviewMatrixValues(
            geometry = geometry,
            targetWidth = 100,
            targetHeight = 200,
            fillTarget = false
        )

        assertPoint(matrix, 0f, 0f, 0f, 0f)
        assertPoint(matrix, 100f, 50f, 100f, 200f)
    }

    private fun assertPoint(
        matrix: FloatArray,
        sourceX: Float,
        sourceY: Float,
        expectedX: Float,
        expectedY: Float
    ) {
        val actualX = matrix[0] * sourceX + matrix[1] * sourceY + matrix[2]
        val actualY = matrix[3] * sourceX + matrix[4] * sourceY + matrix[5]
        assertEquals(expectedX, actualX, 0.0001f)
        assertEquals(expectedY, actualY, 0.0001f)
    }
}
