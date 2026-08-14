package com.integrapose.mobile.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.integrapose.mobile.analytics.BehaviorBoutTracker
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.analytics.BoutSettings
import com.integrapose.mobile.analytics.RoiAnalyticsSettings
import com.integrapose.mobile.analytics.RoiDwellTracker
import com.integrapose.mobile.inference.OverlayRenderer
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.live.CsvSessionWriter
import com.integrapose.mobile.media.AnnotationResolution
import com.integrapose.mobile.media.BitmapVideoEncoder
import com.integrapose.mobile.media.FrameOrientation
import com.integrapose.mobile.media.dimensionsFor
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.tracking.IoUTracker
import com.integrapose.mobile.tracking.IoUTrackerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

data class OfflineProcessResult(
    val analyzedFrames: Int,
    val totalDetections: Int,
    val sourceFrameRate: Double,
    val annotatedVideoPath: String?,
    val csvPath: String?,
    val boutCsvPath: String?,
    val roiCsvPath: String?
)

class OfflineProcessor(private val context: Context) {
    suspend fun processVideo(
        uri: Uri,
        model: InferenceModelConfig,
        runner: ModelInferenceRunner,
        enableTracking: Boolean = true,
        exportAnnotatedVideo: Boolean = true,
        drawRoisOnAnnotatedVideo: Boolean = false,
        exportDetectionCsv: Boolean = true,
        exportBoutSummary: Boolean = true,
        exportRoiMetrics: Boolean = true,
        annotationResolution: AnnotationResolution = AnnotationResolution.default,
        annotationStyle: AnnotationStyle = AnnotationStyle.Default,
        rois: List<BehaviorRoi> = emptyList(),
        boutSettings: BoutSettings = BoutSettings(),
        roiSettings: RoiAnalyticsSettings = RoiAnalyticsSettings(),
        trackerConfig: IoUTrackerConfig = IoUTrackerConfig(),
        ncnnTuning: NcnnRuntimeTuning? = null,
        shouldStop: () -> Boolean = { false },
        onProgress: (Float) -> Unit
    ): OfflineProcessResult = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val csvWriter = if (exportDetectionCsv) CsvSessionWriter(context) else null
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
        var encoder: BitmapVideoEncoder? = null
        var annotatedFile: File? = null
        var annotationWidth = 0
        var annotationHeight = 0
        var csvFile: File? = null
        var analyzedFrames = 0
        var totalDetections = 0
        var lastSourceFrameIndex = 0
        var sourceFrameRate = 30.0

        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            require(durationMs > 0L) { "The selected video has no readable duration." }
            val durationUs = durationMs * 1_000L
            val rotationDegrees = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
            val metadataFrameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            } else {
                null
            }
            val capturedRate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
            sourceFrameRate = when {
                capturedRate != null -> capturedRate
                metadataFrameCount != null -> metadataFrameCount / (durationMs / 1_000.0)
                else -> 30.0
            }.coerceIn(1.0, 120.0)

            val stepUs = (1_000_000.0 / sourceFrameRate).toLong().coerceAtLeast(1L)
            val expectedFrames = metadataFrameCount
                ?: ceil(durationUs / stepUs.toDouble()).toInt().coerceAtLeast(1)
            val progressInterval = (expectedFrames / 100).coerceAtLeast(1)
            csvFile = csvWriter?.start(model.type, "offline")

            for (sourceFrameIndex in 0 until expectedFrames) {
                if (shouldStop()) break
                lastSourceFrameIndex = sourceFrameIndex
                val timestampUs = (sourceFrameIndex * stepUs)
                    .coerceAtMost((durationUs - 1L).coerceAtLeast(0L))
                val rawFrame = retriever.getFrameAtTime(
                    timestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val frame = orientFrame(rawFrame, rotationDegrees)
                try {
                    val rawResult = runner.run(
                        bitmap = frame,
                        config = model,
                        sourceTimestampUs = timestampUs,
                        ncnnTuning = ncnnTuning
                    )
                    val trackedDetections = tracker?.update(rawResult.detections, sourceFrameIndex)
                        ?: rawResult.detections
                    val result = rawResult.copy(detections = trackedDetections)
                    csvWriter?.append(result, sourceFrameIndex.toLong())
                    boutTracker?.onFrame(sourceFrameIndex, trackedDetections)
                    roiTracker?.onFrame(
                        sourceFrameIndex,
                        result.imageWidth,
                        result.imageHeight,
                        trackedDetections
                    )
                    totalDetections += trackedDetections.size
                    analyzedFrames += 1

                    if (exportAnnotatedVideo) {
                        if (encoder == null) {
                            val orientation = if (frame.height > frame.width) {
                                FrameOrientation.PORTRAIT
                            } else {
                                FrameOrientation.LANDSCAPE
                            }
                            val dimensions = annotationResolution.dimensionsFor(orientation)
                            annotationWidth = dimensions.width
                            annotationHeight = dimensions.height
                            annotatedFile = createAnnotatedVideoFile()
                            encoder = BitmapVideoEncoder(
                                outputFile = requireNotNull(annotatedFile),
                                width = dimensions.width,
                                height = dimensions.height,
                                frameRate = sourceFrameRate.roundToInt().coerceIn(1, 120)
                            )
                        }
                        val annotated = OverlayRenderer.renderBitmap(
                            source = frame,
                            inference = result,
                            targetWidth = annotationWidth,
                            targetHeight = annotationHeight,
                            annotationStyle = annotationStyle,
                            skeletonConnections = model.skeletonConnections,
                            rois = if (drawRoisOnAnnotatedVideo) {
                                rois
                            } else {
                                emptyList()
                            }
                        )
                        try {
                            encoder?.encodeFrame(annotated)
                        } finally {
                            annotated.recycle()
                        }
                    }
                } finally {
                    frame.recycle()
                }

                if (sourceFrameIndex % progressInterval == 0 || sourceFrameIndex == expectedFrames - 1) {
                    withContext(Dispatchers.Main) {
                        onProgress((sourceFrameIndex + 1f) / expectedFrames.toFloat())
                    }
                }
            }
        } finally {
            runCatching { csvWriter?.close() }
            try {
                runCatching { encoder?.close() }.getOrThrow()
            } finally {
                retriever.release()
            }
        }

        withContext(Dispatchers.Main) { onProgress(1f) }
        val bouts = boutTracker?.finish(lastSourceFrameIndex, sourceFrameRate).orEmpty()
        val boutFile = if (boutTracker != null) {
            BoutCsvWriter.write(context, bouts, sourceFrameRate)
        } else {
            null
        }
        val roiFile = roiTracker?.let {
            RoiVisitCsvWriter.write(
                context,
                it.finish(lastSourceFrameIndex, sourceFrameRate)
            )
        }
        OfflineProcessResult(
            analyzedFrames = analyzedFrames,
            totalDetections = totalDetections,
            sourceFrameRate = sourceFrameRate,
            annotatedVideoPath = annotatedFile?.absolutePath,
            csvPath = csvFile?.absolutePath,
            boutCsvPath = boutFile?.absolutePath,
            roiCsvPath = roiFile?.absolutePath
        )
    }

    private fun orientFrame(source: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun createAnnotatedVideoFile(): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directory, "offline_annotated_$stamp.mp4")
    }

}
