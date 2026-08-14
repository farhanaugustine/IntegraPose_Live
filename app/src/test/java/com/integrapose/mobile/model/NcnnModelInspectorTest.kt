package com.integrapose.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnModelInspectorTest {
    @Test
    fun ignoresUnrelatedMetadataFields() {
        val inspection = NcnnModelInspector.inspectMetadata(
            metadataText = """
                vendor_extension: experimental_pose_v1
                description: Experimental heatmap pose
                model_name: Experimental pose
                task: pose
                imgsz: [384, 384]
                names:
                  0: mouse
                end2end: false
                nms: false
            """.trimIndent(),
            directoryName = "experimental_pose"
        )

        assertEquals(true, inspection.isOutputCompatible)
        assertNull(inspection.unsupportedReason)
    }

    @Test
    fun readsCompatiblePoseRawOutputMetadataWithoutFamilyRequirement() {
        val inspection = NcnnModelInspector.inspectMetadata(
            metadataText = """
                description: Research bbox-plus-keypoint pose model
                version: 1.2.3
                task: pose
                imgsz:
                - 640
                - 640
                names:
                  0: mouse
                args:
                  batch: 1
                  half: true
                end2end: false
                kpt_shape:
                - 12
                - 3
            """.trimIndent(),
            directoryName = "best_ncnn_model"
        )

        assertEquals(true, inspection.isOutputCompatible)
        assertEquals(ModelType.POSE, inspection.detectedType)
        assertEquals(ModelOutputFormat.RAW_PREDICTIONS, inspection.outputFormat)
        assertEquals(640, inspection.inputSize)
        assertEquals(listOf("mouse"), inspection.classNames)
        assertEquals("12 × 3", inspection.keypointShape)
        assertEquals(1, inspection.recommendedDetectionCount)
        assertNull(inspection.unsupportedReason)
    }

    @Test
    fun rejectsMetadataClaimingEndToEndNcnnOutput() {
        val inspection = NcnnModelInspector.inspectMetadata(
            metadataText = """
                description: Research detection export
                task: detect
                imgsz: [320, 320]
                names:
                  0: walking
                  1: grooming
                args:
                  end2end: true
                  max_det: 4
            """.trimIndent(),
            directoryName = "behavior_ncnn_model"
        )

        assertEquals(ModelOutputFormat.RAW_PREDICTIONS, inspection.outputFormat)
        assertEquals(4, inspection.recommendedDetectionCount)
        assertTrue(inspection.exportMetadata.detectionCountLocked)
        assertEquals(listOf("walking", "grooming"), inspection.classNames)
        assertTrue(inspection.unsupportedReason?.contains("end2end=true") == true)
    }

    @Test
    fun rejectsMetadataClaimingEmbeddedNmsNcnnOutput() {
        val inspection = NcnnModelInspector.inspectMetadata(
            metadataText = """
                description: Research detection export
                task: detect
                imgsz: [320, 320]
                names:
                  0: animal
                end2end: false
                nms: true
            """.trimIndent(),
            directoryName = "behavior_ncnn_model"
        )

        assertEquals(ModelOutputFormat.RAW_PREDICTIONS, inspection.outputFormat)
        assertTrue(inspection.unsupportedReason?.contains("nms=true") == true)
    }

    @Test
    fun rejectsNcnnMetadataWithoutDetectionOrPoseTask() {
        val inspection = NcnnModelInspector.inspectMetadata(
            metadataText = """
                description: Research keypoint model
                imgsz: [640, 640]
                names:
                 0: animal
                end2end: false
            """.trimIndent(),
            directoryName = "research_ncnn_model"
        )

        assertEquals(false, inspection.isOutputCompatible)
        assertTrue(inspection.unsupportedReason?.contains("identify the model") == true)
    }
}
