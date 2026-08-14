package com.integrapose.mobile.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmap(): Bitmap? {
    val fullFrame = when (format) {
        PixelFormat.RGBA_8888 -> rgbaToBitmap()
        ImageFormat.YUV_420_888 -> yuvToBitmap()
        else -> null
    } ?: return null
    val crop = cropRect
    val cropped = if (
        crop.left == 0 && crop.top == 0 &&
        crop.right == fullFrame.width && crop.bottom == fullFrame.height
    ) {
        fullFrame
    } else {
        Bitmap.createBitmap(
            fullFrame,
            crop.left.coerceIn(0, fullFrame.width - 1),
            crop.top.coerceIn(0, fullFrame.height - 1),
            crop.width().coerceIn(1, fullFrame.width - crop.left.coerceAtLeast(0)),
            crop.height().coerceIn(1, fullFrame.height - crop.top.coerceAtLeast(0))
        ).also { if (it !== fullFrame) fullFrame.recycle() }
    }
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return cropped

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(
        cropped,
        0,
        0,
        cropped.width,
        cropped.height,
        matrix,
        true
    )
    if (rotated !== cropped) cropped.recycle()
    return rotated
}

private fun ImageProxy.rgbaToBitmap(): Bitmap? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate()
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        val rowStart = row * plane.rowStride
        for (column in 0 until width) {
            val index = rowStart + column * plane.pixelStride
            val red = buffer.get(index).toInt() and 0xFF
            val green = buffer.get(index + 1).toInt() and 0xFF
            val blue = buffer.get(index + 2).toInt() and 0xFF
            val alpha = if (plane.pixelStride > 3) buffer.get(index + 3).toInt() and 0xFF else 0xFF
            pixels[row * width + column] =
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun ImageProxy.yuvToBitmap(): Bitmap? {
    val nv21 = yuv420888ToNv21()
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val output = ByteArrayOutputStream()
    if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 92, output)) return null
    val bytes = output.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun ImageProxy.yuv420888ToNv21(): ByteArray {
    require(planes.size >= 3) { "YUV image must have three planes." }
    val output = ByteArray(width * height * 3 / 2)
    val yPlane = planes[0]
    val yBuffer = yPlane.buffer.duplicate()
    var destination = 0
    for (row in 0 until height) {
        val rowStart = row * yPlane.rowStride
        for (column in 0 until width) {
            output[destination++] = yBuffer.get(rowStart + column * yPlane.pixelStride)
        }
    }

    val uPlane = planes[1]
    val vPlane = planes[2]
    val uBuffer = uPlane.buffer.duplicate()
    val vBuffer = vPlane.buffer.duplicate()
    for (row in 0 until height / 2) {
        for (column in 0 until width / 2) {
            output[destination++] = vBuffer.get(row * vPlane.rowStride + column * vPlane.pixelStride)
            output[destination++] = uBuffer.get(row * uPlane.rowStride + column * uPlane.pixelStride)
        }
    }
    return output
}
