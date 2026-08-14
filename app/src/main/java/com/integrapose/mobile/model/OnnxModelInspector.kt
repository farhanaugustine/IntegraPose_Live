package com.integrapose.mobile.model

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class OnnxModelInspection(
    val suggestedName: String,
    val detectedType: ModelType?,
    val inputSize: Int?,
    val classNames: List<String>,
    val task: String?,
    val keypointShape: String?,
    val isOutputCompatible: Boolean,
    val outputFormat: ModelOutputFormat,
    val recommendedDetectionCount: Int,
    val exportMetadata: ModelExportMetadata,
    val embeddedMetadataFound: Boolean,
    val summary: String,
    val warnings: List<String>,
    val packageLabel: String = "ONNX model",
    val unsupportedReason: String? = null
)

object OnnxModelInspector {
    suspend fun inspect(context: Context, sourceUri: Uri): OnnxModelInspection =
        withContext(Dispatchers.IO) {
            val displayName = DocumentFile.fromSingleUri(context, sourceUri)?.name
                ?: sourceUri.lastPathSegment
                ?: "model.onnx"
            require(displayName.endsWith(".onnx", ignoreCase = true)) {
                "Select an .onnx model here. Use Import NCNN folder for an NCNN export package."
            }

            val temporaryModel = File.createTempFile("onnx_inspect_", ".onnx", context.cacheDir)
            try {
                context.contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { "Unable to open the selected model." }
                    temporaryModel.outputStream().use { output -> input.copyTo(output) }
                }
                require(temporaryModel.length() > 0L) { "The selected model is empty." }
                inspectFile(temporaryModel, displayName)
            } finally {
                temporaryModel.delete()
            }
        }

    private fun inspectFile(modelFile: File, displayName: String): OnnxModelInspection {
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        options.use {
            environment.createSession(modelFile.absolutePath, options).use { session ->
                val metadata = session.metadata
                val custom = metadata.customMetadata.entries.associate { (key, value) ->
                    key.lowercase() to value
                }
                val exportArguments = custom["args"]
                val endToEnd = ModelMetadataParser.parseBoolean(custom["end2end"])
                    ?: ModelMetadataParser.parseBooleanArgument(
                        exportArguments,
                        "end2end"
                    )
                val embeddedNms = ModelMetadataParser.parseBoolean(custom["nms"])
                    ?: ModelMetadataParser.parseBooleanArgument(
                        exportArguments,
                        "nms"
                    )
                val exportDetectionCount =
                    ModelMetadataParser.parsePositiveInt(custom["detection_count"])
                        ?: ModelMetadataParser.parsePositiveInt(custom["max_detections"])
                        // Legacy exporter metadata remains readable for existing models.
                        ?: ModelMetadataParser.parsePositiveInt(custom["max_det"])
                        ?: ModelMetadataParser.parsePositiveIntArgument(
                            exportArguments,
                            "detection_count"
                        )
                        ?: ModelMetadataParser.parsePositiveIntArgument(
                            exportArguments,
                            "max_detections"
                        )
                        ?: ModelMetadataParser.parsePositiveIntArgument(
                            exportArguments,
                            "max_det"
                        )
                val outputFormat = when {
                    endToEnd == true || embeddedNms == true ->
                        ModelOutputFormat.END_TO_END
                    endToEnd == false || embeddedNms == false ->
                        ModelOutputFormat.RAW_PREDICTIONS
                    else -> ModelOutputFormat.AUTO
                }
                val exportMetadata = ModelExportMetadata(
                    endToEnd = endToEnd,
                    embeddedNms = embeddedNms,
                    exportDetectionCount = exportDetectionCount
                )
                val classNames = ModelMetadataParser.parseClassNames(custom["names"])
                val task = custom["task"]?.trim()?.lowercase()
                val warnings = mutableListOf<String>()
                var detectedType = ModelMetadataParser.inferModelType(task)
                if (detectedType == null) {
                    detectedType = inferTypeFromOutput(session, classNames.size)
                }
                if (task != null && detectedType == null) {
                    warnings += "Embedded task '$task' is not currently supported as detection or pose."
                } else if (detectedType == null) {
                    warnings += "The model does not identify itself as pose or detection; confirm the task below."
                }
                if (classNames.isEmpty()) {
                    warnings += "No embedded class names were found; enter the ID-to-name mapping below."
                }
                if (endToEnd == true && embeddedNms == true) {
                    warnings +=
                        "The metadata reports both end2end=true and nms=true; re-export because these modes are mutually exclusive."
                }
                exportDetectionCount?.let { fixedMaximum ->
                    warnings +=
                        "Exported detection count $fixedMaximum is fixed for this model and cannot be changed in the mobile app."
                }

                val graphInputSize = inspectSquareGraphInput(session)
                val metadataInputSize = ModelMetadataParser.parseSquareInputSize(custom["imgsz"])
                val inputSize = graphInputSize ?: metadataInputSize
                val suggestedName = custom["model_name"]
                    ?.takeIf { it.isNotBlank() }
                    ?: displayName.substringBeforeLast('.')
                val keypointShape = custom["kpt_shape"]
                val summary = buildString {
                    append(if (custom.isEmpty()) "No embedded export metadata" else "Embedded metadata found")
                    task?.let { append(" | task=").append(it) }
                    inputSize?.let { append(" | input=").append(it) }
                    if (classNames.isNotEmpty()) append(" | classes=").append(classNames.size)
                    keypointShape?.let { append(" | keypoints=").append(it) }
                    append(" | ").append(exportMetadata.postProcessingLabel)
                    exportDetectionCount?.let { append(" | detection count ").append(it) }
                }
                val unsupportedReason = when {
                    task != null && detectedType == null ->
                        "The '$task' task is outside the supported detection/pose contract."
                    endToEnd == true && embeddedNms == true ->
                        "The export reports both end2end=true and nms=true. Re-export with one final-output mode."
                    else -> null
                }
                return OnnxModelInspection(
                    suggestedName = suggestedName,
                    detectedType = detectedType,
                    inputSize = inputSize,
                    classNames = classNames,
                    task = task,
                    keypointShape = keypointShape,
                    isOutputCompatible = unsupportedReason == null,
                    outputFormat = outputFormat,
                    recommendedDetectionCount = exportDetectionCount ?: 1,
                    exportMetadata = exportMetadata,
                    embeddedMetadataFound = custom.isNotEmpty(),
                    summary = summary,
                    warnings = warnings,
                    unsupportedReason = unsupportedReason
                )
            }
        }
    }

    private fun inspectSquareGraphInput(session: OrtSession): Int? {
        val input = session.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return null
        val shape = input.shape
        if (shape.size != 4) return null
        val height: Long
        val width: Long
        when {
            shape[1] == 3L -> {
                height = shape[2]
                width = shape[3]
            }
            shape[3] == 3L -> {
                height = shape[1]
                width = shape[2]
            }
            else -> return null
        }
        return height.takeIf { it > 0L && it == width && it <= 2_048L }?.toInt()
    }

    private fun inferTypeFromOutput(session: OrtSession, classCount: Int): ModelType? {
        val output = session.outputInfo.values.firstOrNull()?.info as? TensorInfo ?: return null
        val shape = output.shape
        if (shape.size < 2) return null
        val positiveDimensions = shape.drop(1).filter { it in 5..10_000 }.map { it.toInt() }
        if (positiveDimensions.isEmpty()) return null
        val featureCount = positiveDimensions.minOrNull() ?: return null
        if (classCount > 0) {
            val poseValues = featureCount - 4 - classCount
            if (poseValues >= 3 && poseValues % 3 == 0) return ModelType.POSE
            if (featureCount == 4 + classCount || featureCount == 6) return ModelType.DETECTION
        }
        return if (featureCount == 6) ModelType.DETECTION else null
    }
}
