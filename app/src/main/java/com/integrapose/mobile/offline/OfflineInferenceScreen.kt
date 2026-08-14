package com.integrapose.mobile.offline

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.BuildConfig
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.analytics.BoutSettings
import com.integrapose.mobile.analytics.RoiAnalyticsSettings
import com.integrapose.mobile.analytics.RoiAnchorMode
import com.integrapose.mobile.export.shareExport
import com.integrapose.mobile.export.viewExport
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.importing.OpenReadOnlyDocument
import com.integrapose.mobile.importing.StagedVideoSource
import com.integrapose.mobile.media.AnnotationResolution
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.testing.BundledTestAssets
import com.integrapose.mobile.ui.keepFocusedFieldVisible
import com.integrapose.mobile.tracking.IoUTrackerConfig
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun OfflineInferenceScreen(
    selectedModel: InferenceModelConfig?,
    runner: ModelInferenceRunner,
    ncnnProfile: NcnnExecutionProfile?,
    annotationStyle: AnnotationStyle,
    trackerConfig: IoUTrackerConfig,
    onProcessingBusyChange: (Boolean) -> Unit
) {
    if (selectedModel == null) {
        EmptyOfflineState()
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = remember { OfflineProcessor(context) }
    val compatibleNcnnProcessor = remember {
        CompatibleNcnnOfflineProcessor(context)
    }
    val usesNativeNcnn = selectedModel.runtime == ModelRuntime.NCNN_CPU ||
        selectedModel.runtime == ModelRuntime.NCNN_VULKAN
    val safeProfile = remember(selectedModel.id) {
        NcnnExecutionProfile.safeDefault(
            modelId = selectedModel.id,
            cpuCores = Runtime.getRuntime().availableProcessors()
        )
    }
    val executionProfile = ncnnProfile
        ?.takeIf { it.modelId == selectedModel.id }
        ?: safeProfile

    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var running by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<OfflineProcessResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var compatibilityNotice by remember { mutableStateOf<String?>(null) }
    var stopNotice by remember { mutableStateOf<String?>(null) }
    val stopRequested = remember { AtomicBoolean(false) }
    var trackingEnabled by rememberSaveable { mutableStateOf(true) }
    var exportAnnotated by rememberSaveable { mutableStateOf(true) }
    var drawRoisOnAnnotatedVideo by rememberSaveable { mutableStateOf(false) }
    var exportDetectionCsv by rememberSaveable { mutableStateOf(true) }
    var exportBoutSummary by rememberSaveable { mutableStateOf(true) }
    var exportRoiMetrics by rememberSaveable { mutableStateOf(false) }
    var showAnalyticsSettings by rememberSaveable { mutableStateOf(false) }
    var minBoutFramesText by rememberSaveable { mutableStateOf("3") }
    var maxBoutGapFramesText by rememberSaveable { mutableStateOf("5") }
    var roiAnchorModeName by rememberSaveable {
        mutableStateOf(RoiAnchorMode.BOUNDING_BOX_CENTER.name)
    }
    var roiKeypointIndexText by rememberSaveable { mutableStateOf("0") }
    var roiEntryThresholdText by rememberSaveable { mutableStateOf("0.75") }
    var roiExitThresholdText by rememberSaveable { mutableStateOf("0.25") }
    var roiMaxGapFramesText by rememberSaveable { mutableStateOf("5") }
    var roiMinDwellFramesText by rememberSaveable { mutableStateOf("3") }
    var resolution by rememberSaveable { mutableStateOf(AnnotationResolution.default) }
    var rois by remember { mutableStateOf<List<BehaviorRoi>>(emptyList()) }
    var showRoiEditor by remember { mutableStateOf(false) }
    var preparingBundledVideo by remember { mutableStateOf<String?>(null) }
    var preparingImportedVideo by remember { mutableStateOf(false) }
    val controlsBusy = running || preparingBundledVideo != null ||
        preparingImportedVideo

    DisposableEffect(Unit) {
        onDispose {
            stopRequested.set(true)
            onProcessingBusyChange(false)
        }
    }
    val roiAnchorMode = runCatching {
        RoiAnchorMode.valueOf(roiAnchorModeName)
    }.getOrDefault(RoiAnchorMode.BOUNDING_BOX_CENTER)
    val boutSettings = BoutSettings(
        minBoutFrames = minBoutFramesText.toIntOrNull() ?: 3,
        maxGapFrames = maxBoutGapFramesText.toIntOrNull() ?: 5
    ).sanitized()
    val roiSettings = RoiAnalyticsSettings(
        anchorMode = if (selectedModel.type == ModelType.POSE) {
            roiAnchorMode
        } else {
            RoiAnchorMode.BOUNDING_BOX_CENTER
        },
        keypointIndex = roiKeypointIndexText.toIntOrNull() ?: 0,
        entryThreshold = roiEntryThresholdText.toFloatOrNull() ?: 0.75f,
        exitThreshold = roiExitThresholdText.toFloatOrNull() ?: 0.25f,
        maxGapFrames = roiMaxGapFramesText.toIntOrNull() ?: 5,
        minDwellFrames = roiMinDwellFramesText.toIntOrNull() ?: 3
    ).sanitized()
    val requiresTracking =
        exportBoutSummary || (exportRoiMetrics && rois.isNotEmpty())
    val effectiveTracking = trackingEnabled || requiresTracking
    val hasSelectedOutput = exportAnnotated || exportDetectionCsv ||
        exportBoutSummary || (exportRoiMetrics && rois.isNotEmpty())

    fun selectVideo(uri: Uri) {
        selectedVideo = uri
        result = null
        errorText = null
        compatibilityNotice = null
        progress = 0f
        rois = emptyList()
        drawRoisOnAnnotatedVideo = false
    }

    val picker = rememberLauncherForActivityResult(OpenReadOnlyDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                preparingImportedVideo = true
                errorText = null
                try {
                    selectVideo(StagedVideoSource.prepare(context, uri))
                } catch (error: Throwable) {
                    errorText = error.message
                        ?: "The selected video could not be prepared. " +
                            "Refresh the cloud provider and select it again."
                } finally {
                    preparingImportedVideo = false
                }
            }
        }
    }

    if (showRoiEditor) {
        selectedVideo?.let { video ->
            RoiEditorDialog(
                videoUri = video,
                existingRois = rois,
                onDismiss = { showRoiEditor = false },
                onUseRois = { selected ->
                    val previouslyEmpty = rois.isEmpty()
                    rois = selected
                    exportRoiMetrics = selected.isNotEmpty()
                    drawRoisOnAnnotatedVideo = when {
                        selected.isEmpty() -> false
                        previouslyEmpty -> true
                        else -> drawRoisOnAnnotatedVideo
                    }
                    showRoiEditor = false
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Offline Inference",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE5ECF8)
        )
        Text(
            text = "${selectedModel.name} - ${selectedModel.runtime.displayName} | " +
                "detection count ${selectedModel.detectionCount}",
            color = Color(0xFFBDD0E7)
        )

        if (BuildConfig.BUNDLED_TEST_KIT) {
            DebugTandemOfflineCard()
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0x55304455))) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "1. Choose a video",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EFF9)
                )
                Button(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    enabled = !controlsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (preparingImportedVideo) {
                            "Preparing selected video..."
                        } else {
                            "Select video"
                        }
                    )
                }
                Text(
                    "Cloud videos are opened read-only and copied into the app-owned " +
                        "IntegraPose Live disk library before analysis. Inference never runs " +
                        "from a live provider link, and the source is never changed or deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
                if (BuildConfig.BUNDLED_TEST_KIT) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                preparingBundledVideo = "single"
                                errorText = null
                                runCatching {
                                    BundledTestAssets.prepareVideo(context)
                                }.onSuccess(::selectVideo)
                                    .onFailure { error ->
                                        errorText = error.message
                                            ?: "The bundled test video could not be prepared."
                                    }
                                preparingBundledVideo = null
                            }
                        },
                        enabled = !controlsBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (preparingBundledVideo == "single") {
                                "Preparing single-animal video..."
                            } else {
                                "Use single-animal 20-second video"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                preparingBundledVideo = "two_animal"
                                errorText = null
                                runCatching {
                                    BundledTestAssets.prepareTwoAnimalVideo(context)
                                }.onSuccess(::selectVideo)
                                    .onFailure { error ->
                                        errorText = error.message
                                            ?: "The bundled two-animal video could not be prepared."
                                    }
                                preparingBundledVideo = null
                            }
                        },
                        enabled = !controlsBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (preparingBundledVideo == "two_animal") {
                                "Preparing two-animal video..."
                            } else {
                                "Use two-animal MARS 20-second video"
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
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0x55304455))) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "2. Choose outputs",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EFF9)
                )
                SettingToggleRow(
                    title = "Detection + pose CSV",
                    subtitle = "One row per detection with bbox, class, track ID, and kpt1_x/kpt1_y/kpt1_conf columns.",
                    checked = exportDetectionCsv,
                    enabled = !controlsBusy,
                    onCheckedChange = { exportDetectionCsv = it }
                )
                SettingToggleRow(
                    title = "Assign track IDs",
                    subtitle = if (requiresTracking) {
                        "Required by a selected behavior metric."
                    } else {
                        "Optional when only per-frame detections are needed."
                    },
                    checked = effectiveTracking,
                    enabled = !controlsBusy && !requiresTracking,
                    onCheckedChange = { trackingEnabled = it }
                )
                SettingToggleRow(
                    title = "Annotated MP4 video",
                    subtitle = "Predictions drawn over the selected source video.",
                    checked = exportAnnotated,
                    enabled = !controlsBusy,
                    onCheckedChange = { exportAnnotated = it }
                )
                SettingToggleRow(
                    title = "Detailed behavior bouts CSV",
                    subtitle = "Bout counts and durations with explicit observed and gap-filled frame support.",
                    checked = exportBoutSummary,
                    enabled = !controlsBusy,
                    onCheckedChange = { exportBoutSummary = it }
                )
                if (!usesNativeNcnn && exportAnnotated) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Video resolution",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFD5E1F0)
                        )
                        OutlinedButton(
                            onClick = {
                                resolution = if (resolution == AnnotationResolution.HD_720) {
                                    AnnotationResolution.SD_360
                                } else {
                                    AnnotationResolution.HD_720
                                }
                            },
                            enabled = !controlsBusy
                        ) {
                            Text(resolution.displayName)
                        }
                    }
                }
                Text(
                    "Regions of interest (optional)",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EFF9)
                )
                Text(
                    "Draw, move, and resize named rectangles on the first frame. Entry and exit can use a bbox center or a selected keypoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
                OutlinedButton(
                    onClick = { showRoiEditor = true },
                    enabled = selectedVideo != null && !controlsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (rois.isEmpty()) {
                            "Define regions"
                        } else {
                            "Edit ${rois.size} region(s)"
                        }
                    )
                }
                if (rois.isNotEmpty()) {
                    SettingToggleRow(
                        title = "ROI outlines in annotated MP4",
                        subtitle = "Draw the ${rois.size} named region(s) on every annotated frame.",
                        checked = drawRoisOnAnnotatedVideo,
                        enabled = !controlsBusy && exportAnnotated,
                        onCheckedChange = { drawRoisOnAnnotatedVideo = it }
                    )
                    SettingToggleRow(
                        title = "ROI entry / exit / dwell CSV",
                        subtitle = "Hysteresis- and gap-qualified visits for each tracked animal and named region.",
                        checked = exportRoiMetrics,
                        enabled = !controlsBusy,
                        onCheckedChange = { exportRoiMetrics = it }
                    )
                    Text(
                        "Selected: " + rois.joinToString { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA8F0D3)
                    )
                }
                OutlinedButton(
                    onClick = { showAnalyticsSettings = !showAnalyticsSettings },
                    enabled = !controlsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showAnalyticsSettings) {
                            "Hide behavior settings"
                        } else {
                            "Behavior settings"
                        }
                    )
                }
                if (showAnalyticsSettings) {
                    Text(
                        "Bout cleanup",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EFF9)
                    )
                    AnalyticsNumberField(
                        label = "Minimum bout duration (frames)",
                        value = minBoutFramesText,
                        onValueChange = { minBoutFramesText = numericText(it) },
                        supportingText = "Bouts shorter than this are omitted."
                    )
                    AnalyticsNumberField(
                        label = "Maximum frame gap (frames)",
                        value = maxBoutGapFramesText,
                        onValueChange = { maxBoutGapFramesText = numericText(it) },
                        supportingText = "Filled only when the same behavior is observed on both sides."
                    )
                    Text(
                        "ROI transitions",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EFF9)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Entry/exit anchor",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFD5E1F0)
                        )
                        OutlinedButton(
                            onClick = {
                                roiAnchorModeName = if (
                                    roiAnchorMode == RoiAnchorMode.BOUNDING_BOX_CENTER
                                ) {
                                    RoiAnchorMode.KEYPOINT.name
                                } else {
                                    RoiAnchorMode.BOUNDING_BOX_CENTER.name
                                }
                            },
                            enabled = selectedModel.type == ModelType.POSE
                        ) {
                            Text(roiSettings.anchorMode.displayName)
                        }
                    }
                    if (roiSettings.anchorMode == RoiAnchorMode.KEYPOINT) {
                        AnalyticsNumberField(
                            label = "ROI entry keypoint index",
                            value = roiKeypointIndexText,
                            onValueChange = {
                                roiKeypointIndexText = numericText(it)
                            },
                            supportingText = "Zero-based; index 0 corresponds to kpt1 in the CSV."
                        )
                    }
                    AnalyticsNumberField(
                        label = "ROI entry threshold",
                        value = roiEntryThresholdText,
                        onValueChange = {
                            roiEntryThresholdText = decimalText(it)
                        },
                        supportingText = "Bounding-box overlap required to enter; default 0.75."
                    )
                    AnalyticsNumberField(
                        label = "ROI exit threshold",
                        value = roiExitThresholdText,
                        onValueChange = {
                            roiExitThresholdText = decimalText(it)
                        },
                        supportingText = "Stay inside while overlap exceeds this value; default 0.25."
                    )
                    AnalyticsNumberField(
                        label = "Maximum ROI gap (frames)",
                        value = roiMaxGapFramesText,
                        onValueChange = {
                            roiMaxGapFramesText = numericText(it)
                        },
                        supportingText = "Gaps at or below this value are bridged; default 5."
                    )
                    AnalyticsNumberField(
                        label = "Minimum ROI dwell (frames)",
                        value = roiMinDwellFramesText,
                        onValueChange = {
                            roiMinDwellFramesText = numericText(it)
                        },
                        supportingText = "Shorter visits are omitted; default 3."
                    )
                }
            }
        }

        if (usesNativeNcnn) {
            Text(
                text = if (executionProfile.benchmarked) {
                    "Optimized for this session: ${executionProfile.configurationLabel}"
                } else {
                    "Using safe CPU settings. The Benchmark tab can optimize this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
        }

        Button(
            onClick = {
                if (running) {
                    if (!stopping) {
                        stopRequested.set(true)
                        stopping = true
                        stopNotice =
                            "Stopping after the current inference and finalizing partial outputs..."
                    }
                    return@Button
                }
                val video = selectedVideo ?: return@Button
                stopRequested.set(false)
                running = true
                stopping = false
                progress = 0f
                errorText = null
                compatibilityNotice = null
                stopNotice = null
                result = null
                onProcessingBusyChange(true)
                scope.launch {
                    if (usesNativeNcnn) {
                        runCatching {
                            // Live/Image may have left a model session and preprocessing buffers
                            // resident. Release them before allocating the native video pipeline;
                            // the compatibility fallback reloads the runner only if needed.
                            runner.close()
                            compatibleNcnnProcessor.processVideo(
                                uri = video,
                                model = selectedModel,
                                runner = runner,
                                enableTracking = effectiveTracking,
                                exportAnnotatedVideo = exportAnnotated,
                                drawRoisOnAnnotatedVideo =
                                    drawRoisOnAnnotatedVideo && rois.isNotEmpty(),
                                exportDetectionCsv = exportDetectionCsv,
                                exportBoutSummary = exportBoutSummary,
                                exportRoiMetrics =
                                    exportRoiMetrics && rois.isNotEmpty(),
                                annotationResolution = resolution,
                                threads = executionProfile.threadsPerWorker,
                                workers = executionProfile.workers,
                                backend = executionProfile.backend,
                                runtimeAuditLabel =
                                    executionProfile.auditLabelFor(
                                        executionProfile.backend
                                    ),
                                fallbackTuning =
                                    executionProfile.toStreamingRuntimeTuning(),
                                stopSignal = NativeStopSignal {
                                    stopRequested.get()
                                },
                                annotationStyle = annotationStyle,
                                rois = rois,
                                boutSettings = boutSettings,
                                roiSettings = roiSettings,
                                trackerConfig = trackerConfig,
                                onCompatibilityFallback = {
                                    compatibilityNotice =
                                        "Using the compatible Android Bitmap decoder because this device did not expose readable native YUV planes."
                                },
                                onProgress = { value -> progress = value }
                            )
                        }.onSuccess { run ->
                            result = run.output
                            if (stopRequested.get()) {
                                stopNotice =
                                    "Stopped safely. Available partial video and data outputs were finalized."
                            }
                        }.onFailure { error ->
                            errorText = error.message ?: "NCNN offline processing failed."
                        }
                    } else {
                        runCatching {
                            processor.processVideo(
                                uri = video,
                                model = selectedModel,
                                runner = runner,
                                enableTracking = effectiveTracking,
                                exportAnnotatedVideo = exportAnnotated,
                                drawRoisOnAnnotatedVideo =
                                    drawRoisOnAnnotatedVideo && rois.isNotEmpty(),
                                exportDetectionCsv = exportDetectionCsv,
                                exportBoutSummary = exportBoutSummary,
                                exportRoiMetrics =
                                    exportRoiMetrics && rois.isNotEmpty(),
                                annotationResolution = resolution,
                                annotationStyle = annotationStyle,
                                rois = rois,
                                boutSettings = boutSettings,
                                roiSettings = roiSettings,
                                trackerConfig = trackerConfig,
                                shouldStop = { stopRequested.get() },
                                onProgress = { value -> progress = value }
                            )
                        }.onSuccess { output ->
                            result = output
                            if (stopRequested.get()) {
                                stopNotice =
                                    "Stopped safely. Available partial video and data outputs were finalized."
                            }
                        }.onFailure { error ->
                            errorText = error.message ?: "Offline processing failed."
                        }
                    }
                    running = false
                    stopping = false
                    onProcessingBusyChange(false)
                }
            },
            enabled = if (running) {
                !stopping
            } else {
                selectedVideo != null && !controlsBusy && hasSelectedOutput
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    stopping -> "Stopping and saving..."
                    running -> "Stop and save"
                    else -> "Analyze video"
                }
            )
        }
        if (!hasSelectedOutput) {
            Text(
                "Select at least one video or data output.",
                color = Color(0xFFFFD2A6),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (stopping) {
                    "Finishing the current frame and saving partial outputs..."
                } else {
                    "${(progress * 100f).toInt()}% complete"
                },
                color = Color(0xFFC9D6E8)
            )
        }

        stopNotice?.let { message ->
            Text(message, color = Color(0xFFB8F3CB))
        }

        compatibilityNotice?.let { message ->
            Text(message, color = Color(0xFFFFD2A6))
        }

        errorText?.let { message ->
            Text(message, color = Color(0xFFFFB2B2))
        }

        result?.let { output ->
            OfflineResultCard(
                output = output,
                onViewVideo = { path ->
                    runCatching { viewExport(context, path, "video/mp4") }
                        .onFailure { errorText = it.message }
                },
                onShare = { path, mimeType ->
                    runCatching { shareExport(context, path, mimeType) }
                        .onFailure { errorText = it.message }
                }
            )
        }
    }
}

@Composable
private fun EmptyOfflineState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Offline Inference",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE5ECF8)
        )
        Text(
            if (BuildConfig.BUNDLED_TEST_KIT) {
                "Open the private bundled tandem demo below, or select one of your own models for the normal offline workflow."
            } else {
                "Select a model in the Models tab, then return here to analyze a video."
            },
            color = Color(0xFFD8E2F2)
        )
        if (BuildConfig.BUNDLED_TEST_KIT) {
            DebugTandemOfflineCard()
        }
    }
}

@Composable
private fun DebugTandemOfflineCard() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x663A315E))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                "Private temporal offline demo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE9DFFF)
            )
            Text(
                "DEBUG BUILD - no model selection required",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFD89B)
            )
            Text(
                "Runs the bundled 20-second, two-animal clip with the private NCNN pose " +
                    "model (detection count 2) and 32-frame temporal classifier.",
                color = Color(0xFFD6E2F1)
            )
            Text(
                "Creates an annotated MP4 with a group-behavior banner, a temporal " +
                    "probabilities CSV, and a detection + keypoint CSV. You can preview " +
                    "and share all outputs from the result screen.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
            Button(
                onClick = {
                    context.startActivity(
                        Intent().apply {
                            setClassName(
                                context.packageName,
                                "com.integrapose.mobile.testing.TandemOfflineActivity"
                            )
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open NCNN + temporal offline demo")
            }
            Text(
                "This demo uses its own private test assets, not the model selected above.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFD89B)
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFFD5E1F0))
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAEBFD4)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun AnalyticsNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supportingText: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .keepFocusedFieldVisible()
    )
}

private fun numericText(value: String): String =
    value.filter(Char::isDigit).take(6)

private fun decimalText(value: String): String {
    var decimalSeen = false
    return value.filter { character ->
        when {
            character.isDigit() -> true
            character == '.' && !decimalSeen -> {
                decimalSeen = true
                true
            }
            else -> false
        }
    }.take(8)
}

@Composable
private fun OfflineResultCard(
    output: OfflineProcessResult,
    onViewVideo: (String) -> Unit,
    onShare: (String, String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x66315A45))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Analysis complete",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFB8F3CB)
            )
            Text(
                "${output.analyzedFrames} frames analyzed - ${output.totalDetections} detections",
                color = Color(0xFFD6EFE9)
            )
            Text(
                "Source frame rate: ${"%.2f".format(output.sourceFrameRate)} FPS",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C8DD)
            )
            output.annotatedVideoPath?.let { path ->
                Text(
                    "Annotated video: ${File(path).name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA8E7D4)
                )
                Button(
                    onClick = { onViewVideo(path) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View annotated video")
                }
            }
            output.csvPath?.let { path ->
                Text(
                    "Detection data: ${File(path).name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFDFB2)
                )
            }
            output.boutCsvPath?.let { path ->
                Text(
                    "Detailed behavior bouts: ${File(path).name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFDFB2)
                )
            }
            output.roiCsvPath?.let { path ->
                Text(
                    "ROI entry/exit/dwell visits: ${File(path).name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA8F0D3)
                )
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
            output.roiCsvPath?.let { path ->
                OutlinedButton(
                    onClick = { onShare(path, "text/csv") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Share ROI entry/exit/dwell CSV")
                }
            }
        }
    }
}
