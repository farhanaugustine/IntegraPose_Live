package com.integrapose.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.integrapose.mobile.data.AppDataStore
import com.integrapose.mobile.data.ModelRepository
import com.integrapose.mobile.inference.AnnotationColorPreset
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.RoiLabelSize
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.model.ModelOutputFormat
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.KeypointConnection
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.model.ModelExportMetadata
import com.integrapose.mobile.testing.BundledTestAssets
import com.integrapose.mobile.tracking.IoUTrackerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MainUiState(
    val models: List<InferenceModelConfig> = emptyList(),
    val selectedModelId: String? = null,
    val annotationStyle: AnnotationStyle = AnnotationStyle.Default,
    val trackerConfig: IoUTrackerConfig = IoUTrackerConfig(),
    val isBusy: Boolean = false,
    val message: String? = null
) {
    val selectedModel: InferenceModelConfig?
        get() = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelRepository = ModelRepository(application)
    private val dataStore = AppDataStore(application)
    private val annotationStyleSaveMutex = Mutex()
    private val trackerConfigSaveMutex = Mutex()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.selectedModelIdFlow.collect { selectedId ->
                _uiState.update { it.copy(selectedModelId = selectedId) }
            }
        }
        viewModelScope.launch {
            dataStore.annotationStyleFlow.collect { style ->
                _uiState.update { it.copy(annotationStyle = style) }
            }
        }
        viewModelScope.launch {
            dataStore.trackerConfigFlow.collect { config ->
                _uiState.update { it.copy(trackerConfig = config) }
            }
        }

        refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch {
            val models = modelRepository.listModels()
            _uiState.update { current ->
                val existingSelection = current.selectedModelId
                val resolvedSelection = when {
                    models.isEmpty() -> null
                    existingSelection != null && models.any { it.id == existingSelection } -> existingSelection
                    else -> models.first().id
                }

                current.copy(
                    models = models,
                    selectedModelId = resolvedSelection,
                    message = null
                )
            }

            if (uiState.value.selectedModelId != null) {
                dataStore.setSelectedModelId(uiState.value.selectedModelId)
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            dataStore.setSelectedModelId(modelId)
            _uiState.update { it.copy(selectedModelId = modelId) }
        }
    }

    fun setBoundingBoxColor(color: AnnotationColorPreset) {
        updateAnnotationStyle(uiState.value.annotationStyle.copy(boundingBoxColor = color))
    }

    fun setKeypointColor(color: AnnotationColorPreset) {
        updateAnnotationStyle(uiState.value.annotationStyle.copy(keypointColor = color))
    }

    fun setRoiLabelSize(size: RoiLabelSize) {
        updateAnnotationStyle(uiState.value.annotationStyle.copy(roiLabelSize = size))
    }

    fun setShowClassIndex(show: Boolean) {
        updateAnnotationStyle(uiState.value.annotationStyle.copy(showClassIndex = show))
    }

    fun setTrackerConfig(config: IoUTrackerConfig) {
        val sanitized = config.sanitized()
        _uiState.update {
            it.copy(
                trackerConfig = sanitized,
                message = "Geometry tracker settings saved."
            )
        }
        viewModelScope.launch {
            trackerConfigSaveMutex.withLock {
                dataStore.setTrackerConfig(sanitized)
            }
        }
    }

    fun setSkeletonConnections(modelId: String, connections: List<KeypointConnection>) {
        viewModelScope.launch {
            runCatching {
                modelRepository.updateSkeletonConnections(modelId, connections)
            }.onSuccess { updatedModel ->
                _uiState.update { state ->
                    state.copy(
                        models = state.models.map {
                            if (it.id == updatedModel.id) updatedModel else it
                        },
                        message = if (connections.isEmpty()) {
                            "Skeleton cleared; pose output will show keypoints only."
                        } else {
                            "Skeleton saved with ${connections.size} connection(s)."
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "Could not save the skeleton.")
                }
            }
        }
    }

    fun setDetectionCount(modelId: String, detectionCount: Int) {
        viewModelScope.launch {
            runCatching {
                modelRepository.updateDetectionCount(modelId, detectionCount)
            }.onSuccess { updatedModel ->
                _uiState.update { state ->
                    state.copy(
                        models = state.models.map {
                            if (it.id == updatedModel.id) updatedModel else it
                        },
                        message = "Detection count set to $detectionCount."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        message = error.message ?: "Could not update the detection count."
                    )
                }
            }
        }
    }

    private fun updateAnnotationStyle(style: AnnotationStyle) {
        _uiState.update { it.copy(annotationStyle = style) }
        viewModelScope.launch {
            annotationStyleSaveMutex.withLock {
                dataStore.setAnnotationStyle(uiState.value.annotationStyle)
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            runCatching { modelRepository.deleteModel(modelId) }
                .onFailure { throwable ->
                    _uiState.update { it.copy(message = throwable.message ?: "Failed to delete model") }
                }

            val models = modelRepository.listModels()
            val nextSelection = models.firstOrNull()?.id
            dataStore.setSelectedModelId(nextSelection)

            _uiState.update {
                it.copy(
                    models = models,
                    selectedModelId = nextSelection,
                    isBusy = false
                )
            }
        }
    }

    fun importModel(
        modelUri: Uri,
        name: String,
        type: ModelType,
        inputSize: Int,
        confThreshold: Float,
        iouThreshold: Float,
        classNames: List<String>,
        detectionCount: Int,
        outputFormat: ModelOutputFormat,
        exportMetadata: ModelExportMetadata
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            try {
                val added = modelRepository.addModel(
                    sourceUri = modelUri,
                    name = name,
                    type = type,
                    inputSize = inputSize,
                    confThreshold = confThreshold,
                    iouThreshold = iouThreshold,
                    classNames = classNames,
                    detectionCount = detectionCount,
                    outputFormat = outputFormat,
                    exportMetadata = exportMetadata
                )
                dataStore.setSelectedModelId(added.id)
                val models = modelRepository.listModels()
                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = added.id,
                        message = "Model imported successfully"
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        message = error.message ?: "Failed to import model"
                    )
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun importNcnnModel(
        sourceTreeUri: Uri,
        name: String,
        type: ModelType,
        inputSize: Int,
        confThreshold: Float,
        iouThreshold: Float,
        classNames: List<String>,
        detectionCount: Int,
        outputFormat: ModelOutputFormat,
        exportMetadata: ModelExportMetadata
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            try {
                val added = modelRepository.addNcnnModel(
                    sourceTreeUri = sourceTreeUri,
                    name = name,
                    type = type,
                    inputSize = inputSize,
                    confThreshold = confThreshold,
                    iouThreshold = iouThreshold,
                    classNames = classNames,
                    detectionCount = detectionCount,
                    outputFormat = outputFormat,
                    exportMetadata = exportMetadata,
                    useVulkan = false
                )
                dataStore.setSelectedModelId(added.id)
                _uiState.update {
                    it.copy(
                        models = modelRepository.listModels(),
                        selectedModelId = added.id,
                        message = "NCNN model imported successfully"
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        message = error.message ?: "Failed to import NCNN package"
                    )
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun importBundledNcnn() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            try {
                val existing = modelRepository.listModels().firstOrNull { model ->
                    model.runtime == ModelRuntime.NCNN_CPU &&
                        model.name == BUNDLED_NCNN_NAME
                }
                val model = existing ?: modelRepository.addNcnnModel(
                    sourceDirectory = BundledTestAssets.prepareNcnnModel(getApplication()),
                    name = BUNDLED_NCNN_NAME,
                    type = ModelType.POSE,
                    inputSize = 640,
                    confThreshold = 0.25f,
                    iouThreshold = 0.45f,
                    classNames = listOf("mouse"),
                    useVulkan = false
                )
                dataStore.setSelectedModelId(model.id)
                val models = modelRepository.listModels()
                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = model.id,
                        isBusy = false,
                        message = if (existing == null) {
                            "Bundled NCNN CPU model installed and selected"
                        } else {
                            "Bundled NCNN CPU model selected"
                        }
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Failed to install bundled NCNN model"
                    )
                }
            }
        }
    }

    fun importBundledTwoAnimalNcnn() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            try {
                val existing = modelRepository.listModels().firstOrNull { model ->
                    model.runtime == ModelRuntime.NCNN_CPU &&
                        model.name == BUNDLED_TWO_ANIMAL_NCNN_NAME
                }
                val model = existing ?: modelRepository.addNcnnModel(
                    sourceDirectory = BundledTestAssets.prepareTwoAnimalNcnnModel(
                        getApplication()
                    ),
                    name = BUNDLED_TWO_ANIMAL_NCNN_NAME,
                    type = ModelType.POSE,
                    inputSize = 640,
                    confThreshold = 0.25f,
                    iouThreshold = 0.45f,
                    classNames = listOf("investigation", "mount", "attack"),
                    useVulkan = false,
                    detectionCount = 2
                )
                dataStore.setSelectedModelId(model.id)
                val models = modelRepository.listModels()
                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = model.id,
                        isBusy = false,
                        message = if (existing == null) {
                            "Bundled two-animal NCNN model installed and selected"
                        } else {
                            "Bundled two-animal NCNN model selected"
                        }
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message
                            ?: "Failed to install bundled two-animal NCNN model"
                    )
                }
            }
        }
    }

    fun importBundledOnnx() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            try {
                val existing = modelRepository.listModels().firstOrNull { model ->
                    model.runtime == ModelRuntime.ONNX_CPU &&
                        model.name == BUNDLED_ONNX_NAME
                }
                val model = existing ?: modelRepository.addModel(
                    sourceUri = BundledTestAssets.prepareOnnxModel(getApplication()),
                    name = BUNDLED_ONNX_NAME,
                    type = ModelType.POSE,
                    inputSize = 640,
                    confThreshold = 0.25f,
                    iouThreshold = 0.45f,
                    classNames = listOf("mouse")
                )
                dataStore.setSelectedModelId(model.id)
                val models = modelRepository.listModels()
                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = model.id,
                        isBusy = false,
                        message = if (existing == null) {
                            "Bundled ONNX CPU model installed and selected"
                        } else {
                            "Bundled ONNX CPU model selected"
                        }
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Failed to install bundled ONNX model"
                    )
                }
            }
        }
    }

    companion object {
        private const val BUNDLED_NCNN_NAME = "Internal pose A (NCNN CPU FP16)"
        private const val BUNDLED_TWO_ANIMAL_NCNN_NAME =
            "Internal pose B (NCNN CPU FP16, detection count 2)"
        private const val BUNDLED_ONNX_NAME = "Internal pose A (ONNX CPU FP16)"

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(application) as T
                }
            }
    }
}
