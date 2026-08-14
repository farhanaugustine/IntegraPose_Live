package com.integrapose.mobile.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnBenchmarkPolicyTest {
    @Test
    fun singleCoreDeviceOnlyTestsOneThread() {
        assertEquals(listOf(1), suggestedNcnnThreadCounts(cpuCores = 1))
    }

    @Test
    fun twoCoreDeviceTestsBothPracticalCountsOnce() {
        assertEquals(listOf(1, 2), suggestedNcnnThreadCounts(cpuCores = 2))
    }

    @Test
    fun fourOrMoreCoresTestsOneThroughFourWithPipelineFriendlyFirst() {
        assertEquals(listOf(3, 4, 2, 1), suggestedNcnnThreadCounts(cpuCores = 4))
        assertEquals(listOf(3, 4, 2, 1), suggestedNcnnThreadCounts(cpuCores = 12))
    }

    @Test
    fun singleCoreHasNoConcurrentConfiguration() {
        assertEquals(
            listOf(NcnnCpuConfiguration(workers = 1, threadsPerWorker = 1)),
            suggestedNcnnCpuConfigurations(cpuCores = 1)
        )
    }

    @Test
    fun fourCoreDeviceAddsBoundedFrameParallelTrials() {
        val configurations = suggestedNcnnCpuConfigurations(cpuCores = 4)

        assertTrue(
            configurations.contains(
                NcnnCpuConfiguration(workers = 2, threadsPerWorker = 2)
            )
        )
        assertTrue(
            configurations.contains(
                NcnnCpuConfiguration(workers = 2, threadsPerWorker = 1)
            )
        )
        assertTrue(
            configurations.contains(
                NcnnCpuConfiguration(workers = 3, threadsPerWorker = 1)
            )
        )
        assertTrue(
            configurations.contains(
                NcnnCpuConfiguration(workers = 4, threadsPerWorker = 1)
            )
        )
        assertTrue(configurations.all { it.workers <= 4 })
        assertTrue(configurations.all { it.totalInferenceThreads <= 4 })
    }

    @Test
    fun largeDeviceStillCapsWorkersAndThreadsPerWorker() {
        val configurations = suggestedNcnnCpuConfigurations(cpuCores = 12)

        assertTrue(configurations.all { it.workers <= 4 })
        assertTrue(configurations.all { it.threadsPerWorker <= 4 })
        assertTrue(
            configurations.contains(
                NcnnCpuConfiguration(workers = 2, threadsPerWorker = 4)
            )
        )
    }

    @Test
    fun productionSearchUsesOnlyActionableStreamingAndOfflineTrials() {
        assertEquals(
            listOf(
                NcnnCpuConfiguration(workers = 1, threadsPerWorker = 3),
                NcnnCpuConfiguration(workers = 1, threadsPerWorker = 4),
                NcnnCpuConfiguration(workers = 4, threadsPerWorker = 1)
            ),
            suggestedNcnnCpuConfigurations(
                cpuCores = 8,
                exhaustive = false
            )
        )
    }
}
