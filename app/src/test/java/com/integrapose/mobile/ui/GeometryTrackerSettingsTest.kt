package com.integrapose.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeometryTrackerSettingsTest {
    @Test
    fun validGeometryTrackerValuesAreParsed() {
        val parsed = parseGeometryTrackerConfig(
            minimumConfidenceText = "0.12",
            newTrackConfidenceText = "0.30",
            matchIoUText = "0.40",
            maxMissingFramesText = "45"
        )

        assertEquals(0.12f, parsed.minimumConfidence, 0.0001f)
        assertEquals(0.30f, parsed.newTrackConfidence, 0.0001f)
        assertEquals(0.40f, parsed.matchIoU, 0.0001f)
        assertEquals(45, parsed.maxMissingFrames)
    }

    @Test
    fun newIdConfidenceCannotBeLowerThanTrackingMinimum() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGeometryTrackerConfig(
                minimumConfidenceText = "0.50",
                newTrackConfidenceText = "0.25",
                matchIoUText = "0.30",
                maxMissingFramesText = "30"
            )
        }
    }

    @Test
    fun lostTrackBufferRejectsUnsupportedValues() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGeometryTrackerConfig(
                minimumConfidenceText = "0.10",
                newTrackConfidenceText = "0.25",
                matchIoUText = "0.30",
                maxMissingFramesText = "601"
            )
        }
    }
}
