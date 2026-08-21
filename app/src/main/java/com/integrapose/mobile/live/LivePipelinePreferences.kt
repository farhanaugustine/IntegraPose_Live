package com.integrapose.mobile.live

enum class LivePreviewQuality(
    val storageName: String,
    val displayName: String,
    val width: Int,
    val height: Int
) {
    HD_720P("hd_720p", "720p", 1280, 720),
    SD_480P("sd_480p", "480p", 720, 480);

    companion object {
        val Default: LivePreviewQuality = HD_720P

        fun fromStoredName(value: String?): LivePreviewQuality =
            entries.firstOrNull { it.storageName == value } ?: Default
    }
}

enum class LivePreviewRenderer(
    val storageName: String,
    val displayName: String
) {
    COMPATIBLE("compatible", "Compatible TextureView"),
    PERFORMANCE("performance", "Performance SurfaceView");

    companion object {
        val Default: LivePreviewRenderer = COMPATIBLE

        fun fromStoredName(value: String?): LivePreviewRenderer =
            entries.firstOrNull { it.storageName == value } ?: Default
    }
}

enum class LiveOverlayRefreshRate(
    val storageName: String,
    val displayName: String,
    val maximumFps: Int?
) {
    FULL("full", "Every inference result", null),
    FPS_20("20_fps", "20 updates/s", 20),
    FPS_15("15_fps", "15 updates/s", 15);

    val minimumIntervalUs: Long
        get() = maximumFps?.let { 1_000_000L / it } ?: 0L

    companion object {
        val Default: LiveOverlayRefreshRate = FPS_20

        fun fromStoredName(value: String?): LiveOverlayRefreshRate =
            entries.firstOrNull { it.storageName == value } ?: Default
    }
}
