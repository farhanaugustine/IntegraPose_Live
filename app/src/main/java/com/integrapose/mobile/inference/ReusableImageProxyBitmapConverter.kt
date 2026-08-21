package com.integrapose.mobile.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

data class ConvertedImageProxyBitmap(
    val bitmap: Bitmap,
    val recycleAfterUse: Boolean,
    val usedBulkRgbaCopy: Boolean
)

/**
 * Per-worker RGBA converter for the canonical live path.
 *
 * CameraX RGBA output is already packed as R,G,B,A bytes. Bitmap's pixel-buffer copy accepts
 * that packed representation, so the common path can bulk-copy a row at a time without a fresh
 * IntArray or per-channel ByteBuffer.get() calls. The base bitmap and row-pack buffer are reused.
 */
class ReusableImageProxyBitmapConverter : AutoCloseable {
    private var baseBitmap: Bitmap? = null
    private var transformedBitmap: Bitmap? = null
    private var packedRows: ByteBuffer? = null
    private var fallbackPixels: IntArray? = null

    fun convert(image: ImageProxy): ConvertedImageProxyBitmap? {
        if (image.format != PixelFormat.RGBA_8888) {
            val bitmap = image.toBitmap() ?: return null
            return ConvertedImageProxyBitmap(bitmap, recycleAfterUse = true, false)
        }
        val plane = image.planes.firstOrNull() ?: return null
        val base = ensureBaseBitmap(image.width, image.height)
        val usedBulkCopy = copyRgbaPlane(
            bitmap = base,
            source = plane.buffer,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride
        )

        val crop = image.cropRect
        val safeLeft = crop.left.coerceIn(0, image.width - 1)
        val safeTop = crop.top.coerceIn(0, image.height - 1)
        val safeWidth = crop.width().coerceIn(1, image.width - safeLeft)
        val safeHeight = crop.height().coerceIn(1, image.height - safeTop)
        val fullFrame =
            safeLeft == 0 && safeTop == 0 &&
                safeWidth == image.width && safeHeight == image.height

        val rotation = image.imageInfo.rotationDegrees
        val retainedOutput = if (rotation == 0 && fullFrame) {
            base
        } else if (rotation in ORTHOGONAL_ROTATIONS) {
            renderTransformed(
                source = base,
                crop = Rect(
                    safeLeft,
                    safeTop,
                    safeLeft + safeWidth,
                    safeTop + safeHeight
                ),
                rotationDegrees = rotation
            )
        } else {
            null
        }
        val output = retainedOutput ?: run {
            val cropped = if (fullFrame) {
                base
            } else {
                Bitmap.createBitmap(base, safeLeft, safeTop, safeWidth, safeHeight)
            }
            Bitmap.createBitmap(
                cropped,
                0,
                0,
                cropped.width,
                cropped.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true
            ).also { rotated ->
                if (cropped !== base && rotated !== cropped) cropped.recycle()
            }
        }
        return ConvertedImageProxyBitmap(
            bitmap = output,
            recycleAfterUse = output !== base && output !== transformedBitmap,
            usedBulkRgbaCopy = usedBulkCopy
        )
    }

    private fun ensureBaseBitmap(width: Int, height: Int): Bitmap {
        val current = baseBitmap
        if (current != null && !current.isRecycled &&
            current.width == width && current.height == height
        ) {
            return current
        }
        current?.takeUnless(Bitmap::isRecycled)?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            baseBitmap = it
        }
    }

    private fun renderTransformed(
        source: Bitmap,
        crop: Rect,
        rotationDegrees: Int
    ): Bitmap {
        val outputWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            crop.height()
        } else {
            crop.width()
        }
        val outputHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            crop.width()
        } else {
            crop.height()
        }
        val output = ensureTransformedBitmap(outputWidth, outputHeight)
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.save()
        when (rotationDegrees) {
            90 -> {
                canvas.translate(outputWidth.toFloat(), 0f)
                canvas.rotate(90f)
            }
            180 -> {
                canvas.translate(outputWidth.toFloat(), outputHeight.toFloat())
                canvas.rotate(180f)
            }
            270 -> {
                canvas.translate(0f, outputHeight.toFloat())
                canvas.rotate(270f)
            }
        }
        canvas.drawBitmap(
            source,
            crop,
            RectF(0f, 0f, crop.width().toFloat(), crop.height().toFloat()),
            TRANSFORM_PAINT
        )
        canvas.restore()
        return output
    }

    private fun ensureTransformedBitmap(width: Int, height: Int): Bitmap {
        val current = transformedBitmap
        if (current != null && !current.isRecycled &&
            current.width == width && current.height == height
        ) {
            return current
        }
        current?.takeUnless(Bitmap::isRecycled)?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            transformedBitmap = it
        }
    }

    private fun copyRgbaPlane(
        bitmap: Bitmap,
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Boolean {
        val rowBytes = width * 4
        if (pixelStride == 4) {
            val pixels = if (rowStride == rowBytes) {
                source.duplicate().apply {
                    clear()
                    limit(rowBytes * height)
                }
            } else {
                val packed = ensurePackedRows(rowBytes * height)
                packed.clear()
                repeat(height) { row ->
                    val rowSource = source.duplicate().apply {
                        clear()
                        position(row * rowStride)
                        limit(row * rowStride + rowBytes)
                    }
                    packed.put(rowSource)
                }
                packed.flip()
                packed
            }
            bitmap.copyPixelsFromBuffer(pixels)
            return true
        }

        val requiredPixels = width * height
        val pixels = fallbackPixels
            ?.takeIf { it.size == requiredPixels }
            ?: IntArray(requiredPixels).also { fallbackPixels = it }
        val buffer = source.duplicate()
        repeat(height) { row ->
            val rowStart = row * rowStride
            repeat(width) { column ->
                val index = rowStart + column * pixelStride
                val red = buffer.get(index).toInt() and 0xFF
                val green = buffer.get(index + 1).toInt() and 0xFF
                val blue = buffer.get(index + 2).toInt() and 0xFF
                val alpha = if (pixelStride > 3) {
                    buffer.get(index + 3).toInt() and 0xFF
                } else {
                    0xFF
                }
                pixels[row * width + column] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return false
    }

    private fun ensurePackedRows(capacity: Int): ByteBuffer {
        val current = packedRows
        if (current != null && current.capacity() >= capacity) return current
        return ByteBuffer.allocateDirect(capacity).also { packedRows = it }
    }

    override fun close() {
        baseBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        transformedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        baseBitmap = null
        transformedBitmap = null
        packedRows = null
        fallbackPixels = null
    }

    private companion object {
        val ORTHOGONAL_ROTATIONS = setOf(0, 90, 180, 270)
        val TRANSFORM_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}
