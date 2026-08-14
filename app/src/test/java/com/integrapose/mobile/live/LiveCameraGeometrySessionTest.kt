package com.integrapose.mobile.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCameraGeometrySessionTest {
    private val portrait = LiveCameraGeometry(
        targetRotation = 0,
        isLandscapeViewport = false
    )
    private val landscape = LiveCameraGeometry(
        targetRotation = 1,
        isLandscapeViewport = true
    )

    @Test
    fun idleSessionFollowsObservedGeometry() {
        val session = LiveCameraGeometrySession()

        assertEquals(portrait, session.effective(portrait))
        assertEquals(landscape, session.effective(landscape))
    }

    @Test
    fun recordingSessionKeepsAtomicStartGeometryAcrossConfigurationChange() {
        val session = LiveCameraGeometrySession().lock(portrait)

        assertEquals(portrait, session.effective(landscape))
    }

    @Test
    fun repeatedLockCannotReplaceActiveRecordingGeometry() {
        val session = LiveCameraGeometrySession()
            .lock(portrait)
            .lock(landscape)

        assertEquals(portrait, session.effective(landscape))
    }

    @Test
    fun unlockReturnsToCurrentObservedGeometry() {
        val session = LiveCameraGeometrySession().lock(portrait).unlock()

        assertEquals(landscape, session.effective(landscape))
    }
}
