package com.integrapose.mobile.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NcnnModelInspector {
    suspend fun inspect(context: Context, sourceTreeUri: Uri): OnnxModelInspection =
        withContext(Dispatchers.IO) {
            val directory = requireNotNull(
                DocumentFile.fromTreeUri(context, sourceTreeUri)
            ) { "The selected NCNN folder could not be opened." }
            require(directory.isDirectory) { "Select the NCNN export folder." }
            val files = directory.listFiles().associateBy {
                it.name.orEmpty().lowercase()
            }
            val param = files[PARAM_FILE]
                ?: error("The NCNN folder is missing model.ncnn.param.")
            val weights = files[WEIGHTS_FILE]
                ?: error("The NCNN folder is missing model.ncnn.bin.")
            val metadata = files[METADATA_FILE]
                ?: error("The NCNN folder is missing metadata.yaml.")
            require(hasContent(context, param.uri)) {
                "model.ncnn.param is empty or unreadable."
            }
            require(hasContent(context, weights.uri)) {
                "model.ncnn.bin is empty or unreadable."
            }
            val metadataText = context.contentResolver
                .openInputStream(metadata.uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use(BufferedReader::readText)
                ?: error("metadata.yaml could not be read as UTF-8.")
            require(metadataText.isNotBlank()) { "metadata.yaml is empty." }
            inspectMetadata(
                metadataText = metadataText,
                directoryName = directory.name ?: "NCNN model"
            )
        }

    internal fun inspectMetadata(
        metadataText: String,
        directoryName: String
    ): OnnxModelInspection {
        val yaml = SimpleYaml(metadataText)
        val modelName = yaml.scalar("model_name")
        val task = yaml.scalar("task")?.lowercase()
        val detectedType = ModelMetadataParser.inferModelType(task)
        val exportArguments = yaml.block("args").joinToString(",")
        val endToEnd = ModelMetadataParser.parseBoolean(yaml.scalar("end2end"))
            ?: ModelMetadataParser.parseBooleanArgument(
                exportArguments,
                "end2end"
            )
        val embeddedNms = ModelMetadataParser.parseBoolean(yaml.scalar("nms"))
            ?: ModelMetadataParser.parseBooleanArgument(exportArguments, "nms")
        val exportDetectionCount =
            ModelMetadataParser.parsePositiveInt(yaml.scalar("detection_count"))
                ?: ModelMetadataParser.parsePositiveInt(yaml.scalar("max_detections"))
                // Legacy exporter metadata remains readable for existing models.
                ?: ModelMetadataParser.parsePositiveInt(yaml.scalar("max_det"))
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
        // The current native NCNN postprocessor accepts raw bbox/class rows
        // with optional keypoint triplets and performs NMS on the device.
        val outputFormat = ModelOutputFormat.RAW_PREDICTIONS
        val classNames = ModelMetadataParser.parseClassNames(
            yaml.mapping("names")
        )
        val inputSize = ModelMetadataParser.parseSquareInputSize(
            yaml.block("imgsz").joinToString(" ")
        )
        val keypointShape = yaml.block("kpt_shape")
            .flatMap { line -> Regex("\\d+").findAll(line).map { it.value }.toList() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" × ")
        val exportMetadata = ModelExportMetadata(
            endToEnd = false,
            embeddedNms = false,
            exportDetectionCount = exportDetectionCount
        )
        val warnings = buildList {
            if (detectedType == null) {
                add(
                    "metadata.yaml must declare task=detect, task=detection, or task=pose."
                )
            }
            if (classNames.isEmpty()) {
                add("No class names were found in metadata.yaml.")
            }
            if (endToEnd == true) {
                add("This NCNN runtime expects raw candidate rows; use end2end=false.")
            }
            if (embeddedNms == true) {
                add("NCNN export does not support embedded NMS; re-export without nms=true.")
            }
            exportDetectionCount?.let {
                add("Exported detection count $it is fixed for this model and cannot be changed in the mobile app.")
            }
        }
        val unsupportedReason = when {
            detectedType == null ->
                "metadata.yaml must identify the model as detection or pose."
            endToEnd == true ->
                "NCNN metadata reports end2end=true, but supported NCNN packages must use the raw output head."
            embeddedNms == true ->
                "NCNN metadata reports nms=true, but NCNN export does not support embedded NMS."
            else -> null
        }
        val suggestedName = modelName?.takeIf { it.isNotBlank() }
            ?: directoryName.ifBlank { "NCNN model" }
        val summary = buildString {
            append("NCNN metadata found")
            task?.let { append(" | task=").append(it) }
            inputSize?.let { append(" | input=").append(it) }
            if (classNames.isNotEmpty()) append(" | classes=").append(classNames.size)
            keypointShape?.let { append(" | keypoints=").append(it) }
            append(" | ").append(exportMetadata.postProcessingLabel)
            exportDetectionCount?.let { append(" | detection count ").append(it) }
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
            embeddedMetadataFound = true,
            summary = summary,
            warnings = warnings,
            packageLabel = "NCNN package",
            unsupportedReason = unsupportedReason
        )
    }

    private fun hasContent(context: Context, uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true

    private class SimpleYaml(text: String) {
        private val lines = text.lineSequence().toList()

        fun scalar(key: String): String? {
            val prefix = "$key:"
            val line = lines.firstOrNull {
                it.isNotBlank() && !it.first().isWhitespace() &&
                    it.trimStart().startsWith(prefix)
            } ?: return null
            return clean(line.substringAfter(':')).takeIf { it.isNotBlank() }
        }

        fun block(key: String): List<String> {
            val prefix = "$key:"
            val start = lines.indexOfFirst {
                it.isNotBlank() && !it.first().isWhitespace() &&
                    it.trimStart().startsWith(prefix)
            }
            if (start < 0) return emptyList()
            val inline = clean(lines[start].substringAfter(':'))
            if (inline.isNotBlank()) return listOf(inline)
            return lines.drop(start + 1)
                .takeWhile {
                    it.isBlank() || it.first().isWhitespace() ||
                        it.trimStart().startsWith("- ")
                }
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith('#') }
        }

        fun mapping(key: String): String? {
            val entries = block(key)
                .map { it.removePrefix("- ").trim() }
                .filter { ':' in it }
            return entries.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "{", postfix = "}")
        }

        private fun clean(value: String): String = value.trim()
            .trim(34.toChar(), 39.toChar())
    }

    private const val PARAM_FILE = "model.ncnn.param"
    private const val WEIGHTS_FILE = "model.ncnn.bin"
    private const val METADATA_FILE = "metadata.yaml"
}
