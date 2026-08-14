package com.integrapose.mobile.media

enum class AnnotationResolution(val width: Int, val height: Int, val displayName: String) {
    HD_720(1280, 720, "720p"),
    SD_360(640, 360, "360p");

    companion object {
        val default: AnnotationResolution = HD_720
    }
}

enum class FrameOrientation {
    LANDSCAPE,
    PORTRAIT
}

data class AnnotationDimensions(val width: Int, val height: Int)

fun AnnotationResolution.dimensionsFor(orientation: FrameOrientation): AnnotationDimensions {
    val isPortraitResolution = height > width
    val needsSwap = when (orientation) {
        FrameOrientation.PORTRAIT -> !isPortraitResolution
        FrameOrientation.LANDSCAPE -> isPortraitResolution
    }
    return if (needsSwap) {
        AnnotationDimensions(height, width)
    } else {
        AnnotationDimensions(width, height)
    }
}
