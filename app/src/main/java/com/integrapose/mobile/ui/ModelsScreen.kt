package com.integrapose.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.BuildConfig
import com.integrapose.mobile.importing.OpenReadOnlyDocument
import com.integrapose.mobile.importing.OpenReadOnlyDocumentTree
import com.integrapose.mobile.model.ModelOutputFormat
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.NcnnModelInspector
import com.integrapose.mobile.model.OnnxModelInspection
import com.integrapose.mobile.model.OnnxModelInspector
import com.integrapose.mobile.model.ModelExportMetadata
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.testing.BundledTestAssets
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(
    uiState: MainUiState,
    onImportModel: (
        Uri,
        String,
        ModelType,
        Int,
        Float,
        Float,
        List<String>,
        Int,
        ModelOutputFormat,
        ModelExportMetadata
    ) -> Unit,
    onImportNcnnModel: (
        Uri,
        String,
        ModelType,
        Int,
        Float,
        Float,
        List<String>,
        Int,
        ModelOutputFormat,
        ModelExportMetadata
    ) -> Unit,
    onImportBundledOnnx: () -> Unit,
    onImportBundledNcnn: () -> Unit,
    onImportBundledTwoAnimalNcnn: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDetectionCountChange: (String, Int) -> Unit,
    onDeleteModel: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedIsNcnn by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf<OnnxModelInspection?>(null) }
    var isInspecting by remember { mutableStateOf(false) }
    var inspectionError by remember { mutableStateOf<String?>(null) }
    var isPreparingBundledModel by remember { mutableStateOf(false) }
    var bundledTestError by remember { mutableStateOf<String?>(null) }
    var editingDetectionCount by remember { mutableStateOf<InferenceModelConfig?>(null) }

    fun closeImport() {
        selectedUri = null
        selectedIsNcnn = false
        inspection = null
        inspectionError = null
        isInspecting = false
    }

    fun beginModelInspection(uri: Uri) {
        selectedIsNcnn = false
        selectedUri = uri
        inspection = null
        inspectionError = null
        isInspecting = true
        scope.launch {
            val selectedModelUri = uri
            runCatching { OnnxModelInspector.inspect(context, selectedModelUri) }
                .onSuccess { result ->
                    if (selectedUri == selectedModelUri) inspection = result
                }
                .onFailure { error ->
                    if (selectedUri == selectedModelUri) {
                        inspectionError = error.message ?: "The selected model could not be read."
                    }
                }
            if (selectedUri == selectedModelUri) isInspecting = false
        }
    }

    fun beginNcnnInspection(uri: Uri) {
        selectedIsNcnn = true
        selectedUri = uri
        inspection = null
        inspectionError = null
        isInspecting = true
        scope.launch {
            val selectedModelUri = uri
            runCatching { NcnnModelInspector.inspect(context, selectedModelUri) }
                .onSuccess { result ->
                    if (selectedUri == selectedModelUri) inspection = result
                }
                .onFailure { error ->
                    if (selectedUri == selectedModelUri) {
                        inspectionError = error.message ?:
                            "The selected NCNN package could not be read."
                    }
                }
            if (selectedUri == selectedModelUri) isInspecting = false
        }
    }

    val onnxPicker = rememberLauncherForActivityResult(OpenReadOnlyDocument()) { uri ->
        if (uri != null) beginModelInspection(uri)
    }
    val ncnnPicker = rememberLauncherForActivityResult(
        OpenReadOnlyDocumentTree()
    ) { uri ->
        if (uri != null) beginNcnnInspection(uri)
    }

    if (selectedUri != null) {
        ImportModelDialog(
            uri = selectedUri!!,
            inspection = inspection,
            isInspecting = isInspecting,
            inspectionError = inspectionError,
            onDismiss = ::closeImport,
            onConfirm = { uri, name, type, input, conf, iou, classes,
                detectionCount, outputFormat, exportMetadata ->
                val importAction = if (selectedIsNcnn) {
                    onImportNcnnModel
                } else {
                    onImportModel
                }
                importAction(
                    uri,
                    name,
                    type,
                    input,
                    conf,
                    iou,
                    classes,
                    detectionCount,
                    outputFormat,
                    exportMetadata
                )
                closeImport()
            }
        )
    }

    editingDetectionCount?.let { model ->
        RuntimeDetectionCountDialog(
            model = model,
            onDismiss = { editingDetectionCount = null },
            onConfirm = { maximum ->
                onDetectionCountChange(model.id, maximum)
                editingDetectionCount = null
            }
        )
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
            text = "Models",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE6EDF9)
        )

        Text(
            text = "Import ONNX models or NCNN packages that return bbox detection rows or bbox-plus-keypoint pose rows. File format alone does not guarantee compatibility; review the detected task and output settings before saving.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB8C4D8)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    onnxPicker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                enabled = !uiState.isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text(" ONNX file")
            }

            Button(
                onClick = { ncnnPicker.launch(null) },
                enabled = !uiState.isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text(" NCNN folder")
            }
        }
        Text(
            "Cloud and local sources are opened read-only. IntegraPose Live copies the model into " +
                "private app storage and never deletes the source document.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB8C4D8)
        )
        OutlinedButton(
            onClick = onRefresh,
            enabled = !uiState.isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh models")
        }

        if (BuildConfig.BUNDLED_TEST_KIT) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0x553A315E)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Bundled debug comparison kits",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE9DFFF)
                    )
                    Text(
                        "Install the single-animal or detection-count-2 two-animal 640-pixel NCNN pose model without Android's file picker. Matching 20-second videos are available on Offline and Benchmark.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD4C8EA)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                isPreparingBundledModel = true
                                bundledTestError = null
                                runCatching {
                                    BundledTestAssets.prepareOnnxModel(context)
                                }.onSuccess { uri ->
                                    beginModelInspection(uri)
                                }.onFailure { error ->
                                    bundledTestError = error.message
                                        ?: "The bundled ONNX model could not be prepared."
                                }
                                isPreparingBundledModel = false
                            }
                        },
                        enabled = !uiState.isBusy &&
                            !isPreparingBundledModel &&
                            !isInspecting
                    ) {
                        if (isPreparingBundledModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text(" Preparing model…")
                        } else {
                            Text("Load bundled ONNX model")
                        }
                    }
                    Button(
                        onClick = onImportBundledOnnx,
                        enabled = !uiState.isBusy &&
                            !isPreparingBundledModel &&
                            !isInspecting
                    ) {
                        Text("Install bundled ONNX CPU model")
                    }
                    Button(
                        onClick = onImportBundledNcnn,
                        enabled = !uiState.isBusy &&
                            !isPreparingBundledModel &&
                            !isInspecting
                    ) {
                        Text("Install single-animal NCNN model")
                    }
                    Button(
                        onClick = onImportBundledTwoAnimalNcnn,
                        enabled = !uiState.isBusy &&
                            !isPreparingBundledModel &&
                            !isInspecting
                    ) {
                        Text("Install two-animal NCNN model (detection count 2)")
                    }
                    bundledTestError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB2B2)
                        )
                    }
                }
            }
        }

        if (uiState.models.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x55172233))
            ) {
                Text(
                    text = "No model imported yet.",
                    modifier = Modifier.padding(14.dp),
                    color = Color(0xFFD4DEEE)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                uiState.models.forEach { model ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (uiState.selectedModel?.id == model.id) {
                                Color(0x8833B7FF)
                            } else {
                                Color(0x55203145)
                            }
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                RadioButton(
                                    selected = uiState.selectedModel?.id == model.id,
                                    onClick = { onSelectModel(model.id) }
                                )
                                Column {
                                    Text(
                                        text = model.name,
                                        color = Color(0xFFEAF1FA),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = (
                                            if (model.type == ModelType.POSE) {
                                                "Pose: bbox + keypoints"
                                            } else {
                                                "Detection: bbox"
                                            }
                                            ) + " | ${model.runtime.displayName} | ${model.inputSize}px | conf ${model.confThreshold}",
                                        color = Color(0xFFBFCEE3),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = model.exportMetadata.postProcessingLabel +
                                            " | detection count " + model.detectionCount +
                                            if (model.exportMetadata.detectionCountLocked) {
                                                " (fixed at export)"
                                            } else {
                                                " (runtime)"
                                            },
                                        color = Color(0xFFAFC0D8),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (model.detectionCountIsRuntimeEditable) {
                                        TextButton(
                                            onClick = {
                                                editingDetectionCount = model
                                            },
                                            enabled = !uiState.isBusy
                                        ) {
                                            Text("Set detection count")
                                        }
                                    }
                                    Text(
                                        text = if (model.classNames.isEmpty()) {
                                            "Labels: generic class IDs"
                                        } else {
                                            model.classNames.size.toString() + " labels: " +
                                                model.classNames.take(3).joinToString(", ")
                                        },
                                        color = Color(0xFFAFC0D8),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { onDeleteModel(model.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete model",
                                    tint = Color(0xFFFFA0A0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeDetectionCountDialog(
    model: InferenceModelConfig,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable(model.id, model.detectionCount) {
        mutableStateOf(model.detectionCount.toString())
    }
    val parsed = value.toIntOrNull()
    val valid = parsed != null && parsed in 1..5_000

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detection count") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose the maximum number of subjects retained per frame. " +
                        "Use 1 for one subject, 2 for two subjects, and so on."
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit) },
                    label = { Text("Detections retained per frame") },
                    supportingText = {
                        Text("Runtime mobile NMS setting; allowed range 1 to 5000.")
                    },
                    isError = value.isNotBlank() && !valid,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .keepFocusedFieldVisible()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = { onConfirm(checkNotNull(parsed)) }
            ) {
                Text("Use detection count $value")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportModelDialog(
    uri: Uri,
    inspection: OnnxModelInspection?,
    isInspecting: Boolean,
    inspectionError: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        Uri,
        String,
        ModelType,
        Int,
        Float,
        Float,
        List<String>,
        Int,
        ModelOutputFormat,
        ModelExportMetadata
    ) -> Unit
) {
    var name by rememberSaveable(uri.toString()) {
        mutableStateOf(
            (uri.lastPathSegment ?: "")
                .substringAfterLast('/')
                .substringBeforeLast('.')
        )
    }
    var modelType by rememberSaveable(uri.toString()) { mutableStateOf(ModelType.POSE) }
    var inputSize by rememberSaveable(uri.toString()) { mutableStateOf("640") }
    var confThreshold by rememberSaveable(uri.toString()) { mutableStateOf("0.25") }
    var iouThreshold by rememberSaveable(uri.toString()) { mutableStateOf("0.45") }
    var detectionCount by rememberSaveable(uri.toString()) { mutableStateOf("1") }
    var showClassEditor by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var showAdvanced by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var inspectionApplied by remember(uri) { mutableStateOf(false) }
    val classNames = remember(uri) { mutableStateListOf<String>() }

    LaunchedEffect(inspection) {
        if (inspection != null && !inspectionApplied) {
            name = inspection.suggestedName
            inspection.detectedType?.let { modelType = it }
            inspection.inputSize?.let { inputSize = it.toString() }
            detectionCount = inspection.recommendedDetectionCount.toString()
            classNames.clear()
            if (inspection.classNames.isNotEmpty()) {
                classNames.addAll(inspection.classNames)
            } else {
                classNames.add("")
                showClassEditor = true
            }
            inspectionApplied = true
        }
    }

    val parsedInputSize = inputSize.toIntOrNull()
    val parsedConfidence = confThreshold.toFloatOrNull()
    val parsedIou = iouThreshold.toFloatOrNull()
    val parsedDetectionCount = detectionCount.toIntOrNull()
    val inputIsValid = parsedInputSize != null &&
        parsedInputSize in 32..2_048 &&
        parsedInputSize % 32 == 0
    val confidenceIsValid = parsedConfidence != null && parsedConfidence in 0f..1f
    val iouIsValid = parsedIou != null && parsedIou in 0f..1f
    val detectionCountIsValid = parsedDetectionCount != null &&
        parsedDetectionCount in 1..5_000
    val formIsValid = !isInspecting &&
        inspectionError == null &&
        inspection != null &&
        inspection.isOutputCompatible &&
        inspection.unsupportedReason == null &&
        name.isNotBlank() &&
        inputIsValid &&
        confidenceIsValid &&
        iouIsValid &&
        detectionCountIsValid
    val classCountLocked = inspection?.classNames?.isNotEmpty() == true
    val classPreview = classNames
        .take(4)
        .mapIndexed { id, label -> id.toString() + ": " + label.ifBlank { "not named" } }
        .joinToString("  •  ")

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up model") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Source: ${uri.lastPathSegment ?: uri}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB8C8DD)
                )

                when {
                    isInspecting -> Surface(
                        color = Color(0x332E86C1),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                            Column {
                                Text("Reading model details", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "This can take a moment for a large model.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    inspectionError != null -> Surface(
                        color = Color(0x44B3261E),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("This model could not be opened", fontWeight = FontWeight.SemiBold)
                            Text(inspectionError, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    inspection != null -> Surface(
                        color = if (inspection.unsupportedReason == null &&
                            inspection.isOutputCompatible
                        ) {
                            Color(0x333BAA74)
                        } else {
                            Color(0x44B3261E)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                if (inspection.unsupportedReason == null &&
                                    inspection.isOutputCompatible
                                ) {
                                    "Model checked and ready to review"
                                } else {
                                    "Outside the supported model contract"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            inspection.unsupportedReason?.let { reason ->
                                Text(reason, style = MaterialTheme.typography.bodySmall)
                            }
                            val detectedDetails = listOfNotNull(
                                inspection.detectedType?.let {
                                    if (it == ModelType.POSE) {
                                        "Pose: bbox + keypoints"
                                    } else {
                                        "Detection: bbox"
                                    }
                                } ?: "Confirm detection or pose task",
                                inspection.inputSize?.let { it.toString() + " × " + it + " input" },
                                inspection.classNames.takeIf { it.isNotEmpty() }?.let {
                                    it.size.toString() + if (it.size == 1) " class" else " classes"
                                },
                                inspection.exportMetadata.postProcessingLabel,
                                "detection count " + inspection.recommendedDetectionCount +
                                    if (inspection.exportMetadata.detectionCountLocked) {
                                        " (fixed at export)"
                                    } else {
                                        " (runtime setting)"
                                    }
                            )
                            Text(
                                text = detectedDetails.ifEmpty {
                                    listOf(inspection.packageLabel + " opened successfully")
                                }
                                    .joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            inspection.keypointShape?.let {
                                Text(
                                    text = "Keypoint layout: " + it,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                inspection?.warnings?.forEach { warning ->
                    Text(
                        text = "• " + warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFCC80)
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Model name") },
                    supportingText = { Text("Use a name you will recognize in experiments.") },
                    singleLine = true,
                    isError = name.isBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .keepFocusedFieldVisible(),
                    enabled = !isInspecting && inspectionError == null
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What does this model predict?", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = modelType == ModelType.POSE,
                            onClick = { modelType = ModelType.POSE },
                            label = { Text("Pose + boxes") },
                            enabled = !isInspecting && inspectionError == null
                        )
                        FilterChip(
                            selected = modelType == ModelType.DETECTION,
                            onClick = { modelType = ModelType.DETECTION },
                            label = { Text("Boxes only") },
                            enabled = !isInspecting && inspectionError == null
                        )
                    }
                    if (inspection?.detectedType != null) {
                        Text(
                            "Selected automatically from the model; change it only if needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB8C8DD)
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x44203145)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Class labels", fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (classCountLocked) {
                                        classNames.size.toString() +
                                            if (classNames.size == 1) " label detected" else " labels detected"
                                    } else {
                                        "No class labels were embedded"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB8C8DD)
                                )
                            }
                            TextButton(
                                onClick = { showClassEditor = !showClassEditor },
                                enabled = !isInspecting && inspectionError == null
                            ) {
                                Text(if (showClassEditor) "Done" else "Review")
                            }
                        }

                        if (!showClassEditor && classPreview.isNotBlank()) {
                            Text(
                                text = classPreview,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (showClassEditor) {
                            Text(
                                text = if (classCountLocked) {
                                    "IDs come from the model. Edit only the friendly names used in CSV exports, analytics summaries, and annotated videos."
                                } else {
                                    "Add names in model ID order (0, 1, 2…). If you are unsure, leave this blank and inference will retain generic class IDs."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB8C8DD)
                            )

                            classNames.forEachIndexed { id, label ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.width(42.dp),
                                        color = Color(0x5533B7FF),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = id.toString(),
                                            modifier = Modifier.padding(vertical = 14.dp),
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                    OutlinedTextField(
                                        value = label,
                                        onValueChange = { classNames[id] = it },
                                        label = { Text("Class " + id + " name") },
                                        placeholder = { Text("for example: mouse") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .keepFocusedFieldVisible()
                                    )
                                }
                            }

                            if (!classCountLocked) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { classNames.add("") },
                                        enabled = classNames.size < 1_000,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Text(" Add class")
                                    }
                                    TextButton(
                                        onClick = { classNames.removeAt(classNames.lastIndex) },
                                        enabled = classNames.size > 1,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Remove last")
                                    }
                                }
                            }
                        }
                    }
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide advanced settings" else "Advanced inference settings")
                }

                if (showAdvanced) {
                    Text(
                        "Most users can keep these detected/default values.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8C8DD)
                    )
                    OutlinedTextField(
                        value = inputSize,
                        onValueChange = { inputSize = it.filter(Char::isDigit) },
                        label = { Text("Input size") },
                        supportingText = {
                            Text(
                                if (
                                    BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO &&
                                    inspection?.inputSize != null
                                ) {
                                    "Detected from model metadata or graph input and used automatically."
                                } else {
                                    "Default 640; 32–2048 and a multiple of 32."
                                }
                            )
                        },
                        enabled = !BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO ||
                            inspection?.inputSize == null,
                        isError = inputSize.isNotBlank() && !inputIsValid,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .keepFocusedFieldVisible()
                    )
                    OutlinedTextField(
                        value = confThreshold,
                        onValueChange = { confThreshold = it },
                        label = { Text("Minimum confidence") },
                        supportingText = { Text("0 to 1; default 0.25") },
                        isError = confThreshold.isNotBlank() && !confidenceIsValid,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .keepFocusedFieldVisible()
                    )
                    OutlinedTextField(
                        value = iouThreshold,
                        onValueChange = { iouThreshold = it },
                        label = { Text("Overlap removal (IoU)") },
                        supportingText = {
                            Text(
                                if (inspection?.outputFormat == ModelOutputFormat.END_TO_END) {
                                    "Disabled because this export already returns final detections."
                                } else {
                                    "Mobile NMS only; 0 to 1, default 0.45."
                                }
                            )
                        },
                        isError = iouThreshold.isNotBlank() && !iouIsValid,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .keepFocusedFieldVisible(),
                        enabled = inspection?.outputFormat != ModelOutputFormat.END_TO_END
                    )
                    OutlinedTextField(
                        value = detectionCount,
                        onValueChange = {
                            detectionCount = it.filter(Char::isDigit)
                        },
                        label = { Text("Detections retained per frame") },
                        supportingText = {
                            Text(
                                if (inspection?.exportMetadata?.detectionCountLocked == true) {
                                    "Fixed during model export. Re-export the model to change it."
                                } else {
                                    "Default 1. Increase for multi-animal recordings."
                                }
                            )
                        },
                        isError = detectionCount.isNotBlank() &&
                            !detectionCountIsValid,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .keepFocusedFieldVisible(),
                        enabled = inspection?.exportMetadata?.detectionCountLocked != true
                    )
                }

                Text(
                    if (inspection?.packageLabel == "NCNN package") {
                        "The selected folder must contain model.ncnn.param, model.ncnn.bin, and metadata.yaml."
                    } else {
                        "The ONNX file and confirmed metadata are saved together in IntegraPose Live."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C8DD)
                )
            }
        },
        confirmButton = {
            Button(
                enabled = formIsValid,
                onClick = {
                    val lastNamedClass = classNames.indexOfLast { it.isNotBlank() }
                    val confirmedClassNames = if (lastNamedClass < 0) {
                        emptyList()
                    } else {
                        (0..lastNamedClass).map { id ->
                            classNames[id].trim().ifBlank { "class_" + id }
                        }
                    }
                    onConfirm(
                        uri,
                        name.trim(),
                        modelType,
                        checkNotNull(parsedInputSize),
                        checkNotNull(parsedConfidence),
                        checkNotNull(parsedIou),
                        confirmedClassNames,
                        checkNotNull(parsedDetectionCount),
                        requireNotNull(inspection).outputFormat,
                        inspection.exportMetadata
                    )
                }
            ) {
                Text(if (isInspecting) "Checking…" else "Save model")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(Color(0xCC0D1523), RoundedCornerShape(12.dp))
    )
}
