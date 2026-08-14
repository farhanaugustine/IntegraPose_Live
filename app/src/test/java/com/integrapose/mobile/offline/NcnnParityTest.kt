package com.integrapose.mobile.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnParityTest {
    @Test
    fun acceptsSmallFp16DriftAndReorderedDetections() {
        val cpu = predictionFrame(
            classIds = intArrayOf(0, 0),
            boxes = floatArrayOf(0f, 0f, 10f, 10f, 20f, 20f, 30f, 30f),
            confidences = floatArrayOf(0.9f, 0.8f),
            keypoints = floatArrayOf(5f, 5f, 0.9f, 25f, 25f, 0.8f)
        )
        val vulkan = predictionFrame(
            classIds = intArrayOf(0, 0),
            boxes = floatArrayOf(
                20.5f, 20.25f, 30.5f, 30.25f,
                0.5f, 0.25f, 10.5f, 10.25f
            ),
            confidences = floatArrayOf(0.795f, 0.895f),
            keypoints = floatArrayOf(
                25.5f, 25.25f, 0.795f,
                5.5f, 5.25f, 0.895f
            )
        )

        val result = compareNcnnPredictions(listOf(cpu), listOf(vulkan))

        assertTrue(result.passed)
        assertEquals(2, result.detectionsCompared)
        assertEquals(0, result.unmatchedDetections)
        assertTrue(result.maxBoxDeltaPx <= 2f)
        assertTrue(result.maxKeypointDeltaPx <= 2f)
    }

    @Test
    fun rejectsPositionDriftOutsideTolerance() {
        val cpu = predictionFrame()
        val vulkan = predictionFrame(
            boxes = floatArrayOf(3f, 0f, 13f, 10f),
            keypoints = floatArrayOf(8f, 5f, 0.9f)
        )

        val result = compareNcnnPredictions(listOf(cpu), listOf(vulkan))

        assertFalse(result.passed)
        assertTrue(result.maxBoxDeltaPx > result.boxTolerancePx)
        assertTrue(result.maxKeypointDeltaPx > result.keypointTolerancePx)
    }

    @Test
    fun rejectsMissingFramesAndDetections() {
        val cpu = listOf(
            predictionFrame(frameIndex = 0),
            predictionFrame(frameIndex = 1)
        )
        val vulkan = listOf(
            predictionFrame(
                frameIndex = 0,
                classIds = intArrayOf(),
                confidences = floatArrayOf(),
                boxes = floatArrayOf(),
                keypointOffsets = intArrayOf(0),
                keypoints = floatArrayOf()
            )
        )

        val result = compareNcnnPredictions(cpu, vulkan)

        assertFalse(result.passed)
        assertEquals(1, result.frameMismatches)
        assertEquals(1, result.detectionCountMismatchFrames)
        assertEquals(1, result.unmatchedDetections)
    }

    @Test
    fun rejectsTimestampMismatch() {
        val cpu = predictionFrame(sourceTimestampUs = 33_333L)
        val vulkan = predictionFrame(sourceTimestampUs = 33_334L)

        val result = compareNcnnPredictions(listOf(cpu), listOf(vulkan))

        assertFalse(result.passed)
        assertEquals(1, result.timestampMismatches)
    }

    @Test
    fun reportsDistributedRangeAndAllowsKeypointConfidenceDrift() {
        val cpu = listOf(
            predictionFrame(frameIndex = 0),
            predictionFrame(frameIndex = 590)
        )
        val vulkan = listOf(
            predictionFrame(frameIndex = 0),
            predictionFrame(
                frameIndex = 590,
                keypoints = floatArrayOf(5f, 5f, 0.86f)
            )
        )

        val result = compareNcnnPredictions(cpu, vulkan)

        assertTrue(result.passed)
        assertEquals(0, result.firstFrameIndex)
        assertEquals(590, result.lastFrameIndex)
        assertTrue(
            result.maxKeypointConfidenceDelta > result.confidenceTolerance
        )
        assertTrue(
            result.maxKeypointConfidenceDelta <=
                result.keypointConfidenceTolerance
        )
    }

    private fun predictionFrame(
        frameIndex: Int = 0,
        sourceTimestampUs: Long = 0L,
        classIds: IntArray = intArrayOf(0),
        confidences: FloatArray = floatArrayOf(0.9f),
        boxes: FloatArray = floatArrayOf(0f, 0f, 10f, 10f),
        keypointOffsets: IntArray = IntArray(classIds.size + 1) { it },
        keypoints: FloatArray = floatArrayOf(5f, 5f, 0.9f)
    ) = NativePredictionFrame(
        frameIndex = frameIndex,
        sourceTimestampUs = sourceTimestampUs,
        classIds = classIds,
        confidences = confidences,
        boxes = boxes,
        keypointOffsets = keypointOffsets,
        keypoints = keypoints
    )
}
