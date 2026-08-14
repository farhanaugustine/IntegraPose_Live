package com.integrapose.mobile.inference

import com.integrapose.mobile.model.ModelCoordinateFormat
import com.integrapose.mobile.model.ModelOutputFormat
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.InferenceModelConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decodes supported raw bbox/keypoint rows and exported final-detection rows. */
object DetectionPostProcessor {
    fun decode(
        config: InferenceModelConfig,
        outputData: FloatArray,
        outputShape: LongArray,
        transform: LetterboxTransform
    ): List<DetectionResult> {
        config.requireSupportedModel()
        val rows = OutputRows.create(outputData, outputShape)
            ?: return emptyList()
        val format = if (config.outputFormat == ModelOutputFormat.AUTO) {
            detectOutputFormat(rows)
        } else {
            config.outputFormat
        }
        val maximum = config.detectionCount.coerceIn(1, 5_000)

        return if (format == ModelOutputFormat.END_TO_END) {
            val keypointCount = if (config.type == ModelType.POSE) {
                ((rows.featureCount - END_TO_END_KEYPOINT_START) / 3)
                    .coerceAtLeast(0)
            } else {
                0
            }
            val candidates = decodeEndToEndCandidates(
                rows,
                config,
                transform
            )
            candidates.sortWith(
                compareByDescending<DecodedCandidate> { it.confidence }
            )
            candidates.take(maximum).map { candidate ->
                materializeDetection(
                    candidate = candidate,
                    rows = rows,
                    keypointStart = END_TO_END_KEYPOINT_START,
                    keypointCount = keypointCount,
                    config = config,
                    transform = transform
                )
            }
        } else {
            val classCount = inferRawClassCount(rows.featureCount, config)
            val keypointStart = 4 + classCount
            if (classCount <= 0 || rows.featureCount < keypointStart) {
                return emptyList()
            }
            val keypointCount = if (config.type == ModelType.POSE) {
                ((rows.featureCount - keypointStart) / 3).coerceAtLeast(0)
            } else {
                0
            }
            val candidates = decodeRawCandidates(
                rows = rows,
                classCount = classCount,
                config = config,
                transform = transform
            )
            selectWithNms(
                candidates = candidates,
                iouThreshold = config.iouThreshold,
                maximum = maximum
            ).map { candidate ->
                materializeDetection(
                    candidate = candidate,
                    rows = rows,
                    keypointStart = keypointStart,
                    keypointCount = keypointCount,
                    config = config,
                    transform = transform
                )
            }
        }
    }

    private fun decodeRawCandidates(
        rows: OutputRows,
        classCount: Int,
        config: InferenceModelConfig,
        transform: LetterboxTransform
    ): MutableList<DecodedCandidate> {
        val candidates = ArrayList<DecodedCandidate>(
            min(rows.rowCount, max(64, config.detectionCount * 16))
        )
        for (rowIndex in 0 until rows.rowCount) {
            var classIndex = 0
            var confidence = rows.value(rowIndex, 4)
            for (classOffset in 1 until classCount) {
                val score = rows.value(rowIndex, 4 + classOffset)
                if (score > confidence) {
                    confidence = score
                    classIndex = classOffset
                }
            }
            if (!confidence.isFinite() || confidence < config.confThreshold) {
                continue
            }

            var centerX = rows.value(rowIndex, 0)
            var centerY = rows.value(rowIndex, 1)
            var width = rows.value(rowIndex, 2)
            var height = rows.value(rowIndex, 3)
            if (
                isNormalized(
                    centerX,
                    centerY,
                    width,
                    height,
                    config.coordinateFormat
                )
            ) {
                centerX *= transform.modelWidth
                centerY *= transform.modelHeight
                width *= transform.modelWidth
                height *= transform.modelHeight
            }
            val box = mapBox(
                left = centerX - width / 2f,
                top = centerY - height / 2f,
                right = centerX + width / 2f,
                bottom = centerY + height / 2f,
                transform = transform
            ) ?: continue
            candidates += DecodedCandidate(
                rowIndex = rowIndex,
                classIndex = classIndex,
                confidence = confidence,
                box = box
            )
        }
        return candidates
    }

    private fun decodeEndToEndCandidates(
        rows: OutputRows,
        config: InferenceModelConfig,
        transform: LetterboxTransform
    ): MutableList<DecodedCandidate> {
        if (rows.featureCount < END_TO_END_KEYPOINT_START) {
            return mutableListOf()
        }
        val candidates = ArrayList<DecodedCandidate>(
            min(rows.rowCount, max(64, config.detectionCount * 4))
        )
        for (rowIndex in 0 until rows.rowCount) {
            val confidence = rows.value(rowIndex, 4)
            val classValue = rows.value(rowIndex, 5)
            if (
                !confidence.isFinite() ||
                confidence < config.confThreshold ||
                !classValue.isFinite()
            ) {
                continue
            }
            val first = modelPoint(
                rows.value(rowIndex, 0),
                rows.value(rowIndex, 1),
                config,
                transform
            )
            val second = modelPoint(
                rows.value(rowIndex, 2),
                rows.value(rowIndex, 3),
                config,
                transform
            )
            val box = mapBox(
                first.first,
                first.second,
                second.first,
                second.second,
                transform
            ) ?: continue
            candidates += DecodedCandidate(
                rowIndex = rowIndex,
                classIndex = classValue.roundToInt().coerceAtLeast(0),
                confidence = confidence,
                box = box
            )
        }
        return candidates
    }

    private fun materializeDetection(
        candidate: DecodedCandidate,
        rows: OutputRows,
        keypointStart: Int,
        keypointCount: Int,
        config: InferenceModelConfig,
        transform: LetterboxTransform
    ): DetectionResult {
        val keypoints = List(keypointCount) { keypointIndex ->
            val offset = keypointStart + keypointIndex * 3
            val point = modelPoint(
                rows.value(candidate.rowIndex, offset),
                rows.value(candidate.rowIndex, offset + 1),
                config,
                transform
            )
            Keypoint(
                x = transform.modelToSourceX(point.first),
                y = transform.modelToSourceY(point.second),
                confidence = rows
                    .value(candidate.rowIndex, offset + 2)
                    .takeIf(Float::isFinite)
                    ?: 0f
            )
        }
        return DetectionResult(
            classIndex = candidate.classIndex,
            className = config.classNames.getOrNull(candidate.classIndex)
                ?: "class_${candidate.classIndex}",
            confidence = candidate.confidence,
            box = candidate.box,
            keypoints = keypoints
        )
    }

    private fun selectWithNms(
        candidates: MutableList<DecodedCandidate>,
        iouThreshold: Float,
        maximum: Int
    ): List<DecodedCandidate> {
        candidates.sortWith(
            compareByDescending<DecodedCandidate> { it.confidence }
        )
        val kept = ArrayList<DecodedCandidate>(min(maximum, candidates.size))
        for (candidate in candidates) {
            var suppressed = false
            for (accepted in kept) {
                if (
                    accepted.classIndex == candidate.classIndex &&
                    intersectionOverUnion(
                        accepted.box,
                        candidate.box
                    ) > iouThreshold
                ) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                kept += candidate
                if (kept.size >= maximum) break
            }
        }
        return kept
    }

    private fun modelPoint(
        x: Float,
        y: Float,
        config: InferenceModelConfig,
        transform: LetterboxTransform
    ): Pair<Float, Float> {
        val normalized = isNormalized(
            x,
            y,
            x,
            y,
            config.coordinateFormat
        )
        return if (normalized) {
            x * transform.modelWidth to y * transform.modelHeight
        } else {
            x to y
        }
    }

    private fun isNormalized(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        format: ModelCoordinateFormat
    ): Boolean = when (format) {
        ModelCoordinateFormat.NORMALIZED -> true
        ModelCoordinateFormat.MODEL_PIXELS -> false
        ModelCoordinateFormat.AUTO ->
            max(max(abs(a), abs(b)), max(abs(c), abs(d))) <= 1.5f
    }

    private fun mapBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        transform: LetterboxTransform
    ): BoundingBox? {
        if (
            !left.isFinite() ||
            !top.isFinite() ||
            !right.isFinite() ||
            !bottom.isFinite()
        ) {
            return null
        }
        val mappedLeft = transform.modelToSourceX(min(left, right))
        val mappedTop = transform.modelToSourceY(min(top, bottom))
        val mappedRight = transform.modelToSourceX(max(left, right))
        val mappedBottom = transform.modelToSourceY(max(top, bottom))
        if (
            mappedRight - mappedLeft < 1f ||
            mappedBottom - mappedTop < 1f
        ) {
            return null
        }
        return BoundingBox(
            mappedLeft,
            mappedTop,
            mappedRight,
            mappedBottom
        )
    }

    private fun inferRawClassCount(
        featureCount: Int,
        config: InferenceModelConfig
    ): Int {
        val configured = config.classNames.size
        if (
            configured > 0 &&
            (
                config.type == ModelType.DETECTION ||
                    (featureCount - 4 - configured) % 3 == 0
                )
        ) {
            return configured
        }
        if (config.type == ModelType.DETECTION) return featureCount - 4
        if (featureCount >= 8 && (featureCount - 5) % 3 == 0) return 1
        return (1..min(100, featureCount - 4)).firstOrNull { candidate ->
            featureCount - 4 - candidate >= 3 &&
                (featureCount - 4 - candidate) % 3 == 0
        } ?: 1
    }

    private fun detectOutputFormat(rows: OutputRows): ModelOutputFormat {
        if (
            rows.rowCount > 500 ||
            rows.featureCount < END_TO_END_KEYPOINT_START
        ) {
            return ModelOutputFormat.RAW_PREDICTIONS
        }
        var finiteSamples = 0
        var plausibleSamples = 0
        val sampleCount = min(20, rows.rowCount)
        for (rowIndex in 0 until sampleCount) {
            var finite = true
            for (featureIndex in 0 until rows.featureCount) {
                if (!rows.value(rowIndex, featureIndex).isFinite()) {
                    finite = false
                    break
                }
            }
            if (!finite) continue
            finiteSamples += 1
            val classValue = rows.value(rowIndex, 5)
            val confidence = rows.value(rowIndex, 4)
            val classLooksIntegral =
                abs(classValue - classValue.roundToInt()) < 0.01f &&
                    classValue >= 0f
            val confidenceLooksValid = confidence in 0f..1.01f
            val cornersLookValid =
                rows.value(rowIndex, 2) >= rows.value(rowIndex, 0) &&
                    rows.value(rowIndex, 3) >= rows.value(rowIndex, 1)
            if (
                classLooksIntegral &&
                confidenceLooksValid &&
                cornersLookValid
            ) {
                plausibleSamples += 1
            }
        }
        return if (
            finiteSamples > 0 &&
            plausibleSamples * 2 >= finiteSamples
        ) {
            ModelOutputFormat.END_TO_END
        } else {
            ModelOutputFormat.RAW_PREDICTIONS
        }
    }

    internal fun toCandidateRows(
        output: FloatArray,
        shape: LongArray
    ): List<FloatArray> {
        val rows = OutputRows.create(output, shape) ?: return emptyList()
        return List(rows.rowCount) { rowIndex ->
            FloatArray(rows.featureCount) { featureIndex ->
                rows.value(rowIndex, featureIndex)
            }
        }
    }

    private fun intersectionOverUnion(
        first: BoundingBox,
        second: BoundingBox
    ): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection =
            max(0f, right - left) * max(0f, bottom - top)
        val firstArea =
            max(0f, first.right - first.left) *
                max(0f, first.bottom - first.top)
        val secondArea =
            max(0f, second.right - second.left) *
                max(0f, second.bottom - second.top)
        val union = firstArea + secondArea - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private data class DecodedCandidate(
        val rowIndex: Int,
        val classIndex: Int,
        val confidence: Float,
        val box: BoundingBox
    )

    private class OutputRows private constructor(
        private val output: FloatArray,
        val rowCount: Int,
        val featureCount: Int,
        private val channelsFirst: Boolean
    ) {
        fun value(rowIndex: Int, featureIndex: Int): Float =
            if (channelsFirst) {
                output[featureIndex * rowCount + rowIndex]
            } else {
                output[rowIndex * featureCount + featureIndex]
            }

        companion object {
            fun create(
                output: FloatArray,
                shape: LongArray
            ): OutputRows? {
                val dimensions = shape.map { it.toInt() }
                val (first, second) = when (dimensions.size) {
                    2 -> dimensions[0] to dimensions[1]
                    3 -> dimensions[1] to dimensions[2]
                    else -> return null
                }
                if (
                    first <= 0 ||
                    second <= 0 ||
                    first.toLong() * second.toLong() > output.size.toLong()
                ) {
                    return null
                }
                val channelsFirst = when {
                    first < 5 && second >= 5 -> false
                    second < 5 && first >= 5 -> true
                    else -> first < second && first <= 512
                }
                val rowCount = if (channelsFirst) second else first
                val featureCount = if (channelsFirst) first else second
                if (rowCount <= 0 || featureCount < 5) return null
                return OutputRows(
                    output = output,
                    rowCount = rowCount,
                    featureCount = featureCount,
                    channelsFirst = channelsFirst
                )
            }
        }
    }

    private const val END_TO_END_KEYPOINT_START = 6
}
