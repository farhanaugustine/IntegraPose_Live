package com.integrapose.mobile.analytics

data class BoutSettings(
    val minBoutFrames: Int = 3,
    val maxGapFrames: Int = 5
) {
    fun sanitized(): BoutSettings {
        return copy(
            minBoutFrames = minBoutFrames.coerceIn(1, 36_000),
            maxGapFrames = maxGapFrames.coerceIn(0, 3_600)
        )
    }
}
