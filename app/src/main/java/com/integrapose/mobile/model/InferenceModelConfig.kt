package com.integrapose.mobile.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.util.UUID

@Serializable
data class InferenceModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val filePath: String,
    val type: ModelType,
    val runtime: ModelRuntime = ModelRuntime.ONNX_CPU,
    val auxiliaryFilePath: String? = null,
    val metadataFilePath: String? = null,
    val inputSize: Int = 640,
    val confThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f,
    val classNames: List<String> = emptyList(),
    val outputFormat: ModelOutputFormat = ModelOutputFormat.AUTO,
    val coordinateFormat: ModelCoordinateFormat = ModelCoordinateFormat.AUTO,
    @SerialName("maxDetections")
    val detectionCount: Int = 1,
    val skeletonConnections: List<KeypointConnection> = emptyList(),
    val exportMetadata: ModelExportMetadata = ModelExportMetadata(),
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val detectionCountIsRuntimeEditable: Boolean
        get() = outputFormat != ModelOutputFormat.END_TO_END &&
            exportMetadata.endToEnd != true &&
            exportMetadata.embeddedNms != true &&
            !exportMetadata.detectionCountLocked

    @Suppress("DEPRECATION")
    val isReleaseSupported: Boolean
        get() = outputFormat != ModelOutputFormat.HEATMAP_POSE

    fun requireSupportedModel() {
        require(isReleaseSupported) {
            "This experimental model package is not supported by this release. " +
                "Import a compatible bbox detection or bbox-plus-keypoint pose " +
                "model in ONNX or NCNN format."
        }
    }
}
