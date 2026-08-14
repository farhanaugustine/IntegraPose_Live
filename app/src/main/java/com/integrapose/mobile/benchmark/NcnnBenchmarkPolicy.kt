package com.integrapose.mobile.benchmark

const val DEFAULT_NCNN_AUTO_BENCHMARK_FRAMES = 60

internal data class NcnnCpuConfiguration(
    val workers: Int,
    val threadsPerWorker: Int
) {
    init {
        require(workers >= 1)
        require(threadsPerWorker >= 1)
    }

    val totalInferenceThreads: Int
        get() = workers * threadsPerWorker
}

/**
 * Tests every practical mobile CPU thread count up to four. Video decode,
 * Compose, and Android services still need CPU time, so larger OpenMP pools
 * are intentionally left for an explicit advanced benchmark.
 */
internal fun suggestedNcnnThreadCounts(cpuCores: Int): List<Int> {
    val maximum = cpuCores.coerceIn(1, 4)
    val pipelineFriendly = (maximum - 1).coerceAtLeast(1)
    return listOf(pipelineFriendly, maximum, 2, 1)
        .filter { it <= maximum }
        .distinct()
}

/**
 * Keeps the established single-worker trials, adds two balanced concurrent
 * extractor trials, and probes up to four single-thread frame workers. The
 * latter uses no more inference threads than reported CPU cores and bounds the
 * activation-memory multiplier at four for the 4 GB target.
 */
internal fun suggestedNcnnCpuConfigurations(
    cpuCores: Int,
    exhaustive: Boolean = true
): List<NcnnCpuConfiguration> {
    val availableCores = cpuCores.coerceAtLeast(1)
    val singleWorker = suggestedNcnnThreadCounts(availableCores).map {
        NcnnCpuConfiguration(workers = 1, threadsPerWorker = it)
    }
    if (availableCores < 2) return singleWorker

    if (!exhaustive) {
        val maximumThreads = availableCores.coerceIn(1, 4)
        val pipelineFriendly = (maximumThreads - 1).coerceAtLeast(1)
        val streamingTrials = listOf(pipelineFriendly, maximumThreads)
            .distinct()
            .map {
                NcnnCpuConfiguration(workers = 1, threadsPerWorker = it)
            }
        val offlineWorkers = availableCores.coerceIn(2, 4)
        return (
            streamingTrials +
                NcnnCpuConfiguration(
                    workers = offlineWorkers,
                    threadsPerWorker = 1
                )
            ).distinct()
    }

    val balancedThreads = (availableCores / 2).coerceIn(1, 4)
    val twoWorker = listOf(balancedThreads, 1)
        .distinct()
        .map {
            NcnnCpuConfiguration(workers = 2, threadsPerWorker = it)
        }
        .filter { it.totalInferenceThreads <= availableCores }
    val frameParallel = (3..availableCores.coerceAtMost(4)).map {
        NcnnCpuConfiguration(workers = it, threadsPerWorker = 1)
    }
    return (singleWorker + twoWorker + frameParallel).distinct()
}
