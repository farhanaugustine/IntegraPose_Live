package com.integrapose.mobile.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientedInferenceMapperTest {
    @Test
    fun mapsBoxesAndKeypointsIntoRotatedCropCoordinates() {
        val result = FrameInferenceResult(
            timestampMs = 1L,
            imageWidth = 200,
            imageHeight = 100,
            detections = listOf(
                DetectionResult(
                    classIndex = 0,
                    className = "animal",
                    confidence = 0.9f,
                    box = BoundingBox(20f, 30f, 60f, 50f),
                    keypoints = listOf(Keypoint(30f, 40f, 0.8f))
                )
            ),
            inferenceMs = 5L
        )
        val geometry = OrientedCropGeometry(
            left = 10,
            top = 20,
            right = 110,
            bottom = 70,
            rotationDegrees = 90
        )

        val mapped = result.mapToOrientedCrop(geometry)

        assertEquals(50, mapped.imageWidth)
        assertEquals(100, mapped.imageHeight)
        assertEquals(BoundingBox(20f, 10f, 40f, 50f), mapped.detections.single().box)
        assertEquals(Keypoint(30f, 20f, 0.8f), mapped.detections.single().keypoints.single())
    }

    @Test
    fun frontCameraMirrorRunsAfterRotation() {
        val result = FrameInferenceResult(
            timestampMs = 1L,
            imageWidth = 100,
            imageHeight = 50,
            detections = listOf(
                DetectionResult(
                    classIndex = 0,
                    className = "animal",
                    confidence = 0.9f,
                    box = BoundingBox(10f, 10f, 30f, 20f)
                )
            ),
            inferenceMs = 5L
        )
        val geometry = OrientedCropGeometry(
            left = 0,
            top = 0,
            right = 100,
            bottom = 50,
            rotationDegrees = 0,
            mirrorHorizontally = true
        )

        val box = result.mapToOrientedCrop(geometry).detections.single().box

        assertEquals(BoundingBox(70f, 10f, 90f, 20f), box)
    }
}
