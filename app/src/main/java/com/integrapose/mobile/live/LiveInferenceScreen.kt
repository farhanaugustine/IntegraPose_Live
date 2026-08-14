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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.integrapose.mobile.inference.OverlayCalibration
import com.integrapose.mobile.inference.OverlayRenderer
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.export.publishVideoToMediaStore
import com.integrapose.mobile.export.shareExport
import com.integrapose.mobile.export.viewExport
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.offline.RoiEditorDialog
import com.integrapose.mobile.tracking.IoUTracker
import com.integrapose.mobile.tracking.IoUTrackerConfig
import com.integrapose.mobile.ui.AdaptiveAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    annotationStyle: AnnotationStyle,
    trackerConfig: IoUTrackerConfig,
    onRecordingBusyChange: (Boolean) -> Unit
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

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val annotatedRecorder = remember { AnnotatedVideoRecorder(context) }
    val rawRecorder = remember { RawCameraRecorder(context) }
    val metricsRecorder = remember { LiveMetricsRecorder(context) }
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

    val currentRecordingState by rememberUpdatedState(isRecording)
    val currentFinalizingState by rememberUpdatedState(isFinalizingRecording)
    val currentOptions by rememberUpdatedState(recordingOptions)
    val currentTrackingState by rememberUpdatedState(trackingEnabled)
    val currentAnnotationStyle by rememberUpdatedState(annotationStyle)
    val currentRois by rememberUpdatedState(rois)
    val currentRecordingBusyCallback by rememberUpdatedState(onRecordingBusyChange)
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
                rois = rois,
                boutSettings = options.boutSettings,
                roiSettings = options.roiSettings,
                trackerConfig = trackerConfig
            )
            if (options.rawVideo) {
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
                options.rawVideo && options.annotatedVideo ->
                    "Recording raw + annotated video (30 FPS maximum)."
                options.rawVideo -> "Recording raw source video (30 FPS maximum)."
                options.annotatedVideo ->
                    "Recording annotated inference (30 FPS maximum)."
                else -> "Recording inference data at up to 30 FPS. Analytics run after Stop."
            }
        }.onFailure { error ->
            metricsRecorder.close()
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
        ncnnTuning,
        trackerConfig,
        cameraGeometry.targetRotation,
        cameraGeometry.isLandscapeViewport
    ) {
        latestOverlay = null
        val busy = AtomicBoolean(false)
        val tracker = IoUTracker(trackerConfig)
        var inferenceFrameIndex = 0
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
            .setTargetResolution(LIVE_CAMERA_TARGET_RESOLUTION)
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
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
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
            if (currentFinalizingState) {
                imageProxy.close()
                return@setAnalyzer
            }
            if (!busy.compareAndSet(false, true)) {
                imageProxy.close()
                return@setAnalyzer
            }

            val sourceTimestampUs = imageProxy.imageInfo.timestamp / 1_000L
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
            val bitmap: android.graphics.Bitmap? = imageProxy.toBitmap()
            imageProxy.close()

            if (bitmap == null) {
                busy.set(false)
                return@setAnalyzer
            }

            scope.launch(Dispatchers.Default) {
                runCatching {
                    val rawInference = runner.run(
                        bitmap = bitmap,
                        config = currentModelConfig,
                        sourceTimestampUs = sourceTimestampUs,
                        ncnnTuning = ncnnTuning
                    )
                    val inference = if (currentTrackingState) {
                        rawInference.copy(
                            detections = tracker.update(rawInference.detections, inferenceFrameIndex)
                        )
                    } else {
                        rawInference
                    }
                    inferenceFrameIndex += 1

                    val roiSnapshot = if (
                        roiPreviewRequested.compareAndSet(true, false)
                    ) {
                        val viewport = LiveRoiViewport.fromFrame(
                            sourceWidth = bitmap.width,
                            sourceHeight = bitmap.height,
                            cropRect = sourceCropRect,
                            rotationDegrees = sourceRotationDegrees,
                            mirrorHorizontally =
                                lensFacing == CameraSelector.LENS_FACING_FRONT
                        )
                        LiveRoiPreviewFrame(
                            bitmap = OverlayRenderer.renderOrientedCropBitmap(
                                source = bitmap,
                                inference = inference.copy(detections = emptyList()),
                                cropRect = viewport.cropRect(),
                                rotationDegrees = sourceRotationDegrees,
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
                                    currentRois
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

                    withContext(Dispatchers.Main) {
                        roiSnapshot?.let { snapshot ->
                            roiPreviewFrame?.bitmap?.recycle()
                            roiPreviewFrame = snapshot
                            showRoiEditor = true
                            statusText =
                                "Live analysis frame captured. Define regions, then save them."
                        }
                        val coordinateMatrix = runCatching {
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
                        }
                    }
                }.onFailure { throwable ->
                    scope.launch(Dispatchers.Main) {
                        statusText = throwable.message ?: "Inference error"
                    }
                }

                bitmap.recycle()
                busy.set(false)
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
            metricsRecorder.close()
            rawRecorder.close()
            roiPreviewFrame?.bitmap?.recycle()
            scope.launch { annotatedRecorder.stop() }
            currentRecordingBusyCallback(false)
            unlockRecordingOrientation()
            analyzerExecutor.shutdown()
        }
    }

    fun startRecording() {
        if (
            isRecording || isPreparingRecording ||
            isFinalizingRecording || !recordingOptions.hasOutput
        ) {
            return
        }
        val storageBudget = runCatching {
            LiveRecordingStorage.requireStartCapacity(context, recordingOptions)
        }.getOrElse { error ->
            statusText = error.message ?: "Could not verify recording storage."
            return
        }
        lockRecordingOrientation()
        if (recordingOptions.rawVideo) {
            isPreparingRecording = true
            startRequested = true
            rawCaptureArmed = true
            currentRecordingBusyCallback(true)
            statusText = "Preparing the 30 FPS raw camera recorder; " +
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
            try {
                statusText = "Finalizing recorded video before analytics..."
                if (options.rawVideo) {
                    runCatching { rawRecorder.stop() }
                        .onSuccess { recordedFile ->
                            if (recordedFile == null) {
                                messages += "No raw camera frames were recorded."
                            } else {
                                rawVideoPath = recordedFile.absolutePath
                                runCatching {
                                    publishVideoToMediaStore(context, recordedFile)
                                }.onSuccess { mediaUri ->
                                    rawMediaUri = mediaUri.toString()
                                    messages += "Raw video saved."
                                }.onFailure {
                                    messages +=
                                        "Raw video saved in IntegraPose Live; media-library copy failed."
                                }
                            }
                        }
                        .onFailure { error ->
                            messages += (
                                error.message ?: "Could not finish the raw recording."
                                )
                        }
                }

                if (options.annotatedVideo) {
                    runCatching { annotatedRecorder.stop() }
                    .onSuccess { recordingResult ->
                        val recordedFile = recordingResult.file
                            ?.takeIf { it.isFile && it.length() > 0L }
                        if (recordedFile == null) {
                            messages +=
                                "No inferred frames were available for the annotated video."
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
                                "Annotated video saved in IntegraPose Live; media-library copy failed."
                        }
                        messages += "Annotated encoder wrote " +
                            "${recordingResult.encodedFrames}/" +
                            "${recordingResult.acceptedFrames} accepted frames at " +
                            String.format(
                                Locale.US,
                                "%.1f FPS",
                                recordingResult.acceptedFrameRate
                            ) + "."
                        if (recordingResult.queueDroppedFrames > 0) {
                            messages += "Annotated queue dropped " +
                                "${recordingResult.queueDroppedFrames} frame(s)."
                        }
                    }
                    .onFailure { error ->
                        messages += (
                            error.message ?:
                                "Could not finish the annotated recording."
                            )
                    }
                }
                statusText = "Building tracking, bouts, and ROI analytics after recording..."
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
                }.onFailure { error ->
                    messages += error.message ?: "Could not build behavior analytics."
                }
                if (
                    csvPath != null || boutCsvPath != null ||
                    roiCsvPath != null
                ) {
                    messages += "Selected CSV output saved."
                }
                statusText = messages.joinToString(" ")
                    .ifBlank { "Live session finished." }
            } finally {
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

    if (showRoiEditor) {
        roiPreviewFrame?.let { preview ->
            RoiEditorDialog(
                previewBitmap = preview.bitmap,
                existingRois = rois.mapNotNull(preview.viewport::toEditorRoi),
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
            rois = rois,
            isRecording = isRecording,
            modifier = Modifier.fillMaxSize()
        )
    }
    val controlsContent: @Composable (Boolean) -> Unit = { vertical ->
        LiveControlPanel(
            vertical = vertical,
            isRecording = isRecording,
            isPreparingRecording = isPreparingRecording,
            isFinalizingRecording = isFinalizingRecording,
            hasRecordingOutput = recordingOptions.hasOutput,
            trackingEnabled = trackingEnabled,
            detectionCount = liveDetectionCount,
            detectionCountEditable = selectedModel.detectionCountIsRuntimeEditable,
            onRecord = { if (isRecording) stopRecording() else startRecording() },
            onSetup = { showRecordingOptions = true },
            onCamera = switchCamera,
            onTracking = { trackingEnabled = !trackingEnabled },
            onAlign = { showCalibration = true },
            onDetectionCount = { showDetectionCountDialog = true }
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
                        enabled = hasSelectableOutputs
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
                                hasSelectableOutputs &&
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
                                                text = "Outputs available",
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

                                !hasSelectableOutputs && statusText != null -> {
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

                if (hasSelectableOutputs) {
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
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

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
    }
}

@Composable
private fun LiveControlPanel(
    vertical: Boolean,
    isRecording: Boolean,
    isPreparingRecording: Boolean,
    isFinalizingRecording: Boolean,
    hasRecordingOutput: Boolean,
    trackingEnabled: Boolean,
    detectionCount: Int,
    detectionCountEditable: Boolean,
    onRecord: () -> Unit,
    onSetup: () -> Unit,
    onCamera: () -> Unit,
    onTracking: () -> Unit,
    onAlign: () -> Unit,
    onDetectionCount: () -> Unit
) {
    val idle = !isRecording && !isPreparingRecording && !isFinalizingRecording
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
                    !isPreparingRecording && hasRecordingOutput,
                modifier = Modifier.fillMaxWidth()
            ) { SingleLineLiveButtonText(recordLabel) }
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
                    !isPreparingRecording && hasRecordingOutput,
                modifier = Modifier.weight(2f)
            ) { SingleLineLiveButtonText(recordLabel) }
            OutlinedButton(
                onClick = onSetup,
                enabled = idle,
                modifier = Modifier.weight(1f)
            ) { SingleLineLiveButtonText("Setup") }
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

private const val LIVE_MAPPING_LOG_TAG = "IntegraPoseLiveMap"
private const val LIVE_MAPPING_LOG_INTERVAL_FRAMES = 30
