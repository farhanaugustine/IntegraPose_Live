package com.integrapose.mobile.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class NcnnConfigurationLabelTest {
    @Test
    fun usesSingularUnitsForOneWorkerAndThread() {
        assertEquals(
            "1 worker x 1 thread",
            formatNcnnCpuConfiguration(workers = 1, threadsPerWorker = 1)
        )
    }

    @Test
    fun usesPluralUnitsForParallelConfiguration() {
        assertEquals(
            "4 workers x 2 threads",
            formatNcnnCpuConfiguration(workers = 4, threadsPerWorker = 2)
        )
    }

    @Test
    fun singleWorkerCpuIsAlwaysEligible() {
        assertEquals(
            true,
            isNcnnConfigurationEligible(
                usesVulkan = false,
                workers = 1,
                threadsPerWorker = 3,
                cpuWorkerParityPassed = false,
                validatedCpuWorkers = 1,
                validatedCpuThreads = 3,
                vulkanParityPassed = false
            )
        )
    }

    @Test
    fun onlyValidatedParallelCpuConfigurationIsEligible() {
        assertEquals(
            true,
            isNcnnConfigurationEligible(
                usesVulkan = false,
                workers = 4,
                threadsPerWorker = 1,
                cpuWorkerParityPassed = true,
                validatedCpuWorkers = 4,
                validatedCpuThreads = 1,
                vulkanParityPassed = false
            )
        )
        assertEquals(
            false,
            isNcnnConfigurationEligible(
                usesVulkan = false,
                workers = 3,
                threadsPerWorker = 1,
                cpuWorkerParityPassed = true,
                validatedCpuWorkers = 4,
                validatedCpuThreads = 1,
                vulkanParityPassed = false
            )
        )
    }

    @Test
    fun VulkanRequiresItsOwnParityGate() {
        assertEquals(
            false,
            isNcnnConfigurationEligible(
                usesVulkan = true,
                workers = 1,
                threadsPerWorker = 1,
                cpuWorkerParityPassed = true,
                validatedCpuWorkers = 4,
                validatedCpuThreads = 1,
                vulkanParityPassed = false
            )
        )
    }

    @Test
    fun keepsParallelOfflineAndSingleWorkerStreamingProfilesSeparate() {
        val profile = NcnnExecutionProfile(
            modelId = "model",
            threadsPerWorker = 1,
            workers = 4,
            backend = NativeNcnnBackend.CPU,
            measuredPipelineFps = 36.0,
            benchmarked = true,
            streamingThreads = 3,
            streamingBackend = NativeNcnnBackend.CPU,
            measuredStreamingPipelineFps = 29.0
        )

        assertEquals("NCNN CPU, 4 workers x 1 thread", profile.configurationLabel)
        assertEquals(
            "NCNN CPU, 1 worker x 3 threads",
            profile.streamingConfigurationLabel
        )
        assertEquals(3, profile.toStreamingRuntimeTuning().threads)
        assertEquals(false, profile.toStreamingRuntimeTuning().useVulkan)
    }

    @Test
    fun streamingProfileCanSelectValidatedVulkanIndependently() {
        val profile = NcnnExecutionProfile(
            modelId = "model",
            threadsPerWorker = 1,
            workers = 4,
            backend = NativeNcnnBackend.CPU,
            measuredPipelineFps = 36.0,
            benchmarked = true,
            streamingThreads = 1,
            streamingBackend = NativeNcnnBackend.VULKAN,
            measuredStreamingPipelineFps = 40.0
        )

        assertEquals("NCNN Vulkan", profile.streamingConfigurationLabel)
        assertEquals(true, profile.toStreamingRuntimeTuning().useVulkan)
    }
}
