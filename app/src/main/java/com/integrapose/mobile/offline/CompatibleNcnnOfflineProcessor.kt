package com.integrapose.mobile.offline

import android.content.Context
import android.net.Uri
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.analytics.BoutSettings
import com.integrapose.mobile.analytics.RoiAnalyticsSettings
import com.integrapose.mobile.media.AnnotationResolution
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.tracking.IoUTrackerConfig

data class CompatibleNcnnOfflineRun(
    val output: OfflineProcessResult,
    val nativeBenchmark: NativeNcnnPipelineBenchmark?,
    val usedBitmapDecoder: Boolean
)

class CompatibleNcnnOfflineProcessor(context: Context) {
    private val nativeProcessor = NativeNcnnOfflineProcessor(context)
    private val bitmapProcessor = OfflineProcessor(context)

    suspend fun processVideo(
        uri: Uri,
        model: InferenceModelConfig,
        runner: ModelInferenceRunner,
        enableTracking: Boolean,
        exportAnnotatedVideo: Boolean,
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
        threads: Int,
        workers: Int,
        backend: NativeNcnnBackend,
        runtimeAuditLabel: String? = null,
        fallbackTuning: NcnnRuntimeTuning? = null,
        stopSignal: NativeStopSignal? = null,
        onCompatibilityFallback: () -> Unit = {},
        onProgress: (Float) -> Unit
    ): CompatibleNcnnOfflineRun {
        return try {
            val nativeRun = nativeProcessor.processVideo(
                uri = uri,
                model = model,
                enableTracking = enableTracking,
                exportAnnotatedVideo = exportAnnotatedVideo,
                drawRoisOnAnnotatedVideo = drawRoisOnAnnotatedVideo,
                exportDetectionCsv = exportDetectionCsv,
                exportBoutSummary = exportBoutSummary,
                exportRoiMetrics = exportRoiMetrics,
                threads = threads,
                workers = workers,
                backend = backend,
                runtimeAuditLabel = runtimeAuditLabel,
                stopSignal = stopSignal,
                annotationStyle = annotationStyle,
                rois = rois,
                boutSettings = boutSettings,
                roiSettings = roiSettings,
                trackerConfig = trackerConfig,
                onProgress = onProgress
            )
            CompatibleNcnnOfflineRun(
                output = nativeRun.output,
                nativeBenchmark = nativeRun.benchmark,
                usedBitmapDecoder = false
            )
        } catch (nativeError: Throwable) {
            if (!AndroidSoftwareVideoDecoder.shouldRetry(nativeError)) {
                throw nativeError
            }
            onCompatibilityFallback()
            val output = bitmapProcessor.processVideo(
                uri = uri,
                model = model,
                runner = runner,
                enableTracking = enableTracking,
                exportAnnotatedVideo = exportAnnotatedVideo,
                drawRoisOnAnnotatedVideo = drawRoisOnAnnotatedVideo,
                exportDetectionCsv = exportDetectionCsv,
                exportBoutSummary = exportBoutSummary,
                exportRoiMetrics = exportRoiMetrics,
                annotationResolution = annotationResolution,
                annotationStyle = annotationStyle,
                rois = rois,
                boutSettings = boutSettings,
                roiSettings = roiSettings,
                trackerConfig = trackerConfig,
                ncnnTuning = fallbackTuning,
                shouldStop = {
                    stopSignal?.isStopRequested() == true
                },
                onProgress = onProgress
            )
            CompatibleNcnnOfflineRun(
                output = output,
                nativeBenchmark = null,
                usedBitmapDecoder = true
            )
        }
    }
}
