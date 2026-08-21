package com.integrapose.mobile.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Range
import android.util.Rational
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.integrapose.mobile.BuildConfig
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.ConvertedImageProxyBitmap
import com.integrapose.mobile.inference.OverlayCalibration
import com.integrapose.mobile.inference.OverlayRenderer
import com.integrapose.mobile.inference.OrientedCropGeometry
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.inference.mapToOrientedCrop
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.export.publishVideoToMediaStore
import com.integrapose.mobile.export.shareExport
import com.integrapose.mobile.export.viewExport
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.offline.NcnnExecutionProfile
import com.integrapose.mobile.offline.NativeNcnnBackend
import com.integrapose.mobile.offline.NcnnProfileSelection
import com.integrapose.mobile.offline.RoiEditorDialog
import com.integrapose.mobile.tracking.IoUTracker
import com.integrapose.mobile.tracking.IoUTrackerConfig
import com.integrapose.mobile.ui.AdaptiveAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(markerClass = [TransformExperimental::class])
@Composable
fun LiveInferenceScreen(
    selectedModel: InferenceModelConfig?,
    runner: ModelInferenceRunner,
    ncnnTuning: NcnnRuntimeTuning?,
    ncnnWorkers: Int,
    ncnnVulkanParityPassed: Boolean?,
    annotationStyle: AnnotationStyle,
    trackerConfig: IoUTrackerConfig,
    rawVideoQuality: LiveRawVideoQuality,
    previewQuality: LivePreviewQuality,
    previewRenderer: LivePreviewRenderer,
    overlayRefreshRate: LiveOverlayRefreshRate,
    onRecordingBusyChange: (Boolean) -> Unit,
    onImmersiveModalChange: (Boolean) -> Unit,
    onLiveProfileSelected: (NcnnExecutionProfile) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val targetRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val observedCameraGeometry = LiveCameraGeometry(
        targetRotation = targetRotation,
        isLandscapeViewport = isLandscape
    )
    val activity = remember(context) { context.findActivity() }
    var benchmarkRendererOverride by remember {
        mutableStateOf<LivePreviewRenderer?>(null)
    }
    val effectivePreviewRenderer = if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
        benchmarkRendererOverride ?: previewRenderer
    } else {
        previewRenderer
    }

    var cameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermission = granted
    }

    if (!cameraPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Camera access is off",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFE5ECF8)
            )
            Text(
                text = "Camera access is used only for Live inference. Image, Offline, and Benchmark tools do not need it.",
                color = Color(0xFFD8E2F2)
            )
            Button(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Allow camera")
            }
        }
        return
    }

    if (selectedModel == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text(
                text = "Import and select a compatible model first in the Models tab.",
                color = Color(0xFFD8E2F2)
            )
        }
        return
    }

    val previewView = remember(effectivePreviewRenderer) {
        PreviewView(context).apply {
            implementationMode = when (effectivePreviewRenderer) {
                LivePreviewRenderer.COMPATIBLE ->
                    PreviewView.ImplementationMode.COMPATIBLE
                LivePreviewRenderer.PERFORMANCE ->
                    PreviewView.ImplementationMode.PERFORMANCE
            }
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val annotatedRecorder = remember { AnnotatedVideoRecorder(context) }
    val rawRecorder = remember { RawCameraRecorder(context) }
    val metricsRecorder = remember { LiveMetricsRecorder(context) }
    val annotationTimelineRecorder = remember {
        LiveAnnotationTimelineRecorder(context)
    }
    val annotatedPostProcessor = remember {
        createLiveAnnotatedVideoProcessor(context)
    }
    val liveWorkerPool = remember(runner) {
        if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) {
            LiveInferenceWorkerPool(runner)
        } else {
            null
        }
    }
    val liveBenchmarkCollector = remember { LiveCameraBenchmarkCollector() }
    val liveBenchmarkAnalysisPaused = remember { AtomicBoolean(false) }
    val roiPreviewRequested = remember { AtomicBoolean(false) }
    val calibrationStore = remember { LiveOverlayCalibrationStore(context) }
    val imageTransformFactory = remember {
        ImageProxyTransformFactory().apply {
            // ImageProxy.toBitmap() copies the full, unrotated analysis buffer. Detection and
            // keypoint coordinates therefore remain in the raw buffer coordinate system:
            // neither crop-relative nor rotation-adjusted. PreviewView's target transform must
            // apply the camera rotation exactly once. Declaring rotation on the source too makes
            // CoordinateTransform cancel it and leaves portrait overlays in landscape space.
            setUsingCropRect(false)
            setUsingRotationDegrees(false)
        }
    }

    var latestOverlay by remember { mutableStateOf<LiveOverlayFrame?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isPreparingRecording by remember { mutableStateOf(false) }
    var isFinalizingRecording by remember { mutableStateOf(false) }
    var postProcessProgress by remember {
        mutableStateOf<LivePostProcessProgress?>(null)
    }
    var trackingEnabled by rememberSaveable { mutableStateOf(true) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var showCalibration by rememberSaveable { mutableStateOf(false) }
    var overlayCalibration by remember(lensFacing) {
        mutableStateOf(calibrationStore.load(lensFacing))
    }
    var fillPreview by remember(lensFacing) {
        mutableStateOf(calibrationStore.loadFillPreview(lensFacing))
    }
    var recordingOptions by remember { mutableStateOf(LiveRecordingOptions()) }
    var showRecordingOptions by remember { mutableStateOf(false) }
    var rois by remember { mutableStateOf<List<BehaviorRoi>>(emptyList()) }
    var orientedRois by remember { mutableStateOf<List<BehaviorRoi>>(emptyList()) }
    var recordingRois by remember { mutableStateOf<List<BehaviorRoi>>(emptyList()) }
    var roiPreviewFrame by remember { mutableStateOf<LiveRoiPreviewFrame?>(null) }
    var showRoiEditor by remember { mutableStateOf(false) }
    var rawCaptureArmed by remember { mutableStateOf(false) }
    var cameraGeometrySession by remember {
        mutableStateOf(LiveCameraGeometrySession())
    }
    val cameraGeometry = cameraGeometrySession.effective(observedCameraGeometry)
    var rawVideoCapture by remember {
        mutableStateOf<VideoCapture<Recorder>?>(null)
    }
    var startRequested by remember { mutableStateOf(false) }
    var liveDetectionCount by rememberSaveable(selectedModel.id) {
        mutableIntStateOf(selectedModel.detectionCount)
    }
    var showDetectionCountDialog by remember { mutableStateOf(false) }

    var rawVideoPath by remember { mutableStateOf<String?>(null) }
    var rawMediaUri by remember { mutableStateOf<String?>(null) }
    var annotatedVideoPath by remember { mutableStateOf<String?>(null) }
    var annotatedMediaUri by remember { mutableStateOf<String?>(null) }
    var csvPath by remember { mutableStateOf<String?>(null) }
    var boutCsvPath by remember { mutableStateOf<String?>(null) }
    var roiCsvPath by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isLiveBenchmarking by remember { mutableStateOf(false) }
    var isBenchmarkRecording by remember { mutableStateOf(false) }
    var liveBenchmarkJob by remember { mutableStateOf<Job?>(null) }
    var liveBenchmarkResult by remember {
        mutableStateOf<LiveCameraBenchmarkResult?>(null)
    }
    var pendingLiveBenchmarkProfile by remember {
        mutableStateOf<NcnnExecutionProfile?>(null)
    }
    var benchmarkTuningOverride by remember {
        mutableStateOf<NcnnRuntimeTuning?>(null)
    }
    var benchmarkWorkersOverride by remember { mutableStateOf<Int?>(null) }

    val currentRecordingState by rememberUpdatedState(isRecording)
    val currentFinalizingState by rememberUpdatedState(isFinalizingRecording)
    val currentOptions by rememberUpdatedState(recordingOptions)
    val currentTrackingState by rememberUpdatedState(trackingEnabled)
    val currentAnnotationStyle by rememberUpdatedState(annotationStyle)
    val currentOverlayRefreshRate by rememberUpdatedState(overlayRefreshRate)
    val currentRois by rememberUpdatedState(rois)
    val currentRecordingBusyCallback by rememberUpdatedState(onRecordingBusyChange)
    val currentImmersiveModalCallback by rememberUpdatedState(onImmersiveModalChange)
    val currentLiveProfileSelected by rememberUpdatedState(onLiveProfileSelected)
    val currentInferenceTuning by rememberUpdatedState(
        benchmarkTuningOverride ?: ncnnTuning
    )
    val currentInferenceWorkers by rememberUpdatedState(
        if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) {
            (benchmarkWorkersOverride ?: ncnnWorkers).coerceIn(1, 2)
        } else {
            1
        }
    )
    val liveModel = selectedModel.copy(detectionCount = liveDetectionCount)
    val currentModelConfig by rememberUpdatedState(liveModel)

    LaunchedEffect(selectedModel.id, selectedModel.detectionCount) {
        if (selectedModel.exportMetadata.detectionCountLocked) {
            liveDetectionCount = selectedModel.detectionCount
        }
    }

    fun lockRecordingOrientation() {
        // Snapshot before requesting the Activity lock. rawCaptureArmed may immediately rebind
        // CameraX, and that rebind must not combine values from either side of the configuration
        // transition. See docs/live_preview_fix.md.
        cameraGeometrySession = cameraGeometrySession.lock(observedCameraGeometry)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    fun unlockRecordingOrientation() {
        cameraGeometrySession = cameraGeometrySession.unlock()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    LaunchedEffect(fillPreview) {
        previewView.scaleType = if (fillPreview) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }
    }

    fun beginRecording(capture: VideoCapture<Recorder>? = null) {
        if (isRecording || isFinalizingRecording) return
        val options = recordingOptions
        rawVideoPath = null
        rawMediaUri = null
        annotatedVideoPath = null
        annotatedMediaUri = null
        csvPath = null
        boutCsvPath = null
        roiCsvPath = null
        recordingRois = if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
            orientedRois
        } else {
            rois
        }
        runCatching {
            metricsRecorder.start(
                modelType = selectedModel.type,
                selection = LiveMetricSelection(
                    detectionCsv = options.detectionCsv ||
                        options.classBouts || options.roiVisits,
                    classBouts = options.classBouts,
                    roiVisits = options.roiVisits && rois.isNotEmpty(),
                    assignTrackIds = trackingEnabled ||
                        options.classBouts || options.roiVisits
                ),
                rois = recordingRois,
                boutSettings = options.boutSettings,
                roiSettings = options.roiSettings,
                trackerConfig = trackerConfig
            )
            if (
                BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO &&
                options.annotatedVideo
            ) {
                annotationTimelineRecorder.start()
            }
            if (options.requiresRawMaster()) {
                rawRecorder.start(
                    requireNotNull(capture) {
                        "The raw camera recorder is not ready."
                    }
                )
            }
        }.onSuccess {
            isRecording = true
            isPreparingRecording = false
            currentRecordingBusyCallback(true)
            statusText = when {
                BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO &&
                    options.annotatedVideo ->
                    "Recording the ${rawVideoQuality.displayName} 30 FPS raw master and " +
                        "inference timeline. " +
                        "The annotated derivative will be built after Stop."
                options.rawVideo && options.annotatedVideo ->
                    "Recording ${rawVideoQuality.displayName} raw + annotated video " +
                        "(30 FPS maximum)."
                options.rawVideo ->
                    "Recording ${rawVideoQuality.displayName} raw source video " +
                        "(30 FPS maximum)."
                options.annotatedVideo ->
                    "Recording annotated inference (30 FPS maximum)."
                else -> "Recording inference data at up to 30 FPS. Analytics run after Stop."
            }
        }.onFailure { error ->
            metricsRecorder.close()
            annotationTimelineRecorder.close()
            rawRecorder.close()
            startRequested = false
            rawCaptureArmed = false
            isPreparingRecording = false
            currentRecordingBusyCallback(false)
            unlockRecordingOrientation()
            statusText = error.message ?: "Could not start Live recording."
        }
    }

    LaunchedEffect(startRequested, rawVideoCapture) {
        if (startRequested) {
            val capture = rawVideoCapture ?: return@LaunchedEffect
            startRequested = false
            beginRecording(capture)
        }
    }

    DisposableEffect(
        selectedModel.id,
        lensFacing,
        rawCaptureArmed,
        rawVideoQuality,
        previewQuality,
        previewView,
        ncnnTuning,
        trackerConfig,
        cameraGeometry.targetRotation,
        cameraGeometry.isLandscapeViewport
    ) {
        latestOverlay = null
        val busy = AtomicBoolean(false)
        val tracker = IoUTracker(trackerConfig)
        val trackerLock = Any()
        var inferenceFrameIndex = 0
        var lastPublishedTimestampUs = Long.MIN_VALUE
        var lastOverlayTimestampUs = Long.MIN_VALUE
        val overlayCadence = LiveOverlayCadence()
        var lastPreviewCoordinateMatrix: Matrix? = null
        val cameraProvider = cameraProviderFuture.get()
        val sharedViewPort = ViewPort.Builder(
            if (cameraGeometry.isLandscapeViewport) {
                Rational(16, 9)
            } else {
                Rational(9, 16)
            },
            cameraGeometry.targetRotation
        )
            .setScaleType(ViewPort.FILL_CENTER)
            .build()

        val preview = Preview.Builder()
            .setTargetResolution(Size(previewQuality.width, previewQuality.height))
            .setTargetRotation(cameraGeometry.targetRotation)
            .build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(LIVE_CAMERA_TARGET_RESOLUTION)
            .setTargetRotation(cameraGeometry.targetRotation)
            .build()
        val sessionRawVideoCapture = if (rawCaptureArmed) {
            val requestedQuality = when (rawVideoQuality) {
                LiveRawVideoQuality.HD_720P -> Quality.HD
                LiveRawVideoQuality.SD_480P -> Quality.SD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        requestedQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(requestedQuality)
                    )
                )
                .build()
            VideoCapture.Builder(recorder)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .setTargetFrameRate(
                    Range(
                        AnnotatedVideoRecorder.MAX_RECORDING_FPS,
                        AnnotatedVideoRecorder.MAX_RECORDING_FPS
                    )
                )
                .build()
                .also { it.targetRotation = cameraGeometry.targetRotation }
        } else {
            null
        }

        analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
            // Configuration changes must happen between inference calls. Without this gate,
            // CameraX can immediately lease a just-released worker while the benchmark is
            // waiting for the pool to become idle. On fast camera streams that wait can be
            // unbounded, and changing NCNN tuning underneath an active runner can stall both
            // analysis and the visible preview.
            if (liveBenchmarkAnalysisPaused.get()) {
                imageProxy.close()
                return@setAnalyzer
            }
            val pipelineStartedNs = System.nanoTime()
            val benchmarkToken = liveBenchmarkCollector.onCameraFrame()
            if (currentFinalizingState) {
                liveBenchmarkCollector.onBusyDrop(benchmarkToken)
                imageProxy.close()
                return@setAnalyzer
            }
            val workerLease = liveWorkerPool?.tryAcquire(currentInferenceWorkers)
            val stableWorkerAcquired = if (liveWorkerPool == null) {
                busy.compareAndSet(false, true)
            } else {
                workerLease != null
            }
            if (!stableWorkerAcquired) {
                liveBenchmarkCollector.onBusyDrop(benchmarkToken)
                imageProxy.close()
                return@setAnalyzer
            }
            liveBenchmarkCollector.onAccepted(
                benchmarkToken,
                imageProxy.width,
                imageProxy.height
            )

            val sourceTimestampUs = imageProxy.imageInfo.timestamp / 1_000L
            val sourceBufferWidth = imageProxy.width
            val sourceBufferHeight = imageProxy.height
            val sourceRotationDegrees = imageProxy.imageInfo.rotationDegrees
            val sourceCropRect = Rect(imageProxy.cropRect)
            val logMappingGeometry = BuildConfig.BUNDLED_TEST_KIT &&
                inferenceFrameIndex % LIVE_MAPPING_LOG_INTERVAL_FRAMES == 0
            val analysisTransform = runCatching {
                imageTransformFactory.getOutputTransform(imageProxy)
            }.getOrNull()
            val analysisGeometry = if (logMappingGeometry) {
                "buffer=${imageProxy.width}x${imageProxy.height} " +
                    "crop=${imageProxy.cropRect} " +
                    "rotation=${imageProxy.imageInfo.rotationDegrees} " +
                    "sourceTransformAvailable=${analysisTransform != null}"
            } else {
                null
            }
            val conversionStartedNs = System.nanoTime()
            val convertedBitmap = try {
                if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO && workerLease != null) {
                    workerLease.convert(imageProxy)
                } else {
                    imageProxy.toBitmap()?.let { bitmap ->
                        ConvertedImageProxyBitmap(
                            bitmap = bitmap,
                            recycleAfterUse = true,
                            usedBulkRgbaCopy = false
                        )
                    }
                }
            } catch (error: Throwable) {
                Log.e(LIVE_MAPPING_LOG_TAG, "Live camera RGBA conversion failed", error)
                null
            } finally {
                imageProxy.close()
            }
            val conversionNs = System.nanoTime() - conversionStartedNs

            if (convertedBitmap == null) {
                workerLease?.release() ?: busy.set(false)
                return@setAnalyzer
            }
            val bitmap = convertedBitmap.bitmap
            val tuningForFrame = currentInferenceTuning

            scope.launch(Dispatchers.Default) {
                var measuredInference: FrameInferenceResult? = null
                var publishedForBenchmark = false
                var overlayPublishedForBenchmark = false
                var trackingWriteNs = 0L
                var uiPublishNs = 0L
                try {
                    runCatching {
                    val rawInference = (workerLease?.runner ?: runner).run(
                        bitmap = bitmap,
                        config = currentModelConfig,
                        sourceTimestampUs = sourceTimestampUs,
                        ncnnTuning = tuningForFrame
                    )
                    measuredInference = rawInference
                    val trackingWriteStartedNs = System.nanoTime()
                    val inferencePublication = synchronized(trackerLock) {
                        if (sourceTimestampUs <= lastPublishedTimestampUs) {
                            null
                        } else {
                            lastPublishedTimestampUs = sourceTimestampUs
                            val ordered = if (currentTrackingState) {
                                rawInference.copy(
                                    detections = tracker.update(
                                        rawInference.detections,
                                        inferenceFrameIndex
                                    )
                                )
                            } else {
                                rawInference
                            }
                            inferenceFrameIndex += 1
                            val overlayDue = if (
                                BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE
                            ) {
                                overlayCadence.shouldPublish(
                                    sourceTimestampUs,
                                    currentOverlayRefreshRate.maximumFps
                                )
                            } else {
                                val minimumOverlayIntervalUs =
                                    currentOverlayRefreshRate.minimumIntervalUs
                                val due = minimumOverlayIntervalUs == 0L ||
                                    lastOverlayTimestampUs == Long.MIN_VALUE ||
                                    sourceTimestampUs - lastOverlayTimestampUs >=
                                        minimumOverlayIntervalUs
                                if (due) lastOverlayTimestampUs = sourceTimestampUs
                                due
                            }
                            ordered to overlayDue
                        }
                    }
                    if (inferencePublication == null) return@runCatching
                    val inference = inferencePublication.first
                    val overlayDue = inferencePublication.second
                    val inferenceViewport = if (
                        BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE
                    ) {
                        LiveRoiViewport.fromFrame(
                            sourceWidth = sourceBufferWidth,
                            sourceHeight = sourceBufferHeight,
                            cropRect = sourceCropRect,
                            rotationDegrees = sourceRotationDegrees,
                            mirrorHorizontally = false
                        )
                    } else {
                        null
                    }
                    val frameRois = inferenceViewport?.let { viewport ->
                        currentRois.mapNotNull(viewport::toEditorRoi)
                    } ?: currentRois

                    val roiSnapshot = if (
                        roiPreviewRequested.compareAndSet(true, false)
                    ) {
                        val viewport = LiveRoiViewport.fromFrame(
                            sourceWidth = if (
                                BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE
                            ) sourceBufferWidth else bitmap.width,
                            sourceHeight = if (
                                BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE
                            ) sourceBufferHeight else bitmap.height,
                            cropRect = sourceCropRect,
                            rotationDegrees = sourceRotationDegrees,
                            mirrorHorizontally =
                                lensFacing == CameraSelector.LENS_FACING_FRONT
                        )
                        LiveRoiPreviewFrame(
                            bitmap = OverlayRenderer.renderOrientedCropBitmap(
                                source = bitmap,
                                inference = inference.copy(detections = emptyList()),
                                cropRect = if (
                                    BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE
                                ) {
                                    Rect(0, 0, bitmap.width, bitmap.height)
                                } else {
                                    viewport.cropRect()
                                },
                                rotationDegrees = if (
                                    BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE
                                ) 0 else sourceRotationDegrees,
                                mirrorHorizontally = viewport.mirrorHorizontally
                            ),
                            viewport = viewport
                        )
                    } else {
                        null
                    }

                    if (currentRecordingState) {
                        metricsRecorder.append(rawInference)
                        if (currentOptions.annotatedVideo) {
                            if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) {
                                val safeCrop = Rect(sourceCropRect).apply {
                                    intersect(0, 0, bitmap.width, bitmap.height)
                                    right -= width() % 2
                                    bottom -= height() % 2
                                }
                                if (safeCrop.width() > 0 && safeCrop.height() > 0) {
                                    annotationTimelineRecorder.append(
                                        inference.mapToOrientedCrop(
                                            OrientedCropGeometry(
                                                left = safeCrop.left,
                                                top = safeCrop.top,
                                                right = safeCrop.right,
                                                bottom = safeCrop.bottom,
                                                rotationDegrees = sourceRotationDegrees,
                                                mirrorHorizontally =
                                                    lensFacing ==
                                                        CameraSelector.LENS_FACING_FRONT
                                            )
                                        )
                                    )
                                }
                            } else {
                                val annotated = OverlayRenderer.renderOrientedCropBitmap(
                                    source = bitmap,
                                    inference = inference,
                                    cropRect = sourceCropRect,
                                    rotationDegrees = sourceRotationDegrees,
                                    mirrorHorizontally =
                                        lensFacing == CameraSelector.LENS_FACING_FRONT,
                                    annotationStyle = currentAnnotationStyle,
                                    skeletonConnections = selectedModel.skeletonConnections,
                                    rois = if (currentOptions.drawRoisOnAnnotatedVideo) {
                                        frameRois
                                    } else {
                                        emptyList()
                                    }
                                )
                                try {
                                    if (!annotatedRecorder.isRecording) {
                                        annotatedRecorder.start(
                                            width = annotated.width,
                                            height = annotated.height,
                                            fps = AnnotatedVideoRecorder.MAX_RECORDING_FPS
                                        )
                                    }
                                    annotatedRecorder.enqueueFrame(
                                        annotated,
                                        sourceTimestampUs
                                    )
                                } finally {
                                    annotated.recycle()
                                }
                            }
                        }
                    }

                    val publishToScreen = overlayDue || roiSnapshot != null
                    trackingWriteNs = System.nanoTime() - trackingWriteStartedNs
                    if (publishToScreen) {
                        val uiPublishStartedNs = System.nanoTime()
                        withContext(Dispatchers.Main) {
                        if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
                            orientedRois = frameRois
                        }
                        roiSnapshot?.let { snapshot ->
                            roiPreviewFrame?.bitmap?.recycle()
                            roiPreviewFrame = snapshot
                            showRoiEditor = true
                            statusText =
                                "Live analysis frame captured. Define regions, then save them."
                        }
                        val recordingCoordinateMatrix =
                            if (
                                sessionRawVideoCapture != null &&
                                previewView.width > 0 &&
                                previewView.height > 0
                            ) {
                                runCatching {
                                    val safeCrop = Rect(sourceCropRect).apply {
                                        intersect(0, 0, bitmap.width, bitmap.height)
                                        right -= width() % 2
                                        bottom -= height() % 2
                                    }
                                    val geometry = OrientedCropGeometry(
                                        left = safeCrop.left,
                                        top = safeCrop.top,
                                        right = safeCrop.right,
                                        bottom = safeCrop.bottom,
                                        rotationDegrees = sourceRotationDegrees,
                                        mirrorHorizontally =
                                            lensFacing ==
                                                CameraSelector.LENS_FACING_FRONT
                                    )
                                    Matrix().apply {
                                        setValues(
                                            recordingPreviewMatrixValues(
                                                geometry = geometry,
                                                targetWidth = previewView.width,
                                                targetHeight = previewView.height,
                                                fillTarget =
                                                    previewView.scaleType ==
                                                        PreviewView.ScaleType.FILL_CENTER
                                            )
                                        )
                                    }
                                }.getOrNull()
                            } else {
                                null
                            }
                        val coordinateMatrix = recordingCoordinateMatrix ?: runCatching {
                            val previewTransform = previewView.outputTransform
                                ?: return@runCatching null
                            val sourceTransform = analysisTransform
                                ?: return@runCatching null
                            Matrix().also { matrix ->
                                CoordinateTransform(
                                    sourceTransform,
                                    previewTransform
                                ).transform(matrix)
                            }
                        }.getOrNull()
                        if (analysisGeometry != null) {
                            val previewTransform = previewView.outputTransform
                            Log.d(
                                LIVE_MAPPING_LOG_TAG,
                                "$analysisGeometry bitmap=${bitmap.width}x${bitmap.height} " +
                                    "preview=${previewView.width}x${previewView.height} " +
                                    "scaleType=${previewView.scaleType} " +
                                    "previewTransformAvailable=${previewTransform != null} " +
                                    "coordinateMatrix=$coordinateMatrix"
                            )
                        }
                        coordinateMatrix?.let {
                            lastPreviewCoordinateMatrix = it
                        }
                        // Do not fall back to generic fit-center mapping while CameraX is still
                        // publishing its viewport/rotation matrix. The generic path can look
                        // rotated or edge-shifted on portrait camera streams.
                        lastPreviewCoordinateMatrix?.let { matrix ->
                            latestOverlay = LiveOverlayFrame(
                                inference = inference,
                                coordinateMatrix = matrix
                            )
                            overlayPublishedForBenchmark = true
                        }
                        }
                        uiPublishNs = System.nanoTime() - uiPublishStartedNs
                    }
                    publishedForBenchmark = true
                }.onFailure { throwable ->
                    scope.launch(Dispatchers.Main) {
                        statusText = throwable.message ?: "Inference error"
                    }
                }
                } finally {
                    measuredInference?.let { result ->
                        liveBenchmarkCollector.onCompleted(
                            token = benchmarkToken,
                            result = result,
                            pipelineMs = (
                                (System.nanoTime() - pipelineStartedNs) / 1_000_000L
                                ).coerceAtLeast(0L),
                            published = publishedForBenchmark,
                            overlayPublished = overlayPublishedForBenchmark,
                            usedBulkRgbaCopy = convertedBitmap.usedBulkRgbaCopy,
                            conversionNs = conversionNs,
                            trackingWriteNs = trackingWriteNs,
                            uiPublishNs = uiPublishNs
                        )
                    }
                    if (convertedBitmap.recycleAfterUse && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    workerLease?.release() ?: busy.set(false)
                }
            }
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        cameraProvider.unbindAll()
        var effectDisposed = false
        val bindUseCases = Runnable {
            if (effectDisposed) return@Runnable
            val groupBuilder = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(analysis)
                .setViewPort(sharedViewPort)
            sessionRawVideoCapture?.let(groupBuilder::addUseCase)
            runCatching {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    groupBuilder.build()
                )
            }.onSuccess {
                rawVideoCapture = sessionRawVideoCapture
            }.onFailure {
                statusText = "Failed to bind camera: ${it.message}"
                if (startRequested) {
                    startRequested = false
                    rawCaptureArmed = false
                    isPreparingRecording = false
                    currentRecordingBusyCallback(false)
                    unlockRecordingOrientation()
                }
            }
        }
        previewView.post(bindUseCases)

        onDispose {
            effectDisposed = true
            previewView.removeCallbacks(bindUseCases)
            analysis.clearAnalyzer()
            cameraProvider.unbindAll()
            if (rawVideoCapture === sessionRawVideoCapture) {
                rawVideoCapture = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            liveBenchmarkJob?.cancel()
            liveBenchmarkCollector.cancel()
            metricsRecorder.close()
            annotationTimelineRecorder.close()
            rawRecorder.close()
            roiPreviewFrame?.bitmap?.recycle()
            scope.launch { annotatedRecorder.stop() }
            liveWorkerPool?.let { pool ->
                scope.launch { pool.closeOwnedRunners() }
            }
            currentRecordingBusyCallback(false)
            unlockRecordingOrientation()
            analyzerExecutor.shutdown()
        }
    }

    LaunchedEffect(showRoiEditor) {
        currentImmersiveModalCallback(
            BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE && showRoiEditor
        )
    }

    suspend fun measureLiveCameraConfiguration(
        configuration: LiveCameraBenchmarkConfiguration,
        phaseLabel: String
    ): LiveCameraBenchmarkSample {
        val rendererChanged =
            (benchmarkRendererOverride ?: previewRenderer) != configuration.previewRenderer
        liveBenchmarkAnalysisPaused.set(true)
        liveBenchmarkCollector.cancel()
        try {
            val drainedBeforeChange = withTimeoutOrNull(LIVE_BENCHMARK_IDLE_TIMEOUT_MS) {
                liveWorkerPool?.awaitIdle()
                true
            } == true
            check(drainedBeforeChange) {
                "$phaseLabel could not safely pause the inference workers."
            }

            // Apply the whole candidate only after every runner is idle. Compose may rebind
            // CameraX when the PreviewView renderer changes, so analysis remains gated until
            // the new camera stream has had time to attach.
            benchmarkRendererOverride = configuration.previewRenderer
            benchmarkTuningOverride = configuration.tuning("real Live camera benchmark")
            benchmarkWorkersOverride = configuration.workers
            statusText = "$phaseLabel: warming up ${configuration.label}..."
            delay(
                if (rendererChanged) {
                    LIVE_BENCHMARK_RENDERER_REBIND_MS
                } else {
                    LIVE_BENCHMARK_WARMUP_MS
                }
            )

            // Do not rely on a fixed delay alone. Requiring completed camera inference frames
            // proves that both CameraX and NCNN resumed before the timed 30-frame sample starts.
            val warmupToken = liveBenchmarkCollector.begin(
                configuration,
                targetCompletedFrames = LIVE_BENCHMARK_COMPLETED_WARMUP_FRAMES
            )
            liveBenchmarkAnalysisPaused.set(false)
            val warmedUp = withTimeoutOrNull(LIVE_BENCHMARK_RESUME_TIMEOUT_MS) {
                while (!liveBenchmarkCollector.isComplete(warmupToken)) delay(10L)
                true
            } == true
            check(warmedUp) {
                "$phaseLabel did not receive completed inference frames after the camera transition."
            }

            liveBenchmarkAnalysisPaused.set(true)
            val warmupDrained = withTimeoutOrNull(LIVE_BENCHMARK_IDLE_TIMEOUT_MS) {
                liveWorkerPool?.awaitIdle()
                true
            } == true
            check(warmupDrained) {
                "$phaseLabel could not drain the warm-up frames."
            }
            liveBenchmarkCollector.finish(warmupToken)

            val token = liveBenchmarkCollector.begin(
                configuration,
                targetCompletedFrames = LIVE_BENCHMARK_FRAMES_PER_COMBINATION
            )
            statusText = "$phaseLabel: measuring 30 frames with ${configuration.label}..."
            liveBenchmarkAnalysisPaused.set(false)
            val observedInferenceMs = latestOverlay
                ?.inference
                ?.inferenceMs
                ?.coerceAtLeast(1L)
                ?: 1L
            val combinationTimeoutMs = maxOf(
                LIVE_BENCHMARK_COMBINATION_TIMEOUT_MS,
                observedInferenceMs * LIVE_BENCHMARK_FRAMES_PER_COMBINATION * 2L
            ).coerceAtMost(LIVE_BENCHMARK_MAX_COMBINATION_TIMEOUT_MS)
            val completed = withTimeoutOrNull(combinationTimeoutMs) {
                while (!liveBenchmarkCollector.isComplete(token)) delay(10L)
                true
            } == true
            check(completed) {
                "$phaseLabel did not complete 30 inference frames within the safety timeout."
            }

            liveBenchmarkAnalysisPaused.set(true)
            val measurementDrained = withTimeoutOrNull(LIVE_BENCHMARK_IDLE_TIMEOUT_MS) {
                liveWorkerPool?.awaitIdle()
                true
            } == true
            check(measurementDrained) {
                "$phaseLabel could not drain the measured inference frames."
            }
            return liveBenchmarkCollector.finish(token)
        } catch (error: Throwable) {
            liveBenchmarkCollector.cancel()
            throw error
        } finally {
            liveBenchmarkAnalysisPaused.set(false)
        }
    }

    fun startLiveCameraBenchmark() {
        if (
            !BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO ||
            isRecording || isPreparingRecording || isFinalizingRecording ||
            isLiveBenchmarking || selectedModel.runtime !in setOf(
                ModelRuntime.NCNN_CPU,
                ModelRuntime.NCNN_VULKAN
            )
        ) {
            return
        }
        val candidates = liveCameraBenchmarkCandidates(
            cpuCores = Runtime.getRuntime().availableProcessors(),
            currentTuning = ncnnTuning,
            currentWorkers = ncnnWorkers,
            previewRenderers = if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
                listOf(
                    previewRenderer,
                    *LivePreviewRenderer.entries
                        .filterNot { it == previewRenderer }
                        .toTypedArray()
                )
            } else {
                listOf(previewRenderer)
            }
        )
        liveBenchmarkResult = null
        pendingLiveBenchmarkProfile = null
        isLiveBenchmarking = true
        currentRecordingBusyCallback(true)
        lockRecordingOrientation()
        liveBenchmarkJob = scope.launch {
            var temporaryRawFile: File? = null
            var recorderActive = false
            try {
                val previewSamples = candidates.mapIndexed { index, configuration ->
                    measureLiveCameraConfiguration(
                        configuration,
                        "Preview ${index + 1}/${candidates.size}"
                    )
                }

                val recordingSamples = mutableListOf<LiveCameraBenchmarkSample>()
                val recordingProbes = mutableListOf<LiveCameraRecordingProbe>()
                val rendererGroups = candidates.groupBy { it.previewRenderer }
                var completedRecordingCombinations = 0
                rendererGroups.forEach { (renderer, configurations) ->
                    benchmarkRendererOverride = renderer
                    rawCaptureArmed = true
                    statusText = "Preparing the ${rawVideoQuality.displayName} real CameraX " +
                        "recording benchmark with ${renderer.displayName}..."
                    val capture = withTimeout(LIVE_BENCHMARK_CAPTURE_TIMEOUT_MS) {
                        while (rawVideoCapture == null) delay(25L)
                        requireNotNull(rawVideoCapture)
                    }
                    liveWorkerPool?.awaitIdle()
                    temporaryRawFile = rawRecorder.start(capture)
                    recorderActive = true
                    isBenchmarkRecording = true
                    delay(LIVE_BENCHMARK_RECORDING_STABILIZE_MS)

                    configurations.forEach { configuration ->
                        completedRecordingCombinations += 1
                        recordingSamples += measureLiveCameraConfiguration(
                            configuration,
                            "Recording $completedRecordingCombinations/${candidates.size}"
                        )
                    }
                    val finalizedRaw = rawRecorder.stop()
                    recorderActive = false
                    isBenchmarkRecording = false
                    temporaryRawFile = finalizedRaw ?: temporaryRawFile
                    recordingProbes += withContext(Dispatchers.IO) {
                        probeLiveCameraRecording(
                            requireNotNull(temporaryRawFile) {
                                "The temporary CameraX benchmark recording was unavailable."
                            }
                        ).also { temporaryRawFile?.delete() }
                    }
                    temporaryRawFile = null
                    rawCaptureArmed = false
                    withTimeout(LIVE_BENCHMARK_CAPTURE_TIMEOUT_MS) {
                        while (rawVideoCapture != null) delay(25L)
                    }
                }
                val recordingProbe = recordingProbes.minByOrNull { it.frameRate }
                    ?: error("No CameraX recording probe completed.")
                val result = LiveCameraBenchmarkResult(
                    previewSamples = previewSamples,
                    recordingSamples = recordingSamples.toList(),
                    recordingProbe = recordingProbe
                )
                liveBenchmarkResult = result
                val recommended = result.recommended
                val backend = if (recommended.configuration.useVulkan) {
                    NativeNcnnBackend.VULKAN
                } else {
                    NativeNcnnBackend.CPU
                }
                pendingLiveBenchmarkProfile = NcnnExecutionProfile(
                        modelId = selectedModel.id,
                        threadsPerWorker = recommended.configuration.threadsPerWorker,
                        workers = recommended.configuration.workers,
                        backend = backend,
                        measuredPipelineFps = recommended.publishedFps,
                        benchmarked = true,
                        streamingThreads = recommended.configuration.threadsPerWorker,
                        streamingWorkers = recommended.configuration.workers,
                        streamingBackend = backend,
                        measuredStreamingPipelineFps = recommended.publishedFps,
                        selection = NcnnProfileSelection.AUTOMATIC,
                        vulkanParityPassed = ncnnVulkanParityPassed,
                        livePreviewRendererStorageName =
                            recommended.configuration.previewRenderer.storageName
                    )
                val targetText = if (
                    recommended.publishedFps >= result.recordingProbe.frameRate * 0.98
                ) {
                    "Analysis kept pace with the measured camera rate."
                } else {
                    "The selected model did not reach 30 analysis updates/s on this camera."
                }
                statusText = String.format(
                    Locale.US,
                    "Live camera benchmark complete. Preview %.1f updates/s; " +
                        "%s recording %.1f updates/s; raw camera %.1f FPS. %s " +
                        "Recording pipeline median %d ms, p95 %d ms. Median stages: " +
                        "camera RGBA copy %.1f ms; model %d ms (preprocess %d ms); " +
                        "tracking/writes %.1f ms; UI publication %.1f ms. " +
                        "Busy drops %d/%d (%.1f%%); screen overlay %.1f updates/s; " +
                        "bulk RGBA path %d/%d frames. Likely bottleneck: %s. " +
                        "Analysis buffer %d x %d. Settings: visible preview %s, %s, " +
                        "annotations %s. Recommended %s; review before applying.",
                    result.bestPreview.publishedFps,
                    rawVideoQuality.displayName,
                    recommended.publishedFps,
                    result.recordingProbe.frameRate,
                    targetText,
                    recommended.medianPipelineMs,
                    recommended.p95PipelineMs,
                    recommended.medianConversionMs,
                    recommended.medianModelPipelineMs,
                    recommended.medianPreprocessingMs,
                    recommended.medianTrackingWriteMs,
                    recommended.medianUiPublishMs,
                    recommended.busyDrops,
                    recommended.cameraCallbacks,
                    recommended.busyDropPercent,
                    recommended.overlayFps,
                    recommended.bulkRgbaFrames,
                    recommended.completedFrames,
                    recommended.bottleneckStage,
                    recommended.analysisWidth,
                    recommended.analysisHeight,
                    previewQuality.displayName,
                    recommended.configuration.previewRenderer.displayName,
                    overlayRefreshRate.displayName,
                    recommended.configuration.label
                )
            } catch (timeout: TimeoutCancellationException) {
                statusText =
                    "Live camera benchmark timed out while waiting for CameraX; " +
                        "temporary media removed and the camera restored."
            } catch (cancelled: CancellationException) {
                statusText = "Live camera benchmark cancelled; temporary media removed."
            } catch (error: Throwable) {
                statusText = error.message ?: "Live camera benchmark failed safely."
            } finally {
                withContext(NonCancellable) {
                    liveBenchmarkCollector.cancel()
                    if (recorderActive) {
                        runCatching { rawRecorder.stop() }
                            .getOrNull()
                            ?.let { temporaryRawFile = it }
                    }
                    isBenchmarkRecording = false
                    withContext(Dispatchers.IO) {
                        temporaryRawFile?.delete()
                    }
                    benchmarkTuningOverride = null
                    benchmarkWorkersOverride = null
                    benchmarkRendererOverride = null
                    rawCaptureArmed = false
                    liveBenchmarkAnalysisPaused.set(false)
                    isLiveBenchmarking = false
            currentRecordingBusyCallback(false)
            currentImmersiveModalCallback(false)
            unlockRecordingOrientation()
                    liveBenchmarkJob = null
                }
            }
        }
    }

    fun toggleLiveCameraBenchmark() {
        if (isLiveBenchmarking) {
            liveBenchmarkJob?.cancel()
        } else {
            startLiveCameraBenchmark()
        }
    }

    fun startRecording() {
        if (
            isRecording || isPreparingRecording ||
            isFinalizingRecording || isLiveBenchmarking ||
            !recordingOptions.hasOutput
        ) {
            return
        }
        val effectiveStorageOptions = if (recordingOptions.requiresRawMaster()) {
            recordingOptions.copy(rawVideo = true)
        } else {
            recordingOptions
        }
        val storageBudget = runCatching {
            LiveRecordingStorage.requireStartCapacity(
                context,
                effectiveStorageOptions,
                rawVideoQuality
            )
        }.getOrElse { error ->
            statusText = error.message ?: "Could not verify recording storage."
            return
        }
        lockRecordingOrientation()
        if (recordingOptions.requiresRawMaster()) {
            isPreparingRecording = true
            startRequested = true
            rawCaptureArmed = true
            currentRecordingBusyCallback(true)
            statusText = "Preparing the ${rawVideoQuality.displayName} 30 FPS raw camera " +
                "recorder; " +
                "${LiveRecordingStorage.formatBytes(storageBudget.requiredBytes)} " +
                "disk budget checked."
        } else {
            beginRecording()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        isFinalizingRecording = true
        statusText =
            "Stop requested. Finishing the active inference and saving the session..."
        scope.launch {
            val messages = mutableListOf<String>()
            val options = recordingOptions
            var rawMasterFile: File? = null
            var completedAnnotatedResult: LiveAnnotatedPostProcessResult? = null
            try {
                statusText = "Finalizing recorded video before analytics..."
                if (options.requiresRawMaster()) {
                    runCatching { rawRecorder.stop() }
                        .onSuccess { recordedFile ->
                            if (recordedFile == null) {
                                messages += "No raw camera frames were recorded."
                            } else {
                                rawMasterFile = recordedFile
                                if (options.rawVideo) {
                                    rawVideoPath = recordedFile.absolutePath
                                    runCatching {
                                        publishVideoToMediaStore(context, recordedFile)
                                    }.onSuccess { mediaUri ->
                                        rawMediaUri = mediaUri.toString()
                                        messages += "Raw video saved."
                                    }.onFailure {
                                        messages +=
                                            "Raw video saved in IntegraPose Live; " +
                                            "media-library copy failed."
                                    }
                                }
                            }
                        }
                        .onFailure { error ->
                            messages += (
                                error.message ?: "Could not finish the raw recording."
                                )
                        }
                }

                // CSV/behavior analytics has biological priority and does not depend on the
                // optional annotated derivative. Finalize it concurrently and publish its paths
                // as soon as it completes instead of holding it behind a long video export.
                suspend fun finalizeMetrics() {
                    runCatching {
                        withContext(Dispatchers.IO) { metricsRecorder.stop() }
                    }.onSuccess { metricFiles ->
                        csvPath = metricFiles.detectionCsvPath
                        boutCsvPath = metricFiles.boutCsvPath
                        roiCsvPath = metricFiles.roiCsvPath
                        if (metricFiles.analyzedFrames > 0) {
                            messages += "Post-record analysis used " +
                                "${metricFiles.analyzedFrames} frames at " +
                                String.format(
                                    Locale.US,
                                    "%.1f FPS",
                                    metricFiles.observedFrameRate
                                ) + "; analytics took " +
                                "${metricFiles.analyticsDurationMs} ms."
                        }
                        if (metricFiles.droppedJournalFrames > 0) {
                            messages += "Inference journal dropped " +
                                "${metricFiles.droppedJournalFrames} frame(s); " +
                                "review device throughput."
                        }
                        if (
                            csvPath != null || boutCsvPath != null || roiCsvPath != null
                        ) {
                            messages += "Selected CSV output saved."
                        }
                    }.onFailure { error ->
                        messages += error.message ?: "Could not build behavior analytics."
                    }
                }
                val metricsJob = if (
                    shouldFinalizeMetricsConcurrently(
                        BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO
                    )
                ) {
                    launch { finalizeMetrics() }
                } else {
                    null
                }

                if (options.annotatedVideo) {
                    if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) {
                        val timelineResult = runCatching {
                            annotationTimelineRecorder.stop()
                        }.getOrElse { error ->
                            messages += error.message ?:
                                "Could not finish the annotation timeline."
                            null
                        }
                        val master = rawMasterFile
                        if (timelineResult == null || master == null) {
                            timelineResult?.file?.delete()
                            messages +=
                                "Annotated derivative was not built; its raw master or " +
                                "inference timeline was unavailable."
                        } else {
                            statusText =
                                "Building the 30 FPS annotated derivative from the raw master..."
                            postProcessProgress = LivePostProcessProgress(
                                encodedFrames = 0,
                                sourceFrames = 0
                            )
                            runCatching {
                                try {
                                    annotatedPostProcessor.process(
                                        rawFile = master,
                                        timelineFile = timelineResult,
                                        annotationStyle = annotationStyle,
                                        skeletonConnections =
                                            selectedModel.skeletonConnections,
                                        rois = if (options.drawRoisOnAnnotatedVideo) {
                                        recordingRois
                                        } else {
                                            emptyList()
                                        },
                                        onProgress = { encoded, source ->
                                            postProcessProgress = LivePostProcessProgress(
                                                encodedFrames = encoded,
                                                sourceFrames = source
                                            )
                                            statusText =
                                                "Building 30 FPS annotated derivative: " +
                                                encoded + "/" + source + " frames..."
                                        }
                                    )
                                } finally {
                                    timelineResult.file.delete()
                                }
                            }.onSuccess { result ->
                                completedAnnotatedResult = result
                                annotatedVideoPath = result.file.absolutePath
                                runCatching {
                                    publishVideoToMediaStore(context, result.file)
                                }.onSuccess { mediaUri ->
                                    annotatedMediaUri = mediaUri.toString()
                                    messages += "Annotated video saved."
                                }.onFailure {
                                    messages +=
                                        "Annotated video saved in IntegraPose Live; " +
                                        "media-library copy failed."
                                }
                                messages += "Annotated derivative wrote " +
                                    result.encodedFrames + "/" + result.sourceFrames +
                                    " raw frames at " + result.outputFrameRate +
                                    ".0 FPS using " + result.inferenceSamples +
                                    " inference samples; " +
                                    result.reusedInferenceFrames +
                                    " frames reused the latest available result. " +
                                    "Pipeline: " + result.pipelineName + ". " +
                                    "Postprocessing took " +
                                    String.format(
                                        Locale.US,
                                        "%.1f seconds",
                                        result.processingDurationMs / 1_000.0
                                    ) + " (" + String.format(
                                        Locale.US,
                                        "%.2fx recording duration",
                                        result.processingDurationMs.toDouble() /
                                            result.sourceDurationMs.coerceAtLeast(1L)
                                    ) + ")."
                                result.fallbackReason?.let { reason ->
                                    messages += "Hardware compositor fallback: $reason"
                                }
                                if (timelineResult.droppedSamples > 0) {
                                    messages += "Annotation timeline dropped " +
                                        timelineResult.droppedSamples + " sample(s)."
                                }
                                if (!options.rawVideo) {
                                    master.delete()
                                }
                            }.onFailure { error ->
                                messages += error.message ?:
                                    "Could not build the annotated derivative."
                                if (!options.rawVideo) {
                                    rawVideoPath = master.absolutePath
                                    messages +=
                                        "The raw master was retained in IntegraPose Live " +
                                        "for recovery."
                                }
                            }
                            postProcessProgress = null
                        }
                    } else {
                        runCatching { annotatedRecorder.stop() }
                            .onSuccess { recordingResult ->
                                val recordedFile = recordingResult.file
                                    ?.takeIf { it.isFile && it.length() > 0L }
                                if (recordedFile == null) {
                                    messages +=
                                        "No inferred frames were available for the " +
                                        "annotated video."
                                    return@onSuccess
                                }
                                annotatedVideoPath = recordedFile.absolutePath
                                runCatching {
                                    publishVideoToMediaStore(context, recordedFile)
                                }.onSuccess { mediaUri ->
                                    annotatedMediaUri = mediaUri.toString()
                                    messages += "Annotated video saved."
                                }.onFailure {
                                    messages +=
                                        "Annotated video saved in IntegraPose Live; " +
                                        "media-library copy failed."
                                }
                                messages += "Annotated encoder wrote " +
                                    recordingResult.encodedFrames + "/" +
                                    recordingResult.acceptedFrames +
                                    " accepted frames at " +
                                    String.format(
                                        Locale.US,
                                        "%.1f FPS",
                                        recordingResult.acceptedFrameRate
                                    ) + "."
                                if (recordingResult.queueDroppedFrames > 0) {
                                    messages += "Annotated queue dropped " +
                                        recordingResult.queueDroppedFrames + " frame(s)."
                                }
                            }
                            .onFailure { error ->
                                messages += (
                                    error.message ?:
                                        "Could not finish the annotated recording."
                                    )
                            }
                    }
                }
                if (metricsJob != null) {
                    metricsJob.join()
                } else {
                    // Preserve the established debug/release ordering exactly.
                    finalizeMetrics()
                }
                val allRequestedOutputsSaved =
                    (!options.rawVideo || rawVideoPath != null) &&
                        (!options.annotatedVideo || annotatedVideoPath != null) &&
                        (!options.detectionCsv || csvPath != null) &&
                        (!options.classBouts || boutCsvPath != null) &&
                        (!options.roiVisits || roiCsvPath != null)
                statusText = if (
                    BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO &&
                    allRequestedOutputsSaved
                ) {
                    buildString {
                        append("Recording complete. Selected video and analysis files were saved.")
                        completedAnnotatedResult?.let { result ->
                            append(" All ")
                            append(result.encodedFrames)
                            append(" annotated-video frames were preserved. Processing took ")
                            append(
                                String.format(
                                    Locale.US,
                                    "%.1f seconds.",
                                    result.processingDurationMs / 1_000.0
                                )
                            )
                        }
                    }
                } else {
                    messages.joinToString(" ").ifBlank { "Live session finished." }
                }
                Log.i("IntegraPoseLiveSave", messages.joinToString(" "))
            } finally {
                postProcessProgress = null
                annotationTimelineRecorder.close()
                startRequested = false
                rawCaptureArmed = false
                isPreparingRecording = false
                isFinalizingRecording = false
                currentRecordingBusyCallback(false)
                unlockRecordingOrientation()
            }
        }
    }

    LaunchedEffect(isRecording, recordingOptions.plannedDurationMinutes) {
        if (!isRecording) return@LaunchedEffect
        val durationMs = recordingOptions.plannedDurationMinutes
            .coerceIn(0, LiveRecordingStorage.MAX_PLANNED_MINUTES)
            .takeIf { it > 0 }
            ?.toLong()
            ?.times(60_000L)
        val deadlineMs = durationMs?.let { SystemClock.elapsedRealtime() + it }
        while (isRecording) {
            val remainingMs = deadlineMs?.minus(SystemClock.elapsedRealtime())
            if (remainingMs != null && remainingMs <= 0L) {
                statusText = "Planned recording duration reached; saving the session..."
                stopRecording()
                break
            }
            delay(
                minOf(
                    LiveRecordingStorage.MONITOR_INTERVAL_MS,
                    remainingMs?.coerceAtLeast(1L)
                        ?: LiveRecordingStorage.MONITOR_INTERVAL_MS
                )
            )
            if (
                isRecording &&
                LiveRecordingStorage.availableBytes(context) <
                LiveRecordingStorage.EMERGENCY_FREE_BYTES
            ) {
                statusText = "Device storage is nearly full; stopping safely..."
                stopRecording()
                break
            }
        }
    }

    if (showRecordingOptions) {
        LiveRecordingOptionsDialog(
            modelType = selectedModel.type,
            options = recordingOptions,
            roiCount = rois.size,
            onDefineRois = {
                showRecordingOptions = false
                roiPreviewRequested.set(true)
                statusText = "Capturing the next analysis frame for ROI editing..."
            },
            onDismiss = { showRecordingOptions = false },
            onUseOptions = { selected ->
                recordingOptions = selected
                showRecordingOptions = false
            }
        )
    }

    if (showDetectionCountDialog) {
        LiveDetectionCountDialog(
            currentValue = liveDetectionCount,
            onDismiss = { showDetectionCountDialog = false },
            onConfirm = { count ->
                liveDetectionCount = count
                showDetectionCountDialog = false
                latestOverlay = null
                statusText = "Live detection count set to $count."
            }
        )
    }

    pendingLiveBenchmarkProfile?.let { pendingProfile ->
        val result = liveBenchmarkResult
        val recommended = result?.recommended
        if (recommended != null) {
            AdaptiveAlertDialog(
                onDismissRequest = {
                    pendingLiveBenchmarkProfile = null
                    statusText = "Benchmark recommendation was not applied; the current " +
                        "Live profile remains active."
                },
                title = { Text("Apply recommended Live profile?") },
                text = {
                    Text(
                        String.format(
                            Locale.US,
                            "Winner: %s. Preview %.1f updates/s; %s recording %.1f " +
                                "updates/s; raw camera %.1f FPS. Median %d ms, p95 %d ms; " +
                                "busy drops %.1f%%. Apply and persist this profile for %s?",
                            recommended.configuration.label,
                            result.bestPreview.publishedFps,
                            rawVideoQuality.displayName,
                            recommended.publishedFps,
                            result.recordingProbe.frameRate,
                            recommended.medianPipelineMs,
                            recommended.p95PipelineMs,
                            recommended.busyDropPercent,
                            selectedModel.name
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            currentLiveProfileSelected(pendingProfile)
                            pendingLiveBenchmarkProfile = null
                            statusText = String.format(
                                Locale.US,
                                "Applied %s at %.1f recording updates/s (raw camera %.1f " +
                                    "FPS). This profile will be reused for %s until the " +
                                    "model is deleted or the renderer is manually changed.",
                                recommended.configuration.label,
                                recommended.publishedFps,
                                result.recordingProbe.frameRate,
                                selectedModel.name
                            )
                        }
                    ) {
                        Text("Apply recommended")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            pendingLiveBenchmarkProfile = null
                            statusText = "Benchmark recommendation was not applied; the " +
                                "current Live profile remains active."
                        }
                    ) {
                        Text("Keep current")
                    }
                }
            )
        }
    }

    if (showRoiEditor) {
        roiPreviewFrame?.let { preview ->
            RoiEditorDialog(
                previewBitmap = preview.bitmap,
                existingRois = rois.mapNotNull(preview.viewport::toEditorRoi),
                landscapeCanvasPriority = BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE,
                onDismiss = { showRoiEditor = false },
                onUseRois = { selected ->
                    rois = selected.map(preview.viewport::toSourceRoi)
                    recordingOptions = recordingOptions.copy(
                        roiVisits = selected.isNotEmpty(),
                        drawRoisOnAnnotatedVideo =
                            recordingOptions.drawRoisOnAnnotatedVideo &&
                                selected.isNotEmpty()
                    )
                    showRoiEditor = false
                    statusText = if (selected.isEmpty()) {
                        "Live ROIs cleared."
                    } else {
                        "${selected.size} Live ROI(s) saved."
                    }
                }
            )
        }
    }

    if (showCalibration) {
        LiveOverlayCalibrationDialog(
            cameraName = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                "front camera"
            } else {
                "rear camera"
            },
            automaticMappingActive = latestOverlay?.coordinateMatrix != null,
            fillPreview = fillPreview,
            calibration = overlayCalibration,
            onFillPreviewChange = { fillPreview = it },
            onCalibrationChange = { overlayCalibration = it.sanitized() },
            onSave = {
                calibrationStore.save(lensFacing, overlayCalibration)
                calibrationStore.saveFillPreview(lensFacing, fillPreview)
                statusText = "Preview calibration saved for this camera."
            },
            onReset = {
                calibrationStore.reset(lensFacing)
                overlayCalibration = calibrationStore.defaultFor(lensFacing)
                fillPreview = LiveOverlayCalibrationStore.DEFAULT_FILL_PREVIEW
                statusText = "Preview calibration reset to the recommended camera default."
            },
            onDismiss = { showCalibration = false }
        )
    }

    val latestInference = latestOverlay?.inference
    val detections = latestInference?.detections?.size ?: 0
    val hasSessionDetails = statusText != null || rawVideoPath != null ||
        annotatedVideoPath != null || csvPath != null || boutCsvPath != null ||
        roiCsvPath != null
    val hasSelectableOutputs = rawVideoPath != null || annotatedVideoPath != null ||
        csvPath != null || boutCsvPath != null || roiCsvPath != null
    val hasScrollableSessionDetails = hasSelectableOutputs || liveBenchmarkResult != null
    val portraitScrollState = rememberScrollState()

    val switchCamera = {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }
    val previewContent: @Composable () -> Unit = {
        LivePreviewPanel(
            previewView = previewView,
            overlay = latestOverlay,
            selectedModel = selectedModel,
            lensFacing = lensFacing,
            overlayCalibration = overlayCalibration,
            annotationStyle = annotationStyle,
            latestInference = latestInference,
            detections = detections,
            detectionCount = liveDetectionCount,
            rois = if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
                orientedRois
            } else {
                rois
            },
            isRecording = isRecording || isBenchmarkRecording,
            postProcessProgress = postProcessProgress,
            modifier = Modifier.fillMaxSize()
        )
    }
    val controlsContent: @Composable (Boolean) -> Unit = { vertical ->
        LiveControlPanel(
            vertical = vertical,
            isRecording = isRecording,
            isPreparingRecording = isPreparingRecording,
            isFinalizingRecording = isFinalizingRecording,
            isLiveBenchmarking = isLiveBenchmarking,
            showLiveBenchmark = BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO &&
                selectedModel.runtime in setOf(
                    ModelRuntime.NCNN_CPU,
                    ModelRuntime.NCNN_VULKAN
                ),
            hasRecordingOutput = recordingOptions.hasOutput,
            trackingEnabled = trackingEnabled,
            detectionCount = liveDetectionCount,
            detectionCountEditable = selectedModel.detectionCountIsRuntimeEditable,
            onRecord = { if (isRecording) stopRecording() else startRecording() },
            onSetup = { showRecordingOptions = true },
            onCamera = switchCamera,
            onTracking = { trackingEnabled = !trackingEnabled },
            onAlign = { showCalibration = true },
            onDetectionCount = { showDetectionCountDialog = true },
            onLiveBenchmark = ::toggleLiveCameraBenchmark
        )
    }
    val sessionContent: @Composable (Boolean) -> Unit = { internallyScrollable ->
        if (hasSessionDetails) {
            LiveSessionDetailsCard(
                context = context,
                statusText = statusText,
                rawMediaUri = rawMediaUri,
                annotatedMediaUri = annotatedMediaUri,
                rawVideoPath = rawVideoPath,
                annotatedVideoPath = annotatedVideoPath,
                csvPath = csvPath,
                boutCsvPath = boutCsvPath,
                roiCsvPath = roiCsvPath,
                internallyScrollable = internallyScrollable,
                onError = { statusText = it }
            )
        }
    }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CaptureFrameSlot(
                aspectRatio = 16f / 9f,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) { previewContent() }
            Column(
                modifier = Modifier
                    .widthIn(min = 210.dp, max = 290.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                controlsContent(true)
                sessionContent(false)
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val firstPageHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(
                        state = portraitScrollState,
                        enabled = hasScrollableSessionDetails
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(firstPageHeight)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CaptureFrameSlot(
                        aspectRatio = 9f / 16f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            previewContent()
                            when {
                                hasScrollableSessionDetails &&
                                    portraitScrollState.value < portraitScrollState.maxValue -> {
                                    Card(
                                        onClick = {
                                            scope.launch {
                                                portraitScrollState.animateScrollTo(
                                                    portraitScrollState.maxValue
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color(0xE6172536)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(
                                                horizontal = 12.dp,
                                                vertical = 7.dp
                                            ),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(
                                                text = if (hasSelectableOutputs) {
                                                    "Outputs available"
                                                } else {
                                                    "Benchmark result available"
                                                },
                                                color = Color(0xFFA8F0D3),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Scroll to recording outputs",
                                                tint = Color(0xFFA8F0D3)
                                            )
                                        }
                                    }
                                }

                                !hasScrollableSessionDetails &&
                                    statusText != null &&
                                    postProcessProgress == null -> {
                                    Card(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth(0.94f)
                                            .padding(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color(0xE6172536)
                                        )
                                    ) {
                                        Text(
                                            text = statusText.orEmpty(),
                                            modifier = Modifier.padding(
                                                horizontal = 10.dp,
                                                vertical = 7.dp
                                            ),
                                            color = Color(0xFFFFD2A6),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    controlsContent(false)
                }

                if (hasScrollableSessionDetails) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        sessionContent(false)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureFrameSlot(
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val widthFromHeight = maxHeight * aspectRatio
        val frameWidth = minOf(maxWidth, widthFromHeight)
        val frameHeight = frameWidth / aspectRatio
        Box(
            modifier = Modifier
                .width(frameWidth)
                .height(frameHeight)
        ) {
            content()
        }
    }
}

@Composable
private fun LivePreviewPanel(
    previewView: PreviewView,
    overlay: LiveOverlayFrame?,
    selectedModel: InferenceModelConfig,
    lensFacing: Int,
    overlayCalibration: OverlayCalibration,
    annotationStyle: AnnotationStyle,
    latestInference: FrameInferenceResult?,
    detections: Int,
    detectionCount: Int,
    rois: List<BehaviorRoi>,
    isRecording: Boolean,
    postProcessProgress: LivePostProcessProgress?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        // AndroidView retains the native View at a stable composition position even when the
        // factory lambda captures a different PreviewView. Keying the node makes a renderer
        // benchmark transition actually attach the replacement TextureView/SurfaceView before
        // CameraX posts its bind operation to that PreviewView.
        if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
            key(previewView) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val currentOverlay = overlay ?: return@Canvas
            drawIntoCanvas { canvas ->
                OverlayRenderer.draw(
                    canvas = canvas.nativeCanvas,
                    inference = currentOverlay.inference,
                    targetWidth = size.width,
                    targetHeight = size.height,
                    mirrorX = lensFacing == CameraSelector.LENS_FACING_FRONT,
                    calibration = overlayCalibration,
                    coordinateMatrix = currentOverlay.coordinateMatrix,
                    annotationStyle = annotationStyle,
                    skeletonConnections = selectedModel.skeletonConnections,
                    rois = rois
                )
            }
        }

        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(6.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC101923))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${compactBackendLabel(latestInference?.backend)} | " +
                        "${latestInference?.inferenceMs ?: 0} ms | " +
                        "$detections/$detectionCount detections",
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFB8C8DD),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isRecording) {
                    Text(
                        text = "REC · LOCKED",
                        color = Color(0xFFFF8D8D),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        postProcessProgress?.let { progress ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.94f)
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xEE172536)
                )
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        text = if (progress.sourceFrames > 0) {
                            "Building annotated video: " +
                                progress.encodedFrames + "/" +
                                progress.sourceFrames + " frames"
                        } else {
                            "Preparing annotated video..."
                        },
                        color = Color(0xFFFFD2A6),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (progress.sourceFrames > 0) {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveControlPanel(
    vertical: Boolean,
    isRecording: Boolean,
    isPreparingRecording: Boolean,
    isFinalizingRecording: Boolean,
    isLiveBenchmarking: Boolean,
    showLiveBenchmark: Boolean,
    hasRecordingOutput: Boolean,
    trackingEnabled: Boolean,
    detectionCount: Int,
    detectionCountEditable: Boolean,
    onRecord: () -> Unit,
    onSetup: () -> Unit,
    onCamera: () -> Unit,
    onTracking: () -> Unit,
    onAlign: () -> Unit,
    onDetectionCount: () -> Unit,
    onLiveBenchmark: () -> Unit
) {
    val idle = !isRecording && !isPreparingRecording &&
        !isFinalizingRecording && !isLiveBenchmarking
    val recordLabel = when {
        isFinalizingRecording -> "Saving outputs..."
        isPreparingRecording -> "Preparing..."
        isRecording -> "Stop & save"
        else -> "Start recording"
    }
    val countLabel = "Count $detectionCount"

    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRecord,
                enabled = !isFinalizingRecording &&
                    !isPreparingRecording && !isLiveBenchmarking && hasRecordingOutput,
                modifier = Modifier.fillMaxWidth()
            ) { SingleLineLiveButtonText(recordLabel) }
            if (showLiveBenchmark) {
                OutlinedButton(
                    onClick = onLiveBenchmark,
                    enabled = idle || isLiveBenchmarking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SingleLineLiveButtonText(
                        if (isLiveBenchmarking) "Cancel Live benchmark" else
                            "Benchmark Live camera"
                    )
                }
            }
            OutlinedButton(
                onClick = onSetup,
                enabled = idle,
                modifier = Modifier.fillMaxWidth()
            ) { SingleLineLiveButtonText("Recording setup") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactLiveOutlinedButton(
                    label = "Camera",
                    onClick = onCamera,
                    enabled = idle,
                    modifier = Modifier.weight(1f)
                )
                CompactLiveOutlinedButton(
                    label = if (trackingEnabled) "Tracks on" else "Tracks off",
                    onClick = onTracking,
                    enabled = idle,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactLiveOutlinedButton(
                    label = "Align",
                    onClick = onAlign,
                    enabled = idle,
                    modifier = Modifier.weight(1f)
                )
                CompactLiveOutlinedButton(
                    label = countLabel,
                    onClick = onDetectionCount,
                    enabled = detectionCountEditable && idle,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRecord,
                enabled = !isFinalizingRecording &&
                    !isPreparingRecording && !isLiveBenchmarking && hasRecordingOutput,
                modifier = Modifier.weight(2f)
            ) { SingleLineLiveButtonText(recordLabel) }
            OutlinedButton(
                onClick = onSetup,
                enabled = idle,
                modifier = Modifier.weight(1f)
            ) { SingleLineLiveButtonText("Setup") }
        }
        if (showLiveBenchmark) {
            OutlinedButton(
                onClick = onLiveBenchmark,
                enabled = idle || isLiveBenchmarking,
                modifier = Modifier.fillMaxWidth()
            ) {
                SingleLineLiveButtonText(
                    if (isLiveBenchmarking) "Cancel Live benchmark" else
                        "Benchmark Live camera"
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactLiveOutlinedButton(
                label = "Camera",
                onClick = onCamera,
                enabled = idle,
                modifier = Modifier.weight(1f)
            )
            CompactLiveOutlinedButton(
                label = if (trackingEnabled) "Tracks on" else "Tracks off",
                onClick = onTracking,
                enabled = idle,
                modifier = Modifier.weight(1f)
            )
            CompactLiveOutlinedButton(
                label = "Align",
                onClick = onAlign,
                enabled = idle,
                modifier = Modifier.weight(1f)
            )
            CompactLiveOutlinedButton(
                label = countLabel,
                onClick = onDetectionCount,
                enabled = detectionCountEditable && idle,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CompactLiveOutlinedButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
    ) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun SingleLineLiveButtonText(label: String) {
    Text(
        text = label,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun LiveSessionDetailsCard(
    context: Context,
    statusText: String?,
    rawMediaUri: String?,
    annotatedMediaUri: String?,
    rawVideoPath: String?,
    annotatedVideoPath: String?,
    csvPath: String?,
    boutCsvPath: String?,
    roiCsvPath: String?,
    internallyScrollable: Boolean,
    onError: (String?) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xAA172536))) {
        val contentModifier = if (internallyScrollable) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 170.dp)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = contentModifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            statusText?.let { Text(text = it, color = Color(0xFFFFD2A6)) }
            if (rawMediaUri != null || annotatedMediaUri != null) {
                Text(
                    text = "Device video copies: Movies/IntegraPose Live",
                    color = Color(0xFFA8F0D3),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            rawVideoPath?.let { path ->
                LiveVideoExportRow(
                    title = "Raw source video",
                    path = path,
                    onView = {
                        runCatching { viewExport(context, path, "video/mp4") }
                            .onFailure { onError(it.message) }
                    },
                    onShare = {
                        runCatching { shareExport(context, path, "video/mp4") }
                            .onFailure { onError(it.message) }
                    }
                )
            }
            annotatedVideoPath?.let { path ->
                LiveVideoExportRow(
                    title = "Annotated video",
                    path = path,
                    onView = {
                        runCatching { viewExport(context, path, "video/mp4") }
                            .onFailure { onError(it.message) }
                    },
                    onShare = {
                        runCatching { shareExport(context, path, "video/mp4") }
                            .onFailure { onError(it.message) }
                    }
                )
            }
            csvPath?.let { path ->
                LiveCsvExportRow("Detection + pose CSV", path) {
                    runCatching { shareExport(context, path, "text/csv") }
                        .onFailure { onError(it.message) }
                }
            }
            boutCsvPath?.let { path ->
                LiveCsvExportRow("Detailed behavior bouts CSV", path) {
                    runCatching { shareExport(context, path, "text/csv") }
                        .onFailure { onError(it.message) }
                }
            }
            roiCsvPath?.let { path ->
                LiveCsvExportRow("ROI entry / exit / dwell CSV", path) {
                    runCatching { shareExport(context, path, "text/csv") }
                        .onFailure { onError(it.message) }
                }
            }
        }
    }
}

@Composable
private fun LiveVideoExportRow(
    title: String,
    path: String,
    onView: () -> Unit,
    onShare: () -> Unit
) {
    Text(
        text = "$title: ${File(path).name}",
        color = Color(0xFFA8F0D3),
        style = MaterialTheme.typography.bodyMedium
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(onClick = onView, modifier = Modifier.weight(1f)) {
            Text("View")
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
            Text("Share")
        }
    }
}

@Composable
private fun LiveCsvExportRow(
    title: String,
    path: String,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "$title: ${File(path).name}",
            modifier = Modifier.weight(1f),
            color = Color(0xFFFFDBA7),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(onClick = onShare) {
            Text("Share")
        }
    }
}

private fun compactBackendLabel(backend: String?): String = when {
    backend == null -> "Warming up"
    backend.startsWith("NCNN Vulkan") -> "Vulkan GPU"
    backend.startsWith("NCNN CPU") -> {
        val threads = backend
            .substringAfter('(', "")
            .substringBefore(' ')
            .toIntOrNull()
        if (threads == null) "NCNN CPU" else "NCNN CPU ${threads}t"
    }
    backend.startsWith("ONNX Runtime CPU") -> "ONNX CPU"
    else -> backend.substringBefore(" (")
}

@Composable
private fun LiveDetectionCountDialog(
    currentValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable(currentValue) {
        mutableStateOf(currentValue.toString())
    }
    val parsed = value.toIntOrNull()
    val valid = parsed != null && parsed in 1..5_000

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Live detection count") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose how many detections are retained from each Live frame. " +
                        "Lower values reduce tracking, drawing, CSV, and temporal work."
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit) },
                    label = { Text("Detections retained per frame") },
                    supportingText = {
                        Text("This does not change a model graph that fixed its output count at export.")
                    },
                    isError = value.isNotBlank() && !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = { onConfirm(checkNotNull(parsed)) }
            ) {
                Text("Use count $value")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class LiveOverlayFrame(
    val inference: FrameInferenceResult,
    val coordinateMatrix: Matrix?
)

private data class LivePostProcessProgress(
    val encodedFrames: Int,
    val sourceFrames: Int
) {
    val fraction: Float
        get() = if (sourceFrames > 0) {
            encodedFrames.toFloat().div(sourceFrames.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

private const val LIVE_MAPPING_LOG_TAG = "IntegraPoseLiveMap"
private const val LIVE_MAPPING_LOG_INTERVAL_FRAMES = 30
private const val LIVE_BENCHMARK_WARMUP_MS = 150L
private const val LIVE_BENCHMARK_RENDERER_REBIND_MS = 600L
private const val LIVE_BENCHMARK_COMBINATION_TIMEOUT_MS = 15_000L
private const val LIVE_BENCHMARK_MAX_COMBINATION_TIMEOUT_MS = 60_000L
private const val LIVE_BENCHMARK_IDLE_TIMEOUT_MS = 5_000L
private const val LIVE_BENCHMARK_RESUME_TIMEOUT_MS = 8_000L
private const val LIVE_BENCHMARK_COMPLETED_WARMUP_FRAMES = 2
private const val LIVE_BENCHMARK_RECORDING_STABILIZE_MS = 1_000L
private const val LIVE_BENCHMARK_CAPTURE_TIMEOUT_MS = 15_000L
