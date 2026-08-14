package com.integrapose.mobile.live

/**
 * CameraX geometry that must stay atomic across a recording-session rebind.
 *
 * [targetRotation] and [isLandscapeViewport] describe the same display state. Mixing a new
 * rotation with an older configuration orientation can rotate or offset live annotations.
 */
internal data class LiveCameraGeometry(
    val targetRotation: Int,
    val isLandscapeViewport: Boolean
)

/** Keeps the geometry observed at recording start until all recording outputs are finalized. */
internal data class LiveCameraGeometrySession(
    private val recordingSnapshot: LiveCameraGeometry? = null
) {
    fun effective(observed: LiveCameraGeometry): LiveCameraGeometry =
        recordingSnapshot ?: observed

    fun lock(observed: LiveCameraGeometry): LiveCameraGeometrySession =
        if (recordingSnapshot == null) copy(recordingSnapshot = observed) else this

    fun unlock(): LiveCameraGeometrySession = LiveCameraGeometrySession()
}
