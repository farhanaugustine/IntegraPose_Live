package com.integrapose.mobile.live

/** Persisted global quality requested for Live raw masters and their derivatives. */
enum class LiveRawVideoQuality(
    val storageName: String,
    val displayName: String,
    val dimensionsLabel: String,
    internal val estimatedBitsPerSecond: Long
) {
    HD_720P("hd_720p", "HD 720p", "1280 × 720", 12_000_000L),
    SD_480P("sd_480p", "SD 480p", "720 × 480", 6_000_000L);

    companion object {
        val Default: LiveRawVideoQuality = HD_720P

        fun fromStoredName(value: String?): LiveRawVideoQuality =
            entries.firstOrNull { it.storageName == value } ?: Default
    }
}
