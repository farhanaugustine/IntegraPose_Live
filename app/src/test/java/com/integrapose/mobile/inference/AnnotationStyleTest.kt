package com.integrapose.mobile.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AnnotationStyleTest {
    @Test
    fun defaultUsesSmallRoiLabels() {
        assertEquals(RoiLabelSize.SMALL, AnnotationStyle.Default.roiLabelSize)
        assertEquals(1, AnnotationStyle.Default.roiLabelSize.nativeSizeCode)
        assertEquals(false, AnnotationStyle.Default.showClassIndex)
    }

    @Test
    fun numericClassIndexIsIncludedOnlyWhenEnabled() {
        val detection = DetectionResult(
            classIndex = 2,
            className = "grooming",
            confidence = 0.91f,
            box = BoundingBox(0f, 0f, 10f, 10f),
            trackId = 4
        )

        assertEquals("T4 grooming 0.91", formatDetectionLabel(detection, false))
        assertEquals("T4 #2 grooming 0.91", formatDetectionLabel(detection, true))
    }

    @Test
    fun storedRoiLabelSizeFallsBackSafely() {
        assertEquals(
            RoiLabelSize.LARGE,
            RoiLabelSize.fromStoredName("LARGE", RoiLabelSize.SMALL)
        )
        assertEquals(
            RoiLabelSize.SMALL,
            RoiLabelSize.fromStoredName("not-a-size", RoiLabelSize.SMALL)
        )
    }

    @Test
    fun roiColorIsStableForPersistentIdentifier() {
        val first = RoiAnnotationPalette.argbFor("roi_123")
        val afterRename = RoiAnnotationPalette.argbFor("roi_123")

        assertEquals(first, afterRename)
        assertNotEquals(first, RoiAnnotationPalette.argbFor("roi_124"))
    }
}
