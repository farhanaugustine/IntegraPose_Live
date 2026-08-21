package com.integrapose.mobile.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.OverlayRenderer
import com.integrapose.mobile.model.KeypointConnection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Canonical hardware path: MediaCodec decode -> OpenGL overlay -> MediaCodec encode.
 * Raw camera pixels remain on the surface pipeline; only the transparent annotations are drawn.
 */
@OptIn(UnstableApi::class)
internal class Media3LiveAnnotatedVideoProcessor(context: Context) :
    LiveAnnotatedVideoProcessor {
    private val context = context.applicationContext

    override suspend fun process(
        rawFile: File,
        timelineFile: LiveAnnotationTimelineFile,
        annotationStyle: AnnotationStyle,
        skeletonConnections: List<KeypointConnection>,
        rois: List<BehaviorRoi>,
        onProgress: (encodedFrames: Int, sourceFrames: Int) -> Unit
    ): LiveAnnotatedPostProcessResult {
        val startedNs = System.nanoTime()
        val metadata = withContext(Dispatchers.IO) { readSourceMetadata(rawFile) }
        val timeline = withContext(Dispatchers.IO) { loadTimeline(timelineFile.file) }
        require(timeline.size > 0) {
            "No inferred frames were available for the annotated video."
        }
        val outputFile = createOutputFile()

        return try {
            val exportResult = withContext(Dispatchers.Main.immediate) {
                export(
                    rawFile = rawFile,
                    outputFile = outputFile,
                    sourceFrames = metadata.frameCount,
                    timeline = timeline,
                    annotationStyle = annotationStyle,
                    skeletonConnections = skeletonConnections,
                    rois = rois,
                    onProgress = onProgress
                )
            }
            currentCoroutineContext().ensureActive()
            val encodedFrames = exportResult.videoFrameCount
            require(encodedFrames > 0) {
                "Media3 completed without encoding video frames."
            }
            val sourceFrames = metadata.frameCount.takeIf { it > 0 } ?: encodedFrames
            if (metadata.hasExactFrameCount) {
                require(encodedFrames == sourceFrames) {
                    "Media3 encoded $encodedFrames of $sourceFrames raw master frames; " +
                        "the incomplete hardware derivative was rejected."
                }
            }
            onProgress(encodedFrames, sourceFrames)
            LiveAnnotatedPostProcessResult(
                file = outputFile,
                sourceFrames = sourceFrames,
                encodedFrames = encodedFrames,
                outputFrameRate = OUTPUT_FRAME_RATE,
                inferenceSamples = timeline.size,
                reusedInferenceFrames = (encodedFrames - timeline.size).coerceAtLeast(0),
                processingDurationMs = (System.nanoTime() - startedNs) / 1_000_000L,
                sourceDurationMs = metadata.durationMs,
                pipelineName = "Media3 hardware surface + OpenGL"
            )
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    private suspend fun export(
        rawFile: File,
        outputFile: File,
        sourceFrames: Int,
        timeline: AnnotationTimelineCursor,
        annotationStyle: AnnotationStyle,
        skeletonConnections: List<KeypointConnection>,
        rois: List<BehaviorRoi>,
        onProgress: (encodedFrames: Int, sourceFrames: Int) -> Unit
    ): ExportResult {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Media3 Transformer must be created on the main looper."
        }
        val overlay = object : CanvasOverlay(true) {
            override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val inference = timeline.resultAt(presentationTimeUs) ?: return
                OverlayRenderer.draw(
                    canvas = canvas,
                    inference = inference,
                    targetWidth = canvas.width.toFloat(),
                    targetHeight = canvas.height.toFloat(),
                    annotationStyle = annotationStyle,
                    skeletonConnections = skeletonConnections,
                    rois = rois
                )
            }
        }
        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(Uri.fromFile(rawFile))
        )
            .setRemoveAudio(true)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(OverlayEffect(listOf(overlay)))
                )
            )
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(OUTPUT_BIT_RATE)
                    .setiFrameIntervalSeconds(1f)
                    .build()
            )
            .setEnableFallback(true)
            .build()

        lateinit var transformer: Transformer
        val result = suspendCancellableCoroutine<ExportResult> { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            val progressHolder = ProgressHolder()
            lateinit var progressPoll: Runnable
            progressPoll = Runnable {
                if (!continuation.isActive) return@Runnable
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val estimatedFrames = (
                        sourceFrames * progressHolder.progress.coerceIn(0, 100) / 100f
                    ).toInt().coerceIn(0, sourceFrames)
                    onProgress(estimatedFrames, sourceFrames)
                }
                mainHandler.postDelayed(progressPoll, PROGRESS_INTERVAL_MS)
            }
            val listener = object : Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult
                ) {
                    mainHandler.removeCallbacks(progressPoll)
                    if (continuation.isActive) continuation.resume(exportResult)
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mainHandler.removeCallbacks(progressPoll)
                    if (continuation.isActive) {
                        continuation.resumeWithException(exportException)
                    }
                }
            }
            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(listener)
                .build()
            continuation.invokeOnCancellation {
                mainHandler.removeCallbacks(progressPoll)
                mainHandler.post { transformer.cancel() }
            }
            transformer.start(editedMediaItem, outputFile.absolutePath)
            mainHandler.post(progressPoll)
        }
        return result
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

    private fun readSourceMetadata(rawFile: File): SourceMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(rawFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: error("The raw master has no readable duration.")
            val metadataFrames = if (android.os.Build.VERSION.SDK_INT >= 28) {
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            } else {
                null
            }
            SourceMetadata(
                durationMs = durationMs,
                frameCount = metadataFrames ?: ceil(
                    durationMs * OUTPUT_FRAME_RATE / 1_000.0
                ).toInt().coerceAtLeast(1),
                hasExactFrameCount = metadataFrames != null
            )
        } finally {
            retriever.release()
        }
    }

    private fun createOutputFile(): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directory, "live_annotated_media3_$stamp.mp4")
    }

    private data class SourceMetadata(
        val durationMs: Long,
        val frameCount: Int,
        val hasExactFrameCount: Boolean
    )

    private companion object {
        const val OUTPUT_FRAME_RATE = 30
        const val OUTPUT_BIT_RATE = 5_000_000
        const val PROGRESS_INTERVAL_MS = 150L
    }
}
