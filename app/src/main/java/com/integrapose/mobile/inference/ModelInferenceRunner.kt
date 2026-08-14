package com.integrapose.mobile.inference

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.platform.Fp16Conversions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.model.InferenceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

enum class InputLayout { NCHW, NHWC }

data class NcnnRuntimeTuning(
    val threads: Int,
    val useVulkan: Boolean,
    val source: String = "device benchmark"
) {
    init {
        require(threads in 1..8) { "NCNN threads must be between one and eight." }
    }
}

data class ModelRuntimeInfo(
    val inputWidth: Int,
    val inputHeight: Int,
    val inputLayout: InputLayout,
    val inputType: OnnxJavaType,
    val outputShapes: List<LongArray>,
    val backend: String
) {
    val displayText: String
        get() = "$backend | $inputType | ${inputWidth}x$inputHeight | $inputLayout"
}

class ModelInferenceRunner {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionMutex = Mutex()
    private val inferenceMutex = Mutex()
    private var loaded: LoadedRuntime? = null
    private var preprocessBuffers: PreprocessBuffers? = null

    suspend fun run(
        bitmap: Bitmap,
        config: InferenceModelConfig,
        sourceTimestampUs: Long = System.currentTimeMillis() * 1_000L,
        ncnnTuning: NcnnRuntimeTuning? = null
    ): FrameInferenceResult = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            val totalStartNs = System.nanoTime()
            val local = sessionMutex.withLock {
                ensureRuntime(config, ncnnTuning)
            }
            val preprocessStartNs = System.nanoTime()
            val prepared = preprocess(bitmap, local.info)
            val execution = executeRuntime(local, prepared)

            val inferenceEndNs = System.nanoTime()

            val detections = DetectionPostProcessor.decode(
                config = config,
                outputData = execution.output.data,
                outputShape = execution.output.shape,
                transform = prepared.transform
            )
            val postprocessEndNs = System.nanoTime()
            FrameInferenceResult(
                timestampMs = sourceTimestampUs / 1_000L,
                sourceTimestampUs = sourceTimestampUs,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                detections = detections,
                inferenceMs = nanosToMillis(postprocessEndNs - totalStartNs),
                preprocessingMs = nanosToMillis(
                    execution.preprocessEndNs - preprocessStartNs
                ),
                postprocessingMs = nanosToMillis(postprocessEndNs - inferenceEndNs),
                backend = local.info.backend,
                modelInputWidth = local.info.inputWidth,
                modelInputHeight = local.info.inputHeight
            )
        }
    }

    suspend fun describe(
        config: InferenceModelConfig,
        ncnnTuning: NcnnRuntimeTuning? = null
    ): ModelRuntimeInfo = withContext(Dispatchers.Default) {
        sessionMutex.withLock { ensureRuntime(config, ncnnTuning).info }
    }

    suspend fun close() {
        inferenceMutex.withLock {
            sessionMutex.withLock {
                loaded?.close()
                loaded = null
                preprocessBuffers?.close()
                preprocessBuffers = null
            }
        }
    }

    private fun executeRuntime(
        runtime: LoadedRuntime,
        prepared: PreparedInput
    ): RuntimeExecution = when (runtime) {
        is LoadedOnnx -> {
            val tensor = createInputTensor(
                values = prepared.values,
                directFloats = prepared.directFloats,
                shape = inputShape(runtime.info),
                inputType = runtime.info.inputType
            )
            val preprocessEndNs = System.nanoTime()
            val output = tensor.use { inputTensor ->
                runtime.session.run(mapOf(runtime.inputName to inputTensor)).use { values ->
                    val outputTensor = values[0] as? OnnxTensor
                        ?: throw IllegalStateException(
                            "The first model output is not a tensor."
                        )
                    val info = outputTensor.info as? TensorInfo
                        ?: throw IllegalStateException(
                            "The first model output has no tensor metadata."
                        )
                    val buffer = outputTensor.floatBuffer
                    val data = FloatArray(buffer.remaining())
                    buffer.get(data)
                    RuntimeTensorOutput(data, info.shape.copyOf())
                }
            }
            RuntimeExecution(output, preprocessEndNs)
        }

        is LoadedNcnn -> {
            prepared.directFloats.clear()
            prepared.directFloats.put(prepared.values)
            prepared.directFloats.flip()
            val preprocessEndNs = System.nanoTime()
            val output = NcnnNative.runDirect(
                handle = runtime.handle,
                input = prepared.directBytes,
                width = runtime.info.inputWidth,
                height = runtime.info.inputHeight
            )
            RuntimeExecution(
                output = RuntimeTensorOutput(output.data, output.shape),
                preprocessEndNs = preprocessEndNs
            )
        }
    }

    private fun ensureRuntime(
        config: InferenceModelConfig,
        ncnnTuning: NcnnRuntimeTuning?
    ): LoadedRuntime {
        val tuningKey = if (
            config.runtime == ModelRuntime.NCNN_CPU ||
            config.runtime == ModelRuntime.NCNN_VULKAN
        ) {
            ncnnTuning?.let { "${it.threads}:${it.useVulkan}" }
                ?: "model-default"
        } else {
            "not-applicable"
        }
        val cacheKey = listOf(
            config.id,
            config.runtime.name,
            config.filePath,
            config.auxiliaryFilePath.orEmpty(),
            tuningKey
        ).joinToString("|")
        loaded?.takeIf { it.cacheKey == cacheKey }?.let { return it }
        loaded?.close()
        loaded = null

        val runtime = when (config.runtime) {
            ModelRuntime.ONNX_CPU -> loadOnnxRuntime(config, cacheKey)
            ModelRuntime.NCNN_CPU,
            ModelRuntime.NCNN_VULKAN -> loadNcnnRuntime(
                config,
                cacheKey,
                ncnnTuning
            )
        }
        loaded = runtime
        return runtime
    }

    private fun loadOnnxRuntime(
        config: InferenceModelConfig,
        cacheKey: String
    ): LoadedOnnx {
        val modelFile = File(config.filePath)
        require(modelFile.isFile) { "Model file not found: ${config.filePath}" }
        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val inferenceThreads = min(4, max(1, coreCount - 1))
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(inferenceThreads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = try {
            environment.createSession(modelFile.absolutePath, options)
        } catch (error: Throwable) {
            options.close()
            throw error
        }

        try {
            val inputName = session.inputNames.firstOrNull()
                ?: throw IllegalArgumentException("The model has no input tensor.")
            val tensorInfo = session.inputInfo[inputName]?.info as? TensorInfo
                ?: throw IllegalArgumentException("The model input is not a tensor.")
            val info = buildRuntimeInfo(tensorInfo, session, config, inferenceThreads)
            return LoadedOnnx(cacheKey, session, options, inputName, info)
        } catch (error: Throwable) {
            session.close()
            options.close()
            throw error
        }
    }

    private fun loadNcnnRuntime(
        config: InferenceModelConfig,
        cacheKey: String,
        tuning: NcnnRuntimeTuning?
    ): LoadedNcnn {
        config.requireSupportedModel()
        val paramFile = File(config.filePath)
        require(paramFile.isFile) {
            "NCNN parameter file not found: ${config.filePath}"
        }
        val weightsPath = requireNotNull(config.auxiliaryFilePath) {
            "This NCNN model is missing its .bin weights file."
        }
        val weightsFile = File(weightsPath)
        require(weightsFile.isFile) {
            "NCNN weights file not found: $weightsPath"
        }

        val requestedSize = config.inputSize.coerceIn(32, 2_048)
        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val inferenceThreads = tuning?.threads?.coerceIn(1, 8)
            ?: min(4, max(1, coreCount - 1))
        val useVulkan = tuning?.useVulkan
            ?: (config.runtime == ModelRuntime.NCNN_VULKAN)
        val handle = NcnnNative.create(
            paramPath = paramFile.absolutePath,
            weightsPath = weightsFile.absolutePath,
            threads = inferenceThreads,
            useVulkan = useVulkan
        )
        val info = ModelRuntimeInfo(
            inputWidth = requestedSize,
            inputHeight = requestedSize,
            inputLayout = InputLayout.NCHW,
            inputType = OnnxJavaType.FLOAT,
            outputShapes = emptyList(),
            backend = if (useVulkan) {
                "NCNN Vulkan (${tuning?.source ?: "model setting"})"
            } else {
                "NCNN CPU ($inferenceThreads threads, " +
                    "${tuning?.source ?: "model setting"})"
            }
        )
        return LoadedNcnn(cacheKey, handle, info)
    }

    private fun buildRuntimeInfo(
        inputInfo: TensorInfo,
        session: OrtSession,
        config: InferenceModelConfig,
        threads: Int
    ): ModelRuntimeInfo {
        val shape = inputInfo.shape
        require(shape.size == 4) {
            "Expected a four-dimensional image input, received ${shape.contentToString()}."
        }
        val layout = when {
            shape[1] == 3L -> InputLayout.NCHW
            shape[3] == 3L -> InputLayout.NHWC
            shape[1] <= 0L && shape[3] != 3L -> InputLayout.NCHW
            else -> throw IllegalArgumentException(
                "Could not identify RGB channel layout for input ${shape.contentToString()}."
            )
        }
        val requestedSize = config.inputSize.coerceIn(32, 2_048)
        val inputHeightIndex = if (layout == InputLayout.NCHW) 2 else 1
        val inputWidthIndex = if (layout == InputLayout.NCHW) 3 else 2
        val height = shape[inputHeightIndex].takeIf { it > 0L }?.toInt() ?: requestedSize
        val width = shape[inputWidthIndex].takeIf { it > 0L }?.toInt() ?: requestedSize
        require(width > 0 && height > 0) { "Invalid model input dimensions: ${width}x$height." }
        require(inputInfo.type in SUPPORTED_INPUT_TYPES) {
            "Unsupported model input type ${inputInfo.type}; use FLOAT, FLOAT16, or BFLOAT16."
        }
        val outputShapes = session.outputInfo.values.mapNotNull { it.info as? TensorInfo }
            .map { it.shape.copyOf() }
        return ModelRuntimeInfo(
            inputWidth = width,
            inputHeight = height,
            inputLayout = layout,
            inputType = inputInfo.type,
            outputShapes = outputShapes,
            backend = "ONNX Runtime CPU ($threads threads)"
        )
    }

    private fun preprocess(bitmap: Bitmap, info: ModelRuntimeInfo): PreparedInput {
        val transform = LetterboxTransform.calculate(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            modelWidth = info.inputWidth,
            modelHeight = info.inputHeight
        )
        val buffers = requirePreprocessBuffers(info.inputWidth, info.inputHeight)
        val letterboxed = buffers.letterboxed
        val canvas = buffers.canvas
        canvas.drawColor(Color.rgb(114, 114, 114))
        val destination = RectF(
            transform.padX,
            transform.padY,
            transform.padX + bitmap.width * transform.scale,
            transform.padY + bitmap.height * transform.scale
        )
        canvas.drawBitmap(bitmap, null, destination, RESIZE_PAINT)

        val pixelCount = buffers.pixelCount
        val pixels = buffers.pixels
        letterboxed.getPixels(pixels, 0, info.inputWidth, 0, 0, info.inputWidth, info.inputHeight)

        val values = buffers.values
        if (info.inputLayout == InputLayout.NCHW) {
            for (index in pixels.indices) {
                val pixel = pixels[index]
                values[index] = ((pixel shr 16) and 0xFF) / 255f
                values[index + pixelCount] = ((pixel shr 8) and 0xFF) / 255f
                values[index + pixelCount * 2] = (pixel and 0xFF) / 255f
            }
        } else {
            var outputIndex = 0
            for (pixel in pixels) {
                values[outputIndex++] = ((pixel shr 16) and 0xFF) / 255f
                values[outputIndex++] = ((pixel shr 8) and 0xFF) / 255f
                values[outputIndex++] = (pixel and 0xFF) / 255f
            }
        }
        return PreparedInput(
            values,
            buffers.directBytes,
            buffers.directFloats,
            transform
        )
    }

    private fun requirePreprocessBuffers(
        width: Int,
        height: Int
    ): PreprocessBuffers {
        preprocessBuffers?.takeIf {
            it.width == width && it.height == height && !it.letterboxed.isRecycled
        }?.let { return it }
        preprocessBuffers?.close()
        return PreprocessBuffers.create(width, height).also {
            preprocessBuffers = it
        }
    }

    private fun inputShape(info: ModelRuntimeInfo): LongArray =
        if (info.inputLayout == InputLayout.NCHW) {
            longArrayOf(1L, 3L, info.inputHeight.toLong(), info.inputWidth.toLong())
        } else {
            longArrayOf(1L, info.inputHeight.toLong(), info.inputWidth.toLong(), 3L)
        }

    private fun createInputTensor(
        values: FloatArray,
        directFloats: FloatBuffer,
        shape: LongArray,
        inputType: OnnxJavaType
    ): OnnxTensor {
        directFloats.clear()
        directFloats.put(values)
        directFloats.flip()
        return when (inputType) {
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(
                environment,
                directFloats,
                shape
            )
            OnnxJavaType.FLOAT16 -> {
                val halves = Fp16Conversions.convertFloatBufferToFp16Buffer(
                    directFloats
                )
                halves.rewind()
                OnnxTensor.createTensor(environment, halves, shape)
            }
            OnnxJavaType.BFLOAT16 -> {
                val halves = Fp16Conversions.convertFloatBufferToBf16Buffer(
                    directFloats
                )
                halves.rewind()
                OnnxTensor.createTensor(environment, halves, shape)
            }
            else -> throw OrtException("Unsupported input tensor type: $inputType")
        }
    }

    private sealed interface LoadedRuntime {
        val cacheKey: String
        val info: ModelRuntimeInfo
        fun close()
    }

    private data class LoadedOnnx(
        override val cacheKey: String,
        val session: OrtSession,
        val options: OrtSession.SessionOptions,
        val inputName: String,
        override val info: ModelRuntimeInfo
    ) : LoadedRuntime {
        override fun close() {
            try {
                session.close()
            } finally {
                options.close()
            }
        }
    }

    private data class LoadedNcnn(
        override val cacheKey: String,
        val handle: Long,
        override val info: ModelRuntimeInfo
    ) : LoadedRuntime {
        override fun close() {
            NcnnNative.destroy(handle)
        }
    }

    private data class RuntimeTensorOutput(
        val data: FloatArray,
        val shape: LongArray
    )

    private data class RuntimeExecution(
        val output: RuntimeTensorOutput,
        val preprocessEndNs: Long
    )

    private data class PreparedInput(
        val values: FloatArray,
        val directBytes: ByteBuffer,
        val directFloats: FloatBuffer,
        val transform: LetterboxTransform
    )

    private class PreprocessBuffers private constructor(
        val width: Int,
        val height: Int,
        val letterboxed: Bitmap,
        val canvas: Canvas,
        val pixels: IntArray,
        val values: FloatArray,
        val directBytes: ByteBuffer,
        val directFloats: FloatBuffer
    ) {
        val pixelCount: Int = width * height

        fun close() {
            if (!letterboxed.isRecycled) letterboxed.recycle()
        }

        companion object {
            fun create(width: Int, height: Int): PreprocessBuffers {
                val pixelCount = width * height
                val letterboxed = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                val directBytes = ByteBuffer
                    .allocateDirect(pixelCount * 3 * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                return PreprocessBuffers(
                    width = width,
                    height = height,
                    letterboxed = letterboxed,
                    canvas = Canvas(letterboxed),
                    pixels = IntArray(pixelCount),
                    values = FloatArray(pixelCount * 3),
                    directBytes = directBytes,
                    directFloats = directBytes.asFloatBuffer()
                )
            }
        }
    }

    private companion object {
        val RESIZE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val SUPPORTED_INPUT_TYPES = setOf(
            OnnxJavaType.FLOAT,
            OnnxJavaType.FLOAT16,
            OnnxJavaType.BFLOAT16
        )

        fun nanosToMillis(nanos: Long): Long = (nanos / 1_000_000L).coerceAtLeast(0L)
    }
}
