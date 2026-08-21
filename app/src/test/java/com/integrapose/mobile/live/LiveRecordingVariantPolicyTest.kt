package com.integrapose.mobile.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecordingVariantPolicyTest {
    @Test
    fun existingPathDoesNotRequireRawForAnnotatedOnly() {
        val options = LiveRecordingOptions(rawVideo = false, annotatedVideo = true)

        assertFalse(options.requiresRawMaster(postprocessAnnotatedVideo = false))
    }

    @Test
    fun postprocessPathRequiresRawForAnnotatedOnly() {
        val options = LiveRecordingOptions(rawVideo = false, annotatedVideo = true)

        assertTrue(options.requiresRawMaster(postprocessAnnotatedVideo = true))
    }

    @Test
    fun explicitRawAlwaysRequiresRawCapture() {
        val options = LiveRecordingOptions(rawVideo = true, annotatedVideo = false)

        assertTrue(options.requiresRawMaster(postprocessAnnotatedVideo = false))
        assertTrue(options.requiresRawMaster(postprocessAnnotatedVideo = true))
    }

    @Test
    fun concurrentMetricsAreIsolatedToPostprocessVariant() {
        assertFalse(shouldFinalizeMetricsConcurrently(postprocessAnnotatedVideo = false))
        assertTrue(shouldFinalizeMetricsConcurrently(postprocessAnnotatedVideo = true))
    }

    @Test
    fun hardwarePipelineDisablesAfterTwoFailuresWithoutSuccess() {
        assertTrue(shouldTryHardwarePipeline(consecutiveFailures = 0))
        assertTrue(shouldTryHardwarePipeline(consecutiveFailures = 1))
        assertFalse(shouldTryHardwarePipeline(consecutiveFailures = 2))
        assertFalse(shouldTryHardwarePipeline(consecutiveFailures = 4))
    }
}
