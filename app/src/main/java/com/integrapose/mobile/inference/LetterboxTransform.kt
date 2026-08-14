package com.integrapose.mobile.inference

import kotlin.math.min

/** Maps source-image pixels to a letterboxed model input and back again. */
data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float
) {
    fun modelToSourceX(x: Float): Float =
        ((x - padX) / scale).coerceIn(0f, sourceWidth.toFloat())

    fun modelToSourceY(y: Float): Float =
        ((y - padY) / scale).coerceIn(0f, sourceHeight.toFloat())

    companion object {
        fun calculate(
            sourceWidth: Int,
            sourceHeight: Int,
            modelWidth: Int,
            modelHeight: Int
        ): LetterboxTransform {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source image dimensions must be positive." }
            require(modelWidth > 0 && modelHeight > 0) { "Model input dimensions must be positive." }

            val scale = min(
                modelWidth.toFloat() / sourceWidth.toFloat(),
                modelHeight.toFloat() / sourceHeight.toFloat()
            )
            val scaledWidth = sourceWidth * scale
            val scaledHeight = sourceHeight * scale
            return LetterboxTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                modelWidth = modelWidth,
                modelHeight = modelHeight,
                scale = scale,
                padX = (modelWidth - scaledWidth) / 2f,
                padY = (modelHeight - scaledHeight) / 2f
            )
        }
    }
}
