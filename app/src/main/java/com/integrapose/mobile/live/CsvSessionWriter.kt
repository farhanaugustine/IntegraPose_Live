package com.integrapose.mobile.live

import android.content.Context
import android.os.Environment
import com.integrapose.mobile.inference.DetectionResult
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.model.ModelType
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Wide CSV: one row per detection, with kpt1_x,kpt1_y,kpt1_conf,... pose columns.
 * Keypoints are one-based in column names; detection_index remains zero-based.
 */
class CsvSessionWriter(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var outputFile: File? = null
    private var keypointCount: Int? = null
    private var activeSession = false
    private var nextFrameIndex = 0L
    private var framesSinceFlush = 0
    private val pendingFrames = mutableListOf<PendingFrame>()

    @Synchronized
    fun start(modelType: ModelType, prefix: String = modelType.name.lowercase(Locale.US)): File {
        close()
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "${prefix}_$stamp.csv")
        outputFile = file
        keypointCount = if (modelType == ModelType.DETECTION) 0 else null
        activeSession = true
        nextFrameIndex = 0L
        framesSinceFlush = 0
        pendingFrames.clear()
        if (keypointCount == 0) initializeWriter(0)
        return file
    }

    @Synchronized
    fun append(result: FrameInferenceResult, frameIndex: Long = nextFrameIndex) {
        if (!activeSession) return
        nextFrameIndex = max(nextFrameIndex, frameIndex + 1)
        if (keypointCount == null) {
            val detectedCount = result.detections.maxOfOrNull { it.keypoints.size } ?: 0
            if (detectedCount == 0) {
                pendingFrames += PendingFrame(result, frameIndex)
                return
            }
            initializeWriter(detectedCount)
            val waiting = pendingFrames.toList()
            pendingFrames.clear()
            waiting.forEach { writeFrame(it.result, it.frameIndex, detectedCount) }
        }
        writeFrame(result, frameIndex, requireNotNull(keypointCount))
        if (++framesSinceFlush >= 30) {
            writer?.flush()
            framesSinceFlush = 0
        }
    }

    @Synchronized
    fun close(): File? {
        if (activeSession && writer == null) {
            val count = pendingFrames.asSequence()
                .flatMap { it.result.detections.asSequence() }
                .maxOfOrNull { it.keypoints.size } ?: 0
            initializeWriter(count)
            pendingFrames.forEach { writeFrame(it.result, it.frameIndex, count) }
        }
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        activeSession = false
        pendingFrames.clear()
        return outputFile
    }

    private fun initializeWriter(numberOfKeypoints: Int) {
        if (writer != null) return
        val file = requireNotNull(outputFile) { "CSV output was not initialized." }
        val count = numberOfKeypoints.coerceAtLeast(0)
        keypointCount = count
        writer = file.bufferedWriter(Charsets.UTF_8).also {
            it.write(buildHeader(count))
            it.newLine()
        }
    }

    private fun writeFrame(
        result: FrameInferenceResult,
        frameIndex: Long,
        numberOfKeypoints: Int
    ) {
        val output = writer ?: return
        if (result.detections.isEmpty()) {
            writeRow(output, result, frameIndex, null, null, numberOfKeypoints)
        } else {
            result.detections.forEachIndexed { detectionIndex, detection ->
                writeRow(
                    output,
                    result,
                    frameIndex,
                    detectionIndex,
                    detection,
                    numberOfKeypoints
                )
            }
        }
    }

    private fun writeRow(
        output: BufferedWriter,
        result: FrameInferenceResult,
        frameIndex: Long,
        detectionIndex: Int?,
        detection: DetectionResult?,
        numberOfKeypoints: Int
    ) {
        val values = mutableListOf(
            result.sourceTimestampUs.toString(),
            formatDouble(result.sourceTimestampUs / 1_000_000.0),
            result.timestampMs.toString(),
            frameIndex.toString(),
            detectionIndex?.toString().orEmpty(),
            detection?.trackId?.toString().orEmpty(),
            detection?.classIndex?.toString().orEmpty(),
            detection?.className.orEmpty(),
            detection?.confidence?.let(::formatFloat).orEmpty(),
            detection?.box?.left?.let(::formatFloat).orEmpty(),
            detection?.box?.top?.let(::formatFloat).orEmpty(),
            detection?.box?.right?.let(::formatFloat).orEmpty(),
            detection?.box?.bottom?.let(::formatFloat).orEmpty()
        )
        repeat(numberOfKeypoints) { index ->
            val keypoint = detection?.keypoints?.getOrNull(index)
            values += keypoint?.x?.let(::formatFloat).orEmpty()
            values += keypoint?.y?.let(::formatFloat).orEmpty()
            values += keypoint?.confidence?.let(::formatFloat).orEmpty()
        }
        values += listOf(
            result.inferenceMs.toString(),
            result.preprocessingMs.toString(),
            result.postprocessingMs.toString(),
            result.backend,
            result.modelInputWidth.toString(),
            result.modelInputHeight.toString(),
            result.imageWidth.toString(),
            result.imageHeight.toString()
        )
        output.write(values.joinToString(",", transform = ::escapeCsv))
        output.newLine()
    }

    private fun buildHeader(numberOfKeypoints: Int): String {
        val columns = mutableListOf(
            "source_timestamp_us",
            "source_time_s",
            "processing_timestamp_ms",
            "frame_index",
            "detection_index",
            "track_id",
            "class_id",
            "class_name",
            "confidence",
            "bbox_left",
            "bbox_top",
            "bbox_right",
            "bbox_bottom"
        )
        repeat(numberOfKeypoints) { index ->
            val oneBasedIndex = index + 1
            columns += "kpt${oneBasedIndex}_x"
            columns += "kpt${oneBasedIndex}_y"
            columns += "kpt${oneBasedIndex}_conf"
        }
        columns += listOf(
            "inference_ms",
            "preprocessing_ms",
            "postprocessing_ms",
            "backend",
            "input_width",
            "input_height",
            "image_width",
            "image_height"
        )
        return columns.joinToString(",")
    }

    private fun escapeCsv(value: String): String {
        val quote = '"'
        return if (value.any { it == ',' || it == quote || it == '\n' || it == '\r' }) {
            quote.toString() +
                value.replace(quote.toString(), quote.toString().repeat(2)) +
                quote
        } else {
            value
        }
    }

    private fun formatFloat(value: Float): String =
        String.format(Locale.US, "%.6f", value)

    private fun formatDouble(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    private data class PendingFrame(
        val result: FrameInferenceResult,
        val frameIndex: Long
    )
}
