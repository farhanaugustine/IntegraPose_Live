package com.integrapose.mobile.benchmark

import android.content.Context
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.offline.NativeNcnnBackend
import com.integrapose.mobile.offline.NcnnExecutionProfile
import com.integrapose.mobile.offline.NcnnProfileSelection

enum class NcnnProfileTarget(val storageName: String) {
    LIVE_IMAGE("live_image"),
    OFFLINE("offline")
}

class NcnnExecutionProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(
        profileKey: String,
        target: NcnnProfileTarget
    ): NcnnExecutionProfile? {
        val prefix = keyPrefix(profileKey, target)
        if (!preferences.getBoolean("$prefix.present", false)) return null
        return runCatching {
            NcnnExecutionProfile(
                modelId = requireNotNull(
                    preferences.getString("$prefix.model_id", null)
                ),
                threadsPerWorker = preferences.getInt("$prefix.threads", 1),
                workers = preferences.getInt("$prefix.workers", 1),
                backend = NativeNcnnBackend.valueOf(
                    requireNotNull(preferences.getString("$prefix.backend", null))
                ),
                measuredPipelineFps = Double.fromBits(
                    preferences.getLong("$prefix.pipeline_fps", 0L)
                ),
                benchmarked = preferences.getBoolean("$prefix.benchmarked", true),
                streamingThreads = preferences.getInt("$prefix.streaming_threads", 1),
                streamingBackend = NativeNcnnBackend.valueOf(
                    requireNotNull(
                        preferences.getString("$prefix.streaming_backend", null)
                    )
                ),
                measuredStreamingPipelineFps = Double.fromBits(
                    preferences.getLong("$prefix.streaming_pipeline_fps", 0L)
                ),
                selection = NcnnProfileSelection.valueOf(
                    requireNotNull(preferences.getString("$prefix.selection", null))
                ),
                vulkanParityPassed = when (
                    preferences.getInt("$prefix.vulkan_parity", PARITY_UNKNOWN)
                ) {
                    PARITY_PASSED -> true
                    PARITY_FAILED -> false
                    else -> null
                }
            )
        }.getOrNull()
    }

    fun save(
        profileKey: String,
        target: NcnnProfileTarget,
        profile: NcnnExecutionProfile
    ) {
        val prefix = keyPrefix(profileKey, target)
        preferences.edit()
            .putBoolean("$prefix.present", true)
            .putString("$prefix.model_id", profile.modelId)
            .putInt("$prefix.threads", profile.threadsPerWorker)
            .putInt("$prefix.workers", profile.workers)
            .putString("$prefix.backend", profile.backend.name)
            .putLong(
                "$prefix.pipeline_fps",
                profile.measuredPipelineFps.toRawBits()
            )
            .putBoolean("$prefix.benchmarked", profile.benchmarked)
            .putInt("$prefix.streaming_threads", profile.streamingThreads)
            .putString("$prefix.streaming_backend", profile.streamingBackend.name)
            .putLong(
                "$prefix.streaming_pipeline_fps",
                profile.measuredStreamingPipelineFps.toRawBits()
            )
            .putString("$prefix.selection", profile.selection.name)
            .putInt(
                "$prefix.vulkan_parity",
                when (profile.vulkanParityPassed) {
                    true -> PARITY_PASSED
                    false -> PARITY_FAILED
                    null -> PARITY_UNKNOWN
                }
            )
            .apply()
    }

    private fun keyPrefix(
        profileKey: String,
        target: NcnnProfileTarget
    ): String = "${target.storageName}.$profileKey"

    private companion object {
        const val PREFERENCES_NAME = "ncnn_execution_profiles"
        const val PARITY_UNKNOWN = -1
        const val PARITY_FAILED = 0
        const val PARITY_PASSED = 1
    }
}

fun ncnnProfileStorageKey(model: InferenceModelConfig): String = buildString {
    append(model.id)
    append(':').append(model.runtime.name)
    append(':').append(model.inputSize)
    append(':').append(model.detectionCount)
    append(':').append(model.confThreshold.toRawBits())
    append(':').append(model.iouThreshold.toRawBits())
}
