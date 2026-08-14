package com.integrapose.mobile.live

import android.graphics.Bitmap
import android.graphics.Rect
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.inference.OrientedCropGeometry

internal data class LiveRoiPreviewFrame(
    val bitmap: Bitmap,
    val viewport: LiveRoiViewport
)

/** Converts ROI rectangles between full analysis-buffer and visible-preview coordinates. */
internal data class LiveRoiViewport(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val rotationDegrees: Int,
    val mirrorHorizontally: Boolean
) {
    private val geometry = OrientedCropGeometry(
        left = cropLeft,
        top = cropTop,
        right = cropRight,
        bottom = cropBottom,
        rotationDegrees = rotationDegrees,
        mirrorHorizontally = mirrorHorizontally
    )
    private val normalizedRotation = ((rotationDegrees % 360) + 360) % 360

    init {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "ROI source dimensions must be positive."
        }
        require(cropLeft >= 0 && cropTop >= 0 &&
            cropRight <= sourceWidth && cropBottom <= sourceHeight
        ) {
            "ROI crop must stay inside the source frame."
        }
    }

    fun cropRect(): Rect = Rect(cropLeft, cropTop, cropRight, cropBottom)

    fun toEditorRoi(roi: BehaviorRoi): BehaviorRoi? {
        val safe = roi.sanitized()
        val left = maxOf(safe.left * sourceWidth, cropLeft.toFloat())
        val top = maxOf(safe.top * sourceHeight, cropTop.toFloat())
        val right = minOf(safe.right * sourceWidth, cropRight.toFloat())
        val bottom = minOf(safe.bottom * sourceHeight, cropBottom.toFloat())
        if (right <= left || bottom <= top) return null

        val mapped = listOf(
            geometry.map(left, top),
            geometry.map(right, top),
            geometry.map(right, bottom),
            geometry.map(left, bottom)
        )
        return safe.copy(
            left = mapped.minOf { it.x } / geometry.outputWidth,
            top = mapped.minOf { it.y } / geometry.outputHeight,
            right = mapped.maxOf { it.x } / geometry.outputWidth,
            bottom = mapped.maxOf { it.y } / geometry.outputHeight
        ).sanitized()
    }

    fun toSourceRoi(roi: BehaviorRoi): BehaviorRoi {
        val safe = roi.sanitized()
        val outputWidth = geometry.outputWidth.toFloat()
        val outputHeight = geometry.outputHeight.toFloat()
        val sourcePoints = listOf(
            inverseMap(safe.left * outputWidth, safe.top * outputHeight),
            inverseMap(safe.right * outputWidth, safe.top * outputHeight),
            inverseMap(safe.right * outputWidth, safe.bottom * outputHeight),
            inverseMap(safe.left * outputWidth, safe.bottom * outputHeight)
        )
        return safe.copy(
            left = sourcePoints.minOf { it.first } / sourceWidth,
            top = sourcePoints.minOf { it.second } / sourceHeight,
            right = sourcePoints.maxOf { it.first } / sourceWidth,
            bottom = sourcePoints.maxOf { it.second } / sourceHeight
        ).sanitized()
    }

    private fun inverseMap(displayX: Float, displayY: Float): Pair<Float, Float> {
        val orientedX = if (mirrorHorizontally) {
            geometry.outputWidth - displayX
        } else {
            displayX
        }
        return when (normalizedRotation) {
            0 -> Pair(orientedX + cropLeft, displayY + cropTop)
            90 -> Pair(displayY + cropLeft, cropBottom - orientedX)
            180 -> Pair(cropRight - orientedX, cropBottom - displayY)
            else -> Pair(cropRight - displayY, orientedX + cropTop)
        }
    }

    companion object {
        fun fromFrame(
            sourceWidth: Int,
            sourceHeight: Int,
            cropRect: Rect,
            rotationDegrees: Int,
            mirrorHorizontally: Boolean
        ): LiveRoiViewport {
            val safe = Rect(cropRect)
            require(safe.intersect(0, 0, sourceWidth, sourceHeight)) {
                "The ROI crop does not intersect the source frame."
            }
            safe.right -= safe.width() % 2
            safe.bottom -= safe.height() % 2
            return LiveRoiViewport(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                cropLeft = safe.left,
                cropTop = safe.top,
                cropRight = safe.right,
                cropBottom = safe.bottom,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = mirrorHorizontally
            )
        }
    }
}
