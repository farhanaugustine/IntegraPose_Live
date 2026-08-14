package com.integrapose.mobile.live

import android.content.Context
import android.os.Environment
import java.io.File

internal data class LiveRecordingStorageBudget(
    val requiredBytes: Long,
    val availableBytes: Long
)

/** Conservative disk budget and low-space guard for direct-to-disk Live recording. */
internal object LiveRecordingStorage {
    const val MONITOR_INTERVAL_MS = 5_000L
    const val EMERGENCY_FREE_BYTES = 128L * 1024L * 1024L
    const val START_RESERVE_BYTES = 256L * 1024L * 1024L
    internal const val RAW_VIDEO_BITS_PER_SECOND = 12_000_000L
    internal const val ANNOTATED_VIDEO_BITS_PER_SECOND = 5_000_000L
    internal const val DATA_BITS_PER_SECOND = 1_000_000L

    fun requireStartCapacity(
        context: Context,
        options: LiveRecordingOptions
    ): LiveRecordingStorageBudget {
        val available = availableBytes(context)
        val required = estimatedRequiredBytes(options)
        require(available >= required) {
            "Not enough app storage for this recording plan. " +
                "Required ${formatBytes(required)}; available ${formatBytes(available)}. " +
                "Shorten the planned duration, save fewer outputs, or free space."
        }
        return LiveRecordingStorageBudget(required, available)
    }

    fun availableBytes(context: Context): Long = recordingRoot(context).usableSpace

    fun estimatedRequiredBytes(options: LiveRecordingOptions): Long {
        val minutes = options.plannedDurationMinutes.coerceIn(0, MAX_PLANNED_MINUTES)
        if (minutes == 0) return START_RESERVE_BYTES
        var bitsPerSecond = 0L
        if (options.rawVideo) bitsPerSecond += RAW_VIDEO_BITS_PER_SECOND
        if (options.annotatedVideo) bitsPerSecond += ANNOTATED_VIDEO_BITS_PER_SECOND
        if (options.detectionCsv || options.classBouts || options.roiVisits) {
            bitsPerSecond += DATA_BITS_PER_SECOND
        }
        val seconds = minutes.toLong() * 60L
        val payloadBytes = seconds * ((bitsPerSecond + 7L) / 8L)
        return START_RESERVE_BYTES + payloadBytes
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val gib = safe / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            String.format(java.util.Locale.US, "%.1f GB", gib)
        } else {
            val mib = safe / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%.0f MB", mib)
        }
    }

    private fun recordingRoot(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(root, "IntegraPose Live").also {
            check(it.exists() || it.mkdirs()) {
                "Could not create IntegraPose Live recording storage."
            }
        }
    }

    const val MAX_PLANNED_MINUTES = 1_440
}
