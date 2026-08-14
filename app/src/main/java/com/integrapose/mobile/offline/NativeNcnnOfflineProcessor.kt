package com.integrapose.mobile.offline

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Environment
import com.integrapose.mobile.analytics.BehaviorBoutTracker
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.analytics.BoutSettings
import com.integrapose.mobile.analytics.RoiAnalyticsSettings
import com.integrapose.mobile.analytics.RoiDwellTracker
import com.integrapose.mobile.analytics.toSourceOrientation
import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.Keypoint
import com.integrapose.mobile.live.CsvSessionWriter
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.tracking.IoUTracker
import com.integrapose.mobile.tracking.IoUTrackerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NativeNcnnOfflineRun(
    val output: OfflineProcessResult,
    val benchmark: NativeNcnnPipelineBenchmark
)

class NativeNcnnOfflineProcessor(private val context: Context) {
    suspend fun processVideo(
        uri: Uri,
        model: InferenceModelConfig,
        enableTracking: Boolean,
        exportAnnotatedVideo: Boolean = true,
        drawRoisOnAnnotatedVideo: Boolean = false,
        exportDetectionCsv: Boolean = true,
        exportBoutSummary: Boolean = true,
        exportRoiMetrics: Boolean = true,
        annotationStyle: AnnotationStyle = AnnotationStyle.Default,
        rois: List<BehaviorRoi> = emptyList(),
        boutSettings: BoutSettings = BoutSettings(),
        roiSettings: RoiAnalyticsSettings = RoiAnalyticsSettings(),
        trackerConfig: IoUTrackerConfig = IoUTrackerConfig(),
        threads: Int = (
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1) - 1
        ).coerceIn(1, 4),
        workers: Int = 1,
        backend: NativeNcnnBackend = NativeNcnnBackend.CPU,
        runtimeAuditLabel: String? = null,
        stopSignal: NativeStopSignal? = null,
        onProgress: (Float) -> Unit
    ): NativeNcnnOfflineRun {
        val videoInfo = NativeMediaPipeline.probe(context, uri)
        val sourceFrameRate = videoInfo.declaredFrameRate
            .takeIf { it in 1.0..240.0 }
            ?: 30.0
        val csvWriter = if (exportDetectionCsv) CsvSessionWriter(context) else null
        val csvFile = csvWriter?.start(model.type, "offline_native")
        val annotatedFile = if (exportAnnotatedVideo) {
            createAnnotatedVideoFile()
        } else {
            null
        }
        val tracker = if (enableTracking) IoUTracker(trackerConfig) else null
        val boutTracker = if (enableTracking && exportBoutSummary) {
            BehaviorBoutTracker(boutSettings)
        } else {
            null
        }
        val roiTracker = if (
            enableTracking && exportRoiMetrics && rois.isNotEmpty()
        ) {
            RoiDwellTracker(rois, roiSettings)
        } else {
            null
        }
        val mainHandler = Handler(Looper.getMainLooper())
        var analyzedFrames = 0
        var totalDetections = 0
        var lastFrameIndex = 0
        val baseBackendLabel = if (backend == NativeNcnnBackend.VULKAN) {
            "NCNN Vulkan native pipelined"
        } else {
            "NCNN CPU native pipelined " +
                "(${formatNcnnCpuConfiguration(workers, threads)})"
        }
        val backendLabel = runtimeAuditLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { "$baseBackendLabel [$it]" }
            ?: baseBackendLabel

        val callback = object : NativeFrameCallback {
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
                val rawDetections = decodeDetections(
                    model = model,
                    classIds = classIds,
                    confidences = confidences,
                    boxes = boxes,
                    keypointOffsets = keypointOffsets,
                    keypoints = keypoints
                )
                val trackedDetections = tracker?.update(
                    rawDetections,
                    frameIndex
                ) ?: rawDetections
                val frameResult = FrameInferenceResult(
                    timestampMs = sourceTimestampUs / 1_000L,
                    sourceTimestampUs = sourceTimestampUs,
                    imageWidth = sourceWidth,
                    imageHeight = sourceHeight,
                    detections = trackedDetections,
                    inferenceMs = inferenceTimeNs.toMillis(),
                    preprocessingMs = preprocessingTimeNs.toMillis(),
                    postprocessingMs = postprocessingTimeNs.toMillis(),
                    backend = backendLabel,
                    modelInputWidth = model.inputSize,
                    modelInputHeight = model.inputSize
                )
                csvWriter?.append(frameResult, frameIndex.toLong())
                boutTracker?.onFrame(frameIndex, trackedDetections)
                roiTracker?.onFrame(
                    frameIndex,
                    sourceWidth,
                    sourceHeight,
                    trackedDetections,
                    rotationDegrees = videoInfo.rotationDegrees
                )
                analyzedFrames += 1
                totalDetections += trackedDetections.size
                lastFrameIndex = frameIndex

                if (frameIndex % PROGRESS_INTERVAL_FRAMES == 0) {
                    val progress = if (videoInfo.durationUs > 0L) {
                        (sourceTimestampUs.toDouble() /
                            videoInfo.durationUs.toDouble())
                            .toFloat()
                            .coerceIn(0f, 0.99f)
                    } else {
                        0f
                    }
                    mainHandler.post { onProgress(progress) }
                }
                return trackedDetections
                    .map { it.trackId ?: NO_TRACK_ID }
                    .toIntArray()
            }
        }

        val nativeResult = try {
            try {
                NativeMediaPipeline.benchmarkNcnn(
                    context = context,
                    uri = uri,
                    model = model,
                    maxFrames = 0,
                    threads = threads,
                    workers = workers,
                    backend = backend,
                    annotatedVideoPath = annotatedFile?.absolutePath,
                    annotationStyle = annotationStyle,
                    annotationRois = if (drawRoisOnAnnotatedVideo) {
                        rois.map {
                            it.toSourceOrientation(videoInfo.rotationDegrees)
                        }
                    } else {
                        emptyList()
                    },
                    stopSignal = stopSignal,
                    frameCallback = callback
                )
            } catch (error: Throwable) {
                annotatedFile?.delete()
                runCatching { csvWriter?.close() }
                csvFile?.delete()
                throw error
            }
        } finally {
            csvWriter?.close()
        }
        if (nativeResult.framesProcessed == 0) {
            annotatedFile?.delete()
        }

        val bouts = boutTracker
            ?.finish(lastFrameIndex, sourceFrameRate)
            .orEmpty()
        val boutFile = if (boutTracker != null) {
            BoutCsvWriter.write(
                context = context,
                bouts = bouts,
                frameRate = sourceFrameRate,
                prefix = "offline_native_detailed_bouts"
            )
        } else {
            null
        }
        val roiFile = roiTracker?.let {
            RoiVisitCsvWriter.write(
                context,
                it.finish(lastFrameIndex, sourceFrameRate)
            )
        }
        withContext(Dispatchers.Main) { onProgress(1f) }
        return NativeNcnnOfflineRun(
            output = OfflineProcessResult(
                analyzedFrames = analyzedFrames,
                totalDetections = totalDetections,
                sourceFrameRate = sourceFrameRate,
                annotatedVideoPath = annotatedFile
                    ?.takeIf { it.isFile && it.length() > 0L }
                    ?.absolutePath,
                csvPath = csvFile?.absolutePath,
                boutCsvPath = boutFile?.absolutePath,
                roiCsvPath = roiFile?.absolutePath
            ),
            benchmark = nativeResult
        )
    }

    private fun decodeDetections(
        model: InferenceModelConfig,
        classIds: IntArray,
        confidences: FloatArray,
        boxes: FloatArray,
        keypointOffsets: IntArray,
        keypoints: FloatArray
    ): List<DetectionResult> {
        val count = classIds.size
        require(confidences.size == count) {
            "Native confidence count does not match class IDs."
        }
        require(boxes.size == count * 4) {
            "Native box count does not match detections."
        }
        require(keypointOffsets.size == count + 1) {
            "Native keypoint offsets do not match detections."
        }
        require(keypoints.size % 3 == 0) {
            "Native keypoint values are not x/y/confidence triples."
        }
        val totalKeypoints = keypoints.size / 3
        require(
            keypointOffsets.firstOrNull() == 0 &&
                keypointOffsets.lastOrNull() == totalKeypoints
        ) {
            "Native keypoint offsets do not span the keypoint array."
        }

        return List(count) { index ->
            val keypointStart = keypointOffsets[index]
            val keypointEnd = keypointOffsets[index + 1]
            require(
                keypointStart in 0..keypointEnd &&
                    keypointEnd <= totalKeypoints
            ) {
                "Native keypoint offsets are out of range."
            }
            val classIndex = classIds[index]
            val boxOffset = index * 4
            DetectionResult(
                classIndex = classIndex,
                className = model.classNames.getOrNull(classIndex)
                    ?: "class_$classIndex",
                confidence = confidences[index],
                box = BoundingBox(
                    left = boxes[boxOffset],
                    top = boxes[boxOffset + 1],
                    right = boxes[boxOffset + 2],
                    bottom = boxes[boxOffset + 3]
                ),
                keypoints = List(keypointEnd - keypointStart) {
                    keypointIndex ->
                    val pointOffset =
                        (keypointStart + keypointIndex) * 3
                    Keypoint(
                        x = keypoints[pointOffset],
                        y = keypoints[pointOffset + 1],
                        confidence = keypoints[pointOffset + 2]
                    )
                }
            )
        }
    }

    private fun Long.toMillis(): Long =
        (this / 1_000_000L).coerceAtLeast(0L)

    private fun createAnnotatedVideoFile(): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())
        return File(directory, "offline_native_annotated_$stamp.mp4")
    }

    private companion object {
        const val NO_TRACK_ID = -1
        const val PROGRESS_INTERVAL_FRAMES = 5
    }
}
