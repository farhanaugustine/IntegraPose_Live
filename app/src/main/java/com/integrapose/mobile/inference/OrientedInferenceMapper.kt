package com.integrapose.mobile.inference

/**
 * Moves an inference result from the unrotated CameraX analysis buffer into the
 * cropped, rotated, and optionally mirrored coordinate system written by VideoCapture.
 */
internal fun FrameInferenceResult.mapToOrientedCrop(
    geometry: OrientedCropGeometry
): FrameInferenceResult = copy(
    imageWidth = geometry.outputWidth,
    imageHeight = geometry.outputHeight,
    detections = detections.map { detection ->
        val corners = listOf(
            geometry.map(detection.box.left, detection.box.top),
            geometry.map(detection.box.right, detection.box.top),
            geometry.map(detection.box.right, detection.box.bottom),
            geometry.map(detection.box.left, detection.box.bottom)
        )
        detection.copy(
            box = BoundingBox(
                left = corners.minOf(OverlayPoint::x),
                top = corners.minOf(OverlayPoint::y),
                right = corners.maxOf(OverlayPoint::x),
                bottom = corners.maxOf(OverlayPoint::y)
            ),
            keypoints = detection.keypoints.map { keypoint ->
                val mapped = geometry.map(keypoint.x, keypoint.y)
                keypoint.copy(x = mapped.x, y = mapped.y)
            }
        )
    }
)
