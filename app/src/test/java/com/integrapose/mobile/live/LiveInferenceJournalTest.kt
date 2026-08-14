package com.integrapose.mobile.live

import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.Keypoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveInferenceJournalTest {
    @Test
    fun roundTripPreservesMappedBoxesKeypointsAndUnicodeLabels() {
        val file = File.createTempFile("integrapose_live_", ".journal")
        try {
            val expected = FrameInferenceResult(
                timestampMs = 123L,
                sourceTimestampUs = 456_789L,
                imageWidth = 1_920,
                imageHeight = 1_080,
                detections = listOf(
                    DetectionResult(
                        classIndex = 2,
                        className = "grooming β",
                        confidence = 0.91f,
                        box = BoundingBox(101.5f, 202.5f, 303.5f, 404.5f),
                        keypoints = listOf(Keypoint(150.25f, 250.75f, 0.88f)),
                        trackId = 42
                    )
                ),
                inferenceMs = 17L,
                preprocessingMs = 2L,
                postprocessingMs = 3L,
                backend = "NCNN Vulkan",
                modelInputWidth = 640,
                modelInputHeight = 384
            )
            LiveInferenceJournal(file).use { it.append(9L, expected) }

            val replayed = mutableListOf<Pair<Long, FrameInferenceResult>>()
            LiveInferenceJournal.forEachFrame(file) { frameIndex, result ->
                replayed += frameIndex to result
            }

            val (frameIndex, actual) = replayed.single()
            assertEquals(9L, frameIndex)
            assertEquals(expected, actual)
        } finally {
            file.delete()
        }
    }
}
