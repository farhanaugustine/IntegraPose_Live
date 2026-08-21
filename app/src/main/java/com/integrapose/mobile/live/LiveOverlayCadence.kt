package com.integrapose.mobile.live

/** Fractional frame-rate gate that stays accurate on jittery camera timestamps. */
internal class LiveOverlayCadence {
    private var lastTimestampUs = Long.MIN_VALUE
    private var accumulatedUnits = 0L

    fun shouldPublish(timestampUs: Long, maximumFps: Int?): Boolean {
        if (maximumFps == null) return true
        require(maximumFps > 0)
        if (lastTimestampUs == Long.MIN_VALUE || timestampUs < lastTimestampUs) {
            lastTimestampUs = timestampUs
            accumulatedUnits = 0L
            return true
        }

        val elapsedUs = (timestampUs - lastTimestampUs).coerceAtMost(1_000_000L)
        lastTimestampUs = timestampUs
        accumulatedUnits = (accumulatedUnits + elapsedUs * maximumFps)
            .coerceAtMost(2_000_000L)
        if (accumulatedUnits < PUBLICATION_THRESHOLD_UNITS) return false
        accumulatedUnits = (accumulatedUnits - ONE_FRAME_UNITS).coerceAtLeast(0L)
        return true
    }

    private companion object {
        const val ONE_FRAME_UNITS = 1_000_000L
        // Camera timestamps often round 33,333.333 us to 33,333 us. This tolerance
        // avoids turning a requested 15 Hz cadence into 10 Hz through rounding alone.
        const val PUBLICATION_THRESHOLD_UNITS = 990_000L
    }
}
