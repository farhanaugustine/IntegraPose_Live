package com.integrapose.mobile.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.OverlayRenderer
import com.integrapose.mobile.media.SurfaceCanvasVideoEncoder
import com.integrapose.mobile.model.KeypointConnection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LiveAnnotatedPostProcessResult(
    val file: File,
    val sourceFrames: Int,
    val encodedFrames: Int,
    val outputFrameRate: Int,
    val inferenceSamples: Int,
    val reusedInferenceFrames: Int,
    val processingDurationMs: Long,
    val sourceDurationMs: Long,
    val pipelineName: String,
    val fallbackReason: String? = null
)

internal interface LiveAnnotatedVideoProcessor {
    suspend fun process(
        rawFile: File,
        timelineFile: LiveAnnotationTimelineFile,
        annotationStyle: AnnotationStyle,
        skeletonConnections: List<KeypointConnection>,
        rois: List<BehaviorRoi>,
        onProgress: (encodedFrames: Int, sourceFrames: Int) -> Unit = { _, _ -> }
    ): LiveAnnotatedPostProcessResult
}

internal data class TimedAnnotation(
    val relativeTimestampUs: Long,
    val result: FrameInferenceResult
)

internal class AnnotationTimelineCursor(
    private val frames: List<TimedAnnotation>
) {
    private var index = 0

    val size: Int get() = frames.size

    fun resultAt(timestampUs: Long): FrameInferenceResult? {
        if (frames.isEmpty()) return null
        while (
            index + 1 < frames.size &&
            frames[index + 1].relativeTimestampUs <= timestampUs
        ) {
            index += 1
        }
        return frames[index].result
    }
}

/**
 * Test-variant compositor. It runs only after CameraX has finalized the raw master,
 * so live inference never waits for annotation rendering or MP4 encoding.
 */
internal class LiveAnnotatedPostProcessor(private val context: Context) :
    LiveAnnotatedVideoProcessor {
    override suspend fun process(
        rawFile: File,
        timelineFile: LiveAnnotationTimelineFile,
        annotationStyle: AnnotationStyle,
        skeletonConnections: List<KeypointConnection>,
        rois: List<BehaviorRoi>,
        onProgress: (encodedFrames: Int, sourceFrames: Int) -> Unit
    ): LiveAnnotatedPostProcessResult = withContext(Dispatchers.Default) {
        val processingStartedNs = System.nanoTime()
        val timeline = loadTimeline(timelineFile.file)
        require(timeline.size > 0) {
            "No inferred frames were available for the annotated video."
        }
        val retriever = MediaMetadataRetriever()
        var encoder: SurfaceCanvasVideoEncoder? = null
        var outputFile: File? = null
        var encodedFrames = 0
        try {
            retriever.setDataSource(rawFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            require(durationMs > 0L) { "The raw master has no readable duration." }
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
            val sourceFrames = metadataFrameCount
                ?: ceil(durationUs / FRAME_INTERVAL_US.toDouble()).toInt().coerceAtLeast(1)
            val progressInterval = (sourceFrames / 40).coerceAtLeast(1)
            outputFile = createOutputFile()

            var batchStart = 0
            while (batchStart < sourceFrames) {
                val requestedCount = minOf(DECODE_BATCH_SIZE, sourceFrames - batchStart)
                val decodedBatch = decodeBatch(
                    retriever = retriever,
                    startFrameIndex = batchStart,
                    frameCount = requestedCount,
                    durationUs = durationUs,
                    useFrameIndex = metadataFrameCount != null
                )
                try {
                    decodedBatch.forEach { decoded ->
                        val frameIndex = decoded.frameIndex
                        val timestampUs = (frameIndex * FRAME_INTERVAL_US)
                            .coerceAtMost((durationUs - 1L).coerceAtLeast(0L))
                        val frame = orientFrame(decoded.bitmap, rotationDegrees)
                        try {
                            val inference = requireNotNull(timeline.resultAt(timestampUs))
                            if (encoder == null) {
                                val width = frame.width - frame.width % 2
                                val height = frame.height - frame.height % 2
                                require(width > 0 && height > 0) {
                                    "The raw master frame dimensions are invalid."
                                }
                                encoder = SurfaceCanvasVideoEncoder(
                                    outputFile = requireNotNull(outputFile),
                                    width = width,
                                    height = height,
                                    frameRate = OUTPUT_FRAME_RATE
                                )
                            }
                            val activeEncoder = requireNotNull(encoder)
                            val targetWidth = frame.width - frame.width % 2
                            val targetHeight = frame.height - frame.height % 2
                            activeEncoder.encodeFrame { canvas ->
                                canvas.drawColor(Color.BLACK)
                                canvas.drawBitmap(
                                    frame,
                                    null,
                                    RectF(
                                        0f,
                                        0f,
                                        targetWidth.toFloat(),
                                        targetHeight.toFloat()
                                    ),
                                    FRAME_PAINT
                                )
                                OverlayRenderer.draw(
                                    canvas = canvas,
                                    inference = inference,
                                    targetWidth = targetWidth.toFloat(),
                                    targetHeight = targetHeight.toFloat(),
                                    annotationStyle = annotationStyle,
                                    skeletonConnections = skeletonConnections,
                                    rois = rois
                                )
                            }
                            encodedFrames += 1
                        } finally {
                            if (!frame.isRecycled) frame.recycle()
                        }
                        if (
                            frameIndex % progressInterval == 0 ||
                            frameIndex == sourceFrames - 1
                        ) {
                            withContext(Dispatchers.Main) {
                                onProgress(encodedFrames, sourceFrames)
                            }
                        }
                    }
                } finally {
                    decodedBatch.forEach { decoded ->
                        if (!decoded.bitmap.isRecycled) decoded.bitmap.recycle()
                    }
                }
                batchStart += requestedCount
            }
            require(encodedFrames > 0) {
                "No raw master frames could be decoded for annotation."
            }
            require(encodedFrames == sourceFrames) {
                "Decoded $encodedFrames of $sourceFrames raw master frames; " +
                    "the incomplete annotated derivative was not accepted."
            }
            val completedEncoder = requireNotNull(encoder)
            completedEncoder.close()
            require(completedEncoder.writtenFrames == sourceFrames) {
                "Encoded ${completedEncoder.writtenFrames} of $sourceFrames raw master " +
                    "frames; the incomplete annotated derivative was not accepted."
            }
            encoder = null
            LiveAnnotatedPostProcessResult(
                file = requireNotNull(outputFile),
                sourceFrames = sourceFrames,
                encodedFrames = encodedFrames,
                outputFrameRate = OUTPUT_FRAME_RATE,
                inferenceSamples = timeline.size,
                reusedInferenceFrames = (encodedFrames - timeline.size).coerceAtLeast(0),
                processingDurationMs =
                    (System.nanoTime() - processingStartedNs) / 1_000_000L,
                sourceDurationMs = durationMs,
                pipelineName = "compatibility bitmap batches"
            )
        } catch (error: Throwable) {
            runCatching { encoder?.close() }
            outputFile?.delete()
            throw error
        } finally {
            retriever.release()
        }
    }

    private fun loadTimeline(file: File): AnnotationTimelineCursor {
        val frames = mutableListOf<FrameInferenceResult>()
        LiveInferenceJournal.forEachFrame(file) { _, result -> frames += result }
        val firstTimestampUs = frames.firstOrNull()?.sourceTimestampUs ?: 0L
        return AnnotationTimelineCursor(
            frames.map { result ->
                TimedAnnotation(
                    relativeTimestampUs =
                        (result.sourceTimestampUs - firstTimestampUs).coerceAtLeast(0L),
                    result = result
                )
            }
        )
    }

    private fun decodeBatch(
        retriever: MediaMetadataRetriever,
        startFrameIndex: Int,
        frameCount: Int,
        durationUs: Long,
        useFrameIndex: Boolean
    ): List<DecodedFrame> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useFrameIndex) {
            return retriever.getFramesAtIndex(startFrameIndex, frameCount)
                .mapIndexed { offset, bitmap ->
                    DecodedFrame(startFrameIndex + offset, bitmap)
                }
        }
        return (startFrameIndex until startFrameIndex + frameCount).mapNotNull {
            frameIndex ->
            val timestampUs = (frameIndex * FRAME_INTERVAL_US)
                .coerceAtMost((durationUs - 1L).coerceAtLeast(0L))
            retriever.getFrameAtTime(
                timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )?.let { bitmap -> DecodedFrame(frameIndex, bitmap) }
        }
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

    private fun createOutputFile(): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directory, "live_annotated_postprocessed_$stamp.mp4")
    }

    private data class DecodedFrame(
        val frameIndex: Int,
        val bitmap: Bitmap
    )

    private companion object {
        const val OUTPUT_FRAME_RATE = 30
        const val FRAME_INTERVAL_US = 1_000_000L / OUTPUT_FRAME_RATE
        const val DECODE_BATCH_SIZE = 8
        val FRAME_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}
