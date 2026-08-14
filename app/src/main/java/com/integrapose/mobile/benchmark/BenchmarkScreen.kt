package com.integrapose.mobile.benchmark

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.BuildConfig
import com.integrapose.mobile.importing.OpenReadOnlyDocument
import com.integrapose.mobile.importing.StagedVideoSource
import com.integrapose.mobile.export.shareExport
import com.integrapose.mobile.export.viewExport
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.offline.NativeDecodeBenchmark
import com.integrapose.mobile.offline.NativeMediaPipeline
import com.integrapose.mobile.offline.NativeNcnnAutoBenchmark
import com.integrapose.mobile.offline.NativeNcnnBackend
import com.integrapose.mobile.offline.NativeNcnnOfflineProcessor
import com.integrapose.mobile.offline.NativeNcnnPipelineBenchmark
import com.integrapose.mobile.offline.NativeStopSignal
import com.integrapose.mobile.offline.NcnnExecutionProfile
import com.integrapose.mobile.offline.NcnnProfileSelection
import com.integrapose.mobile.offline.OfflineProcessResult
import com.integrapose.mobile.testing.BundledTestAssets
import com.integrapose.mobile.tracking.IoUTrackerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class BenchmarkSessionState internal constructor(
    internal val maximumThreads: Int
) {
    internal val selectedVideo = mutableStateOf<Uri?>(null)
    internal val preparingVideo = mutableStateOf(false)
    internal val preparingTwoAnimalVideo = mutableStateOf(false)
    internal val benchmarkingDecode = mutableStateOf(false)
    internal val benchmarkingModel = mutableStateOf(false)
    internal val benchmarkingAuto = mutableStateOf(false)
    internal val benchmarkingCurrent = mutableStateOf(false)
    internal val runningFullClip = mutableStateOf(false)
    internal val progress = mutableFloatStateOf(0f)
    internal val errorText = mutableStateOf<String?>(null)
    internal val operationText = mutableStateOf<String?>(null)
    internal val decodeResult = mutableStateOf<NativeDecodeBenchmark?>(null)
    internal val modelResult = mutableStateOf<BenchmarkResult?>(null)
    internal val autoResult = mutableStateOf<NativeNcnnAutoBenchmark?>(null)
    internal val pipelineResult = mutableStateOf<NativeNcnnPipelineBenchmark?>(null)
    internal val outputResult = mutableStateOf<OfflineProcessResult?>(null)
    internal val threads = mutableStateOf((maximumThreads - 1).coerceAtLeast(1))
    internal val workers = mutableStateOf(1)
    internal val backend = mutableStateOf(NativeNcnnBackend.CPU)
    internal val showAdvanced = mutableStateOf(false)
    internal val stopRequested = AtomicBoolean(false)

    /**
     * Native benchmark calls are blocking. Keeping this state above the tab content lets the
     * app prevent another inference surface from starting until the active call has returned.
     */
    val isBusy: Boolean
        get() = preparingVideo.value || preparingTwoAnimalVideo.value ||
            benchmarkingDecode.value ||
            benchmarkingModel.value || benchmarkingAuto.value ||
            benchmarkingCurrent.value || runningFullClip.value
}

@Composable
fun rememberBenchmarkSessionState(modelId: String?): BenchmarkSessionState {
    val maximumThreads = remember {
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
    return remember(modelId) { BenchmarkSessionState(maximumThreads) }
}

@Composable
fun BenchmarkScreen(
    selectedModel: InferenceModelConfig?,
    sessionState: BenchmarkSessionState,
    runner: ModelInferenceRunner,
    annotationStyle: AnnotationStyle,
    trackerConfig: IoUTrackerConfig,
    activeLiveProfile: NcnnExecutionProfile?,
    activeOfflineProfile: NcnnExecutionProfile?,
    onLiveProfileSelected: (NcnnExecutionProfile) -> Unit,
    onOfflineProfileSelected: (NcnnExecutionProfile) -> Unit,
    onOpenModels: () -> Unit
) {
    if (selectedModel == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Benchmark this device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE5ECF8)
            )
            Text(
                if (BuildConfig.BUNDLED_TEST_KIT) {
                    "Run the private tandem R&D benchmark below, or select one of your own compatible models."
                } else {
                    "Start with one of your own compatible detection or pose models. IntegraPose Live does not include model weights."
                },
                color = Color(0xFFD8E2F2)
            )
            if (BuildConfig.BUNDLED_TEST_KIT) {
                DebugTandemBenchmarkCard()
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xB31A293A))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "First-time setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EFF9)
                    )
                    Text("1. Add and select a model in Models.", color = Color(0xFFD8E2F2))
                    Text("2. Return here and choose a representative video.", color = Color(0xFFD8E2F2))
                    Text("3. Run the automatic benchmark to find the best settings.", color = Color(0xFFD8E2F2))
                    Button(
                        onClick = onOpenModels,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Models")
                    }
                }
            }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nativeProcessor = remember { NativeNcnnOfflineProcessor(context) }
    val deviceProfile = remember { DeviceProfile.collect(context) }
    val supportsNativeNcnn = selectedModel.runtime == ModelRuntime.NCNN_CPU ||
        selectedModel.runtime == ModelRuntime.NCNN_VULKAN
    val maximumThreads = sessionState.maximumThreads
    var selectedVideo by sessionState.selectedVideo
    var preparingVideo by sessionState.preparingVideo
    var preparingTwoAnimalVideo by sessionState.preparingTwoAnimalVideo
    var benchmarkingDecode by sessionState.benchmarkingDecode
    var benchmarkingModel by sessionState.benchmarkingModel
    var benchmarkingAuto by sessionState.benchmarkingAuto
    var benchmarkingCurrent by sessionState.benchmarkingCurrent
    var runningFullClip by sessionState.runningFullClip
    var progress by sessionState.progress
    var errorText by sessionState.errorText
    var operationText by sessionState.operationText
    var decodeResult by sessionState.decodeResult
    var modelResult by sessionState.modelResult
    var autoResult by sessionState.autoResult
    var pipelineResult by sessionState.pipelineResult
    var outputResult by sessionState.outputResult
    var threads by sessionState.threads
    var workers by sessionState.workers
    var backend by sessionState.backend
    var showAdvanced by sessionState.showAdvanced
    val controlsBusy = preparingVideo || preparingTwoAnimalVideo ||
        benchmarkingDecode || benchmarkingModel || benchmarkingAuto ||
        benchmarkingCurrent || runningFullClip

    fun selectVideo(uri: Uri) {
        selectedVideo = uri
        decodeResult = null
        modelResult = null
        autoResult = null
        pipelineResult = null
        outputResult = null
        errorText = null
        operationText = null
        progress = 0f
    }

    val picker = rememberLauncherForActivityResult(OpenReadOnlyDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                preparingVideo = true
                errorText = null
                try {
                    selectVideo(StagedVideoSource.prepare(context, uri))
                } catch (error: Throwable) {
                    errorText = error.message
                        ?: "The selected video could not be prepared. " +
                            "Refresh the cloud provider and select it again."
                } finally {
                    preparingVideo = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Device Benchmark",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE5ECF8)
        )
        Text(
            "Compare CPU and Vulkan GPU throughput and prediction agreement, then choose which processing device to use.",
            color = Color(0xFFBDD0E7)
        )

        if (BuildConfig.BUNDLED_TEST_KIT) {
            DebugTandemBenchmarkCard()
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0x55304455))) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Detected hardware",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EFF9)
                )
                Text(
                    if (BuildConfig.BUNDLED_TEST_KIT) {
                        deviceProfile.summary
                    } else {
                        deviceProfile.deviceName
                    },
                    color = Color(0xFFD6EFE9)
                )
                Text(
                    if (deviceProfile.vulkanAvailable) {
                        "NCNN found ${deviceProfile.ncnnVulkanDeviceCount} Vulkan compute device(s). Both CPU and GPU will be measured; failed agreement prevents automatic GPU selection but not an informed manual choice."
                    } else {
                        "No NCNN Vulkan compute device was found. CPU remains available."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0x55304455))) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Representative video",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EFF9)
                )
                Text(
                    "Model: ${selectedModel.name} | detection count ${selectedModel.detectionCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBDD0E7)
                )
                Button(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    enabled = !controlsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (preparingVideo) {
                            "Preparing selected video..."
                        } else {
                            "Select representative video"
                        }
                    )
                }
                Text(
                    "Cloud videos are opened read-only and copied into the app-owned " +
                        "IntegraPose Live disk library before benchmarking. The benchmark " +
                        "never runs from a live provider link, and the source is never changed " +
                        "or deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
                if (BuildConfig.BUNDLED_TEST_KIT) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                preparingVideo = true
                                errorText = null
                                runCatching {
                                    BundledTestAssets.prepareVideo(context)
                                }.onSuccess(::selectVideo)
                                    .onFailure { error ->
                                        errorText = error.message
                                            ?: "The bundled test video could not be prepared."
                                    }
                                preparingVideo = false
                            }
                        },
                        enabled = !controlsBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (preparingVideo) {
                                "Preparing single-animal video..."
                            } else {
                                "Use single-animal 20-second video"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                preparingTwoAnimalVideo = true
                                errorText = null
                                runCatching {
                                    BundledTestAssets.prepareTwoAnimalVideo(context)
                                }.onSuccess(::selectVideo)
                                    .onFailure { error ->
                                        errorText = error.message
                                            ?: "The bundled two-animal video could not be prepared."
                                    }
                                preparingTwoAnimalVideo = false
                            }
                        },
                        enabled = !controlsBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (preparingTwoAnimalVideo) {
                                "Preparing two-animal video..."
                            } else {
                                "Use internal two-subject 20-second video"
                            }
                        )
                    }
                }
                selectedVideo?.let { video ->
                    Text(
                        "Selected: ${video.lastPathSegment ?: video}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA9D6F5)
                    )
                }
                if (BuildConfig.BUNDLED_TEST_KIT || !supportsNativeNcnn) {
                    Button(
                        onClick = {
                            val video = selectedVideo ?: return@Button
                            benchmarkingModel = true
                            errorText = null
                            modelResult = null
                            scope.launch {
                                var frame: Bitmap? = null
                                runCatching {
                                    loadRepresentativeBenchmarkFrame(context, video).also {
                                        frame = it
                                    }.let { bitmap ->
                                        benchmarkModel(
                                            runner = runner,
                                            bitmap = bitmap,
                                            model = selectedModel,
                                            warmupIterations = 2,
                                            measuredIterations = 12
                                        )
                                    }
                                }.onSuccess { modelResult = it }
                                    .onFailure { error ->
                                        errorText = error.message
                                            ?: "The model benchmark failed."
                                    }
                                frame?.recycle()
                                runCatching { runner.close() }
                                benchmarkingModel = false
                            }
                        },
                        enabled = selectedVideo != null && !controlsBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (benchmarkingModel) {
                                "Running model inference..."
                            } else if (!supportsNativeNcnn) {
                                "Measure ONNX CPU throughput"
                            } else {
                                "Run timing-only model diagnostic"
                            }
                        )
                    }
                    Text(
                        if (!supportsNativeNcnn) {
                            "ONNX uses its supported CPU path, so this reports straightforward FPS rather than offering a CPU/GPU choice."
                        } else {
                            "Debug diagnostic: measures one representative frame without validating CPU/GPU prediction agreement."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8C8DD)
                    )
                }
            }
        }

        if (BuildConfig.BUNDLED_TEST_KIT || !supportsNativeNcnn) {
            modelResult?.let {
                ModelBenchmarkCard(
                    result = it,
                    showDiagnostics = BuildConfig.BUNDLED_TEST_KIT
                )
            }
        }

        if (supportsNativeNcnn) {
            Button(
                onClick = {
                    if (benchmarkingAuto) {
                        sessionState.stopRequested.set(true)
                        operationText =
                            "Stopping after the current frame and releasing benchmark resources..."
                        return@Button
                    }
                    val video = selectedVideo ?: return@Button
                    sessionState.stopRequested.set(false)
                    benchmarkingAuto = true
                    errorText = null
                    operationText = null
                    autoResult = null
                    pipelineResult = null
                    outputResult = null
                    scope.launch {
                        runCatching {
                            runner.close()
                            NativeMediaPipeline.autoBenchmarkNcnn(
                                context = context,
                                uri = video,
                                model = selectedModel,
                                framesPerTrial = if (BuildConfig.BUNDLED_TEST_KIT) {
                                    60
                                } else {
                                    30
                                },
                                diagnosticMode = BuildConfig.BUNDLED_TEST_KIT,
                                stopSignal = NativeStopSignal {
                                    sessionState.stopRequested.get()
                                }
                            )
                        }.onSuccess { benchmark ->
                            autoResult = benchmark
                            val recommended = benchmark.recommended
                            threads = recommended.threads
                            workers = recommended.workers
                            backend = benchmark.recommendedBackend
                        }.onFailure { error ->
                            if (error is CancellationException) {
                                operationText = error.message
                                    ?: "Benchmark stopped safely."
                            } else {
                                errorText = error.message
                                    ?: "The automatic NCNN benchmark failed."
                            }
                        }
                        benchmarkingAuto = false
                        sessionState.stopRequested.set(false)
                    }
                },
                enabled = when {
                    benchmarkingAuto -> !sessionState.stopRequested.get()
                    controlsBusy -> false
                    autoResult != null -> false
                    else -> selectedVideo != null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        benchmarkingAuto -> "Stop benchmark"
                        autoResult != null -> "Benchmark complete"
                        else -> "Benchmark CPU and Vulkan GPU"
                    }
                )
            }
            Text(
                if (BuildConfig.BUNDLED_TEST_KIT) {
                    "Debug comparison: exhaustive configurations with 60 frames per trial. You choose the processing device after the results."
                } else {
                    "Compares three actionable CPU/GPU configurations with 30 distributed frames per trial. You choose the processing device after the results."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
            if (autoResult != null) {
                Text(
                    "This benchmark is complete. Select another representative video to intentionally run it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA9D6F5)
                )
            }

        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0x665A4931))) {
                Text(
                    "CPU/GPU device comparison applies to NCNN models. ONNX uses the supported CPU throughput measurement above.",
                    modifier = Modifier.padding(14.dp),
                    color = Color(0xFFFFDFB2)
                )
            }
        }

        if (controlsBusy) {
            if (runningFullClip) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(progress * 100f).toInt()}% complete",
                    color = Color(0xFFC9D6E8)
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (benchmarkingAuto) {
                Text(
                    "Keep the app in the foreground while configurations are compared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFDFB2)
                )
            }
        }

        errorText?.let { message ->
            Text(message, color = Color(0xFFFFB2B2))
        }
        operationText?.let { message ->
            Text(message, color = Color(0xFFA9D6F5))
        }

        autoResult?.let { benchmark ->
            AutoBenchmarkCard(
                benchmark = benchmark,
                showDiagnostics = BuildConfig.BUNDLED_TEST_KIT
            )
            BackendChoiceCard(
                benchmark = benchmark,
                modelId = selectedModel.id,
                activeLiveProfile = activeLiveProfile,
                activeOfflineProfile = activeOfflineProfile,
                enabled = !controlsBusy,
                onLiveProfileSelected = onLiveProfileSelected,
                onOfflineProfileSelected = { profile ->
                    threads = profile.threadsPerWorker
                    workers = profile.workers
                    backend = profile.backend
                    pipelineResult = null
                    outputResult = null
                    onOfflineProfileSelected(profile)
                }
            )
        }
        if (supportsNativeNcnn && autoResult != null) {
            Button(
                onClick = {
                    if (runningFullClip) {
                        sessionState.stopRequested.set(true)
                        operationText =
                            "Stopping the full-clip test and finalizing partial outputs..."
                        return@Button
                    }
                    val video = selectedVideo ?: return@Button
                    sessionState.stopRequested.set(false)
                    runningFullClip = true
                    progress = 0f
                    errorText = null
                    operationText = null
                    pipelineResult = null
                    outputResult = null
                    scope.launch {
                        runCatching {
                            runner.close()
                            nativeProcessor.processVideo(
                                uri = video,
                                model = selectedModel,
                                enableTracking = true,
                                exportAnnotatedVideo = true,
                                threads = threads,
                                workers = workers,
                                backend = backend,
                                runtimeAuditLabel = activeOfflineProfile
                                    ?.auditLabelFor(backend),
                                trackerConfig = trackerConfig,
                                stopSignal = NativeStopSignal {
                                    sessionState.stopRequested.get()
                                },
                                annotationStyle = annotationStyle,
                                onProgress = { value -> progress = value }
                            )
                        }.onSuccess { run ->
                            outputResult = run.output
                            pipelineResult = run.benchmark
                            if (sessionState.stopRequested.get()) {
                                operationText =
                                    "Full-clip test stopped safely; available partial outputs were finalized."
                            }
                        }.onFailure { error ->
                            errorText = error.message
                                ?: "The complete native pipeline test failed."
                        }
                        runningFullClip = false
                        sessionState.stopRequested.set(false)
                    }
                },
                enabled = when {
                    runningFullClip -> !sessionState.stopRequested.get()
                    controlsBusy -> false
                    else -> selectedVideo != null &&
                        activeOfflineProfile?.modelId == selectedModel.id &&
                        activeOfflineProfile.benchmarked
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (runningFullClip) {
                        "Stop & save full-clip test"
                    } else {
                        "Run selected full-clip test"
                    }
                )
            }
            Text(
                if (
                    activeOfflineProfile?.modelId == selectedModel.id &&
                    activeOfflineProfile.benchmarked
                ) {
                    "The full-clip test is the authoritative FPS result because it includes tracking, CSV work, annotations, H.264 encoding, and MP4 finalization."
                } else {
                    "Choose CPU, Vulkan GPU, or the recommendation above before running the full-clip test."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFDFB2)
            )
        }
        pipelineResult?.let {
            PipelineBenchmarkCard(
                benchmark = it,
                showDiagnostics = BuildConfig.BUNDLED_TEST_KIT
            )
        }
        outputResult?.let { output ->
            BenchmarkOutputCard(
                output = output,
                onView = { path ->
                    runCatching { viewExport(context, path, "video/mp4") }
                        .onFailure { errorText = it.message }
                },
                onShare = { path, mime ->
                    runCatching { shareExport(context, path, mime) }
                        .onFailure { errorText = it.message }
                }
            )
        }

        if (BuildConfig.BUNDLED_TEST_KIT) {
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                enabled = !controlsBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showAdvanced) "Hide debug diagnostics" else "Show debug diagnostics")
            }

            if (showAdvanced) {
                AdvancedBenchmarkControls(
                selectedVideo = selectedVideo,
                selectedModel = selectedModel,
                supportsNativeNcnn = supportsNativeNcnn,
                controlsBusy = controlsBusy,
                maximumThreads = maximumThreads,
                threads = threads,
                workers = workers,
                backend = backend,
                vulkanApproved = autoResult?.vulkanParity?.passed == true,
                benchmarkingDecode = benchmarkingDecode,
                benchmarkingCurrent = benchmarkingCurrent,
                onThreadsChange = { threads = it },
                onWorkersChange = { workers = it },
                onBackendChange = { backend = it },
                onBenchmarkDecode = { video ->
                    benchmarkingDecode = true
                    errorText = null
                    decodeResult = null
                    scope.launch {
                        runCatching {
                            NativeMediaPipeline.benchmarkDecode(
                                context = context,
                                uri = video,
                                maxFrames = 600
                            )
                        }.onSuccess { decodeResult = it }
                            .onFailure { errorText = it.message }
                        benchmarkingDecode = false
                    }
                },
                onBenchmarkCurrent = { video ->
                    benchmarkingCurrent = true
                    errorText = null
                    pipelineResult = null
                    scope.launch {
                        runCatching {
                            runner.close()
                            NativeMediaPipeline.benchmarkNcnn(
                                context = context,
                                uri = video,
                                model = selectedModel,
                                maxFrames = 120,
                                threads = threads,
                                workers = workers,
                                backend = backend
                            )
                        }.onSuccess { pipelineResult = it }
                            .onFailure { errorText = it.message }
                        benchmarkingCurrent = false
                    }
                }
            )
                decodeResult?.let { DecodeBenchmarkCard(it) }
            }
        }
    }
}

private suspend fun loadRepresentativeBenchmarkFrame(
    context: Context,
    uri: Uri
): Bitmap = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val durationUs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.coerceAtLeast(1L)
            ?.times(1_000L)
            ?: 1L
        retriever.getFrameAtTime(
            durationUs / 2L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        ) ?: error("A representative frame could not be decoded from the selected video.")
    } finally {
        retriever.release()
    }
}

@Composable
private fun ModelBenchmarkCard(
    result: BenchmarkResult,
    showDiagnostics: Boolean
) {
    val passesThirtyFps = result.estimatedFps >= 30.0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (passesThirtyFps) Color(0x66315A45) else Color(0x665A4931)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                if (showDiagnostics) {
                    "Selected model result"
                } else {
                    "ONNX CPU throughput"
                },
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EFF9)
            )
            Text(
                if (showDiagnostics) {
                    String.format(
                        Locale.US,
                        "%.1f FPS | median %d ms | p95 %d ms",
                        result.estimatedFps,
                        result.medianMs,
                        result.p95Ms
                    )
                } else {
                    String.format(Locale.US, "%.1f FPS", result.estimatedFps)
                },
                color = if (passesThirtyFps) Color(0xFFA8F0D3) else Color(0xFFFFD8A8)
            )
            if (showDiagnostics) {
                Text(
                    "${result.iterations} measured runs | ${result.inputWidth} x ${result.inputHeight} | ${result.backend}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBDD0E7)
                )
            }
            Text(
                "This is model-path throughput. Use the full-clip test for decoder, tracking, annotation, and encoder throughput together.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
        }
    }
}

@Composable
private fun DebugTandemBenchmarkCard() {
    val context = LocalContext.current
    var fullClip by remember { mutableStateOf(false) }
    var temporalStride by remember { mutableStateOf(1) }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0x663A315E))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Internal pose + temporal benchmark",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE9DFFF)
            )
            Text(
                "DEBUG BUILD - bundled R&D assets",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFD89B)
            )
            Text(
                "Auto-tunes the sequential native decoder + NCNN pose pipeline " +
                    "(detection count 2), then measures tracking, temporal feature construction, " +
                    "and the 32-frame ONNX classifier together. The detector's existing " +
                    "box/keypoint mapping is consumed unchanged.",
                color = Color(0xFFD6E2F1)
            )

            Text("Test length", color = Color(0xFFBDD0E7))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!fullClip) {
                    Button(
                        onClick = { fullClip = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("Quick: 120") }
                } else {
                    OutlinedButton(
                        onClick = { fullClip = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("Quick: 120") }
                }
                if (fullClip) {
                    Button(
                        onClick = { fullClip = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Full: 600") }
                } else {
                    OutlinedButton(
                        onClick = { fullClip = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Full: 600") }
                }
            }

            Text("Temporal stride", color = Color(0xFFBDD0E7))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (temporalStride == 1) {
                    Button(
                        onClick = { temporalStride = 1 },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stride 1") }
                } else {
                    OutlinedButton(
                        onClick = { temporalStride = 1 },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stride 1") }
                }
                if (temporalStride == 2) {
                    Button(
                        onClick = { temporalStride = 2 },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stride 2") }
                } else {
                    OutlinedButton(
                        onClick = { temporalStride = 2 },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stride 2") }
                }
            }
            Text(
                if (temporalStride == 1) {
                    "TCN updates on every frame after the 32-frame warm-up."
                } else {
                    "TCN updates every other frame; the detector still runs on every frame."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
            Button(
                onClick = {
                    context.startActivity(
                        Intent().apply {
                            setClassName(
                                context.packageName,
                                "com.integrapose.mobile.testing.TandemBenchmarkActivity"
                            )
                            putExtra("max_frames", if (fullClip) 600 else 120)
                            putExtra(
                                "classifier_iterations",
                                if (fullClip) 300 else 120
                            )
                            putExtra("temporal_stride", temporalStride)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Auto-tune and run native NCNN + temporal")
            }
            Text(
                "These private model/video assets are absent from release builds.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFD89B)
            )
        }
    }
}

@Composable
private fun AdvancedBenchmarkControls(
    selectedVideo: Uri?,
    selectedModel: InferenceModelConfig,
    supportsNativeNcnn: Boolean,
    controlsBusy: Boolean,
    maximumThreads: Int,
    threads: Int,
    workers: Int,
    backend: NativeNcnnBackend,
    vulkanApproved: Boolean,
    benchmarkingDecode: Boolean,
    benchmarkingCurrent: Boolean,
    onThreadsChange: (Int) -> Unit,
    onWorkersChange: (Int) -> Unit,
    onBackendChange: (NativeNcnnBackend) -> Unit,
    onBenchmarkDecode: (Uri) -> Unit,
    onBenchmarkCurrent: (Uri) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x553A315E))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Advanced checks",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE9DFFF)
            )
            Text(
                "These controls are for diagnostics and are not required for normal offline analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD4C8EA)
            )
            OutlinedButton(
                onClick = { selectedVideo?.let(onBenchmarkDecode) },
                enabled = selectedVideo != null && !controlsBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (benchmarkingDecode) {
                        "Benchmarking decoder..."
                    } else {
                        "Decoder-only diagnostic (no model inference)"
                    }
                )
            }

            if (supportsNativeNcnn && BuildConfig.BUNDLED_TEST_KIT) {
                OutlinedButton(
                    onClick = {
                        onBackendChange(NativeNcnnBackend.CPU)
                        onThreadsChange(
                            if (threads <= 1) maximumThreads else threads - 1
                        )
                    },
                    enabled = !controlsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Threads per worker: $threads")
                }
                OutlinedButton(
                    onClick = {
                        onBackendChange(NativeNcnnBackend.CPU)
                        onWorkersChange(
                            if (workers >= maximumThreads) 1 else workers + 1
                        )
                    },
                    enabled = !controlsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Parallel workers: $workers")
                }
                OutlinedButton(
                    onClick = {
                        if (backend == NativeNcnnBackend.CPU) {
                            onWorkersChange(1)
                            onBackendChange(NativeNcnnBackend.VULKAN)
                        } else {
                            onBackendChange(NativeNcnnBackend.CPU)
                        }
                    },
                    enabled = !controlsBusy &&
                        (vulkanApproved || backend == NativeNcnnBackend.VULKAN),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Backend: ${backend.displayName}")
                }
                if (!vulkanApproved) {
                    Text(
                        "The Vulkan override unlocks only after this model and device pass prediction parity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFDFB2)
                    )
                }
                OutlinedButton(
                    onClick = { selectedVideo?.let(onBenchmarkCurrent) },
                    enabled = selectedVideo != null && !controlsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (benchmarkingCurrent) {
                            "Testing selected configuration..."
                        } else {
                            "Test selected configuration on 120 frames"
                        }
                    )
                }
                Text(
                    "Current diagnostic model: ${selectedModel.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
            }
        }
    }
}

@Composable
private fun DecodeBenchmarkCard(benchmark: NativeDecodeBenchmark) {
    val passed = benchmark.decodeFps >= 30.0
    val compatibilityMode = benchmark.decoderName.isNotBlank()
    val modeLabel = if (compatibilityMode) "Compatibility" else "Hardware"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (passed) Color(0x66315A45) else Color(0x665A3931)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (passed) "$modeLabel decode target passed"
                else "$modeLabel decode target missed",
                fontWeight = FontWeight.SemiBold,
                color = if (passed) Color(0xFFB8F3CB) else Color(0xFFFFC4B8)
            )
            Text(
                "%.1f FPS - %d frames - %d ms".format(
                    benchmark.decodeFps,
                    benchmark.framesDecoded,
                    benchmark.wallTimeMs
                ),
                color = Color(0xFFD6EFE9)
            )
            Text(
                "${benchmark.mimeType} - ${benchmark.width} x ${benchmark.height} - rotation ${benchmark.rotationDegrees} degrees",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
            Text(
                if (compatibilityMode) {
                    "Decoder: ${benchmark.decoderName}"
                } else {
                    "Decoder: Android automatic hardware selection"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
        }
    }
}

@Composable
private fun AutoBenchmarkCard(
    benchmark: NativeNcnnAutoBenchmark,
    showDiagnostics: Boolean
) {
    val recommended = benchmark.recommended
    val streaming = benchmark.recommendedStreaming
    val before = benchmark.deviceBefore
    val after = benchmark.deviceAfter
    val recommendationLabel = if (recommended.usesVulkan) {
        "NCNN Vulkan GPU"
    } else if (showDiagnostics) {
        "NCNN CPU - ${recommended.cpuConfigurationLabel}"
    } else {
        "NCNN CPU"
    }
    val streamingLabel = if (streaming.usesVulkan) {
        "NCNN Vulkan GPU"
    } else if (showDiagnostics) {
        "NCNN CPU - ${streaming.cpuConfigurationLabel}"
    } else {
        "NCNN CPU"
    }
    val cpuGateText = when {
        benchmark.cpuWorkerParity?.passed == true ->
            "Parallel CPU accuracy passed on ${benchmark.cpuWorkerParity.framesCompared} distributed frames."
        benchmark.cpuWorkerParity != null ->
            "Parallel CPU accuracy failed, so those timings were excluded."
        benchmark.cpuWorkerFailure != null ->
            "Parallel CPU was excluded: ${benchmark.cpuWorkerFailure}"
        else ->
            "The fastest eligible CPU setting used one worker; no parallel parity gate was needed."
    }
    val gpuGateText = when {
        benchmark.vulkanParity?.passed == true ->
            "Vulkan accuracy passed on ${benchmark.vulkanParity.framesCompared} distributed frames."
        benchmark.vulkanParity != null ->
            "Vulkan prediction agreement did not pass. Its FPS is still shown for manual selection."
        benchmark.vulkanFailure != null ->
            "Vulkan was excluded: ${benchmark.vulkanFailure}"
        after.systemReportsVulkan ->
            "Android reports Vulkan, but NCNN did not expose a compute device."
        else ->
            "No NCNN Vulkan compute device was detected."
    }
    val backendDecision = when {
        recommended.usesVulkan ->
            "GPU is recommended because it passed prediction agreement and was fastest."
        benchmark.vulkanSample != null &&
            benchmark.vulkanParity?.passed == true ->
            "CPU is recommended because it was faster than the validated GPU path."
        benchmark.vulkanSample != null ->
            "CPU is recommended because GPU prediction agreement did not pass."
        else ->
            "CPU is recommended because no runnable GPU result was available."
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (benchmark.targetMet) {
                Color(0x66315A45)
            } else {
                Color(0x665A4931)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                "Recommended offline: $recommendationLabel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD6EFE9)
            )
            Text(
                "Recommended live/image: $streamingLabel",
                color = Color(0xFFD6EFE9)
            )
            if (showDiagnostics) {
                Text(
                    "Live and image inference use one ordered worker; offline can use " +
                        "multiple frame workers when parity passes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
            }
            Text(
                if (showDiagnostics) {
                    "%.1f short-test pipeline FPS - %.1f aggregate model FPS".format(
                        recommended.pipelineFps,
                        recommended.inferenceFps
                    )
                } else {
                    "%.1f measured pipeline FPS".format(
                        recommended.pipelineFps
                    )
                },
                color = if (benchmark.targetMet) {
                    Color(0xFFB8F3CB)
                } else {
                    Color(0xFFFFDFB2)
                }
            )
            Text(backendDecision, color = Color(0xFFA9D6F5))
            if (showDiagnostics) {
                Text(
                    cpuGateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFDFB2)
                )
                Text(
                    gpuGateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFDFB2)
                )
            }
            if (
                after.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
                (
                    after.thermalStatus > before.thermalStatus &&
                        after.thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT
                    )
            ) {
                Text(
                    "Thermal warning: the phone reached ${after.thermalStatusLabel}. Immediate repeat or next-model benchmarks may be slower; let the device cool before comparing results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFC4B8)
                )
            }
            if (showDiagnostics) {
                Text(
                    before.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
                Text(
                    "Available RAM: ${before.availableMemoryMb} MB - app PSS: ${before.processPssMb} to ${after.processPssMb} MB - thermal: ${before.thermalStatusLabel} to ${after.thermalStatusLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
                if (benchmark.cpuTrialFailures.isNotEmpty()) {
                    Text(
                        "Skipped CPU trial(s): " +
                            benchmark.cpuTrialFailures.joinToString("; "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFC4B8)
                    )
                }
                benchmark.vulkanParity?.let { parity ->
                    Text(
                        "CPU/GPU maximum deltas: confidence %.5f, keypoint confidence %.5f, box %.3f px, keypoint %.3f px".format(
                            parity.maxConfidenceDelta,
                            parity.maxKeypointConfidenceDelta,
                            parity.maxBoxDeltaPx,
                            parity.maxKeypointDeltaPx
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (parity.passed) {
                            Color(0xFFB8F3CB)
                        } else {
                            Color(0xFFFFC4B8)
                        }
                    )
                }

                Text(
                    "Measured configurations (${benchmark.framesPerTrial} frames each)",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFD6EFE9)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Backend", modifier = Modifier.weight(1.2f), color = Color(0xFFA9D6F5))
                    Text("Pipeline", modifier = Modifier.weight(1f), color = Color(0xFFA9D6F5))
                    Text("Model", modifier = Modifier.weight(1f), color = Color(0xFFA9D6F5))
                }
                benchmark.samples
                    .sortedWith(compareBy({ it.usesVulkan }, { it.workers }, { it.threads }))
                    .forEach { sample ->
                        val eligible = benchmark.isEligible(sample)
                        val rowColor = if (eligible) {
                            Color(0xFFD6EFE9)
                        } else {
                            Color(0xFF8D9BAD)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (sample.usesVulkan) {
                                    "Vulkan" +
                                        if (eligible) "" else " (timing only)"
                                } else {
                                    "CPU ${sample.workers}w x ${sample.threads}t" +
                                        if (eligible) "" else " (timing only)"
                                },
                                modifier = Modifier.weight(1.2f),
                                color = rowColor
                            )
                            Text(
                                "%.1f FPS".format(sample.pipelineFps),
                                modifier = Modifier.weight(1f),
                                color = rowColor
                            )
                            Text(
                                "%.1f FPS".format(sample.inferenceFps),
                                modifier = Modifier.weight(1f),
                                color = rowColor
                            )
                        }
                    }
            }
            Text(
                "This short test chooses a configuration. Use the full-clip test below for sustained end-to-end performance.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFDFB2)
            )
        }
    }
}

@Composable
private fun BackendChoiceCard(
    benchmark: NativeNcnnAutoBenchmark,
    modelId: String,
    activeLiveProfile: NcnnExecutionProfile?,
    activeOfflineProfile: NcnnExecutionProfile?,
    enabled: Boolean,
    onLiveProfileSelected: (NcnnExecutionProfile) -> Unit,
    onOfflineProfileSelected: (NcnnExecutionProfile) -> Unit
) {
    var pendingVulkanTarget by remember(benchmark) {
        mutableStateOf<NcnnProfileTarget?>(null)
    }
    val recommendedProfile = benchmark.executionProfileFor(
        modelId,
        NcnnProfileSelection.AUTOMATIC
    )
    val cpuProfile = benchmark.executionProfileFor(
        modelId,
        NcnnProfileSelection.MANUAL_CPU
    )
    val vulkanProfile = benchmark.executionProfileFor(
        modelId,
        NcnnProfileSelection.MANUAL_VULKAN
    )
    val vulkanParity = benchmark.vulkanParity
    val currentLiveLabel = activeLiveProfile
        ?.takeIf { it.modelId == modelId && it.benchmarked }
        ?.let {
            "${profileSelectionLabel(it.selection)} - " +
                it.streamingConfigurationLabel
        }
        ?: "Safe CPU default (no saved benchmark choice)"
    val currentOfflineLabel = activeOfflineProfile
        ?.takeIf { it.modelId == modelId && it.benchmarked }
        ?.let {
            "${profileSelectionLabel(it.selection)} - " +
                it.configurationLabel
        }
        ?: "Safe CPU default (no saved benchmark choice)"

    Card(colors = CardDefaults.cardColors(containerColor = Color(0x66304455))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Choose saved defaults",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EFF9)
            )
            Text(
                "Live/image: $currentLiveLabel",
                color = Color(0xFFA9D6F5)
            )
            Text(
                "Offline: $currentOfflineLabel",
                color = Color(0xFFA9D6F5)
            )
            Text(
                "Live and offline can keep different CPU or Vulkan profiles. Choices are saved for this model and used automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
            Text(
                "CPU: %.1f FPS offline; %.1f FPS live/image.".format(
                    benchmark.recommendedCpu.pipelineFps,
                    benchmark.recommendedStreamingCpu.pipelineFps
                ),
                color = Color(0xFFD6EFE9)
            )
            val cpuParity = benchmark.cpuWorkerParity
            Text(
                when {
                    cpuParity?.passed == true ->
                        "CPU consistency: passed across parallel and single-worker inference."
                    cpuParity != null ->
                        "CPU consistency warning: parallel output differed, so CPU selection uses the single-worker reference."
                    else ->
                        "CPU consistency: selected CPU path is the single-worker reference."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cpuParity?.passed == false) {
                    Color(0xFFFFC4B8)
                } else {
                    Color(0xFFB8F3CB)
                }
            )
            if (cpuParity?.passed == false) {
                Text(
                    "CPU error profile: ${parityErrorProfile(cpuParity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFC4B8)
                )
            }

            val vulkanSample = benchmark.vulkanSample
            Text(
                if (vulkanSample != null) {
                    "Vulkan GPU: %.1f FPS offline/live/image.".format(
                        vulkanSample.pipelineFps
                    )
                } else {
                    "Vulkan GPU: no runnable performance result."
                },
                color = Color(0xFFD6EFE9)
            )
            Text(
                when {
                    vulkanParity?.passed == true ->
                        "GPU prediction agreement: passed."
                    vulkanParity != null ->
                        "GPU prediction agreement: warning. Review the error profile before choosing GPU."
                    benchmark.vulkanFailure != null ->
                        "GPU unavailable: ${benchmark.vulkanFailure}"
                    else ->
                        "GPU unavailable on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (vulkanParity?.passed == true) {
                    Color(0xFFB8F3CB)
                } else {
                    Color(0xFFFFC4B8)
                }
            )
            if (vulkanParity?.passed == false) {
                Text(
                    "GPU error profile: ${parityErrorProfile(vulkanParity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFC4B8)
                )
            }

            Button(
                onClick = {
                    recommendedProfile?.let { profile ->
                        onLiveProfileSelected(profile)
                        onOfflineProfileSelected(profile)
                    }
                },
                enabled = enabled && recommendedProfile != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use both recommendations")
            }
            Text(
                "Live and image inference",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EFF9)
            )
            Text(
                "Recommended: ${recommendedProfile?.streamingConfigurationLabel ?: "unavailable"} at %.1f FPS.".format(
                    benchmark.recommendedStreaming.pipelineFps
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD6EFE9)
            )
            Button(
                onClick = { recommendedProfile?.let(onLiveProfileSelected) },
                enabled = enabled && recommendedProfile != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use live/image recommendation")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { cpuProfile?.let(onLiveProfileSelected) },
                    enabled = enabled && cpuProfile != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Use CPU")
                }
                OutlinedButton(
                    onClick = {
                        if (vulkanParity?.passed == true) {
                            vulkanProfile?.let(onLiveProfileSelected)
                        } else {
                            pendingVulkanTarget = NcnnProfileTarget.LIVE_IMAGE
                        }
                    },
                    enabled = enabled && vulkanProfile != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Use Vulkan")
                }
            }

            Text(
                "Offline inference",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EFF9)
            )
            Text(
                "Recommended: ${recommendedProfile?.configurationLabel ?: "unavailable"} at %.1f FPS.".format(
                    benchmark.recommended.pipelineFps
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD6EFE9)
            )
            Button(
                onClick = { recommendedProfile?.let(onOfflineProfileSelected) },
                enabled = enabled && recommendedProfile != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use offline recommendation")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { cpuProfile?.let(onOfflineProfileSelected) },
                    enabled = enabled && cpuProfile != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Use CPU")
                }
                OutlinedButton(
                    onClick = {
                        if (vulkanParity?.passed == true) {
                            vulkanProfile?.let(onOfflineProfileSelected)
                        } else {
                            pendingVulkanTarget = NcnnProfileTarget.OFFLINE
                        }
                    },
                    enabled = enabled && vulkanProfile != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Use Vulkan")
                }
            }
            Text(
                "Recommendations exclude a backend that failed prediction agreement. Manual Vulkan choices remain available after reviewing the warning, and parity status is recorded in inference CSV output.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
        }
    }

    pendingVulkanTarget?.let { target ->
        val targetDescription = if (target == NcnnProfileTarget.LIVE_IMAGE) {
            "live and image inference"
        } else {
            "offline inference"
        }
        AlertDialog(
            onDismissRequest = { pendingVulkanTarget = null },
            title = { Text("Use Vulkan despite prediction differences?") },
            text = {
                Text(
                    "Vulkan ran successfully, but its predictions differed from the CPU reference. This choice will apply to $targetDescription. " +
                        (vulkanParity?.let(::parityErrorProfile)
                            ?: "No parity profile was available.") +
                        " This may be acceptable for your use case. The manual GPU choice and parity status will be recorded."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingVulkanTarget = null
                        vulkanProfile?.let { profile ->
                            if (target == NcnnProfileTarget.LIVE_IMAGE) {
                                onLiveProfileSelected(profile)
                            } else {
                                onOfflineProfileSelected(profile)
                            }
                        }
                    }
                ) {
                    Text("Use Vulkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingVulkanTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun profileSelectionLabel(selection: NcnnProfileSelection): String = when (selection) {
    NcnnProfileSelection.SAFE_DEFAULT -> "Safe default"
    NcnnProfileSelection.AUTOMATIC -> "Recommended"
    NcnnProfileSelection.MANUAL_CPU -> "CPU"
    NcnnProfileSelection.MANUAL_VULKAN -> "Vulkan GPU"
}

private fun parityErrorProfile(
    parity: com.integrapose.mobile.offline.NativeNcnnParityResult
): String = buildString {
    append("${parity.framesCompared} frames compared; ")
    append("${parity.detectionCountMismatchFrames} detection-count mismatch frame(s); ")
    append("${parity.unmatchedDetections} unmatched detection(s); ")
    append(
        "max confidence %.4f, box %.2f px, keypoint %.2f px, keypoint confidence %.4f."
            .format(
                parity.maxConfidenceDelta,
                parity.maxBoxDeltaPx,
                parity.maxKeypointDeltaPx,
                parity.maxKeypointConfidenceDelta
            )
    )
}

@Composable
private fun PipelineBenchmarkCard(
    benchmark: NativeNcnnPipelineBenchmark,
    showDiagnostics: Boolean
) {
    val frames = benchmark.framesProcessed.coerceAtLeast(1)
    val decodeMs = benchmark.decoderTimeMs.toDouble() / frames
    val prepMs = benchmark.preprocessingTimeMs.toDouble() / frames
    val inferMs = benchmark.inferenceTimeMs.toDouble() / frames
    val postMs = benchmark.postprocessingTimeMs.toDouble() / frames
    val drawMs = benchmark.annotationTimeMs.toDouble() / frames
    val encodeMs = benchmark.encodingTimeMs.toDouble() / frames
    val fullPipeline = benchmark.framesEncoded > 0
    val passed = benchmark.pipelineFps >= 30.0
    val execution = if (benchmark.usesVulkan) {
        "NCNN Vulkan GPU"
    } else if (showDiagnostics) {
        "NCNN CPU - ${benchmark.cpuConfigurationLabel}"
    } else {
        "NCNN CPU"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (passed) Color(0x66315A45) else Color(0x665A3931)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                when {
                    fullPipeline && passed -> "Full-pipeline 30 FPS target passed"
                    fullPipeline -> "Full-pipeline 30 FPS target missed"
                    passed -> "Compute-stage 30 FPS target passed"
                    else -> "Compute-stage 30 FPS target missed"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (passed) Color(0xFFB8F3CB) else Color(0xFFFFC4B8)
            )
            Text(
                if (showDiagnostics) {
                    "%.1f measured pipeline FPS - %.1f aggregate model FPS".format(
                        benchmark.pipelineFps,
                        benchmark.inferenceFps
                    )
                } else {
                    "%.1f end-to-end FPS".format(benchmark.pipelineFps)
                },
                color = Color(0xFFD6EFE9)
            )
            Text(
                if (benchmark.framesRequested == 0) {
                    "${benchmark.framesProcessed} frames (full clip) - $execution" +
                        if (showDiagnostics) " - ${benchmark.wallTimeMs} ms" else ""
                } else {
                    "${benchmark.framesProcessed}/${benchmark.framesRequested} frames - $execution" +
                        if (showDiagnostics) " - ${benchmark.wallTimeMs} ms" else ""
                },
                color = Color(0xFFD6EFE9)
            )
            if (showDiagnostics) {
                Text(
                    "Mean/frame: decode %.2f ms - prepare %.2f ms - infer %.2f ms - postprocess %.2f ms".format(
                        decodeMs,
                        prepMs,
                        inferMs,
                        postMs
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
                if (fullPipeline) {
                    Text(
                        "Draw %.2f ms - encode %.2f ms - %d/%d frames encoded".format(
                            drawMs,
                            encodeMs,
                            benchmark.framesEncoded,
                            benchmark.framesProcessed
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8C8DD)
                    )
                }
            }
            Text(
                "${benchmark.totalDetections} detections - ${benchmark.framesWithDetections}/${benchmark.framesProcessed} frames with detections",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD6EFE9)
            )
            if (showDiagnostics) {
                Text(
                    "${benchmark.backend} - source ${benchmark.sourceWidth} x ${benchmark.sourceHeight} - model input ${benchmark.inputSize} x ${benchmark.inputSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
            }
            Text(
                if (fullPipeline) {
                    "This result includes tracking/CSV callbacks, drawing, H.264 encoding, and MP4 finalization."
                } else {
                    "This diagnostic excludes tracking, CSV, overlays, and MP4 encoding."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFDFB2)
            )
        }
    }
}

@Composable
private fun BenchmarkOutputCard(
    output: OfflineProcessResult,
    onView: (String) -> Unit,
    onShare: (String, String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x553A315E))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Full-clip verification files",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE9DFFF)
            )
            output.annotatedVideoPath?.let { path ->
                Text(
                    File(path).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA8E7D4)
                )
                Button(
                    onClick = { onView(path) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View annotated video")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                output.csvPath?.let { path ->
                    OutlinedButton(
                        onClick = { onShare(path, "text/csv") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share CSV")
                    }
                }
                output.annotatedVideoPath?.let { path ->
                    OutlinedButton(
                        onClick = { onShare(path, "video/mp4") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share video")
                    }
                }
            }
            output.boutCsvPath?.let { path ->
                OutlinedButton(
                    onClick = { onShare(path, "text/csv") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Share detailed behavior bouts")
                }
            }
        }
    }
}
