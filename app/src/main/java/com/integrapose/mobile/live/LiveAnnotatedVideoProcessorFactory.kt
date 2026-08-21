package com.integrapose.mobile.live

import android.content.Context
import com.integrapose.mobile.BuildConfig
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.model.KeypointConnection
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Selects the hardware compositor when the device supports it, with the established
 * compatibility processor retained as a measured fallback.
 */
internal fun createLiveAnnotatedVideoProcessor(context: Context): LiveAnnotatedVideoProcessor {
    val compatibility = LiveAnnotatedPostProcessor(context)
    if (!BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) return compatibility

    val hardware = runCatching {
        Class.forName(MEDIA3_PROCESSOR_CLASS)
            .getConstructor(Context::class.java)
            .newInstance(context) as LiveAnnotatedVideoProcessor
    }.getOrNull() ?: return compatibility

    return AutoSelectingLiveAnnotatedVideoProcessor(
        hardware = hardware,
        compatibility = compatibility,
        profileStore = LiveAnnotatedPipelineProfileStore(context)
    )
}

private class AutoSelectingLiveAnnotatedVideoProcessor(
    private val hardware: LiveAnnotatedVideoProcessor,
    private val compatibility: LiveAnnotatedVideoProcessor,
    private val profileStore: LiveAnnotatedPipelineProfileStore
) : LiveAnnotatedVideoProcessor {
    override suspend fun process(
        rawFile: File,
        timelineFile: LiveAnnotationTimelineFile,
        annotationStyle: AnnotationStyle,
        skeletonConnections: List<KeypointConnection>,
        rois: List<BehaviorRoi>,
        onProgress: (encodedFrames: Int, sourceFrames: Int) -> Unit
    ): LiveAnnotatedPostProcessResult {
        if (!profileStore.shouldTryHardware()) {
            return compatibility.process(
                rawFile,
                timelineFile,
                annotationStyle,
                skeletonConnections,
                rois,
                onProgress
            ).copy(fallbackReason = "Hardware compositor disabled after repeated device failures.")
        }

        val hardwareResult = runCatching {
            hardware.process(
                rawFile,
                timelineFile,
                annotationStyle,
                skeletonConnections,
                rois,
                onProgress
            )
        }
        hardwareResult.onSuccess(profileStore::recordHardwareSuccess)
        hardwareResult.getOrNull()?.let { return it }
        hardwareResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }

        val reason = hardwareResult.exceptionOrNull()?.message
            ?.take(240)
            ?: "The device hardware compositor rejected this recording."
        profileStore.recordHardwareFailure(reason)
        return compatibility.process(
            rawFile,
            timelineFile,
            annotationStyle,
            skeletonConnections,
            rois,
            onProgress
        ).copy(fallbackReason = reason)
    }
}

private const val MEDIA3_PROCESSOR_CLASS =
    "com.integrapose.mobile.live.Media3LiveAnnotatedVideoProcessor"

/** Small, measured device profile: no fictional control over vendor codec thread counts. */
internal class LiveAnnotatedPipelineProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "live_annotated_pipeline_profile",
        Context.MODE_PRIVATE
    )

    fun shouldTryHardware(): Boolean {
        val consecutiveFailures = preferences.getInt(KEY_CONSECUTIVE_FAILURES, 0)
        return shouldTryHardwarePipeline(consecutiveFailures)
    }

    fun recordHardwareSuccess(result: LiveAnnotatedPostProcessResult) {
        preferences.edit()
            .putInt(KEY_SUCCESSES, preferences.getInt(KEY_SUCCESSES, 0) + 1)
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .putLong(KEY_LAST_PROCESSING_MS, result.processingDurationMs)
            .putLong(KEY_LAST_SOURCE_MS, result.sourceDurationMs)
            .remove(KEY_LAST_FAILURE)
            .apply()
    }

    fun recordHardwareFailure(reason: String) {
        preferences.edit()
            .putInt(
                KEY_CONSECUTIVE_FAILURES,
                preferences.getInt(KEY_CONSECUTIVE_FAILURES, 0) + 1
            )
            .putString(KEY_LAST_FAILURE, reason)
            .apply()
    }

    private companion object {
        const val KEY_SUCCESSES = "hardware_successes"
        const val KEY_CONSECUTIVE_FAILURES = "hardware_consecutive_failures"
        const val KEY_LAST_PROCESSING_MS = "hardware_last_processing_ms"
        const val KEY_LAST_SOURCE_MS = "hardware_last_source_ms"
        const val KEY_LAST_FAILURE = "hardware_last_failure"
    }
}

internal fun shouldTryHardwarePipeline(
    consecutiveFailures: Int
): Boolean = consecutiveFailures < 2
