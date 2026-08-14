package com.integrapose.mobile.branding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandAnimationTimelineTest {
    @Test
    fun durationIsLongEnoughToRead() {
        assertTrue(BRAND_ANIMATION_DURATION_MS >= 3_000L)
    }


    @Test
    fun startsBlankAndFinishesFullyResolved() {
        val start = BrandAnimationTimeline.frameAt(0f)
        val end = BrandAnimationTimeline.frameAt(1f)

        assertEquals(0f, start.phoneProgress, 0.0001f)
        assertTrue(start.cornerProgress.all { it == 0f })
        assertEquals(0f, start.mouseAlpha, 0.0001f)
        assertEquals(0f, start.dotProgress, 0.0001f)
        assertEquals(0f, start.wordmarkProgress, 0.0001f)
        assertEquals(0f, start.taglineProgress, 0.0001f)

        assertEquals(1f, end.phoneProgress, 0.0001f)
        assertTrue(end.cornerProgress.all { it == 1f })
        assertEquals(1f, end.mouseAlpha, 0.0001f)
        assertEquals(1f, end.dotProgress, 0.0001f)
        assertEquals(1f, end.wordmarkProgress, 0.0001f)
        assertEquals(1f, end.taglineProgress, 0.0001f)
    }

    @Test
    fun acquisitionPrecedesTextAndTaglineFollowsWordmark() {
        val acquisition = BrandAnimationTimeline.frameAt(0.42f)
        val textReveal = BrandAnimationTimeline.frameAt(0.78f)

        assertTrue(acquisition.phoneProgress > 0.9f)
        assertTrue(acquisition.mouseAlpha > 0f)
        assertEquals(0f, acquisition.wordmarkProgress, 0.0001f)
        assertTrue(textReveal.wordmarkProgress > textReveal.taglineProgress)
    }

    @Test
    fun recordingDotHasOneControlledPulse() {
        val before = BrandAnimationTimeline.frameAt(0.50f)
        val pulse = BrandAnimationTimeline.frameAt(0.76f)
        val end = BrandAnimationTimeline.frameAt(1f)

        assertEquals(0f, before.dotProgress, 0.0001f)
        assertTrue(pulse.dotProgress > 0.95f)
        assertTrue(pulse.dotScale > 1f)
        assertEquals(1f, end.dotScale, 0.0001f)
    }
}
