package com.integrapose.mobile.live

import com.integrapose.mobile.inference.FrameInferenceResult
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationTimelineCursorTest {
    @Test
    fun reusesLatestInferenceBetweenSamples() {
        val first = result(100L)
        val second = result(200L)
        val third = result(300L)
        val cursor = AnnotationTimelineCursor(
            listOf(
                TimedAnnotation(0L, first),
                TimedAnnotation(80_000L, second),
                TimedAnnotation(160_000L, third)
            )
        )

        assertEquals(first, cursor.resultAt(0L))
        assertEquals(first, cursor.resultAt(79_999L))
        assertEquals(second, cursor.resultAt(80_000L))
        assertEquals(second, cursor.resultAt(159_999L))
        assertEquals(third, cursor.resultAt(200_000L))
    }

    private fun result(timestampMs: Long) = FrameInferenceResult(
        timestampMs = timestampMs,
        imageWidth = 100,
        imageHeight = 50,
        detections = emptyList(),
        inferenceMs = 5L
    )
}
