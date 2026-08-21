package com.integrapose.mobile.live

import com.integrapose.mobile.inference.OrientedCropGeometry
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic source-buffer to PreviewView mapping used while VideoCapture is
 * bound. It uses the same crop/rotation/mirror geometry as the saved annotation.
 */
internal fun recordingPreviewMatrixValues(
    geometry: OrientedCropGeometry,
    targetWidth: Int,
    targetHeight: Int,
    fillTarget: Boolean
): FloatArray {
    require(targetWidth > 0 && targetHeight > 0) {
        "Preview dimensions must be positive."
    }
    val scaleX = targetWidth.toFloat() / geometry.outputWidth.toFloat()
    val scaleY = targetHeight.toFloat() / geometry.outputHeight.toFloat()
    val scale = if (fillTarget) max(scaleX, scaleY) else min(scaleX, scaleY)
    val offsetX = (targetWidth - geometry.outputWidth * scale) / 2f
    val offsetY = (targetHeight - geometry.outputHeight * scale) / 2f
    val source = geometry.matrixValues()
    return floatArrayOf(
        source[0] * scale,
        source[1] * scale,
        source[2] * scale + offsetX,
        source[3] * scale,
        source[4] * scale,
        source[5] * scale + offsetY,
        0f,
        0f,
        1f
    )
}
