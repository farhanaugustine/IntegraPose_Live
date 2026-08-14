package com.integrapose.mobile.live

import com.integrapose.mobile.analytics.BehaviorRoi
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRoiViewportTest {
    @Test
    fun fullCropMapsToFullEditorAtEveryRotationAndMirrorMode() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            listOf(false, true).forEach { mirror ->
                val viewport = viewport(rotation, mirror)
                val sourceCrop = BehaviorRoi(
                    id = "crop",
                    name = "Crop",
                    left = 0.1f,
                    top = 0.1f,
                    right = 0.9f,
                    bottom = 0.9f
                )

                val editor = requireNotNull(viewport.toEditorRoi(sourceCrop))
                assertRoi(editor, 0f, 0f, 1f, 1f)
            }
        }
    }

    @Test
    fun editorRoundTripPreservesSourceCoordinates() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            listOf(false, true).forEach { mirror ->
                val viewport = viewport(rotation, mirror)
                val source = BehaviorRoi(
                    id = "subject",
                    name = "Subject",
                    left = 0.25f,
                    top = 0.2f,
                    right = 0.65f,
                    bottom = 0.7f
                )

                val editor = requireNotNull(viewport.toEditorRoi(source))
                val restored = viewport.toSourceRoi(editor)
                assertRoi(restored, source.left, source.top, source.right, source.bottom)
            }
        }
    }

    @Test
    fun roiOutsideVisibleCropIsNotOfferedToEditor() {
        val outside = BehaviorRoi(
            id = "outside",
            name = "Outside",
            left = 0f,
            top = 0f,
            right = 0.05f,
            bottom = 0.05f
        )

        assertEquals(null, viewport(0, false).toEditorRoi(outside))
    }

    private fun viewport(rotation: Int, mirror: Boolean) = LiveRoiViewport(
        sourceWidth = 200,
        sourceHeight = 100,
        cropLeft = 20,
        cropTop = 10,
        cropRight = 180,
        cropBottom = 90,
        rotationDegrees = rotation,
        mirrorHorizontally = mirror
    )

    private fun assertRoi(
        roi: BehaviorRoi,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        assertEquals(left, roi.left, 0.0001f)
        assertEquals(top, roi.top, 0.0001f)
        assertEquals(right, roi.right, 0.0001f)
        assertEquals(bottom, roi.bottom, 0.0001f)
    }
}
