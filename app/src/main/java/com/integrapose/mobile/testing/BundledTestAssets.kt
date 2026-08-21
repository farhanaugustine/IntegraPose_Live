package com.integrapose.mobile.testing

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.integrapose.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BundledTestAssets {
    const val ONNX_DISPLAY_NAME = "bundled_pose_fp16.onnx"
    const val VIDEO_DISPLAY_NAME = "behavior_test_20s.mp4"
    const val TWO_ANIMAL_VIDEO_DISPLAY_NAME = "two_subjects_20s.mp4"

    val ncnnAssetPaths: List<String> = listOf(
        "test_bundle/ncnn/model.ncnn.param",
        "test_bundle/ncnn/model.ncnn.bin",
        "test_bundle/ncnn/metadata.yaml"
    )

    val twoAnimalNcnnAssetPaths: List<String> = listOf(
        "tandem_benchmark/ncnn/model.ncnn.param",
        "tandem_benchmark/ncnn/model.ncnn.bin",
        "tandem_benchmark/ncnn/metadata.yaml"
    )

    private val testVideo = AssetSpec(
        assetPath = "test_bundle/video/behavior_test_20s.mp4",
        outputName = VIDEO_DISPLAY_NAME,
        expectedBytes = 303_654L
    )
    private val twoAnimalTestVideo = AssetSpec(
        assetPath = "tandem_benchmark/video/two_subjects_20s.mp4",
        outputName = TWO_ANIMAL_VIDEO_DISPLAY_NAME,
        expectedBytes = 10_626_970L
    )
    private val ncnnModel = listOf(
        AssetSpec(
            assetPath = ncnnAssetPaths[0],
            outputName = "model.ncnn.param",
            expectedBytes = 28_972L
        ),
        AssetSpec(
            assetPath = ncnnAssetPaths[1],
            outputName = "model.ncnn.bin",
            expectedBytes = 5_628_360L
        ),
        AssetSpec(
            assetPath = ncnnAssetPaths[2],
            outputName = "metadata.yaml",
            expectedBytes = 590L
        )
    )
    private val twoAnimalNcnnModel = listOf(
        AssetSpec(
            assetPath = twoAnimalNcnnAssetPaths[0],
            outputName = "model.ncnn.param",
            expectedBytes = 28_970L
        ),
        AssetSpec(
            assetPath = twoAnimalNcnnAssetPaths[1],
            outputName = "model.ncnn.bin",
            expectedBytes = 5_289_984L
        ),
        AssetSpec(
            assetPath = twoAnimalNcnnAssetPaths[2],
            outputName = "metadata.yaml",
            expectedBytes = 544L
        )
    )

    suspend fun prepareOnnxModel(context: Context): Uri {
        val onnxModel = withContext(Dispatchers.IO) {
            requireDebugBuild()
            val candidates = context.assets.list(ONNX_ASSET_DIRECTORY)
                .orEmpty()
                .filter { it.endsWith(".onnx", ignoreCase = true) }
            require(candidates.size == 1) {
                "The bundled test kit must contain exactly one ONNX model."
            }
            AssetSpec(
                assetPath = "$ONNX_ASSET_DIRECTORY/${candidates.single()}",
                outputName = ONNX_DISPLAY_NAME,
                expectedBytes = 5_728_017L
            )
        }
        return prepareUri(context, onnxModel, "onnx")
    }

    suspend fun prepareVideo(context: Context): Uri =
        prepareUri(context, testVideo, "video")

    suspend fun prepareTwoAnimalVideo(context: Context): Uri =
        prepareUri(context, twoAnimalTestVideo, "video_two_animal")

    suspend fun prepareNcnnModel(context: Context): File = withContext(Dispatchers.IO) {
        requireDebugBuild()
        val directory = File(bundleCacheRoot(context), "ncnn").also { it.mkdirs() }
        ncnnModel.forEach { copyAsset(context, it, directory) }
        directory
    }

    suspend fun prepareTwoAnimalNcnnModel(context: Context): File =
        withContext(Dispatchers.IO) {
            requireDebugBuild()
            val directory = File(
                bundleCacheRoot(context),
                "ncnn_two_animal"
            ).also { it.mkdirs() }
            twoAnimalNcnnModel.forEach { copyAsset(context, it, directory) }
            directory
        }

    private suspend fun prepareUri(
        context: Context,
        asset: AssetSpec,
        subdirectory: String
    ): Uri = withContext(Dispatchers.IO) {
        requireDebugBuild()
        val directory = File(bundleCacheRoot(context), subdirectory).also { it.mkdirs() }
        val file = copyAsset(context, asset, directory)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file
        )
    }

    private fun copyAsset(context: Context, asset: AssetSpec, directory: File): File {
        val destination = File(directory, asset.outputName)
        if (destination.isFile && destination.length() == asset.expectedBytes) {
            return destination
        }

        val partial = File(directory, asset.outputName + ".partial")
        context.assets.open(asset.assetPath, AssetManager.ACCESS_STREAMING).use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        require(partial.length() == asset.expectedBytes) {
            "Bundled test asset was incomplete: " + asset.outputName
        }
        if (destination.exists()) {
            require(destination.delete()) {
                "Could not replace the cached test asset: " + asset.outputName
            }
        }
        require(partial.renameTo(destination)) {
            "Could not prepare the bundled test asset: " + asset.outputName
        }
        return destination
    }

    private fun bundleCacheRoot(context: Context): File =
        File(context.cacheDir, "bundled_test_assets/v3").also { it.mkdirs() }

    private fun requireDebugBuild() {
        check(BuildConfig.BUNDLED_TEST_KIT) {
            "Bundled test assets are available only in debug builds."
        }
    }

    private data class AssetSpec(
        val assetPath: String,
        val outputName: String,
        val expectedBytes: Long
    )

    private const val ONNX_ASSET_DIRECTORY = "test_bundle/onnx"
}
