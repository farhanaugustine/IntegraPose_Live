package com.integrapose.mobile.inference

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class OverlayCalibration(
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val rotationDegrees: Float = 0f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f
) {
    fun sanitized(): OverlayCalibration = copy(
        rotationDegrees = rotationDegrees.coerceIn(MIN_ROTATION, MAX_ROTATION),
        offsetXFraction = offsetXFraction.coerceIn(MIN_OFFSET, MAX_OFFSET),
        offsetYFraction = offsetYFraction.coerceIn(MIN_OFFSET, MAX_OFFSET),
        scaleX = scaleX.coerceIn(MIN_SCALE, MAX_SCALE),
        scaleY = scaleY.coerceIn(MIN_SCALE, MAX_SCALE)
    )

    companion object {
        const val MIN_OFFSET = -0.30f
        const val MAX_OFFSET = 0.30f
        const val MIN_ROTATION = -180f
        const val MAX_ROTATION = 180f
        const val MIN_SCALE = 0.50f
        const val MAX_SCALE = 1.50f
        val Default = OverlayCalibration()
    }
}

internal data class OverlayPoint(val x: Float, val y: Float)

internal class OverlayCoordinateTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    private val targetWidth: Float,
    private val targetHeight: Float,
    automaticMirrorX: Boolean,
    calibration: OverlayCalibration
) {
    private val safe = calibration.sanitized()
    private val fitScale = min(
        targetWidth / sourceWidth.toFloat(),
        targetHeight / sourceHeight.toFloat()
    )
    private val fitOffsetX = (targetWidth - sourceWidth * fitScale) / 2f
    private val fitOffsetY = (targetHeight - sourceHeight * fitScale) / 2f
    private val reverseX = automaticMirrorX.xor(safe.flipHorizontal)
    private val reverseY = safe.flipVertical
    private val centerX = targetWidth / 2f
    private val centerY = targetHeight / 2f
    private val rotationRadians = safe.rotationDegrees * (PI.toFloat() / 180f)
    private val rotationCosine = cos(rotationRadians)
    private val rotationSine = sin(rotationRadians)

    fun map(sourceX: Float, sourceY: Float): OverlayPoint {
        return adjustTarget(
            fitOffsetX + sourceX * fitScale,
            fitOffsetY + sourceY * fitScale
        )
    }

    fun adjustTarget(targetX: Float, targetY: Float): OverlayPoint {
        var mappedX = targetX
        var mappedY = targetY
        if (reverseX) mappedX = targetWidth - mappedX
        if (reverseY) mappedY = targetHeight - mappedY
        mappedX = centerX + (mappedX - centerX) * safe.scaleX
        mappedY = centerY + (mappedY - centerY) * safe.scaleY
        val deltaX = mappedX - centerX
        val deltaY = mappedY - centerY
        return OverlayPoint(
            x = centerX + deltaX * rotationCosine - deltaY * rotationSine +
                safe.offsetXFraction * targetWidth,
            y = centerY + deltaX * rotationSine + deltaY * rotationCosine +
                safe.offsetYFraction * targetHeight
        )
    }
}
