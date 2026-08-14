package com.integrapose.mobile.live

import android.content.Context
import androidx.camera.core.CameraSelector
import com.integrapose.mobile.inference.OverlayCalibration

internal class LiveOverlayCalibrationStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "live_overlay_calibration",
        Context.MODE_PRIVATE
    )

    init {
        if (preferences.getInt(MAPPING_VERSION_KEY, 0) < MAPPING_VERSION) {
            // Older values compensated for a crop-relative CameraX matrix and a device-specific
            // rear-camera flip. Those corrections are invalid with full-buffer mapping.
            val editor = preferences.edit()
            listOf("front", "rear").forEach { prefix ->
                editor
                    .remove("${prefix}_orientation")
                    .remove("${prefix}_flip_horizontal")
                    .remove("${prefix}_flip_vertical")
                    .remove("${prefix}_rotation_degrees")
                    .remove("${prefix}_offset_x")
                    .remove("${prefix}_offset_y")
                    .remove("${prefix}_scale_x")
                    .remove("${prefix}_scale_y")
            }
            editor.putInt(MAPPING_VERSION_KEY, MAPPING_VERSION).apply()
        }
    }

    fun load(lensFacing: Int): OverlayCalibration {
        val prefix = keyPrefix(lensFacing)
        val defaults = defaultFor(lensFacing)
        var flipHorizontal = preferences.getBoolean(
            "${prefix}_flip_horizontal",
            defaults.flipHorizontal
        )
        var flipVertical = preferences.getBoolean(
            "${prefix}_flip_vertical",
            defaults.flipVertical
        )
        var rotationDegrees = preferences.getFloat(
            "${prefix}_rotation_degrees",
            defaults.rotationDegrees
        )
        if (!preferences.contains("${prefix}_flip_horizontal") &&
            !preferences.contains("${prefix}_flip_vertical") &&
            !preferences.contains("${prefix}_rotation_degrees")) {
            when (preferences.getString("${prefix}_orientation", "AUTOMATIC")) {
                "REVERSE_HORIZONTAL" -> {
                    flipHorizontal = true
                    flipVertical = false
                    rotationDegrees = 0f
                }
                "REVERSE_VERTICAL" -> {
                    flipHorizontal = false
                    flipVertical = true
                    rotationDegrees = 0f
                }
                "ROTATE_180" -> {
                    flipHorizontal = false
                    flipVertical = false
                    rotationDegrees = 180f
                }
            }
        }
        return OverlayCalibration(
            flipHorizontal = flipHorizontal,
            flipVertical = flipVertical,
            rotationDegrees = rotationDegrees,
            offsetXFraction = preferences.getFloat("${prefix}_offset_x", 0f),
            offsetYFraction = preferences.getFloat("${prefix}_offset_y", 0f),
            scaleX = preferences.getFloat("${prefix}_scale_x", 1f),
            scaleY = preferences.getFloat("${prefix}_scale_y", 1f)
        ).sanitized()
    }

    fun loadFillPreview(lensFacing: Int): Boolean =
        preferences.getBoolean(
            "${keyPrefix(lensFacing)}_fill_preview",
            DEFAULT_FILL_PREVIEW
        )

    @Suppress("UNUSED_PARAMETER")
    fun defaultFor(lensFacing: Int): OverlayCalibration {
        // CameraX's source-to-PreviewView matrix handles rotation and front-camera mirroring.
        // Manual calibration is an optional target-side adjustment, never a phone-specific
        // default.
        return OverlayCalibration.Default
    }

    fun save(lensFacing: Int, calibration: OverlayCalibration) {
        val prefix = keyPrefix(lensFacing)
        val safe = calibration.sanitized()
        preferences.edit()
            .putBoolean("${prefix}_flip_horizontal", safe.flipHorizontal)
            .putBoolean("${prefix}_flip_vertical", safe.flipVertical)
            .putFloat("${prefix}_rotation_degrees", safe.rotationDegrees)
            .putFloat("${prefix}_offset_x", safe.offsetXFraction)
            .putFloat("${prefix}_offset_y", safe.offsetYFraction)
            .putFloat("${prefix}_scale_x", safe.scaleX)
            .putFloat("${prefix}_scale_y", safe.scaleY)
            .remove("${prefix}_orientation")
            .apply()
    }

    fun saveFillPreview(lensFacing: Int, fillPreview: Boolean) {
        preferences.edit()
            .putBoolean("${keyPrefix(lensFacing)}_fill_preview", fillPreview)
            .apply()
    }

    fun reset(lensFacing: Int) {
        val prefix = keyPrefix(lensFacing)
        preferences.edit()
            .remove("${prefix}_orientation")
            .remove("${prefix}_flip_horizontal")
            .remove("${prefix}_flip_vertical")
            .remove("${prefix}_rotation_degrees")
            .remove("${prefix}_offset_x")
            .remove("${prefix}_offset_y")
            .remove("${prefix}_scale_x")
            .remove("${prefix}_scale_y")
            .remove("${prefix}_fill_preview")
            .apply()
    }

    private fun keyPrefix(lensFacing: Int): String =
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "rear"

    companion object {
        const val DEFAULT_FILL_PREVIEW = true
        private const val MAPPING_VERSION_KEY = "automatic_mapping_version"
        private const val MAPPING_VERSION = 2
    }
}
