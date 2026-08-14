package com.integrapose.mobile.offline

import android.content.Context
import android.net.Uri
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.benchmark.DEFAULT_NCNN_AUTO_BENCHMARK_FRAMES
import com.integrapose.mobile.benchmark.DeviceProfile
import com.integrapose.mobile.benchmark.suggestedNcnnCpuConfigurations
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.inference.RoiAnnotationPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class NativeVideoInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationUs: Long,
    val declaredFrameRate: Double,
    val mimeType: String
)

data class NativeDecodeBenchmark(
    val framesDecoded: Int,
    val framesRequested: Int,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val sourceDurationUs: Long,
    val wallTimeMs: Long,
    val decodeFps: Double,
    val mimeType: String,
    val decoderName: String,
    val eosReached: Boolean
)

enum class NativeNcnnBackend(val displayName: String) {
    CPU("NCNN CPU"),
    VULKAN("NCNN Vulkan")
}

enum class NcnnProfileSelection {
    SAFE_DEFAULT,
    AUTOMATIC,
    MANUAL_CPU,
    MANUAL_VULKAN
}

data class NcnnExecutionProfile(
    val modelId: String,
    val threadsPerWorker: Int,
    val workers: Int,
    val backend: NativeNcnnBackend,
    val measuredPipelineFps: Double,
    val benchmarked: Boolean,
    val streamingThreads: Int = threadsPerWorker,
    val streamingBackend: NativeNcnnBackend = backend,
    val measuredStreamingPipelineFps: Double = measuredPipelineFps,
    val selection: NcnnProfileSelection = if (benchmarked) {
        NcnnProfileSelection.AUTOMATIC
    } else {
        NcnnProfileSelection.SAFE_DEFAULT
    },
    val vulkanParityPassed: Boolean? = null
) {
    init {
        require(threadsPerWorker >= 1)
        require(workers >= 1)
        require(streamingThreads >= 1)
    }

    val configurationLabel: String
        get() = if (backend == NativeNcnnBackend.VULKAN) {
            backend.displayName
        } else {
            "${backend.displayName}, " +
                formatNcnnCpuConfiguration(workers, threadsPerWorker)
        }

    val streamingConfigurationLabel: String
        get() = if (streamingBackend == NativeNcnnBackend.VULKAN) {
            streamingBackend.displayName
        } else {
            "${streamingBackend.displayName}, 1 worker x " +
                "$streamingThreads " +
                if (streamingThreads == 1) "thread" else "threads"
        }

    fun auditLabelFor(runtimeBackend: NativeNcnnBackend): String {
        val selectionLabel = when (selection) {
            NcnnProfileSelection.SAFE_DEFAULT -> "safe CPU default"
            NcnnProfileSelection.AUTOMATIC -> "automatic benchmark selection"
            NcnnProfileSelection.MANUAL_CPU -> "manual CPU selection after benchmark"
            NcnnProfileSelection.MANUAL_VULKAN ->
                "manual Vulkan selection after benchmark"
        }
        if (runtimeBackend != NativeNcnnBackend.VULKAN) {
            return selectionLabel
        }
        val parityLabel = when (vulkanParityPassed) {
            true -> "CPU/GPU parity passed"
            false -> "CPU/GPU parity failed"
            null -> "CPU/GPU parity unavailable"
        }
        return "$selectionLabel; $parityLabel"
    }

    fun toStreamingRuntimeTuning(): NcnnRuntimeTuning = NcnnRuntimeTuning(
        threads = streamingThreads,
        useVulkan = streamingBackend == NativeNcnnBackend.VULKAN,
        source = auditLabelFor(streamingBackend)
    )

    companion object {
        fun safeDefault(modelId: String, cpuCores: Int): NcnnExecutionProfile =
            NcnnExecutionProfile(
                modelId = modelId,
                threadsPerWorker = (cpuCores.coerceIn(1, 4) - 1).coerceAtLeast(1),
                workers = 1,
                backend = NativeNcnnBackend.CPU,
                measuredPipelineFps = 0.0,
                benchmarked = false,
                selection = NcnnProfileSelection.SAFE_DEFAULT
            )
    }
}

internal fun formatNcnnCpuConfiguration(
    workers: Int,
    threadsPerWorker: Int
): String {
    val workerUnit = if (workers == 1) "worker" else "workers"
    val threadUnit = if (threadsPerWorker == 1) "thread" else "threads"
    return "$workers $workerUnit x $threadsPerWorker $threadUnit"
}

internal fun isNcnnConfigurationEligible(
    usesVulkan: Boolean,
    workers: Int,
    threadsPerWorker: Int,
    cpuWorkerParityPassed: Boolean,
    validatedCpuWorkers: Int,
    validatedCpuThreads: Int,
    vulkanParityPassed: Boolean
): Boolean = when {
    usesVulkan -> vulkanParityPassed
    workers == 1 -> true
    else -> cpuWorkerParityPassed &&
        workers == validatedCpuWorkers &&
        threadsPerWorker == validatedCpuThreads
}

data class NativeNcnnPipelineBenchmark(
    val framesProcessed: Int,
    val framesRequested: Int,
    val threads: Int,
    val workers: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputSize: Int,
    val totalDetections: Int,
    val framesWithDetections: Int,
    val framesEncoded: Int,
    val wallTimeMs: Long,
    val decoderTimeMs: Long,
    val preprocessingTimeMs: Long,
    val inferenceTimeMs: Long,
    val outputTimeMs: Long,
    val postprocessingTimeMs: Long,
    val annotationTimeMs: Long,
    val encodingTimeMs: Long,
    val pipelineFps: Double,
    val inferenceFps: Double,
    val usesVulkan: Boolean,
    val backend: String,
    val eosReached: Boolean
) {
    val totalInferenceThreads: Int
        get() = threads * workers

    val cpuConfigurationLabel: String
        get() = formatNcnnCpuConfiguration(workers, threads)
}

data class NativeNcnnAutoBenchmark(
    val deviceBefore: DeviceProfile,
    val deviceAfter: DeviceProfile,
    val framesPerTrial: Int,
    val samples: List<NativeNcnnPipelineBenchmark>,
    val cpuWorkerParity: NativeNcnnParityResult?,
    val cpuWorkerFailure: String?,
    val validatedCpuWorkers: Int,
    val validatedCpuThreads: Int,
    val vulkanParity: NativeNcnnParityResult?,
    val vulkanFailure: String?,
    val cpuTrialFailures: List<String> = emptyList()
) {
    init {
        require(samples.isNotEmpty()) {
            "At least one NCNN benchmark sample is required."
        }
        require(validatedCpuWorkers >= 1)
        require(validatedCpuThreads >= 1)
    }

    val recommended: NativeNcnnPipelineBenchmark
        get() = eligibleSamples.maxWithOrNull(
            compareBy<NativeNcnnPipelineBenchmark> { it.pipelineFps }
                .thenBy { it.inferenceFps }
                .thenBy { -it.workers }
                .thenBy { -it.threads }
        ) ?: samples.first()

    val recommendedBackend: NativeNcnnBackend
        get() = if (recommended.usesVulkan) {
            NativeNcnnBackend.VULKAN
        } else {
            NativeNcnnBackend.CPU
        }

    val recommendedStreaming: NativeNcnnPipelineBenchmark
        get() = eligibleSamples
            .filter { it.workers == 1 }
            .maxWithOrNull(
                compareBy<NativeNcnnPipelineBenchmark> { it.pipelineFps }
                    .thenBy { it.inferenceFps }
                    .thenBy { -it.threads }
            ) ?: recommended

    val cpuSamples: List<NativeNcnnPipelineBenchmark>
        get() = samples.filterNot { it.usesVulkan }

    val recommendedCpu: NativeNcnnPipelineBenchmark
        get() = cpuSamples
            .filter(::isEligible)
            .maxWithOrNull(
                compareBy<NativeNcnnPipelineBenchmark> { it.pipelineFps }
                    .thenBy { it.inferenceFps }
                    .thenBy { -it.workers }
                    .thenBy { -it.threads }
            ) ?: cpuSamples.first()

    val recommendedStreamingCpu: NativeNcnnPipelineBenchmark
        get() = cpuSamples
            .filter { it.workers == 1 }
            .maxWithOrNull(
                compareBy<NativeNcnnPipelineBenchmark> { it.pipelineFps }
                    .thenBy { it.inferenceFps }
                    .thenBy { -it.threads }
            ) ?: recommendedCpu

    val vulkanSample: NativeNcnnPipelineBenchmark?
        get() = samples.firstOrNull { it.usesVulkan }

    val targetMet: Boolean
        get() = recommended.pipelineFps >= 30.0

    val totalFramesMeasured: Int
        get() = samples.sumOf { it.framesProcessed } +
            (cpuWorkerParity?.cpuFrames ?: 0) +
            (cpuWorkerParity?.vulkanFrames ?: 0) +
            (vulkanParity?.cpuFrames ?: 0) +
            (vulkanParity?.vulkanFrames ?: 0)

    fun isEligible(sample: NativeNcnnPipelineBenchmark): Boolean =
        isNcnnConfigurationEligible(
            usesVulkan = sample.usesVulkan,
            workers = sample.workers,
            threadsPerWorker = sample.threads,
            cpuWorkerParityPassed = cpuWorkerParity?.passed == true,
            validatedCpuWorkers = validatedCpuWorkers,
            validatedCpuThreads = validatedCpuThreads,
            vulkanParityPassed = vulkanParity?.passed == true
        )

    fun executionProfileFor(
        modelId: String,
        selection: NcnnProfileSelection
    ): NcnnExecutionProfile? {
        val offlineSample: NativeNcnnPipelineBenchmark
        val streamingSample: NativeNcnnPipelineBenchmark
        when (selection) {
            NcnnProfileSelection.SAFE_DEFAULT -> return null
            NcnnProfileSelection.AUTOMATIC -> {
                offlineSample = recommended
                streamingSample = recommendedStreaming
            }
            NcnnProfileSelection.MANUAL_CPU -> {
                offlineSample = recommendedCpu
                streamingSample = recommendedStreamingCpu
            }
            NcnnProfileSelection.MANUAL_VULKAN -> {
                offlineSample = vulkanSample ?: return null
                streamingSample = offlineSample
            }
        }
        return NcnnExecutionProfile(
            modelId = modelId,
            threadsPerWorker = offlineSample.threads,
            workers = offlineSample.workers,
            backend = offlineSample.toBackend(),
            measuredPipelineFps = offlineSample.pipelineFps,
            benchmarked = true,
            streamingThreads = streamingSample.threads,
            streamingBackend = streamingSample.toBackend(),
            measuredStreamingPipelineFps = streamingSample.pipelineFps,
            selection = selection,
            vulkanParityPassed = vulkanParity?.passed
        )
    }

    private val eligibleSamples: List<NativeNcnnPipelineBenchmark>
        get() = samples.filter(::isEligible)

    private fun NativeNcnnPipelineBenchmark.toBackend(): NativeNcnnBackend =
        if (usesVulkan) NativeNcnnBackend.VULKAN else NativeNcnnBackend.CPU
}

fun interface NativeStopSignal {
    fun isStopRequested(): Boolean
}

interface NativeFrameCallback {
    fun onNativeFrame(
        frameIndex: Int,
        sourceTimestampUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        inferenceTimeNs: Long,
        preprocessingTimeNs: Long,
        postprocessingTimeNs: Long,
        classIds: IntArray,
        confidences: FloatArray,
        boxes: FloatArray,
        keypointOffsets: IntArray,
        keypoints: FloatArray
    ): IntArray
}

object NativeMediaPipeline {
    private val loadFailure: Throwable? = runCatching {
        System.loadLibrary("integrapose_ncnn")
    }.exceptionOrNull()

    suspend fun probe(context: Context, uri: Uri): NativeVideoInfo =
        withContext(Dispatchers.IO) {
            withVideoFileDescriptor(context, uri) { fd, offset, length ->
                requireAvailable()
                nativeProbe(fd, offset, length)
            }
        }

    suspend fun benchmarkDecode(
        context: Context,
        uri: Uri,
        maxFrames: Int = 0
    ): NativeDecodeBenchmark = withContext(Dispatchers.IO) {
        require(maxFrames >= 0) { "maxFrames cannot be negative." }
        withVideoFileDescriptor(context, uri) { fd, offset, length ->
            requireAvailable()
            val info = nativeProbe(fd, offset, length)
            withDecoderFallback(info.mimeType) { decoderName ->
                nativeBenchmarkDecode(
                    fd,
                    offset,
                    length,
                    maxFrames,
                    decoderName
                )
            }
        }
    }

    suspend fun benchmarkNcnn(
        context: Context,
        uri: Uri,
        model: InferenceModelConfig,
        maxFrames: Int = 120,
        frameStride: Int = 1,
        threads: Int = (
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1) - 1
        ).coerceIn(1, 4),
        workers: Int = 1,
        backend: NativeNcnnBackend = NativeNcnnBackend.CPU,
        annotatedVideoPath: String? = null,
        annotationStyle: AnnotationStyle = AnnotationStyle.Default,
        annotationRois: List<BehaviorRoi> = emptyList(),
        stopSignal: NativeStopSignal? = null,
        frameCallback: NativeFrameCallback? = null
    ): NativeNcnnPipelineBenchmark = withContext(Dispatchers.IO) {
        require(
            model.runtime == ModelRuntime.NCNN_CPU ||
                model.runtime == ModelRuntime.NCNN_VULKAN
        ) {
            "The native NCNN benchmark requires an NCNN model."
        }
        model.requireSupportedModel()
        require(maxFrames >= 0) { "maxFrames cannot be negative." }
        require(frameStride in 1..100_000) {
            "frameStride must be between 1 and 100000."
        }
        require(frameStride == 1 || annotatedVideoPath == null) {
            "Strided benchmarks cannot export an annotated video."
        }
        require(workers in 1..4) {
            "workers must be between one and four."
        }
        require(backend == NativeNcnnBackend.CPU || workers == 1) {
            "NCNN Vulkan uses one ordered inference worker."
        }
        require(annotationRois.size <= 256) {
            "Annotated video supports at most 256 ROI outlines."
        }
        val weightsPath = requireNotNull(model.auxiliaryFilePath) {
            "The NCNN model is missing its .bin weights file."
        }
        withVideoFileDescriptor(context, uri) { fd, offset, length ->
            requireAvailable()
            val info = nativeProbe(fd, offset, length)
            withDecoderFallback(info.mimeType) { decoderName ->
                nativeBenchmarkNcnn(
                fd = fd,
                offset = offset,
                length = length,
                paramPath = model.filePath,
                weightsPath = weightsPath,
                inputSize = model.inputSize,
                threads = threads.coerceIn(1, 8),
                workers = workers,
                useVulkan = backend == NativeNcnnBackend.VULKAN,
                maxFrames = maxFrames,
                frameStride = frameStride,
                classCount = model.classNames.size,
                isPose = model.type == ModelType.POSE,
                confThreshold = model.confThreshold,
                iouThreshold = model.iouThreshold,
                detectionCount = model.detectionCount,
                outputFormat = model.outputFormat.ordinal,
                coordinateFormat = model.coordinateFormat.ordinal,
                boxColorArgb = annotationStyle.boxArgb,
                keypointColorArgb = annotationStyle.keypointArgb,
                skeletonConnections = model.skeletonConnections.flatMap {
                    listOf(it.startIndex, it.endIndex)
                }.toIntArray(),
                roiCoordinates = annotationRois.flatMap { roi ->
                    val safe = roi.sanitized()
                    listOf(safe.left, safe.top, safe.right, safe.bottom)
                }.toFloatArray(),
                roiNames = annotationRois.map {
                    it.sanitized().name
                }.toTypedArray(),
                roiColors = annotationRois.map { roi ->
                    RoiAnnotationPalette.argbFor(roi.id)
                }.toIntArray(),
                roiLabelSize = annotationStyle.roiLabelSize.nativeSizeCode,
                annotatedVideoPath = annotatedVideoPath
                    ?.takeIf { it.isNotBlank() },
                stopSignal = stopSignal,
                frameCallback = frameCallback,
                decoderName = decoderName
                )
            }
        }
    }

    suspend fun autoBenchmarkNcnn(
        context: Context,
        uri: Uri,
        model: InferenceModelConfig,
        framesPerTrial: Int = DEFAULT_NCNN_AUTO_BENCHMARK_FRAMES,
        diagnosticMode: Boolean = false,
        stopSignal: NativeStopSignal? = null
    ): NativeNcnnAutoBenchmark = withContext(Dispatchers.IO) {
        fun stopIfRequested() {
            if (stopSignal?.isStopRequested() == true) {
                throw CancellationException(
                    "Benchmark stopped safely. No processing-device choice was changed."
                )
            }
        }

        stopIfRequested()
        require(
            model.runtime == ModelRuntime.NCNN_CPU ||
                model.runtime == ModelRuntime.NCNN_VULKAN
        ) {
            "Automatic native tuning requires an NCNN model."
        }
        require(framesPerTrial in 15..300) {
            "framesPerTrial must be between 15 and 300."
        }

        val deviceBefore = DeviceProfile.collect(context)
        val videoInfo = probe(context, uri)
        val estimatedFrameCount = (
            videoInfo.durationUs.toDouble() /
                1_000_000.0 * videoInfo.declaredFrameRate
            ).roundToInt().coerceAtLeast(1)
        val paritySampleFrames = if (diagnosticMode) {
            PARITY_SAMPLE_FRAMES
        } else {
            framesPerTrial.coerceAtMost(PRODUCTION_PARITY_SAMPLE_FRAMES)
        }
        val parityFrameStride = (
            estimatedFrameCount / paritySampleFrames
            ).coerceAtLeast(1)
        val candidates = suggestedNcnnCpuConfigurations(
            cpuCores = deviceBefore.cpuCores,
            exhaustive = diagnosticMode
        )
        val cpuTrialFailures = mutableListOf<String>()
        val cpuSamples = candidates.mapNotNull { configuration ->
            try {
                stopIfRequested()
                benchmarkNcnn(
                    context = context,
                    uri = uri,
                    model = model,
                    maxFrames = framesPerTrial,
                    threads = configuration.threadsPerWorker,
                    workers = configuration.workers,
                    backend = NativeNcnnBackend.CPU,
                    stopSignal = stopSignal
                ).also { stopIfRequested() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val reason = if (error is OutOfMemoryError) {
                    "not enough memory"
                } else {
                    error.message?.lineSequence()?.firstOrNull()
                        ?.take(160)
                        ?: error::class.java.simpleName
                }
                cpuTrialFailures +=
                    "${configuration.workers}w x " +
                    "${configuration.threadsPerWorker}t: $reason"
                null
            }
        }
        check(cpuSamples.isNotEmpty()) {
            "Every automatic CPU configuration failed. " +
                cpuTrialFailures.joinToString("; ")
        }
        val timedCpuCandidate = cpuSamples.maxWithOrNull(
            compareBy<NativeNcnnPipelineBenchmark> { it.pipelineFps }
                .thenBy { it.inferenceFps }
                .thenBy { -it.workers }
                .thenBy { -it.threads }
        ) ?: cpuSamples.first()
        val bestSingleWorker = cpuSamples
            .filter { it.workers == 1 }
            .maxWithOrNull(
                compareBy<NativeNcnnPipelineBenchmark> { it.pipelineFps }
                    .thenBy { it.inferenceFps }
                    .thenBy { -it.threads }
            ) ?: cpuSamples.first()

        var recommendedCpu = timedCpuCandidate
        var cpuWorkerParity: NativeNcnnParityResult? = null
        var cpuWorkerFailure: String? = null
        if (timedCpuCandidate.workers > 1) {
            runCatching {
                val referenceCollector = NativePredictionCollector()
                benchmarkNcnn(
                    context = context,
                    uri = uri,
                    model = model,
                    maxFrames = paritySampleFrames,
                    frameStride = parityFrameStride,
                    threads = timedCpuCandidate.threads,
                    workers = 1,
                    backend = NativeNcnnBackend.CPU,
                    stopSignal = stopSignal,
                    frameCallback = referenceCollector
                )
                stopIfRequested()
                val candidateCollector = NativePredictionCollector()
                benchmarkNcnn(
                    context = context,
                    uri = uri,
                    model = model,
                    maxFrames = paritySampleFrames,
                    frameStride = parityFrameStride,
                    threads = timedCpuCandidate.threads,
                    workers = timedCpuCandidate.workers,
                    backend = NativeNcnnBackend.CPU,
                    stopSignal = stopSignal,
                    frameCallback = candidateCollector
                )
                stopIfRequested()
                cpuWorkerParity = compareNcnnPredictions(
                    cpuFrames = referenceCollector.frames,
                    vulkanFrames = candidateCollector.frames
                )
                if (cpuWorkerParity?.passed != true) {
                    cpuWorkerFailure =
                        "Parallel CPU timing was excluded because prediction parity failed."
                    recommendedCpu = bestSingleWorker
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                cpuWorkerFailure = error.message
                    ?: error::class.java.simpleName
                recommendedCpu = bestSingleWorker
            }
        }

        var vulkanParity: NativeNcnnParityResult? = null
        var vulkanSample: NativeNcnnPipelineBenchmark? = null
        var vulkanFailure: String? = null
        if (deviceBefore.ncnnVulkanDeviceCount > 0) {
            runCatching {
                val cpuCollector = NativePredictionCollector()
                benchmarkNcnn(
                    context = context,
                    uri = uri,
                    model = model,
                    maxFrames = paritySampleFrames,
                    frameStride = parityFrameStride,
                    threads = recommendedCpu.threads,
                    workers = recommendedCpu.workers,
                    backend = NativeNcnnBackend.CPU,
                    stopSignal = stopSignal,
                    frameCallback = cpuCollector
                )
                stopIfRequested()
                val vulkanCollector = NativePredictionCollector()
                benchmarkNcnn(
                    context = context,
                    uri = uri,
                    model = model,
                    maxFrames = paritySampleFrames,
                    frameStride = parityFrameStride,
                    threads = recommendedCpu.threads,
                    workers = 1,
                    backend = NativeNcnnBackend.VULKAN,
                    stopSignal = stopSignal,
                    frameCallback = vulkanCollector
                )
                stopIfRequested()
                vulkanParity = compareNcnnPredictions(
                    cpuFrames = cpuCollector.frames,
                    vulkanFrames = vulkanCollector.frames
                )
                vulkanSample = benchmarkNcnn(
                    context = context,
                    uri = uri,
                    model = model,
                    maxFrames = framesPerTrial,
                    threads = recommendedCpu.threads,
                    workers = 1,
                    backend = NativeNcnnBackend.VULKAN,
                    stopSignal = stopSignal
                )
                stopIfRequested()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                vulkanFailure = error.message
                    ?: error::class.java.simpleName
            }
        }
        NativeNcnnAutoBenchmark(
            deviceBefore = deviceBefore,
            deviceAfter = DeviceProfile.collect(context),
            framesPerTrial = framesPerTrial,
            samples = cpuSamples + listOfNotNull(vulkanSample),
            cpuWorkerParity = cpuWorkerParity,
            cpuWorkerFailure = cpuWorkerFailure,
            validatedCpuWorkers = recommendedCpu.workers,
            validatedCpuThreads = recommendedCpu.threads,
            vulkanParity = vulkanParity,
            vulkanFailure = vulkanFailure,
            cpuTrialFailures = cpuTrialFailures
        )
    }

    private const val PARITY_SAMPLE_FRAMES = 60
    private const val PRODUCTION_PARITY_SAMPLE_FRAMES = 30

    private fun <T> withDecoderFallback(
        mimeType: String,
        block: (String?) -> T
    ): T {
        AndroidSoftwareVideoDecoder.rememberedFor(mimeType)?.let {
            return block(it)
        }
        return try {
            block(null)
        } catch (hardwareError: Throwable) {
            if (!AndroidSoftwareVideoDecoder.shouldRetry(hardwareError)) {
                throw hardwareError
            }
            val softwareDecoder = AndroidSoftwareVideoDecoder.findFor(mimeType)
                ?: throw IllegalStateException(
                    "This device cannot expose CPU-readable hardware-decoder frames, and no Android software decoder is available for $mimeType.",
                    hardwareError
                )
            try {
                block(softwareDecoder).also {
                    AndroidSoftwareVideoDecoder.rememberFor(
                        mimeType,
                        softwareDecoder
                    )
                }
            } catch (softwareError: Throwable) {
                softwareError.addSuppressed(hardwareError)
                throw softwareError
            }
        }
    }

    private fun <T> withVideoFileDescriptor(
        context: Context,
        uri: Uri,
        block: (fd: Int, offset: Long, length: Long) -> T
    ): T {
        val asset = requireNotNull(
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        ) {
            "The selected video could not be opened."
        }
        asset.use {
            val offset = asset.startOffset.coerceAtLeast(0L)
            val statSize = asset.parcelFileDescriptor.statSize
            val length = when {
                asset.length > 0L -> asset.length
                statSize > offset -> statSize - offset
                else -> Long.MAX_VALUE
            }
            return block(asset.parcelFileDescriptor.fd, offset, length)
        }
    }

    private fun requireAvailable() {
        val failure = loadFailure ?: return
        throw IllegalStateException(
            "The native media pipeline could not be loaded: " +
                (failure.message ?: failure::class.java.simpleName),
            failure
        )
    }

    @JvmStatic
    private external fun nativeProbe(
        fd: Int,
        offset: Long,
        length: Long
    ): NativeVideoInfo

    @JvmStatic
    private external fun nativeBenchmarkDecode(
        fd: Int,
        offset: Long,
        length: Long,
        maxFrames: Int,
        decoderName: String?
    ): NativeDecodeBenchmark

    @JvmStatic
    private external fun nativeBenchmarkNcnn(
        fd: Int,
        offset: Long,
        length: Long,
        paramPath: String,
        weightsPath: String,
        inputSize: Int,
        threads: Int,
        workers: Int,
        useVulkan: Boolean,
        maxFrames: Int,
        frameStride: Int,
        classCount: Int,
        isPose: Boolean,
        confThreshold: Float,
        iouThreshold: Float,
        detectionCount: Int,
        outputFormat: Int,
        coordinateFormat: Int,
        boxColorArgb: Int,
        keypointColorArgb: Int,
        skeletonConnections: IntArray,
        roiCoordinates: FloatArray,
        roiNames: Array<String>,
        roiColors: IntArray,
        roiLabelSize: Int,
        annotatedVideoPath: String?,
        stopSignal: NativeStopSignal?,
        frameCallback: NativeFrameCallback?,
        decoderName: String?
    ): NativeNcnnPipelineBenchmark
}
