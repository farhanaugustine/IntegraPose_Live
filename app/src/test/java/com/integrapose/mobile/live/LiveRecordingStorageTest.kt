package com.integrapose.mobile.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecordingStorageTest {
    @Test
    fun `manual recording keeps an emergency start reserve`() {
        val estimate = LiveRecordingStorage.estimatedRequiredBytes(
            LiveRecordingOptions(plannedDurationMinutes = 0)
        )

        assertEquals(LiveRecordingStorage.START_RESERVE_BYTES, estimate)
    }

    @Test
    fun `planned duration budgets every selected output stream`() {
        val minutes = 10
        val options = LiveRecordingOptions(
            plannedDurationMinutes = minutes,
            rawVideo = true,
            annotatedVideo = true,
            detectionCsv = true
        )
        val expectedBitsPerSecond =
            LiveRecordingStorage.RAW_VIDEO_BITS_PER_SECOND +
                LiveRecordingStorage.ANNOTATED_VIDEO_BITS_PER_SECOND +
                LiveRecordingStorage.DATA_BITS_PER_SECOND
        val expected = LiveRecordingStorage.START_RESERVE_BYTES +
            minutes * 60L * ((expectedBitsPerSecond + 7L) / 8L)

        assertEquals(expected, LiveRecordingStorage.estimatedRequiredBytes(options))
        assertTrue(expected > LiveRecordingStorage.START_RESERVE_BYTES)
    }

    @Test
    fun `duration is capped at one day`() {
        val tooLong = LiveRecordingOptions(
            plannedDurationMinutes = Int.MAX_VALUE,
            rawVideo = false,
            annotatedVideo = true,
            detectionCsv = false
        )
        val capped = tooLong.copy(
            plannedDurationMinutes = LiveRecordingStorage.MAX_PLANNED_MINUTES
        )

        assertEquals(
            LiveRecordingStorage.estimatedRequiredBytes(capped),
            LiveRecordingStorage.estimatedRequiredBytes(tooLong)
        )
    }
}
