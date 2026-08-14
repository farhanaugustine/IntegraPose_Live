package com.integrapose.mobile.inference

import com.integrapose.mobile.model.ModelOutputFormat
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.InferenceModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPostProcessorTest {
    @Test
    fun decodesRawChannelsFirstDetectionAndReversesLetterbox() {
        val config = modelConfig(
            type = ModelType.DETECTION,
            outputFormat = ModelOutputFormat.RAW_PREDICTIONS
        )
        val candidates = 10
        val output = FloatArray(5 * candidates)
        output[0] = 320f
        output[candidates] = 320f
        output[candidates * 2] = 320f
        output[candidates * 3] = 180f
        output[candidates * 4] = 0.9f

        val detections = DetectionPostProcessor.decode(
            config,
            output,
            longArrayOf(1, 5, candidates.toLong()),
            LetterboxTransform.calculate(1280, 720, 640, 640)
        )

        assertEquals(1, detections.size)
        assertEquals(320f, detections.single().box.left, 0.01f)
        assertEquals(960f, detections.single().box.right, 0.01f)
        assertEquals(180f, detections.single().box.top, 0.01f)
        assertEquals(540f, detections.single().box.bottom, 0.01f)
    }

    @Test
    fun decodesSmallEndToEndNormalizedOutput() {
        val config = modelConfig(
            type = ModelType.DETECTION,
            outputFormat = ModelOutputFormat.AUTO
        )
        val detections = DetectionPostProcessor.decode(
            config,
            floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f, 0.8f, 0f),
            longArrayOf(1, 1, 6),
            LetterboxTransform.calculate(1000, 500, 640, 640)
        )

        assertEquals(1, detections.size)
        assertTrue(detections.single().box.left in 249f..251f)
        assertTrue(detections.single().box.right in 749f..751f)
        assertEquals(0f, detections.single().box.top, 0.01f)
        assertEquals(500f, detections.single().box.bottom, 0.01f)
    }

    @Test
    fun rawPoseCoordinatesRemainMappedToSourceFramePixels() {
        val config = modelConfig(
            type = ModelType.POSE,
            outputFormat = ModelOutputFormat.RAW_PREDICTIONS
        )
        val candidates = 10
        val output = FloatArray(8 * candidates)
        output[0] = 320f
        output[candidates] = 320f
        output[candidates * 2] = 320f
        output[candidates * 3] = 180f
        output[candidates * 4] = 0.9f
        output[candidates * 5] = 320f
        output[candidates * 6] = 320f
        output[candidates * 7] = 0.8f

        val detection = DetectionPostProcessor.decode(
            config,
            output,
            longArrayOf(1, 8, candidates.toLong()),
            LetterboxTransform.calculate(1280, 720, 640, 640)
        ).single()

        assertEquals(320f, detection.box.left, 0.01f)
        assertEquals(960f, detection.box.right, 0.01f)
        assertEquals(180f, detection.box.top, 0.01f)
        assertEquals(540f, detection.box.bottom, 0.01f)
        assertEquals(640f, detection.keypoints.single().x, 0.01f)
        assertEquals(360f, detection.keypoints.single().y, 0.01f)
        assertEquals(0.8f, detection.keypoints.single().confidence, 0.001f)
    }

    @Test
    fun finalPoseCoordinatesRemainMappedToSourceFramePixels() {
        val config = modelConfig(
            type = ModelType.POSE,
            outputFormat = ModelOutputFormat.END_TO_END
        )

        val detection = DetectionPostProcessor.decode(
            config,
            floatArrayOf(
                0.25f, 0.25f, 0.75f, 0.75f, 0.9f, 0f,
                0.5f, 0.5f, 0.8f
            ),
            longArrayOf(1, 1, 9),
            LetterboxTransform.calculate(1000, 500, 640, 640)
        ).single()

        assertEquals(250f, detection.box.left, 0.01f)
        assertEquals(750f, detection.box.right, 0.01f)
        assertEquals(0f, detection.box.top, 0.01f)
        assertEquals(500f, detection.box.bottom, 0.01f)
        assertEquals(500f, detection.keypoints.single().x, 0.01f)
        assertEquals(250f, detection.keypoints.single().y, 0.01f)
        assertEquals(0.8f, detection.keypoints.single().confidence, 0.001f)
    }

    @Test
    fun finalDetectionsAreNotSuppressedTwice() {
        val config = modelConfig(
            type = ModelType.DETECTION,
            outputFormat = ModelOutputFormat.END_TO_END
        ).copy(detectionCount = 2)
        val output = floatArrayOf(
            100f, 100f, 300f, 300f, 0.9f, 0f,
            120f, 120f, 320f, 320f, 0.8f, 0f
        )

        val detections = DetectionPostProcessor.decode(
            config,
            output,
            longArrayOf(1, 2, 6),
            LetterboxTransform.calculate(640, 640, 640, 640)
        )

        assertEquals(2, detections.size)
        assertEquals(listOf(0.9f, 0.8f), detections.map { it.confidence })
    }

    @Test
    fun rawDetectionsStillReceiveMobileNms() {
        val config = modelConfig(
            type = ModelType.DETECTION,
            outputFormat = ModelOutputFormat.RAW_PREDICTIONS
        ).copy(detectionCount = 2)
        val candidates = 10
        val output = FloatArray(5 * candidates)
        output[0] = 200f
        output[1] = 220f
        output[candidates] = 200f
        output[candidates + 1] = 220f
        output[candidates * 2] = 200f
        output[candidates * 2 + 1] = 200f
        output[candidates * 3] = 200f
        output[candidates * 3 + 1] = 200f
        output[candidates * 4] = 0.9f
        output[candidates * 4 + 1] = 0.8f

        val detections = DetectionPostProcessor.decode(
            config,
            output,
            longArrayOf(1, 5, candidates.toLong()),
            LetterboxTransform.calculate(640, 640, 640, 640)
        )

        assertEquals(1, detections.size)
        assertEquals(0.9f, detections.single().confidence, 0.001f)
    }

    @Test
    fun deferredKeypointsRemainAttachedToClassAwareNmsSelections() {
        val config = modelConfig(
            type = ModelType.POSE,
            outputFormat = ModelOutputFormat.RAW_PREDICTIONS
        ).copy(
            classNames = listOf("first", "second"),
            detectionCount = 2
        )
        val output = floatArrayOf(
            200f, 200f, 100f, 100f, 0.9f, 0.1f, 100f, 110f, 0.8f,
            205f, 205f, 100f, 100f, 0.8f, 0.1f, 500f, 510f, 0.2f,
            200f, 200f, 100f, 100f, 0.1f, 0.7f, 300f, 310f, 0.6f
        )

        val detections = DetectionPostProcessor.decode(
            config,
            output,
            longArrayOf(1, 3, 9),
            LetterboxTransform.calculate(640, 640, 640, 640)
        )

        assertEquals(2, detections.size)
        assertEquals(listOf("first", "second"), detections.map { it.className })
        assertEquals(100f, detections[0].keypoints.single().x, 0.01f)
        assertEquals(110f, detections[0].keypoints.single().y, 0.01f)
        assertEquals(300f, detections[1].keypoints.single().x, 0.01f)
        assertEquals(310f, detections[1].keypoints.single().y, 0.01f)
    }

    private fun modelConfig(
        type: ModelType,
        outputFormat: ModelOutputFormat
    ) = InferenceModelConfig(
        name = "test",
        filePath = "unused.onnx",
        type = type,
        classNames = listOf("animal"),
        outputFormat = outputFormat
    )
}
