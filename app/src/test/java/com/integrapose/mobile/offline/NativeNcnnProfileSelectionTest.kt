package com.integrapose.mobile.offline

import com.integrapose.mobile.benchmark.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeNcnnProfileSelectionTest {
    @Test
    fun failedVulkanParityKeepsTimingButCannotWinRecommendation() {
        val benchmark = benchmark(vulkanParityPassed = false)

        assertEquals(NativeNcnnBackend.CPU, benchmark.recommendedBackend)
        assertFalse(benchmark.isEligible(requireNotNull(benchmark.vulkanSample)))

        val manualGpu = requireNotNull(
            benchmark.executionProfileFor(
                modelId = "model",
                selection = NcnnProfileSelection.MANUAL_VULKAN
            )
        )
        assertEquals(NativeNcnnBackend.VULKAN, manualGpu.backend)
        assertEquals(NcnnProfileSelection.MANUAL_VULKAN, manualGpu.selection)
        assertEquals(
            "manual Vulkan selection after benchmark; CPU/GPU parity failed",
            manualGpu.auditLabelFor(NativeNcnnBackend.VULKAN)
        )
        assertEquals(
            manualGpu.auditLabelFor(NativeNcnnBackend.VULKAN),
            manualGpu.toStreamingRuntimeTuning().source
        )
    }

    @Test
    fun passedFasterVulkanCanWinRecommendation() {
        val benchmark = benchmark(vulkanParityPassed = true)

        assertEquals(NativeNcnnBackend.VULKAN, benchmark.recommendedBackend)
        val automatic = requireNotNull(
            benchmark.executionProfileFor(
                modelId = "model",
                selection = NcnnProfileSelection.AUTOMATIC
            )
        )
        assertEquals(NativeNcnnBackend.VULKAN, automatic.backend)
        assertEquals(NcnnProfileSelection.AUTOMATIC, automatic.selection)
        assertEquals(
            "automatic benchmark selection; CPU/GPU parity passed",
            automatic.auditLabelFor(NativeNcnnBackend.VULKAN)
        )
    }

    @Test
    fun manualCpuAlwaysUsesBestEligibleCpuProfiles() {
        val benchmark = benchmark(vulkanParityPassed = false)
        val manualCpu = benchmark.executionProfileFor(
            modelId = "model",
            selection = NcnnProfileSelection.MANUAL_CPU
        )

        assertNotNull(manualCpu)
        assertEquals(NativeNcnnBackend.CPU, manualCpu?.backend)
        assertEquals(NativeNcnnBackend.CPU, manualCpu?.streamingBackend)
        assertEquals(
            "manual CPU selection after benchmark",
            manualCpu?.auditLabelFor(NativeNcnnBackend.CPU)
        )
    }

    private fun benchmark(
        vulkanParityPassed: Boolean
    ): NativeNcnnAutoBenchmark {
        val cpu = sample(
            usesVulkan = false,
            pipelineFps = 25.0,
            inferenceFps = 28.0
        )
        val gpu = sample(
            usesVulkan = true,
            pipelineFps = 40.0,
            inferenceFps = 45.0
        )
        return NativeNcnnAutoBenchmark(
            deviceBefore = deviceProfile(),
            deviceAfter = deviceProfile(),
            framesPerTrial = 30,
            samples = listOf(cpu, gpu),
            cpuWorkerParity = null,
            cpuWorkerFailure = null,
            validatedCpuWorkers = 1,
            validatedCpuThreads = 3,
            vulkanParity = parity(vulkanParityPassed),
            vulkanFailure = null
        )
    }

    private fun sample(
        usesVulkan: Boolean,
        pipelineFps: Double,
        inferenceFps: Double
    ): NativeNcnnPipelineBenchmark = NativeNcnnPipelineBenchmark(
        framesProcessed = 30,
        framesRequested = 30,
        threads = 3,
        workers = 1,
        sourceWidth = 1280,
        sourceHeight = 720,
        inputSize = 640,
        totalDetections = 30,
        framesWithDetections = 30,
        framesEncoded = 0,
        wallTimeMs = 1_000,
        decoderTimeMs = 50,
        preprocessingTimeMs = 100,
        inferenceTimeMs = 700,
        outputTimeMs = 10,
        postprocessingTimeMs = 50,
        annotationTimeMs = 0,
        encodingTimeMs = 0,
        pipelineFps = pipelineFps,
        inferenceFps = inferenceFps,
        usesVulkan = usesVulkan,
        backend = if (usesVulkan) "NCNN Vulkan" else "NCNN CPU",
        eosReached = false
    )

    private fun parity(passed: Boolean): NativeNcnnParityResult =
        NativeNcnnParityResult(
            cpuFrames = 30,
            vulkanFrames = 30,
            framesCompared = 30,
            firstFrameIndex = 0,
            lastFrameIndex = 29,
            detectionsCompared = 30,
            frameMismatches = 0,
            timestampMismatches = 0,
            layoutMismatches = 0,
            detectionCountMismatchFrames = if (passed) 0 else 1,
            unmatchedDetections = if (passed) 0 else 1,
            keypointLayoutMismatches = 0,
            maxConfidenceDelta = if (passed) 0.01f else 0.03f,
            maxBoxDeltaPx = 1f,
            maxKeypointDeltaPx = 1f,
            maxKeypointConfidenceDelta = 0.01f,
            confidenceTolerance = 0.02f,
            keypointConfidenceTolerance = 0.05f,
            boxTolerancePx = 2f,
            keypointTolerancePx = 2f,
            passed = passed
        )

    private fun deviceProfile(): DeviceProfile = DeviceProfile(
        deviceName = "Test device",
        androidVersion = "Test",
        soc = null,
        abis = listOf("arm64-v8a"),
        cpuCores = 8,
        totalMemoryMb = 8_000,
        availableMemoryMb = 4_000,
        appMemoryClassMb = 512,
        processPssMb = 100,
        nativeHeapMb = 50,
        lowRamDevice = false,
        pageSizeBytes = 4_096,
        thermalStatus = 0,
        systemReportsVulkan = true,
        ncnnVulkanDeviceCount = 1
    )
}
