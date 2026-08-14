package com.integrapose.mobile.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxTransformTest {
    @Test
    fun mapsLandscapeModelCoordinatesBackToSourcePixels() {
        val transform = LetterboxTransform.calculate(
            sourceWidth = 1280,
            sourceHeight = 720,
            modelWidth = 640,
            modelHeight = 640
        )

        assertEquals(0.5f, transform.scale, 0.0001f)
        assertEquals(0f, transform.padX, 0.0001f)
        assertEquals(140f, transform.padY, 0.0001f)
        assertEquals(640f, transform.modelToSourceX(320f), 0.001f)
        assertEquals(360f, transform.modelToSourceY(320f), 0.001f)
    }
}
