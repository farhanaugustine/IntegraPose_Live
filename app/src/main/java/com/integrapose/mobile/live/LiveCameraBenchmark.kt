package com.integrapose.mobile.live

import android.media.MediaMetadataRetriever
import androidx.camera.core.ImageProxy
import com.integrapose.mobile.inference.ConvertedImageProxyBitmap
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.inference.ReusableImageProxyBitmapConverter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlinx.coroutines.delay

internal data class LiveCameraBenchmarkConfiguration(
    val workers: Int,
    val threadsPerWorker: Int,
    val useVulkan: Boolean,
    val previewRenderer: LivePreviewRenderer = LivePreviewRenderer.Default
) {
    init {
        require(workers in 1..2)
        require(threadsPerWorker in 1..8)
        require(!useVulkan || workers == 1)
    }

    val label: String
        get() = (if (useVulkan) {
            "Vulkan GPU, 1 ordered worker"
        } else {
            "$workers ${if (workers == 1) "worker" else "workers"} x " +
                "$threadsPerWorker ${if (threadsPerWorker == 1) "thread" else "threads"}"
        }) + "; ${previewRenderer.displayName}"

    fun tuning(source: String): NcnnRuntimeTuning = NcnnRuntimeTuning(
        threads = threadsPerWorker,
        useVulkan = useVulkan,
        source = source
    )
}

internal fun liveCameraBenchmarkCandidates(
    cpuCores: Int,
    currentTuning: NcnnRuntimeTuning?,
    currentWorkers: Int,
    previewRenderers: List<LivePreviewRenderer> = listOf(LivePreviewRenderer.Default)
): List<LiveCameraBenchmarkConfiguration> {
    val currentVulkan = if (currentTuning?.useVulkan == true) {
        listOf(
            LiveCameraBenchmarkConfiguration(
                workers = 1,
                threadsPerWorker = currentTuning.threads,
                useVulkan = true
            )
        )
    } else {
        emptyList()
    }
    val cores = cpuCores.coerceAtLeast(1)
    val maximumThreads = cores.coerceAtMost(4)
    val currentThreads = currentTuning?.threads?.coerceIn(1, 8)
        ?: (maximumThreads - 1).coerceAtLeast(1)
    val singleWorker = listOf(
        currentThreads,
        (maximumThreads - 1).coerceAtLeast(1),
        maximumThreads,
        2,
        1
    )
        .filter { it <= maximumThreads }
        .distinct()
        .map { LiveCameraBenchmarkConfiguration(1, it, false) }
    val parallel = if (cores >= 2) {
        listOf(
            LiveCameraBenchmarkConfiguration(2, 1, false),
            LiveCameraBenchmarkConfiguration(
                workers = 2,
                threadsPerWorker = (cores / 2).coerceIn(1, 2),
                useVulkan = false
            )
        ).filter { it.workers * it.threadsPerWorker <= cores }
    } else {
        emptyList()
    }
    val boundedCurrentWorkers = currentWorkers.coerceIn(1, if (cores >= 2) 2 else 1)
    val current = LiveCameraBenchmarkConfiguration(
        workers = boundedCurrentWorkers,
        threadsPerWorker = currentThreads.coerceAtMost(
            (cores / boundedCurrentWorkers).coerceAtLeast(1)
        ),
        useVulkan = false
    )
    val inferenceCandidates =
        (currentVulkan + listOf(current) + singleWorker + parallel)
        .distinct()
        .take(MAX_INFERENCE_CANDIDATES)
    val rendererCandidates = previewRenderers.distinct().ifEmpty {
        listOf(LivePreviewRenderer.Default)
    }
    return rendererCandidates.flatMap { renderer ->
        inferenceCandidates.map { it.copy(previewRenderer = renderer) }
    }.take(MAX_COMBINATION_CANDIDATES)
}

internal class LiveInferenceWorkerPool(
    primaryRunner: ModelInferenceRunner,
    maximumWorkers: Int = 2
) {
    private val slots = List(maximumWorkers.coerceIn(1, 2)) { index ->
        WorkerSlot(
            runner = if (index == 0) primaryRunner else ModelInferenceRunner(),
            owned = index > 0
        )
    }

    fun tryAcquire(workerLimit: Int): WorkerLease? {
        val limit = workerLimit.coerceIn(1, slots.size)
        for (index in 0 until limit) {
            val slot = slots[index]
            if (slot.busy.compareAndSet(false, true)) {
                return WorkerLease(slot)
            }
        }
        return null
    }

    suspend fun awaitIdle() {
        while (slots.any { it.busy.get() }) delay(10L)
    }

    suspend fun closeOwnedRunners() {
        awaitIdle()
        slots.forEach { it.bitmapConverter.close() }
        slots.filter { it.owned }.forEach { it.runner.close() }
    }

    internal data class WorkerSlot(
        val runner: ModelInferenceRunner,
        val owned: Boolean,
        val bitmapConverter: ReusableImageProxyBitmapConverter =
            ReusableImageProxyBitmapConverter(),
        val busy: AtomicBoolean = AtomicBoolean(false)
    )

    internal class WorkerLease internal constructor(
        private val slot: WorkerSlot
    ) {
        val runner: ModelInferenceRunner get() = slot.runner

        fun convert(image: ImageProxy): ConvertedImageProxyBitmap? =
            slot.bitmapConverter.convert(image)

        fun release() {
            check(slot.busy.compareAndSet(true, false)) {
                "Live inference worker was released twice."
            }
        }
    }
}

internal data class LiveCameraBenchmarkSample(
    val configuration: LiveCameraBenchmarkConfiguration,
    val durationMs: Long,
    val cameraCallbacks: Int,
    val acceptedFrames: Int,
    val completedFrames: Int,
    val publishedFrames: Int,
    val busyDrops: Int,
    val medianPipelineMs: Long,
    val p95PipelineMs: Long,
    val medianModelPipelineMs: Long,
    val medianPreprocessingMs: Long,
    val overlayPublishedFrames: Int = 0,
    val bulkRgbaFrames: Int = 0,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val medianConversionMs: Double = 0.0,
    val medianTrackingWriteMs: Double = 0.0,
    val medianUiPublishMs: Double = 0.0
) {
    val publishedFps: Double
        get() = if (durationMs > 0L) publishedFrames * 1_000.0 / durationMs else 0.0

    val cameraCallbackFps: Double
        get() = if (durationMs > 0L) cameraCallbacks * 1_000.0 / durationMs else 0.0

    val overlayFps: Double
        get() = if (durationMs > 0L) overlayPublishedFrames * 1_000.0 / durationMs else 0.0

    val busyDropPercent: Double
        get() = if (cameraCallbacks > 0) busyDrops * 100.0 / cameraCallbacks else 0.0

    val bottleneckStage: String
        get() = listOf(
            "camera RGBA copy" to medianConversionMs,
            "model pipeline" to medianModelPipelineMs.toDouble(),
            "tracking/writes" to medianTrackingWriteMs,
            "UI publication" to medianUiPublishMs
        ).maxByOrNull { it.second }?.first ?: "unresolved"
}

internal data class LiveCameraRecordingProbe(
    val frames: Int,
    val durationMs: Long
) {
    val frameRate: Double
        get() = if (durationMs > 0L) frames * 1_000.0 / durationMs else 0.0
}

internal data class LiveCameraBenchmarkResult(
    val previewSamples: List<LiveCameraBenchmarkSample>,
    val recordingSamples: List<LiveCameraBenchmarkSample>,
    val recordingProbe: LiveCameraRecordingProbe
) {
    val bestPreview: LiveCameraBenchmarkSample = previewSamples.maxWithOrNull(
        compareBy<LiveCameraBenchmarkSample> { it.publishedFps }
            .thenBy { -it.p95PipelineMs }
            .thenBy { -it.configuration.workers }
            .thenBy { -it.configuration.threadsPerWorker }
    ) ?: recordingSamples.first()

    val recommended: LiveCameraBenchmarkSample = recordingSamples.maxWithOrNull(
        compareBy<LiveCameraBenchmarkSample> { it.publishedFps }
            .thenBy { -it.p95PipelineMs }
            .thenBy { -it.configuration.workers }
            .thenBy { -it.configuration.threadsPerWorker }
    ) ?: bestPreview
}

internal class LiveCameraBenchmarkCollector {
    private var nextToken = 1L
    private var state: MutableSample? = null

    @Synchronized
    fun begin(
        configuration: LiveCameraBenchmarkConfiguration,
        targetCompletedFrames: Int? = null
    ): Long {
        check(state == null) { "A Live camera benchmark sample is already active." }
        require(targetCompletedFrames == null || targetCompletedFrames > 0)
        return nextToken++.also { token ->
            state = MutableSample(
                token,
                configuration,
                System.nanoTime(),
                targetCompletedFrames = targetCompletedFrames
            )
        }
    }

    @Synchronized
    fun onCameraFrame(): Long? {
        val active = state?.takeIf { it.accepting } ?: return null
        active.cameraCallbacks += 1
        return active.token
    }

    @Synchronized
    fun onBusyDrop(token: Long?) {
        state?.takeIf { it.token == token && it.accepting }?.busyDrops =
            (state?.busyDrops ?: 0) + 1
    }

    @Synchronized
    fun onAccepted(token: Long?, analysisWidth: Int = 0, analysisHeight: Int = 0) {
        state?.takeIf { it.token == token && it.accepting }?.let { active ->
            active.acceptedFrames += 1
            if (analysisWidth > 0 && analysisHeight > 0) {
                active.analysisWidth = analysisWidth
                active.analysisHeight = analysisHeight
            }
        }
    }

    @Synchronized
    fun onCompleted(
        token: Long?,
        result: FrameInferenceResult,
        pipelineMs: Long,
        published: Boolean,
        overlayPublished: Boolean = published,
        usedBulkRgbaCopy: Boolean = false,
        conversionNs: Long = 0L,
        trackingWriteNs: Long = 0L,
        uiPublishNs: Long = 0L
    ) {
        val active = state?.takeIf { it.token == token } ?: return
        if (!active.accepting && active.targetCompletedFrames != null) return
        active.completedFrames += 1
        if (published) active.publishedFrames += 1
        if (overlayPublished) active.overlayPublishedFrames += 1
        if (usedBulkRgbaCopy) active.bulkRgbaFrames += 1
        active.pipelineTimesMs += pipelineMs
        active.modelPipelineTimesMs += result.inferenceMs
        active.preprocessingTimesMs += result.preprocessingMs
        if (conversionNs > 0L) active.conversionTimesNs += conversionNs
        if (trackingWriteNs > 0L) active.trackingWriteTimesNs += trackingWriteNs
        if (uiPublishNs > 0L) active.uiPublishTimesNs += uiPublishNs
        if (
            active.targetCompletedFrames != null &&
            active.completedFrames >= active.targetCompletedFrames
        ) {
            active.accepting = false
            active.stoppedNs = System.nanoTime()
        }
    }

    @Synchronized
    fun isComplete(token: Long): Boolean =
        state?.takeIf { it.token == token }?.accepting == false

    @Synchronized
    fun stopAccepting(token: Long) {
        state?.takeIf { it.token == token }?.let {
            if (it.accepting) {
                it.accepting = false
                it.stoppedNs = System.nanoTime()
            }
        }
    }

    @Synchronized
    fun finish(token: Long): LiveCameraBenchmarkSample {
        val active = requireNotNull(state?.takeIf { it.token == token }) {
            "The Live camera benchmark sample is no longer active."
        }
        check(!active.accepting) { "Stop the benchmark sample before finishing it." }
        state = null
        return LiveCameraBenchmarkSample(
            configuration = active.configuration,
            durationMs = ((active.stoppedNs - active.startedNs) / 1_000_000L)
                .coerceAtLeast(1L),
            cameraCallbacks = active.cameraCallbacks,
            acceptedFrames = active.acceptedFrames,
            completedFrames = active.completedFrames,
            publishedFrames = active.publishedFrames,
            busyDrops = active.busyDrops,
            medianPipelineMs = percentile(active.pipelineTimesMs, 0.50),
            p95PipelineMs = percentile(active.pipelineTimesMs, 0.95),
            medianModelPipelineMs = percentile(active.modelPipelineTimesMs, 0.50),
            medianPreprocessingMs = percentile(active.preprocessingTimesMs, 0.50),
            overlayPublishedFrames = active.overlayPublishedFrames,
            bulkRgbaFrames = active.bulkRgbaFrames,
            analysisWidth = active.analysisWidth,
            analysisHeight = active.analysisHeight,
            medianConversionMs = percentileNanosMs(active.conversionTimesNs, 0.50),
            medianTrackingWriteMs = percentileNanosMs(
                active.trackingWriteTimesNs,
                0.50
            ),
            medianUiPublishMs = percentileNanosMs(active.uiPublishTimesNs, 0.50)
        )
    }

    @Synchronized
    fun cancel() {
        state = null
    }

    private data class MutableSample(
        val token: Long,
        val configuration: LiveCameraBenchmarkConfiguration,
        val startedNs: Long,
        val targetCompletedFrames: Int? = null,
        var stoppedNs: Long = startedNs,
        var accepting: Boolean = true,
        var cameraCallbacks: Int = 0,
        var acceptedFrames: Int = 0,
        var completedFrames: Int = 0,
        var publishedFrames: Int = 0,
        var busyDrops: Int = 0,
        var overlayPublishedFrames: Int = 0,
        var bulkRgbaFrames: Int = 0,
        var analysisWidth: Int = 0,
        var analysisHeight: Int = 0,
        val pipelineTimesMs: MutableList<Long> = mutableListOf(),
        val modelPipelineTimesMs: MutableList<Long> = mutableListOf(),
        val preprocessingTimesMs: MutableList<Long> = mutableListOf(),
        val conversionTimesNs: MutableList<Long> = mutableListOf(),
        val trackingWriteTimesNs: MutableList<Long> = mutableListOf(),
        val uiPublishTimesNs: MutableList<Long> = mutableListOf()
    )

    private companion object {
        fun percentile(values: List<Long>, fraction: Double): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            val index = (ceil(sorted.size * fraction).toInt() - 1)
                .coerceIn(sorted.indices)
            return sorted[index]
        }

        fun percentileNanosMs(values: List<Long>, fraction: Double): Double =
            percentile(values, fraction) / 1_000_000.0
    }
}

internal fun probeLiveCameraRecording(file: File): LiveCameraRecordingProbe {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.coerceAtLeast(1L)
            ?: 1L
        val frames = if (android.os.Build.VERSION.SDK_INT >= 28) {
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        } else {
            null
        } ?: (durationMs * 30.0 / 1_000.0).toInt().coerceAtLeast(1)
        LiveCameraRecordingProbe(frames, durationMs)
    } finally {
        retriever.release()
    }
}

internal const val LIVE_BENCHMARK_FRAMES_PER_COMBINATION = 30
private const val MAX_INFERENCE_CANDIDATES = 6
private const val MAX_COMBINATION_CANDIDATES = MAX_INFERENCE_CANDIDATES * 2
