package com.integrapose.mobile.offline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.integrapose.mobile.analytics.BehaviorRoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiEditorGeometryTest {
    private val roi = BehaviorRoi("a", "Arena", 0.2f, 0.3f, 0.6f, 0.7f)

    @Test
    fun movePreservesSizeAndClampsToFrame() {
        val moved = roi.movedBy(0.8f, -0.8f)
        assertEquals(0.6f, moved.left, 0.0001f)
        assertEquals(1f, moved.right, 0.0001f)
        assertEquals(0f, moved.top, 0.0001f)
        assertEquals(0.4f, moved.bottom, 0.0001f)
    }

    @Test
    fun resizeUsesSelectedCornerAndKeepsMinimumSize() {
        val resized = roi.resizedFrom(RoiCorner.TOP_LEFT, 0.9f, 0.95f)
        assertEquals(roi.right - MINIMUM_ROI_SIZE, resized.left, 0.0001f)
        assertEquals(roi.bottom - MINIMUM_ROI_SIZE, resized.top, 0.0001f)
        assertTrue(resized.right > resized.left)
        assertTrue(resized.bottom > resized.top)
    }

    @Test
    fun cornerHitTestHonorsIndependentDisplayTolerances() {
        assertEquals(
            RoiCorner.BOTTOM_RIGHT,
            roi.cornerNear(0.61f, 0.69f, 0.02f, 0.02f)
        )
    }

    @Test
    fun zoomedViewportStillMapsTouchesToSourceNormalizedCoordinates() {
        val rect = roiImageRect(
            container = IntSize(1_000, 500),
            imageWidth = 1_000,
            imageHeight = 1_000,
            zoom = 2f,
            pan = Offset(100f, 0f)
        )

        val center = requireNotNull(
            toRoiNormalized(Offset(600f, 250f), rect, clamp = false)
        )
        assertEquals(0.5f, center.x, 0.0001f)
        assertEquals(0.5f, center.y, 0.0001f)
    }

    @Test
    fun viewportPanIsBoundedAtEveryZoomLevel() {
        val clamped = clampRoiViewportPan(
            container = IntSize(1_000, 500),
            imageWidth = 1_000,
            imageHeight = 1_000,
            zoom = 3f,
            pan = Offset(5_000f, -5_000f)
        )

        assertEquals(500f, clamped.x, 0.0001f)
        assertEquals(-500f, clamped.y, 0.0001f)
    }
}
