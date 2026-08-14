package com.integrapose.mobile.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceModelConfigTest {
    @Test
    fun acceptsSupportedRawBboxKeypointOutput() {
        val model = model(ModelOutputFormat.RAW_PREDICTIONS)

        assertTrue(model.isReleaseSupported)
        model.requireSupportedModel()
    }

    @Test
    fun rawMobileNmsAllowsRuntimeDetectionCount() {
        val model = model(ModelOutputFormat.RAW_PREDICTIONS)

        assertTrue(model.detectionCountIsRuntimeEditable)
    }

    @Test
    fun exportedDetectionCountRemainsLocked() {
        val model = model(ModelOutputFormat.RAW_PREDICTIONS).copy(
            exportMetadata = ModelExportMetadata(
                endToEnd = false,
                embeddedNms = false,
                exportDetectionCount = 2
            )
        )

        assertFalse(model.detectionCountIsRuntimeEditable)
    }

    @Test
    fun endToEndOutputRemainsLockedWithoutDetectionCountMetadata() {
        val model = model(ModelOutputFormat.END_TO_END)

        assertFalse(model.detectionCountIsRuntimeEditable)
    }

    @Suppress("DEPRECATION")
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedHeatmapOutputFormat() {
        model(ModelOutputFormat.HEATMAP_POSE).requireSupportedModel()
    }

    private fun model(outputFormat: ModelOutputFormat) = InferenceModelConfig(
        name = "test",
        filePath = "model.ncnn.param",
        type = ModelType.POSE,
        runtime = ModelRuntime.NCNN_CPU,
        auxiliaryFilePath = "model.ncnn.bin",
        outputFormat = outputFormat,
        exportMetadata = ModelExportMetadata()
    )
}
