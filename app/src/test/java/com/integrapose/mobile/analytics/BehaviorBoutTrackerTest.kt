package com.integrapose.mobile.analytics

import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorBoutTrackerTest {
    @Test
    fun bridgesOnlyMissingFramesFlankedByTheSameClass() {
        val tracker = BehaviorBoutTracker(
            BoutSettings(minBoutFrames = 1, maxGapFrames = 3)
        )
        tracker.onFrame(0, listOf(detection(classIndex = 0)))
        tracker.onFrame(1, emptyList())
        tracker.onFrame(2, emptyList())
        tracker.onFrame(3, listOf(detection(classIndex = 0)))

        val bout = tracker.finish(3, 30.0).single()
        assertEquals(4, bout.durationFrames)
        assertEquals(2, bout.detectionCount)
        assertEquals(2, bout.bridgedFrames)
        assertEquals(2, bout.maximumBridgedGapFrames)
        assertEquals(0.5, bout.observedFraction, 0.0001)
    }

    @Test
    fun explicitOtherBehaviorBreaksBoutEvenWithinGapTolerance() {
        val tracker = BehaviorBoutTracker(
            BoutSettings(minBoutFrames = 1, maxGapFrames = 10)
        )
        tracker.onFrame(0, listOf(detection(classIndex = 0)))
        tracker.onFrame(1, listOf(detection(classIndex = 1)))
        tracker.onFrame(2, listOf(detection(classIndex = 0)))

        val bouts = tracker.finish(2, 30.0)
        assertEquals(listOf(0, 1, 0), bouts.map { it.classIndex })
        assertTrue(bouts.all { it.durationFrames == 1 })
    }

    @Test
    fun minimumDurationUsesGapClosedTimeSpan() {
        val tracker = BehaviorBoutTracker(
            BoutSettings(minBoutFrames = 5, maxGapFrames = 4)
        )
        tracker.onFrame(0, listOf(detection(classIndex = 0)))
        tracker.onFrame(4, listOf(detection(classIndex = 0)))

        val bout = tracker.finish(4, 10.0).single()
        assertEquals(5, bout.durationFrames)
        assertEquals(3, bout.bridgedFrames)
    }

    @Test
    fun highestConfidenceWinsSameTrackFrameConflict() {
        val tracker = BehaviorBoutTracker(
            BoutSettings(minBoutFrames = 1, maxGapFrames = 0)
        )
        tracker.onFrame(
            0,
            listOf(
                detection(classIndex = 0, confidence = 0.2f),
                detection(classIndex = 1, confidence = 0.9f)
            )
        )

        val bout = tracker.finish(0, 30.0).single()
        assertEquals(1, bout.classIndex)
        assertEquals(1, bout.resolvedClassConflictFrames)
        assertEquals(0, bout.concurrentClassFrames)
        assertEquals("inclusive_start_and_end_frames", bout.intervalSemantics)
    }

    private fun detection(
        classIndex: Int,
        confidence: Float = 0.9f
    ): DetectionResult = DetectionResult(
        classIndex = classIndex,
        className = "class_\$classIndex",
        confidence = confidence,
        box = BoundingBox(10f, 10f, 20f, 20f),
        trackId = 7
    )
}
