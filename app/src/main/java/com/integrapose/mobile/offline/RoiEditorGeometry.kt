package com.integrapose.mobile.offline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.integrapose.mobile.analytics.BehaviorRoi
import kotlin.math.abs
import kotlin.math.min

internal enum class RoiCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT
}

internal fun BehaviorRoi.containsNormalized(x: Float, y: Float): Boolean =
    x in left..right && y in top..bottom

internal fun BehaviorRoi.cornerNear(
    x: Float,
    y: Float,
    toleranceX: Float,
    toleranceY: Float
): RoiCorner? = listOf(
    RoiCorner.TOP_LEFT to (left to top),
    RoiCorner.TOP_RIGHT to (right to top),
    RoiCorner.BOTTOM_RIGHT to (right to bottom),
    RoiCorner.BOTTOM_LEFT to (left to bottom)
).firstOrNull { (_, point) ->
    abs(x - point.first) <= toleranceX &&
        abs(y - point.second) <= toleranceY
}?.first

internal fun BehaviorRoi.movedBy(
    deltaX: Float,
    deltaY: Float
): BehaviorRoi {
    val width = right - left
    val height = bottom - top
    val newLeft = (left + deltaX).coerceIn(0f, (1f - width).coerceAtLeast(0f))
    val newTop = (top + deltaY).coerceIn(0f, (1f - height).coerceAtLeast(0f))
    return copy(
        left = newLeft,
        top = newTop,
        right = newLeft + width,
        bottom = newTop + height
    )
}

internal fun BehaviorRoi.resizedFrom(
    corner: RoiCorner,
    x: Float,
    y: Float,
    minimumSize: Float = MINIMUM_ROI_SIZE
): BehaviorRoi {
    val safeX = x.coerceIn(0f, 1f)
    val safeY = y.coerceIn(0f, 1f)
    return when (corner) {
        RoiCorner.TOP_LEFT -> copy(
            left = safeX.coerceAtMost(right - minimumSize),
            top = safeY.coerceAtMost(bottom - minimumSize)
        )
        RoiCorner.TOP_RIGHT -> copy(
            right = safeX.coerceAtLeast(left + minimumSize),
            top = safeY.coerceAtMost(bottom - minimumSize)
        )
        RoiCorner.BOTTOM_RIGHT -> copy(
            right = safeX.coerceAtLeast(left + minimumSize),
            bottom = safeY.coerceAtLeast(top + minimumSize)
        )
        RoiCorner.BOTTOM_LEFT -> copy(
            left = safeX.coerceAtMost(right - minimumSize),
            bottom = safeY.coerceAtLeast(top + minimumSize)
        )
    }.sanitized()
}

internal fun roiImageRect(
    container: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    zoom: Float = MIN_ROI_ZOOM,
    pan: Offset = Offset.Zero
): Rect {
    if (
        container.width <= 0 || container.height <= 0 ||
        imageWidth <= 0 || imageHeight <= 0
    ) {
        return Rect.Zero
    }
    val fitScale = min(
        container.width.toFloat() / imageWidth,
        container.height.toFloat() / imageHeight
    )
    val safeZoom = zoom.coerceIn(MIN_ROI_ZOOM, MAX_ROI_ZOOM)
    val width = imageWidth * fitScale * safeZoom
    val height = imageHeight * fitScale * safeZoom
    val centerX = container.width * 0.5f + pan.x
    val centerY = container.height * 0.5f + pan.y
    return Rect(
        left = centerX - width * 0.5f,
        top = centerY - height * 0.5f,
        right = centerX + width * 0.5f,
        bottom = centerY + height * 0.5f
    )
}

internal fun clampRoiViewportPan(
    container: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    zoom: Float,
    pan: Offset
): Offset {
    val base = roiImageRect(container, imageWidth, imageHeight)
    if (base == Rect.Zero) return Offset.Zero
    val safeZoom = zoom.coerceIn(MIN_ROI_ZOOM, MAX_ROI_ZOOM)
    val maximumX = (base.width * (safeZoom - 1f) * 0.5f).coerceAtLeast(0f)
    val maximumY = (base.height * (safeZoom - 1f) * 0.5f).coerceAtLeast(0f)
    return Offset(
        x = pan.x.coerceIn(-maximumX, maximumX),
        y = pan.y.coerceIn(-maximumY, maximumY)
    )
}

internal fun toRoiNormalized(
    position: Offset,
    imageRect: Rect,
    clamp: Boolean
): Offset? {
    if (imageRect.width <= 0f || imageRect.height <= 0f) return null
    if (!clamp && !imageRect.contains(position)) return null
    return Offset(
        ((position.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f),
        ((position.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f)
    )
}

internal const val MINIMUM_ROI_SIZE = 0.015f
internal const val MIN_ROI_ZOOM = 1f
internal const val MAX_ROI_ZOOM = 5f
