package com.integrapose.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelMetadataParserTest {
    @Test
    fun readsPythonClassDictionary() {
        assertEquals(
            listOf("mouse", "food pellet"),
            ModelMetadataParser.parseClassNames(
                "{0: 'mouse', 1: 'food pellet'}"
            )
        )
    }

    @Test
    fun readsPythonClassList() {
        assertEquals(
            listOf("fly", "arena"),
            ModelMetadataParser.parseClassNames("['fly', 'arena']")
        )
    }

    @Test
    fun keepsClassIdsStableWhenAnEditableRowIsMissing() {
        assertEquals(
            listOf("mouse", "class_1", "food"),
            ModelMetadataParser.parseEditableMapping(
                "0=mouse\n2=food"
            )
        )
    }

    @Test
    fun readsTaskAndSquareInputSize() {
        assertEquals(
            ModelType.POSE,
            ModelMetadataParser.inferModelType("pose")
        )
        assertEquals(
            ModelType.DETECTION,
            ModelMetadataParser.inferModelType("detect")
        )
        assertEquals(
            640,
            ModelMetadataParser.parseSquareInputSize("[640, 640]")
        )
        assertEquals(
            null,
            ModelMetadataParser.parseSquareInputSize("[640, 384]")
        )
    }

    @Test
    fun readsOutputContractArguments() {
        val arguments = "{'batch': 1, 'end2end': true, 'nms': false, 'max_det': 7}"

        assertEquals(
            true,
            ModelMetadataParser.parseBooleanArgument(arguments, "end2end")
        )
        assertEquals(
            false,
            ModelMetadataParser.parseBooleanArgument(arguments, "nms")
        )
        assertEquals(
            7,
            ModelMetadataParser.parsePositiveIntArgument(arguments, "max_det")
        )
    }

}
