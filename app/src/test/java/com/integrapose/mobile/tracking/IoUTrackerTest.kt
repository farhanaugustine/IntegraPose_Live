package com.integrapose.mobile.tracking

import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IoUTrackerTest {
    @Test
    fun behaviorClassChangeKeepsTheSameTrackIdentity() {
        val tracker = IoUTracker()
        val first = tracker.update(listOf(detection(0, 10f)), 0).single()
        val second = tracker.update(listOf(detection(1, 11f)), 1).single()

        assertEquals(first.trackId, second.trackId)
    }

    @Test
    fun separatedAnimalsReceiveDifferentTrackIdentities() {
        val tracker = IoUTracker()
        val detections = tracker.update(
            listOf(detection(0, 10f), detection(0, 70f)),
            0
        )

        assertNotEquals(detections[0].trackId, detections[1].trackId)
    }

    @Test
    fun lostTrackBufferKeepsIdentityUntilConfiguredFrameLimit() {
        val tracker = IoUTracker(IoUTrackerConfig(maxMissingFrames = 2))
        val firstId = tracker.update(listOf(detection(0, 10f)), 0).single().trackId

        tracker.update(emptyList(), 1)
        val recoveredId = tracker.update(listOf(detection(0, 11f)), 2)
            .single()
            .trackId

        assertEquals(firstId, recoveredId)
    }

    @Test
    fun expiredLostTrackBufferAssignsNewIdentity() {
        val tracker = IoUTracker(IoUTrackerConfig(maxMissingFrames = 2))
        val firstId = tracker.update(listOf(detection(0, 10f)), 0).single().trackId

        tracker.update(emptyList(), 1)
        tracker.update(emptyList(), 2)
        val newId = tracker.update(listOf(detection(0, 11f)), 3)
            .single()
            .trackId

        assertNotEquals(firstId, newId)
    }

    @Test
    fun invalidConfigurationIsSanitizedToSupportedBounds() {
        val sanitized = IoUTrackerConfig(
            minimumConfidence = Float.NaN,
            newTrackConfidence = -2f,
            matchIoU = 4f,
            maxMissingFrames = Int.MAX_VALUE
        ).sanitized()

        assertEquals(0.10f, sanitized.minimumConfidence, 0.0001f)
        assertEquals(0.10f, sanitized.newTrackConfidence, 0.0001f)
        assertEquals(1f, sanitized.matchIoU, 0.0001f)
        assertEquals(
            IoUTrackerConfig.MAX_MISSING_FRAMES,
            sanitized.maxMissingFrames
        )
    }

    private fun detection(classIndex: Int, left: Float): DetectionResult =
        DetectionResult(
            classIndex = classIndex,
            className = "class_$classIndex",
            confidence = 0.9f,
            box = BoundingBox(left, 10f, left + 20f, 30f)
        )
}
