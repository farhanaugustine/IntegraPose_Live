package com.integrapose.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.integrapose.mobile.BuildConfig
import com.integrapose.mobile.benchmark.BenchmarkScreen
import com.integrapose.mobile.benchmark.NcnnExecutionProfileStore
import com.integrapose.mobile.benchmark.NcnnProfileTarget
import com.integrapose.mobile.benchmark.ncnnProfileStorageKey
import com.integrapose.mobile.benchmark.rememberBenchmarkSessionState
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.image.ImageInferenceScreen
import com.integrapose.mobile.live.LiveInferenceScreen
import com.integrapose.mobile.live.LivePreviewRenderer
import com.integrapose.mobile.offline.OfflineInferenceScreen
import com.integrapose.mobile.offline.NcnnExecutionProfile
import kotlinx.coroutines.launch

@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val ncnnProfileStore = remember(context) {
        NcnnExecutionProfileStore(context)
    }
    val ncnnProfileKey = if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
        uiState.selectedModel?.let { "${it.id}:annotated_model_lifecycle_v2" }
    } else {
        uiState.selectedModel?.let(::ncnnProfileStorageKey)
    }
    val liveNcnnProfileKey = if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
        ncnnProfileKey?.let { "$it:annotated_live_camera_v2" }
    } else if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) {
        ncnnProfileKey?.let {
            "$it:raw=${uiState.liveRawVideoQuality.storageName}" +
                ":preview=${uiState.livePreviewQuality.storageName}" +
                ":renderer=${uiState.livePreviewRenderer.storageName}" +
                ":overlay=${uiState.liveOverlayRefreshRate.storageName}"
        }
    } else {
        ncnnProfileKey
    }
    var selectedTab by rememberSaveable {
        mutableStateOf(
            if (BuildConfig.START_ON_BENCHMARK) AppTab.BENCHMARK else AppTab.LIVE
        )
    }
    var liveNcnnProfile by remember(liveNcnnProfileKey) {
        mutableStateOf<NcnnExecutionProfile?>(
            liveNcnnProfileKey?.let {
                ncnnProfileStore.load(it, NcnnProfileTarget.LIVE_IMAGE)
            }
        )
    }
    var offlineNcnnProfile by remember(ncnnProfileKey) {
        mutableStateOf<NcnnExecutionProfile?>(
            ncnnProfileKey?.let {
                ncnnProfileStore.load(it, NcnnProfileTarget.OFFLINE)
            }
        )
    }
    var liveRecordingBusy by remember { mutableStateOf(false) }
    var offlineProcessingBusy by remember { mutableStateOf(false) }
    var immersiveLiveModal by remember { mutableStateOf(false) }
    val streamingTuning = liveNcnnProfile
        ?.takeIf { it.modelId == uiState.selectedModel?.id }
        ?.toStreamingRuntimeTuning()
    val effectiveLivePreviewRenderer =
        if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
            liveNcnnProfile
                ?.takeIf { it.modelId == uiState.selectedModel?.id && it.benchmarked }
                ?.livePreviewRendererStorageName
                ?.let(LivePreviewRenderer::fromStoredName)
                ?: uiState.livePreviewRenderer
        } else {
            uiState.livePreviewRenderer
        }
    val benchmarkSessionState = rememberBenchmarkSessionState(
        modelId = uiState.selectedModel?.let {
            "${it.id}:${it.detectionCount}:${it.confThreshold}:${it.iouThreshold}"
        }
    )
    val benchmarkBusy = benchmarkSessionState.isBusy
    val snackbarHostState = remember { SnackbarHostState() }
    val runner = remember { ModelInferenceRunner() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            scope.launch { runner.close() }
        }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!immersiveLiveModal) {
            NavigationBar(containerColor = Color(0xDD101723)) {
                AppTab.entries.forEach { tab ->
                    val navigationEnabled = when {
                        benchmarkBusy -> tab == AppTab.BENCHMARK
                        liveRecordingBusy -> tab == AppTab.LIVE
                        offlineProcessingBusy -> tab == AppTab.OFFLINE
                        else -> true
                    }
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            if (navigationEnabled) {
                                selectedTab = tab
                            }
                        },
                        enabled = navigationEnabled,
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF03070B), Color(0xFF11243C), Color(0xFF2A1722))
                    )
                )
                .padding(padding)
        ) {
            when (selectedTab) {
                AppTab.LIVE -> LiveInferenceScreen(
                    selectedModel = uiState.selectedModel,
                    runner = runner,
                    ncnnTuning = streamingTuning,
                    ncnnWorkers = liveNcnnProfile?.streamingWorkers ?: 1,
                    ncnnVulkanParityPassed = liveNcnnProfile?.vulkanParityPassed,
                    annotationStyle = uiState.annotationStyle,
                    trackerConfig = uiState.trackerConfig,
                    rawVideoQuality = uiState.liveRawVideoQuality,
                    previewQuality = if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) {
                        uiState.livePreviewQuality
                    } else {
                        com.integrapose.mobile.live.LivePreviewQuality.Default
                    },
                    previewRenderer = if (BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO) {
                        effectiveLivePreviewRenderer
                    } else {
                        com.integrapose.mobile.live.LivePreviewRenderer.Default
                    },
                    overlayRefreshRate = if (
                        BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO
                    ) {
                        uiState.liveOverlayRefreshRate
                    } else {
                        com.integrapose.mobile.live.LiveOverlayRefreshRate.FULL
                    },
                    onRecordingBusyChange = { liveRecordingBusy = it },
                    onImmersiveModalChange = { visible ->
                        immersiveLiveModal =
                            BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE && visible
                    },
                    onLiveProfileSelected = { profile ->
                        liveNcnnProfile = profile
                        liveNcnnProfileKey?.let {
                            ncnnProfileStore.save(
                                it,
                                NcnnProfileTarget.LIVE_IMAGE,
                                profile
                            )
                        }
                    }
                )

                AppTab.IMAGE -> ImageInferenceScreen(
                    selectedModel = uiState.selectedModel,
                    runner = runner,
                    ncnnTuning = streamingTuning,
                    annotationStyle = uiState.annotationStyle
                )

                AppTab.OFFLINE -> OfflineInferenceScreen(
                    selectedModel = uiState.selectedModel,
                    runner = runner,
                    ncnnProfile = offlineNcnnProfile,
                    annotationStyle = uiState.annotationStyle,
                    trackerConfig = uiState.trackerConfig,
                    onProcessingBusyChange = { offlineProcessingBusy = it }
                )

                AppTab.BENCHMARK -> BenchmarkScreen(
                    selectedModel = uiState.selectedModel,
                    sessionState = benchmarkSessionState,
                    runner = runner,
                    annotationStyle = uiState.annotationStyle,
                    trackerConfig = uiState.trackerConfig,
                    activeLiveProfile = liveNcnnProfile,
                    activeOfflineProfile = offlineNcnnProfile,
                    onLiveProfileSelected = { profile ->
                        liveNcnnProfile = profile
                        liveNcnnProfileKey?.let {
                            ncnnProfileStore.save(
                                it,
                                NcnnProfileTarget.LIVE_IMAGE,
                                profile
                            )
                        }
                    },
                    onOfflineProfileSelected = { profile ->
                        offlineNcnnProfile = profile
                        ncnnProfileKey?.let {
                            ncnnProfileStore.save(
                                it,
                                NcnnProfileTarget.OFFLINE,
                                profile
                            )
                        }
                    },
                    onOpenModels = { selectedTab = AppTab.MODELS }
                )

                AppTab.MODELS -> ModelsScreen(
                    uiState = uiState,
                    onImportModel = viewModel::importModel,
                    onImportNcnnModel = viewModel::importNcnnModel,
                    onImportBundledOnnx = viewModel::importBundledOnnx,
                    onImportBundledNcnn = viewModel::importBundledNcnn,
                    onImportBundledTwoAnimalNcnn =
                        viewModel::importBundledTwoAnimalNcnn,
                    onSelectModel = viewModel::selectModel,
                    onDetectionCountChange = viewModel::setDetectionCount,
                    onDeleteModel = { modelId ->
                        if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
                            ncnnProfileStore.clearModel(modelId)
                            if (uiState.selectedModel?.id == modelId) {
                                liveNcnnProfile = null
                                offlineNcnnProfile = null
                            }
                        }
                        viewModel.deleteModel(modelId)
                    },
                    onRefresh = viewModel::refreshModels
                )

                AppTab.SETTINGS -> SettingsScreen(
                    style = uiState.annotationStyle,
                    selectedModel = uiState.selectedModel,
                    trackerConfig = uiState.trackerConfig,
                    liveRawVideoQuality = uiState.liveRawVideoQuality,
                    livePreviewQuality = uiState.livePreviewQuality,
                    livePreviewRenderer = effectiveLivePreviewRenderer,
                    liveOverlayRefreshRate = uiState.liveOverlayRefreshRate,
                    onBoundingBoxColorChange = viewModel::setBoundingBoxColor,
                    onKeypointColorChange = viewModel::setKeypointColor,
                    onRoiLabelSizeChange = viewModel::setRoiLabelSize,
                    onShowClassIndexChange = viewModel::setShowClassIndex,
                    onSkeletonConnectionsChange = viewModel::setSkeletonConnections,
                    onTrackerConfigChange = viewModel::setTrackerConfig,
                    onLiveRawVideoQualityChange = viewModel::setLiveRawVideoQuality,
                    onLivePreviewQualityChange = viewModel::setLivePreviewQuality,
                    onLivePreviewRendererChange = { renderer ->
                        if (BuildConfig.MODEL_SCOPED_PIPELINE_AUTOTUNE) {
                            liveNcnnProfileKey?.let {
                                ncnnProfileStore.clear(it, NcnnProfileTarget.LIVE_IMAGE)
                            }
                            liveNcnnProfile = null
                        }
                        viewModel.setLivePreviewRenderer(renderer)
                    },
                    onLiveOverlayRefreshRateChange = viewModel::setLiveOverlayRefreshRate
                )
            }
        }
    }
}
