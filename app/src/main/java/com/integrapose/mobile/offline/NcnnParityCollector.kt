package com.integrapose.mobile.offline

internal class NativePredictionCollector : NativeFrameCallback {
    private val captured = mutableListOf<NativePredictionFrame>()

    val frames: List<NativePredictionFrame>
        get() = synchronized(captured) { captured.toList() }

    override fun onNativeFrame(
        frameIndex: Int,
        sourceTimestampUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        inferenceTimeNs: Long,
        preprocessingTimeNs: Long,
        postprocessingTimeNs: Long,
        classIds: IntArray,
        confidences: FloatArray,
        boxes: FloatArray,
        keypointOffsets: IntArray,
        keypoints: FloatArray
    ): IntArray {
        synchronized(captured) {
            captured += NativePredictionFrame(
                frameIndex = frameIndex,
                sourceTimestampUs = sourceTimestampUs,
                classIds = classIds.copyOf(),
                confidences = confidences.copyOf(),
                boxes = boxes.copyOf(),
                keypointOffsets = keypointOffsets.copyOf(),
                keypoints = keypoints.copyOf()
            )
        }
        return IntArray(classIds.size) { NO_TRACK_ID }
    }

    private companion object {
        const val NO_TRACK_ID = -1
    }
}
