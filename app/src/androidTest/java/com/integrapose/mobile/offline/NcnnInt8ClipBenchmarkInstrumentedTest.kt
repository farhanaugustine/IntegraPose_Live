package com.integrapose.mobile.offline

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.model.ModelOutputFormat
import com.integrapose.mobile.model.ModelRuntime
import com.integrapose.mobile.model.ModelType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Development-only emulator benchmark.
 *
 * Model and video assets are intentionally staged into the installed app's private files
 * directory with adb. They are never bundled into either Android source tree.
 */
@RunWith(AndroidJUnit4::class)
class NcnnInt8ClipBenchmarkInstrumentedTest {
    @Test
    fun compareOptimizedFp32AndInt8OnClip() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val frameCount = arguments.getString("bench_frames")?.toIntOrNull() ?: 30
        val threads = arguments.getString("bench_threads")?.toIntOrNull() ?: 4
        val requestedBackend = arguments.getString("bench_backend")
            ?.lowercase(Locale.US) ?: "cpu"
        require(frameCount in 1..404)
        require(threads in 1..8)
        require(requestedBackend in setOf("cpu", "vulkan", "both"))

        val root = File(context.filesDir, "ncnn_bench")
        val video = File(root, "benchmark.mp4").requireRegularFile()
        val fp32 = modelConfig(root, "fp32", "YOLO26X optimized FP32")
        val int8 = modelConfig(root, "int8", "YOLO26X NCNN INT8")
        val videoUri = Uri.fromFile(video)
        val backends = when (requestedBackend) {
            "cpu" -> listOf(NativeNcnnBackend.CPU)
            "vulkan" -> listOf(NativeNcnnBackend.VULKAN)
            else -> listOf(NativeNcnnBackend.CPU, NativeNcnnBackend.VULKAN)
        }

        backends.forEach { backend ->
            val fp32Result = NativeMediaPipeline.benchmarkNcnn(
                context = context,
                uri = videoUri,
                model = fp32,
                maxFrames = frameCount,
                threads = threads,
                workers = 1,
                backend = backend
            )
            val int8Result = NativeMediaPipeline.benchmarkNcnn(
                context = context,
                uri = videoUri,
                model = int8,
                maxFrames = frameCount,
                threads = threads,
                workers = 1,
                backend = backend
            )

            assertTrue(fp32Result.framesProcessed > 0)
            assertEquals(fp32Result.framesProcessed, int8Result.framesProcessed)
            printResult("FP32", fp32Result)
            printResult("INT8", int8Result)
            println(
                "INTEGRAPOSE_NCNN_COMPARISON" +
                    " backend=${backend.name}" +
                    " frames=${fp32Result.framesProcessed}" +
                    " inference_speedup=${formatRatio(
                        int8Result.inferenceFps,
                        fp32Result.inferenceFps
                    )}" +
                    " pipeline_speedup=${formatRatio(
                        int8Result.pipelineFps,
                        fp32Result.pipelineFps
                    )}"
            )
        }
    }

    private fun modelConfig(
        root: File,
        directoryName: String,
        displayName: String
    ): InferenceModelConfig {
        val directory = File(root, directoryName)
        val param = File(directory, "model.ncnn.param").requireRegularFile()
        val weights = File(directory, "model.ncnn.bin").requireRegularFile()
        return InferenceModelConfig(
            id = "emulator-benchmark-$directoryName",
            name = displayName,
            filePath = param.absolutePath,
            auxiliaryFilePath = weights.absolutePath,
            type = ModelType.DETECTION,
            runtime = ModelRuntime.NCNN_CPU,
            inputSize = 640,
            confThreshold = 0.25f,
            iouThreshold = 0.45f,
            classNames = listOf("Sniffing", "Wall-Rearing", "Ambulatory"),
            outputFormat = ModelOutputFormat.RAW_PREDICTIONS
        )
    }

    private fun File.requireRegularFile(): File = also {
        require(isFile && canRead()) {
            "Missing emulator benchmark asset: $absolutePath"
        }
    }

    private fun printResult(
        precision: String,
        result: NativeNcnnPipelineBenchmark
    ) {
        println(
            "INTEGRAPOSE_NCNN_RESULT" +
                " precision=$precision" +
                " backend=${if (result.usesVulkan) "VULKAN" else "CPU"}" +
                " frames=${result.framesProcessed}" +
                " threads=${result.threads}" +
                " workers=${result.workers}" +
                " wall_ms=${result.wallTimeMs}" +
                " inference_ms=${result.inferenceTimeMs}" +
                " preprocess_ms=${result.preprocessingTimeMs}" +
                " postprocess_ms=${result.postprocessingTimeMs}" +
                " pipeline_fps=${format(result.pipelineFps)}" +
                " inference_fps=${format(result.inferenceFps)}" +
                " detections=${result.totalDetections}"
        )
    }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.4f", value)

    private fun formatRatio(numerator: Double, denominator: Double): String =
        if (denominator > 0.0) format(numerator / denominator) else "nan"
}
