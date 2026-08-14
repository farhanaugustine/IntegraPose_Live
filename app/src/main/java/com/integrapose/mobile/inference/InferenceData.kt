package com.integrapose.mobile.inference

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class Keypoint(
    val x: Float,
    val y: Float,
    val confidence: Float
)

data class DetectionResult(
    val classIndex: Int,
    val className: String,
    val confidence: Float,
    val box: BoundingBox,
    val keypoints: List<Keypoint> = emptyList(),
    val trackId: Int? = null
)

data class FrameInferenceResult(
    val timestampMs: Long,
    val sourceTimestampUs: Long = timestampMs * 1_000L,
    val imageWidth: Int,
    val imageHeight: Int,
    val detections: List<DetectionResult>,
    val inferenceMs: Long,
    val preprocessingMs: Long = 0L,
    val postprocessingMs: Long = 0L,
    val backend: String = "ONNX Runtime CPU",
    val modelInputWidth: Int = 640,
    val modelInputHeight: Int = 640
)
