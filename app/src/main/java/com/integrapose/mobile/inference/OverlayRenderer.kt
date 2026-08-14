package com.integrapose.mobile.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.model.KeypointConnection
import kotlin.math.min
import java.util.Locale

object OverlayRenderer {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(230, 86, 214, 165)
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 28f
    }
    private val keypointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 255, 138, 74)
    }
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(210, 75, 195, 255)
    }
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(235, 255, 205, 64)
    }
    private val roiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 24f
    }

    fun renderBitmap(
        source: Bitmap,
        inference: FrameInferenceResult,
        targetWidth: Int = source.width,
        targetHeight: Int = source.height,
        annotationStyle: AnnotationStyle = AnnotationStyle.Default,
        skeletonConnections: List<KeypointConnection> = emptyList(),
        rois: List<BehaviorRoi> = emptyList()
    ): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val scale = min(
            targetWidth.toFloat() / source.width.toFloat(),
            targetHeight.toFloat() / source.height.toFloat()
        )
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val destination = android.graphics.RectF(
            (targetWidth - scaledWidth) / 2f,
            (targetHeight - scaledHeight) / 2f,
            (targetWidth + scaledWidth) / 2f,
            (targetHeight + scaledHeight) / 2f
        )
        canvas.drawBitmap(source, null, destination, BITMAP_PAINT)
        draw(
            canvas,
            inference,
            targetWidth.toFloat(),
            targetHeight.toFloat(),
            annotationStyle = annotationStyle,
            skeletonConnections = skeletonConnections,
            rois = rois
        )
        return output
    }

    /**
     * Renders a CameraX analysis buffer into the orientation seen by the user,
     * then draws annotations in that display coordinate system. Rotating the
     * scene before drawing keeps all text upright; an MP4 orientation hint
     * would rotate already-rendered labels along with the camera pixels.
     */
    fun renderOrientedCropBitmap(
        source: Bitmap,
        inference: FrameInferenceResult,
        cropRect: Rect,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean = false,
        annotationStyle: AnnotationStyle = AnnotationStyle.Default,
        skeletonConnections: List<KeypointConnection> = emptyList(),
        rois: List<BehaviorRoi> = emptyList()
    ): Bitmap {
        val safeCrop = Rect(cropRect)
        require(safeCrop.intersect(0, 0, source.width, source.height)) {
            "The annotated frame crop does not intersect the source bitmap."
        }
        safeCrop.right -= safeCrop.width() % 2
        safeCrop.bottom -= safeCrop.height() % 2
        require(safeCrop.width() > 0 && safeCrop.height() > 0) {
            "The annotated frame crop is too small."
        }

        val geometry = OrientedCropGeometry(
            left = safeCrop.left,
            top = safeCrop.top,
            right = safeCrop.right,
            bottom = safeCrop.bottom,
            rotationDegrees = rotationDegrees,
            mirrorHorizontally = mirrorHorizontally
        )
        val sourceToOutput = Matrix().apply { setValues(geometry.matrixValues()) }
        val output = Bitmap.createBitmap(
            geometry.outputWidth,
            geometry.outputHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, sourceToOutput, BITMAP_PAINT)
        draw(
            canvas = canvas,
            inference = inference,
            targetWidth = geometry.outputWidth.toFloat(),
            targetHeight = geometry.outputHeight.toFloat(),
            coordinateMatrix = sourceToOutput,
            annotationStyle = annotationStyle,
            skeletonConnections = skeletonConnections,
            rois = rois
        )
        return output
    }

    fun draw(
        canvas: Canvas,
        inference: FrameInferenceResult,
        targetWidth: Float,
        targetHeight: Float,
        mirrorX: Boolean = false,
        calibration: OverlayCalibration = OverlayCalibration.Default,
        coordinateMatrix: Matrix? = null,
        annotationStyle: AnnotationStyle = AnnotationStyle.Default,
        skeletonConnections: List<KeypointConnection> = emptyList(),
        rois: List<BehaviorRoi> = emptyList()
    ) {
        if (inference.imageWidth <= 0 || inference.imageHeight <= 0) return
        val activeBoxPaint = Paint(boxPaint).apply {
            color = annotationStyle.boxArgb
        }
        val activeKeypointPaint = Paint(keypointPaint).apply {
            color = annotationStyle.keypointArgb
        }
        val activeSkeletonPaint = Paint(skeletonPaint).apply {
            color = annotationStyle.keypointArgb
        }
        val transform = OverlayCoordinateTransform(
            inference.imageWidth,
            inference.imageHeight,
            targetWidth,
            targetHeight,
            mirrorX && coordinateMatrix == null,
            calibration
        )
        val mappedCoordinates = FloatArray(2)
        fun mapPoint(x: Float, y: Float): OverlayPoint {
            val matrix = coordinateMatrix ?: return transform.map(x, y)
            mappedCoordinates[0] = x
            mappedCoordinates[1] = y
            matrix.mapPoints(mappedCoordinates)
            return transform.adjustTarget(mappedCoordinates[0], mappedCoordinates[1])
        }

        val drawRoiLabels = annotationStyle.roiLabelSize != RoiLabelSize.OFF
        val activeRoiTextPaint = Paint(roiTextPaint).apply {
            textSize = annotationStyle.roiLabelSize.textSizePx
        }
        rois.forEach { roi ->
            val safe = roi.sanitized()
            val activeRoiPaint = Paint(roiPaint).apply {
                color = RoiAnnotationPalette.argbFor(safe.id)
            }
            val topLeft = mapPoint(
                safe.left * inference.imageWidth,
                safe.top * inference.imageHeight
            )
            val topRight = mapPoint(
                safe.right * inference.imageWidth,
                safe.top * inference.imageHeight
            )
            val bottomRight = mapPoint(
                safe.right * inference.imageWidth,
                safe.bottom * inference.imageHeight
            )
            val bottomLeft = mapPoint(
                safe.left * inference.imageWidth,
                safe.bottom * inference.imageHeight
            )
            canvas.drawLine(topLeft.x, topLeft.y, topRight.x, topRight.y, activeRoiPaint)
            canvas.drawLine(topRight.x, topRight.y, bottomRight.x, bottomRight.y, activeRoiPaint)
            canvas.drawLine(
                bottomRight.x,
                bottomRight.y,
                bottomLeft.x,
                bottomLeft.y,
                activeRoiPaint
            )
            canvas.drawLine(bottomLeft.x, bottomLeft.y, topLeft.x, topLeft.y, activeRoiPaint)

            if (!drawRoiLabels) return@forEach
            val label = "ROI: " + safe.name
            val labelLeft = minOf(
                topLeft.x,
                topRight.x,
                bottomRight.x,
                bottomLeft.x
            ).coerceIn(0f, targetWidth)
            val labelAnchor = minOf(
                topLeft.y,
                topRight.y,
                bottomRight.y,
                bottomLeft.y
            ).coerceIn(0f, targetHeight)
            val textWidth = activeRoiTextPaint.measureText(label)
            val textHeight = activeRoiTextPaint.textSize + 8f
            val labelTop = (labelAnchor - textHeight).coerceAtLeast(0f)
            val labelRight = (labelLeft + textWidth + 16f).coerceAtMost(targetWidth)
            canvas.drawRect(
                labelLeft,
                labelTop,
                labelRight,
                labelTop + textHeight,
                textBgPaint
            )
            canvas.drawText(
                label,
                labelLeft + 8f,
                labelTop + activeRoiTextPaint.textSize,
                activeRoiTextPaint
            )
        }

        inference.detections.forEach { detection ->
            val boxCorners = listOf(
                mapPoint(detection.box.left, detection.box.top),
                mapPoint(detection.box.right, detection.box.top),
                mapPoint(detection.box.right, detection.box.bottom),
                mapPoint(detection.box.left, detection.box.bottom)
            )
            val left = boxCorners.minOf(OverlayPoint::x)
            val right = boxCorners.maxOf(OverlayPoint::x)
            val top = boxCorners.minOf(OverlayPoint::y)
            val bottom = boxCorners.maxOf(OverlayPoint::y)
            canvas.drawRect(left, top, right, bottom, activeBoxPaint)

            val mappedPoints = detection.keypoints.map {
                mapPoint(it.x, it.y)
            }
            skeletonConnections.forEach { connection ->
                val start = connection.startIndex
                val end = connection.endIndex
                if (start !in detection.keypoints.indices || end !in detection.keypoints.indices) {
                    return@forEach
                }
                val first = detection.keypoints[start]
                val second = detection.keypoints[end]
                if (first.confidence >= KEYPOINT_THRESHOLD && second.confidence >= KEYPOINT_THRESHOLD) {
                    canvas.drawLine(
                        mappedPoints[start].x,
                        mappedPoints[start].y,
                        mappedPoints[end].x,
                        mappedPoints[end].y,
                        activeSkeletonPaint
                    )
                }
            }
            detection.keypoints.forEachIndexed { index, keypoint ->
                if (keypoint.confidence >= KEYPOINT_THRESHOLD) {
                    canvas.drawCircle(
                        mappedPoints[index].x,
                        mappedPoints[index].y,
                        5f,
                        activeKeypointPaint
                    )
                }
            }

            val label = buildString {
                detection.trackId?.let { append("T$it ") }
                append("#${detection.classIndex} ${detection.className} ")
                append(String.format(Locale.US, "%.2f", detection.confidence))
            }
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize + 8f
            val labelTop = (top - textHeight).coerceAtLeast(0f)
            val labelRight = (left + textWidth + 18f).coerceAtMost(targetWidth)
            canvas.drawRect(left, labelTop, labelRight, labelTop + textHeight, textBgPaint)
            canvas.drawText(label, left + 8f, labelTop + textPaint.textSize, textPaint)
        }
    }

    private const val KEYPOINT_THRESHOLD = 0.25f
    private val BITMAP_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
}

internal data class OrientedCropGeometry(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val rotationDegrees: Int,
    val mirrorHorizontally: Boolean = false
) {
    val cropWidth: Int = right - left
    val cropHeight: Int = bottom - top
    private val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val outputWidth: Int
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) {
            cropHeight
        } else {
            cropWidth
        }
    val outputHeight: Int
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) {
            cropWidth
        } else {
            cropHeight
        }

    init {
        require(cropWidth > 0 && cropHeight > 0) { "Crop dimensions must be positive." }
        require(normalizedRotation in setOf(0, 90, 180, 270)) {
            "Rotation must be 0, 90, 180, or 270 degrees."
        }
    }

    fun matrixValues(): FloatArray {
        val values = when (normalizedRotation) {
        0 -> floatArrayOf(
            1f, 0f, -left.toFloat(),
            0f, 1f, -top.toFloat(),
            0f, 0f, 1f
        )
        90 -> floatArrayOf(
            0f, -1f, bottom.toFloat(),
            1f, 0f, -left.toFloat(),
            0f, 0f, 1f
        )
        180 -> floatArrayOf(
            -1f, 0f, right.toFloat(),
            0f, -1f, bottom.toFloat(),
            0f, 0f, 1f
        )
        else -> floatArrayOf(
            0f, 1f, -top.toFloat(),
            -1f, 0f, right.toFloat(),
            0f, 0f, 1f
        )
        }
        if (mirrorHorizontally) {
            values[0] = -values[0]
            values[1] = -values[1]
            values[2] = outputWidth.toFloat() - values[2]
        }
        return values
    }

    fun map(x: Float, y: Float): OverlayPoint {
        val values = matrixValues()
        return OverlayPoint(
            x = values[0] * x + values[1] * y + values[2],
            y = values[3] * x + values[4] * y + values[5]
        )
    }
}
