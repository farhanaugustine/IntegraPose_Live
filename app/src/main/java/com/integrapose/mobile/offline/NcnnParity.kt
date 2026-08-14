package com.integrapose.mobile.offline

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class NativeNcnnParityResult(
    val cpuFrames: Int,
    val vulkanFrames: Int,
    val framesCompared: Int,
    val firstFrameIndex: Int,
    val lastFrameIndex: Int,
    val detectionsCompared: Int,
    val frameMismatches: Int,
    val timestampMismatches: Int,
    val layoutMismatches: Int,
    val detectionCountMismatchFrames: Int,
    val unmatchedDetections: Int,
    val keypointLayoutMismatches: Int,
    val maxConfidenceDelta: Float,
    val maxBoxDeltaPx: Float,
    val maxKeypointDeltaPx: Float,
    val maxKeypointConfidenceDelta: Float,
    val confidenceTolerance: Float,
    val keypointConfidenceTolerance: Float,
    val boxTolerancePx: Float,
    val keypointTolerancePx: Float,
    val passed: Boolean
)

internal data class NativePredictionFrame(
    val frameIndex: Int,
    val sourceTimestampUs: Long,
    val classIds: IntArray,
    val confidences: FloatArray,
    val boxes: FloatArray,
    val keypointOffsets: IntArray,
    val keypoints: FloatArray
)

internal fun compareNcnnPredictions(
    cpuFrames: List<NativePredictionFrame>,
    vulkanFrames: List<NativePredictionFrame>,
    confidenceTolerance: Float = 0.02f,
    keypointConfidenceTolerance: Float = 0.05f,
    boxTolerancePx: Float = 2f,
    keypointTolerancePx: Float = 2f
): NativeNcnnParityResult {
    require(confidenceTolerance >= 0f)
    require(keypointConfidenceTolerance >= 0f)
    require(boxTolerancePx >= 0f)
    require(keypointTolerancePx >= 0f)

    val cpuByIndex = cpuFrames.associateBy { it.frameIndex }
    val vulkanByIndex = vulkanFrames.associateBy { it.frameIndex }
    val commonIndices = cpuByIndex.keys.intersect(vulkanByIndex.keys).sorted()
    val frameMismatches = cpuByIndex.keys.union(vulkanByIndex.keys).size -
        commonIndices.size

    var timestampMismatches = 0
    var layoutMismatches = 0
    var detectionCountMismatchFrames = 0
    var unmatchedDetections = 0
    var keypointLayoutMismatches = 0
    var detectionsCompared = 0
    var maxConfidenceDelta = 0f
    var maxBoxDeltaPx = 0f
    var maxKeypointDeltaPx = 0f
    var maxKeypointConfidenceDelta = 0f

    commonIndices.forEach { frameIndex ->
        val cpu = requireNotNull(cpuByIndex[frameIndex])
        val vulkan = requireNotNull(vulkanByIndex[frameIndex])
        if (cpu.sourceTimestampUs != vulkan.sourceTimestampUs) {
            timestampMismatches += 1
        }
        if (!cpu.hasValidLayout() || !vulkan.hasValidLayout()) {
            layoutMismatches += 1
            return@forEach
        }
        if (cpu.classIds.size != vulkan.classIds.size) {
            detectionCountMismatchFrames += 1
        }

        val usedVulkan = BooleanArray(vulkan.classIds.size)
        cpu.classIds.indices.forEach { cpuIndex ->
            var bestVulkanIndex = -1
            var bestIou = -1f
            vulkan.classIds.indices.forEach { vulkanIndex ->
                if (!usedVulkan[vulkanIndex] &&
                    cpu.classIds[cpuIndex] == vulkan.classIds[vulkanIndex]
                ) {
                    val overlap = boxIou(cpu, cpuIndex, vulkan, vulkanIndex)
                    if (overlap > bestIou) {
                        bestIou = overlap
                        bestVulkanIndex = vulkanIndex
                    }
                }
            }
            if (bestVulkanIndex < 0) {
                unmatchedDetections += 1
                return@forEach
            }

            usedVulkan[bestVulkanIndex] = true
            detectionsCompared += 1
            maxConfidenceDelta = max(
                maxConfidenceDelta,
                finiteDelta(
                    cpu.confidences[cpuIndex],
                    vulkan.confidences[bestVulkanIndex]
                )
            )
            repeat(4) { coordinate ->
                maxBoxDeltaPx = max(
                    maxBoxDeltaPx,
                    finiteDelta(
                        cpu.boxes[cpuIndex * 4 + coordinate],
                        vulkan.boxes[bestVulkanIndex * 4 + coordinate]
                    )
                )
            }

            val cpuStart = cpu.keypointOffsets[cpuIndex]
            val cpuEnd = cpu.keypointOffsets[cpuIndex + 1]
            val vulkanStart = vulkan.keypointOffsets[bestVulkanIndex]
            val vulkanEnd = vulkan.keypointOffsets[bestVulkanIndex + 1]
            if (cpuEnd - cpuStart != vulkanEnd - vulkanStart) {
                keypointLayoutMismatches += 1
                return@forEach
            }
            repeat(cpuEnd - cpuStart) { keypointIndex ->
                val cpuOffset = (cpuStart + keypointIndex) * 3
                val vulkanOffset = (vulkanStart + keypointIndex) * 3
                val xDelta = finiteDelta(
                    cpu.keypoints[cpuOffset],
                    vulkan.keypoints[vulkanOffset]
                )
                val yDelta = finiteDelta(
                    cpu.keypoints[cpuOffset + 1],
                    vulkan.keypoints[vulkanOffset + 1]
                )
                maxKeypointDeltaPx = max(
                    maxKeypointDeltaPx,
                    hypot(xDelta.toDouble(), yDelta.toDouble()).toFloat()
                )
                maxKeypointConfidenceDelta = max(
                    maxKeypointConfidenceDelta,
                    finiteDelta(
                        cpu.keypoints[cpuOffset + 2],
                        vulkan.keypoints[vulkanOffset + 2]
                    )
                )
            }
        }
        unmatchedDetections += usedVulkan.count { !it }
    }

    val passed = cpuFrames.isNotEmpty() &&
        frameMismatches == 0 &&
        timestampMismatches == 0 &&
        layoutMismatches == 0 &&
        detectionCountMismatchFrames == 0 &&
        unmatchedDetections == 0 &&
        keypointLayoutMismatches == 0 &&
        maxConfidenceDelta <= confidenceTolerance &&
        maxBoxDeltaPx <= boxTolerancePx &&
        maxKeypointDeltaPx <= keypointTolerancePx &&
        maxKeypointConfidenceDelta <= keypointConfidenceTolerance

    return NativeNcnnParityResult(
        cpuFrames = cpuFrames.size,
        vulkanFrames = vulkanFrames.size,
        framesCompared = commonIndices.size,
        firstFrameIndex = commonIndices.firstOrNull() ?: -1,
        lastFrameIndex = commonIndices.lastOrNull() ?: -1,
        detectionsCompared = detectionsCompared,
        frameMismatches = frameMismatches,
        timestampMismatches = timestampMismatches,
        layoutMismatches = layoutMismatches,
        detectionCountMismatchFrames = detectionCountMismatchFrames,
        unmatchedDetections = unmatchedDetections,
        keypointLayoutMismatches = keypointLayoutMismatches,
        maxConfidenceDelta = maxConfidenceDelta,
        maxBoxDeltaPx = maxBoxDeltaPx,
        maxKeypointDeltaPx = maxKeypointDeltaPx,
        maxKeypointConfidenceDelta = maxKeypointConfidenceDelta,
        confidenceTolerance = confidenceTolerance,
        keypointConfidenceTolerance = keypointConfidenceTolerance,
        boxTolerancePx = boxTolerancePx,
        keypointTolerancePx = keypointTolerancePx,
        passed = passed
    )
}

private fun NativePredictionFrame.hasValidLayout(): Boolean {
    if (confidences.size != classIds.size || boxes.size != classIds.size * 4) {
        return false
    }
    if (keypointOffsets.size != classIds.size + 1 || keypoints.size % 3 != 0) {
        return false
    }
    if (keypointOffsets.firstOrNull() != 0 ||
        keypointOffsets.lastOrNull() != keypoints.size / 3
    ) {
        return false
    }
    return (0 until keypointOffsets.lastIndex).all { index ->
        val start = keypointOffsets[index]
        val end = keypointOffsets[index + 1]
        start in 0..end && end <= keypoints.size / 3
    }
}

private fun boxIou(
    first: NativePredictionFrame,
    firstIndex: Int,
    second: NativePredictionFrame,
    secondIndex: Int
): Float {
    val firstOffset = firstIndex * 4
    val secondOffset = secondIndex * 4
    val left = max(first.boxes[firstOffset], second.boxes[secondOffset])
    val top = max(first.boxes[firstOffset + 1], second.boxes[secondOffset + 1])
    val right = min(first.boxes[firstOffset + 2], second.boxes[secondOffset + 2])
    val bottom = min(first.boxes[firstOffset + 3], second.boxes[secondOffset + 3])
    val intersection = max(0f, right - left) * max(0f, bottom - top)
    val firstArea = max(0f, first.boxes[firstOffset + 2] - first.boxes[firstOffset]) *
        max(0f, first.boxes[firstOffset + 3] - first.boxes[firstOffset + 1])
    val secondArea = max(0f, second.boxes[secondOffset + 2] - second.boxes[secondOffset]) *
        max(0f, second.boxes[secondOffset + 3] - second.boxes[secondOffset + 1])
    val union = firstArea + secondArea - intersection
    return if (union > 0f) intersection / union else 0f
}

private fun finiteDelta(first: Float, second: Float): Float =
    if (first.isFinite() && second.isFinite()) {
        abs(first - second)
    } else {
        Float.POSITIVE_INFINITY
    }
