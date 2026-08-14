package com.integrapose.mobile.analytics

import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import com.integrapose.mobile.inference.Keypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiDwellTrackerTest {
    private val roi = BehaviorRoi("arena", "Arena", 0.25f, 0.25f, 0.75f, 0.75f)

    @Test
    fun regularRoiDefaultsAreStable() {
        val settings = RoiAnalyticsSettings()

        assertEquals(0.75f, settings.entryThreshold)
        assertEquals(0.25f, settings.exitThreshold)
        assertEquals(5, settings.maxGapFrames)
        assertEquals(3, settings.minDwellFrames)
        assertEquals(RoiAnchorMode.BOUNDING_BOX_CENTER, settings.anchorMode)
    }

    @Test
    fun visitFramesAreInclusive() {
        val tracker = tracker(maxGap = 0, minDwell = 1)
        tracker.onFrame(0, 100, 100, emptyList())
        tracker.onFrame(1, 100, 100, listOf(detection(box = box(40f, 40f, 60f, 60f))))
        tracker.onFrame(2, 100, 100, listOf(detection(box = box(40f, 40f, 60f, 60f))))
        tracker.onFrame(3, 100, 100, listOf(detection(box = box(0f, 0f, 20f, 20f))))

        val visit = tracker.finish(3, 30.0).single()
        assertEquals(1, visit.entryFrame)
        assertEquals(2, visit.endFrame)
        assertEquals(2, visit.dwellFrames)
        assertEquals(2, visit.observedFrames)
    }

    @Test
    fun shortMissingGapIsBridged() {
        val tracker = tracker(maxGap = 2, minDwell = 1)
        tracker.onFrame(0, 100, 100, listOf(detection()))
        tracker.onFrame(1, 100, 100, emptyList())
        tracker.onFrame(2, 100, 100, listOf(detection()))

        val visit = tracker.finish(2, 30.0).single()
        assertEquals(3, visit.dwellFrames)
        assertEquals(2, visit.observedFrames)
        assertEquals(1, visit.bridgedFrames)
        assertEquals(1, visit.maximumBridgedGapFrames)
    }

    @Test
    fun minimumDwellUsesInclusiveGapClosedDuration() {
        val tooShort = RoiDwellTracker(listOf(roi), RoiAnalyticsSettings())
        tooShort.onFrame(0, 100, 100, listOf(detection()))
        tooShort.onFrame(1, 100, 100, listOf(detection()))
        assertTrue(tooShort.finish(1, 30.0).isEmpty())

        val qualified = RoiDwellTracker(listOf(roi), RoiAnalyticsSettings())
        qualified.onFrame(0, 100, 100, listOf(detection()))
        qualified.onFrame(1, 100, 100, listOf(detection()))
        qualified.onFrame(2, 100, 100, listOf(detection()))
        assertEquals(3, qualified.finish(2, 30.0).single().dwellFrames)
    }

    @Test
    fun boundingBoxEntryAndExitThresholdsProvideHysteresis() {
        val tracker = tracker(maxGap = 0, minDwell = 1)
        tracker.onFrame(0, 100, 100, listOf(detection(box = box(40f, 40f, 60f, 60f))))
        tracker.onFrame(1, 100, 100, listOf(detection(box = box(0f, 40f, 40f, 60f))))
        tracker.onFrame(2, 100, 100, listOf(detection(box = box(0f, 40f, 20f, 60f))))

        val visit = tracker.finish(2, 30.0).single()
        assertEquals(0, visit.entryFrame)
        assertEquals(1, visit.endFrame)
        assertEquals(2, visit.dwellFrames)
    }

    @Test
    fun selectedKeypointControlsMembershipForPoseModels() {
        val settings = RoiAnalyticsSettings(
            anchorMode = RoiAnchorMode.KEYPOINT,
            keypointIndex = 1,
            maxGapFrames = 0,
            minDwellFrames = 1
        )
        val tracker = RoiDwellTracker(listOf(roi), settings)
        val points = listOf(
            Keypoint(5f, 5f, 0.9f),
            Keypoint(50f, 50f, 0.9f)
        )
        tracker.onFrame(0, 100, 100, listOf(detection(box = box(0f, 0f, 10f, 10f), keypoints = points)))

        val visit = tracker.finish(0, 30.0).single()
        assertEquals(1, visit.anchorKeypointIndex)
        assertEquals(RoiAnchorMode.KEYPOINT, visit.anchorMode)
    }

    @Test
    fun noValidKeypointsFallsBackToBoundingBox() {
        val settings = RoiAnalyticsSettings(
            anchorMode = RoiAnchorMode.KEYPOINT,
            keypointIndex = 0,
            maxGapFrames = 0,
            minDwellFrames = 1
        )
        val tracker = RoiDwellTracker(listOf(roi), settings)
        tracker.onFrame(0, 100, 100, listOf(keypointDetection(0f, 5f, 5f)))

        assertEquals(1, tracker.finish(0, 30.0).single().dwellFrames)
    }

    @Test
    fun invalidSelectedKeypointDoesNotSubstituteAnotherValidIndex() {
        val tracker = RoiDwellTracker(
            listOf(roi),
            RoiAnalyticsSettings(
                anchorMode = RoiAnchorMode.KEYPOINT,
                keypointIndex = 0,
                maxGapFrames = 0,
                minDwellFrames = 1
            )
        )
        tracker.onFrame(
            0,
            100,
            100,
            listOf(
                detection(
                    box = box(0f, 0f, 10f, 10f),
                    keypoints = listOf(
                        Keypoint(50f, 50f, 0f),
                        Keypoint(50f, 50f, 0.9f)
                    )
                )
            )
        )

        assertTrue(tracker.finish(0, 30.0).isEmpty())
    }

    @Test
    fun roiCoordinatesFollowFrameRotation() {
        val rotatedRoi = BehaviorRoi("rotated", "Rotated", 0.65f, 0.15f, 0.75f, 0.25f)
        val tracker = RoiDwellTracker(
            listOf(rotatedRoi),
            RoiAnalyticsSettings(
                anchorMode = RoiAnchorMode.KEYPOINT,
                maxGapFrames = 0,
                minDwellFrames = 1
            )
        )
        tracker.onFrame(0, 100, 100, listOf(keypointDetection(0.9f, 20f, 30f)), 90)

        assertEquals("rotated", tracker.finish(0, 30.0).single().roi.id)
    }

    @Test
    fun detectionsWithoutTrackIdentityAreIgnored() {
        val tracker = tracker(maxGap = 0, minDwell = 1)
        tracker.onFrame(0, 100, 100, listOf(detection(trackId = null)))

        assertTrue(tracker.finish(0, 30.0).isEmpty())
    }

    private fun tracker(maxGap: Int, minDwell: Int) = RoiDwellTracker(
        listOf(roi),
        RoiAnalyticsSettings(maxGapFrames = maxGap, minDwellFrames = minDwell)
    )

    private fun keypointDetection(
        confidence: Float,
        x: Float = 50f,
        y: Float = 50f
    ) = detection(keypoints = listOf(Keypoint(x, y, confidence)))

    private fun detection(
        box: BoundingBox = box(40f, 40f, 60f, 60f),
        keypoints: List<Keypoint> = emptyList(),
        trackId: Int? = 7
    ) = DetectionResult(
        classIndex = 0,
        className = "grooming",
        confidence = 0.9f,
        box = box,
        keypoints = keypoints,
        trackId = trackId
    )

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        BoundingBox(left, top, right, bottom)
}
