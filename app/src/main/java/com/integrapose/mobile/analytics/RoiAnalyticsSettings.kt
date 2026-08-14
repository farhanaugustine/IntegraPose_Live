package com.integrapose.mobile.analytics

enum class RoiAnchorMode(val displayName: String, val contractName: String) {
    BOUNDING_BOX_CENTER("Bounding box", "bbox_only"),
    KEYPOINT("Selected keypoint", "keypoint_index")
}

/**
 * Regular ROI membership and temporal qualification settings.
 */
data class RoiAnalyticsSettings(
    val anchorMode: RoiAnchorMode = RoiAnchorMode.BOUNDING_BOX_CENTER,
    val keypointIndex: Int = 0,
    val entryThreshold: Float = 0.75f,
    val exitThreshold: Float = 0.25f,
    val maxGapFrames: Int = 5,
    val minDwellFrames: Int = 3
) {
    fun sanitized(): RoiAnalyticsSettings = copy(
        keypointIndex = keypointIndex.coerceIn(0, 1_023),
        entryThreshold = entryThreshold.coerceIn(0f, 1f),
        exitThreshold = exitThreshold.coerceIn(0f, entryThreshold.coerceIn(0f, 1f)),
        maxGapFrames = maxGapFrames.coerceIn(0, 3_600),
        minDwellFrames = minDwellFrames.coerceIn(1, 36_000)
    )
}
