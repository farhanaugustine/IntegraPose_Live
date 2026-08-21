package com.integrapose.mobile.live

import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCameraBenchmarkTest {
    @Test
    fun cpuSearchIncludesBoundedSingleAndTwoWorkerConfigurations() {
        val candidates = liveCameraBenchmarkCandidates(
            cpuCores = 8,
            currentTuning = NcnnRuntimeTuning(threads = 3, useVulkan = false),
            currentWorkers = 1
        )

        assertTrue(candidates.any { it.workers == 1 })
        assertTrue(candidates.any { it.workers == 2 })
        assertTrue(candidates.all { it.workers in 1..2 })
        assertTrue(candidates.all { it.workers * it.threadsPerWorker <= 8 })
        assertTrue(candidates.size <= 6)
    }

    @Test
    fun rendererSweepCrossesEveryInferenceCandidateWithBothModes() {
        val candidates = liveCameraBenchmarkCandidates(
            cpuCores = 8,
            currentTuning = NcnnRuntimeTuning(threads = 2, useVulkan = false),
            currentWorkers = 2,
            previewRenderers = LivePreviewRenderer.entries
        )

        val runtimeConfigurations = candidates.map {
            Triple(it.workers, it.threadsPerWorker, it.useVulkan)
        }.toSet()
        runtimeConfigurations.forEach { runtime ->
            assertEquals(
                LivePreviewRenderer.entries.toSet(),
                candidates.filter {
                    Triple(it.workers, it.threadsPerWorker, it.useVulkan) == runtime
                }.map { it.previewRenderer }.toSet()
            )
        }
    }

    @Test
    fun selectedVulkanIsComparedWithCpuWithoutCompetingGpuWorkers() {
        val candidates = liveCameraBenchmarkCandidates(
            cpuCores = 8,
            currentTuning = NcnnRuntimeTuning(threads = 4, useVulkan = true),
            currentWorkers = 2
        )

        assertTrue(candidates.any { it.useVulkan })
        assertTrue(candidates.any { !it.useVulkan })
        assertTrue(candidates.filter { it.useVulkan }.all { it.workers == 1 })
    }

    @Test
    fun recordingPhaseIsAuthoritativeForRecommendation() {
        val previewWinner = sample(workers = 1, threads = 4, fpsFrames = 40)
        val recordingWinner = sample(workers = 2, threads = 1, fpsFrames = 28)
        val recordingLoser = sample(workers = 1, threads = 4, fpsFrames = 20)

        val result = LiveCameraBenchmarkResult(
            previewSamples = listOf(previewWinner),
            recordingSamples = listOf(recordingLoser, recordingWinner),
            recordingProbe = LiveCameraRecordingProbe(frames = 300, durationMs = 10_000)
        )

        assertEquals(previewWinner.configuration, result.bestPreview.configuration)
        assertEquals(recordingWinner.configuration, result.recommended.configuration)
    }

    @Test
    fun collectorSeparatesCompletedFromUsableOrderedUpdates() {
        val collector = LiveCameraBenchmarkCollector()
        val configuration = LiveCameraBenchmarkConfiguration(1, 2, false)
        val token = collector.begin(configuration)
        assertEquals(token, collector.onCameraFrame())
        collector.onAccepted(token)
        collector.onCompleted(
            token = token,
            result = inferenceResult(),
            pipelineMs = 20L,
            published = false
        )
        collector.stopAccepting(token)
        val sample = collector.finish(token)

        assertEquals(1, sample.completedFrames)
        assertEquals(0, sample.publishedFrames)
        assertFalse(sample.publishedFps > 0.0)
    }

    @Test
    fun collectorReportsLiveOnlyStageTimingsAndBulkConversion() {
        val collector = LiveCameraBenchmarkCollector()
        val token = collector.begin(LiveCameraBenchmarkConfiguration(1, 2, false))
        collector.onCameraFrame()
        collector.onAccepted(token, analysisWidth = 1280, analysisHeight = 720)
        collector.onCompleted(
            token = token,
            result = inferenceResult(),
            pipelineMs = 24L,
            published = true,
            overlayPublished = false,
            usedBulkRgbaCopy = true,
            conversionNs = 4_000_000L,
            trackingWriteNs = 2_000_000L,
            uiPublishNs = 0L
        )
        collector.stopAccepting(token)
        val sample = collector.finish(token)

        assertEquals(1280, sample.analysisWidth)
        assertEquals(720, sample.analysisHeight)
        assertEquals(1, sample.bulkRgbaFrames)
        assertEquals(0, sample.overlayPublishedFrames)
        assertEquals(4.0, sample.medianConversionMs, 0.001)
        assertEquals(2.0, sample.medianTrackingWriteMs, 0.001)
        assertEquals(0.0, sample.medianUiPublishMs, 0.001)
        assertEquals("model pipeline", sample.bottleneckStage)
    }

    @Test
    fun collectorStopsAtThirtyCompletedFrames() {
        val collector = LiveCameraBenchmarkCollector()
        val token = collector.begin(
            LiveCameraBenchmarkConfiguration(1, 2, false),
            targetCompletedFrames = LIVE_BENCHMARK_FRAMES_PER_COMBINATION
        )
        repeat(35) {
            collector.onCompleted(
                token = token,
                result = inferenceResult(),
                pipelineMs = 20L,
                published = true
            )
        }

        assertTrue(collector.isComplete(token))
        val sample = collector.finish(token)
        assertEquals(30, sample.completedFrames)
    }

    private fun sample(
        workers: Int,
        threads: Int,
        fpsFrames: Int
    ) = LiveCameraBenchmarkSample(
        configuration = LiveCameraBenchmarkConfiguration(workers, threads, false),
        durationMs = 1_000L,
        cameraCallbacks = 30,
        acceptedFrames = fpsFrames,
        completedFrames = fpsFrames,
        publishedFrames = fpsFrames,
        busyDrops = 0,
        medianPipelineMs = 20L,
        p95PipelineMs = 25L,
        medianModelPipelineMs = 18L,
        medianPreprocessingMs = 3L
    )

    private fun inferenceResult() = FrameInferenceResult(
        timestampMs = 1L,
        imageWidth = 640,
        imageHeight = 640,
        detections = emptyList(),
        inferenceMs = 15L,
        preprocessingMs = 3L
    )
}
