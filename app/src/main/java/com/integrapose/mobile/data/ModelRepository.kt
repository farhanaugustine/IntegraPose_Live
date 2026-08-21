package com.integrapose.mobile.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.integrapose.mobile.model.ModelOutputFormat
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.KeypointConnection
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.model.ModelExportMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

class ModelRepository(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val modelDir: File = File(context.filesDir, "models").also { it.mkdirs() }
    private val configFile: File = File(context.filesDir, "model_registry.json")
    private val atomicConfigFile = AtomicFile(configFile)

    suspend fun listModels(): List<InferenceModelConfig> = mutex.withLock {
        loadRegistryUnsafe().models
            .filter { it.isReleaseSupported }
            .sortedByDescending { it.createdAtMs }
    }

    suspend fun addModel(
        sourceUri: Uri,
        name: String,
        type: ModelType,
        inputSize: Int,
        confThreshold: Float,
        iouThreshold: Float,
        classNames: List<String>,
        detectionCount: Int = 1,
        outputFormat: ModelOutputFormat = ModelOutputFormat.AUTO,
        exportMetadata: ModelExportMetadata = ModelExportMetadata()
    ): InferenceModelConfig = mutex.withLock {
        withContext(Dispatchers.IO) {
            val registry = loadRegistryUnsafe()
            val id = java.util.UUID.randomUUID().toString()
            val modelFile = File(modelDir, "$id.onnx")
            val displayName = DocumentFile.fromSingleUri(context, sourceUri)?.name.orEmpty()
            require(displayName.endsWith(".onnx", ignoreCase = true)) {
                "Use the ONNX file importer for .onnx or the NCNN folder importer for model.ncnn.param, model.ncnn.bin, and metadata.yaml."
            }
            require(inputSize in 32..2_048 && inputSize % 32 == 0) {
                "Input size must be a multiple of 32 between 32 and 2048 (640 is the default)."
            }

            try {
                context.contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { "Unable to open model file." }
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
                require(modelFile.length() > 0L) { "The selected model file is empty." }
            } catch (error: Throwable) {
                modelFile.delete()
                throw error
            }

            try {
                val config = InferenceModelConfig(
                    id = id,
                    name = name.ifBlank { "Imported model ${registry.models.size + 1}" },
                    filePath = modelFile.absolutePath,
                    type = type,
                    runtime = ModelRuntime.ONNX_CPU,
                    inputSize = inputSize,
                    confThreshold = confThreshold.coerceIn(0.01f, 0.99f),
                    iouThreshold = iouThreshold.coerceIn(0.05f, 0.95f),
                    classNames = classNames.filter { it.isNotBlank() },
                    outputFormat = outputFormat,
                    detectionCount = (
                        exportMetadata.exportDetectionCount ?: detectionCount
                        ).coerceIn(1, 5_000),
                    exportMetadata = exportMetadata
                )
                config.requireSupportedModel()

                val updated = registry.copy(models = registry.models + config)
                saveRegistryUnsafe(updated)
                config
            } catch (error: Throwable) {
                modelFile.delete()
                throw error
            }
        }
    }

    suspend fun addNcnnModel(
        sourceDirectory: File,
        name: String,
        type: ModelType = ModelType.POSE,
        inputSize: Int = 640,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
        classNames: List<String> = emptyList(),
        useVulkan: Boolean = false,
        detectionCount: Int = 1,
        outputFormat: ModelOutputFormat = ModelOutputFormat.RAW_PREDICTIONS,
        exportMetadata: ModelExportMetadata = ModelExportMetadata(
            endToEnd = false,
            embeddedNms = false
        )
    ): InferenceModelConfig = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(inputSize in 32..2_048 && inputSize % 32 == 0) {
                "Input size must be a multiple of 32 between 32 and 2048 (640 is the default)."
            }
            val paramSource = File(sourceDirectory, "model.ncnn.param")
            val weightsSource = File(sourceDirectory, "model.ncnn.bin")
            val metadataSource = File(sourceDirectory, "metadata.yaml")
            require(paramSource.isFile && paramSource.length() > 0L) {
                "The NCNN model is missing model.ncnn.param."
            }
            require(weightsSource.isFile && weightsSource.length() > 0L) {
                "The NCNN model is missing model.ncnn.bin."
            }
            require(metadataSource.isFile && metadataSource.length() > 0L) {
                "The NCNN model is missing metadata.yaml."
            }

            val registry = loadRegistryUnsafe()
            val id = java.util.UUID.randomUUID().toString()
            val paramFile = File(modelDir, "$id.ncnn.param")
            val weightsFile = File(modelDir, "$id.ncnn.bin")
            val metadataFile = File(modelDir, "$id.metadata.yaml")
            try {
                paramSource.copyTo(paramFile)
                weightsSource.copyTo(weightsFile)
                metadataSource.copyTo(metadataFile)
                require(paramFile.length() == paramSource.length()) {
                    "The NCNN parameter file was not copied completely."
                }
                require(weightsFile.length() == weightsSource.length()) {
                    "The NCNN weights file was not copied completely."
                }
                require(metadataFile.length() == metadataSource.length()) {
                    "The NCNN metadata file was not copied completely."
                }

                val config = InferenceModelConfig(
                    id = id,
                    name = name.ifBlank { "Imported NCNN model ${registry.models.size + 1}" },
                    filePath = paramFile.absolutePath,
                    auxiliaryFilePath = weightsFile.absolutePath,
                    type = type,
                    runtime = if (useVulkan) {
                        ModelRuntime.NCNN_VULKAN
                    } else {
                        ModelRuntime.NCNN_CPU
                    },
                    metadataFilePath = metadataFile.absolutePath,
                    inputSize = inputSize,
                    confThreshold = confThreshold.coerceIn(0.01f, 0.99f),
                    iouThreshold = iouThreshold.coerceIn(0.05f, 0.95f),
                    classNames = classNames.filter { it.isNotBlank() },
                    outputFormat = outputFormat,
                    detectionCount = (
                        exportMetadata.exportDetectionCount ?: detectionCount
                        ).coerceIn(1, 5_000),
                    exportMetadata = exportMetadata
                )
                config.requireSupportedModel()
                saveRegistryUnsafe(registry.copy(models = registry.models + config))
                config
            } catch (error: Throwable) {
                paramFile.delete()
                weightsFile.delete()
                metadataFile.delete()
                throw error
            }
        }
    }

    suspend fun addNcnnModel(
        sourceTreeUri: Uri,
        name: String,
        type: ModelType,
        inputSize: Int,
        confThreshold: Float,
        iouThreshold: Float,
        classNames: List<String>,
        detectionCount: Int,
        outputFormat: ModelOutputFormat,
        exportMetadata: ModelExportMetadata,
        useVulkan: Boolean = false
    ): InferenceModelConfig = withContext(Dispatchers.IO) {
        val source = requireNotNull(DocumentFile.fromTreeUri(context, sourceTreeUri)) {
            "The selected NCNN folder could not be opened."
        }
        require(source.isDirectory) { "Select an NCNN export folder." }
        val sourceFiles = source.listFiles().associateBy {
            it.name.orEmpty().lowercase()
        }
        val requiredNames = listOf(
            "model.ncnn.param",
            "model.ncnn.bin",
            "metadata.yaml"
        )
        val temporaryDirectory = File(
            context.cacheDir,
            "ncnn_import_${java.util.UUID.randomUUID()}"
        )
        check(temporaryDirectory.mkdirs()) {
            "Could not create temporary storage for the NCNN package."
        }
        try {
            requiredNames.forEach { filename ->
                val document = sourceFiles[filename]
                    ?: error("The NCNN folder is missing $filename.")
                val target = File(temporaryDirectory, filename)
                context.contentResolver.openInputStream(document.uri).use { input ->
                    requireNotNull(input) { "$filename could not be opened." }
                    target.outputStream().use(input::copyTo)
                }
                require(target.length() > 0L) { "$filename is empty." }
            }
            addNcnnModel(
                sourceDirectory = temporaryDirectory,
                name = name,
                type = type,
                inputSize = inputSize,
                confThreshold = confThreshold,
                iouThreshold = iouThreshold,
                classNames = classNames,
                useVulkan = useVulkan,
                detectionCount = detectionCount,
                outputFormat = outputFormat,
                exportMetadata = exportMetadata
            )
        } finally {
            requiredNames.forEach { filename ->
                File(temporaryDirectory, filename).delete()
            }
            temporaryDirectory.delete()
        }
    }

    suspend fun deleteModel(modelId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val registry = loadRegistryUnsafe()
            val target = registry.models.firstOrNull { it.id == modelId } ?: return@withContext

            val updated = registry.copy(models = registry.models.filterNot { it.id == modelId })
            saveRegistryUnsafe(updated)

            runCatching { File(target.filePath).delete() }
            target.auxiliaryFilePath?.let { path ->
                runCatching { File(path).delete() }
            }
            target.metadataFilePath?.let { path ->
                runCatching { File(path).delete() }
            }

        }
    }

    suspend fun getModel(modelId: String): InferenceModelConfig? = mutex.withLock {
        loadRegistryUnsafe().models.firstOrNull {
            it.id == modelId && it.isReleaseSupported
        }
    }

    suspend fun updateSkeletonConnections(
        modelId: String,
        connections: List<KeypointConnection>
    ): InferenceModelConfig = mutex.withLock {
        withContext(Dispatchers.IO) {
            val registry = loadRegistryUnsafe()
            val target = registry.models.firstOrNull { it.id == modelId }
                ?: error("The selected model is no longer available.")
            require(target.type == ModelType.POSE || connections.isEmpty()) {
                "Skeleton connections can be saved only for pose models."
            }
            require(connections.size <= 512) {
                "A skeleton can contain at most 512 connections."
            }
            val seen = mutableSetOf<Pair<Int, Int>>()
            connections.forEach { connection ->
                require(
                    connection.startIndex in 0..10_000 &&
                        connection.endIndex in 0..10_000 &&
                        connection.startIndex != connection.endIndex
                ) { "Skeleton connections contain an invalid keypoint index." }
                val normalized = minOf(connection.startIndex, connection.endIndex) to
                    maxOf(connection.startIndex, connection.endIndex)
                require(seen.add(normalized)) {
                    "Skeleton connections contain a duplicate edge."
                }
            }
            val updatedModel = target.copy(skeletonConnections = connections)
            saveRegistryUnsafe(
                registry.copy(
                    models = registry.models.map {
                        if (it.id == modelId) updatedModel else it
                    }
                )
            )
            updatedModel
        }
    }

    suspend fun updateDetectionCount(
        modelId: String,
        detectionCount: Int
    ): InferenceModelConfig = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(detectionCount in 1..5_000) {
                "Detection count must be between 1 and 5000."
            }
            val registry = loadRegistryUnsafe()
            val target = registry.models.firstOrNull { it.id == modelId }
                ?: error("The selected model is no longer available.")
            require(target.detectionCountIsRuntimeEditable) {
                "This model's detection count is fixed by its exported output. " +
                    "Re-export the model to change it."
            }
            val updatedModel = target.copy(detectionCount = detectionCount)
            saveRegistryUnsafe(
                registry.copy(
                    models = registry.models.map {
                        if (it.id == modelId) updatedModel else it
                    }
                )
            )
            updatedModel
        }
    }

    private fun loadRegistryUnsafe(): ModelRegistry {
        return try {
            atomicConfigFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                json.decodeFromString<ModelRegistry>(reader.readText())
            }
        } catch (_: FileNotFoundException) {
            ModelRegistry()
        } catch (error: Throwable) {
            throw IllegalStateException(
                "The saved model registry is unreadable. Imported model files were preserved; " +
                    "restore the registry from backup or re-import the models.",
                error
            )
        }
    }

    private fun saveRegistryUnsafe(registry: ModelRegistry) {
        val bytes = json.encodeToString(registry).toByteArray(Charsets.UTF_8)
        var output: FileOutputStream? = null
        try {
            output = atomicConfigFile.startWrite()
            output.write(bytes)
            atomicConfigFile.finishWrite(output)
            output = null
        } catch (error: Throwable) {
            output?.let(atomicConfigFile::failWrite)
            throw IllegalStateException("Could not save the model registry.", error)
        }
    }
}

@Serializable
private data class ModelRegistry(
    val models: List<InferenceModelConfig> = emptyList()
)
