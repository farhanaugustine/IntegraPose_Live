package com.integrapose.mobile.media

import java.nio.ByteBuffer

/** Writable view of one YUV plane as reported by [android.media.Image.Plane]. */
internal data class WritableYuvPlane(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int
)

/**
 * Converts ARGB pixels into the actual YUV_420_888 plane layout supplied by MediaCodec.
 *
 * Flexible YUV does not promise tightly packed I420. In particular, U and V can be interleaved
 * and every plane can have padded rows. Writing through the reported row and pixel strides keeps
 * luma and chroma at the same coordinates on vendor codecs.
 */
internal object Yuv420FrameWriter {
    fun writeArgb8888(
        pixels: IntArray,
        width: Int,
        height: Int,
        planes: List<WritableYuvPlane>,
        cropLeft: Int = 0,
        cropTop: Int = 0
    ) {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "YUV420 frames require positive even dimensions."
        }
        require(cropLeft >= 0 && cropTop >= 0 && cropLeft % 2 == 0 && cropTop % 2 == 0) {
            "YUV420 crop offsets must be non-negative even values."
        }
        require(pixels.size >= width * height) { "The ARGB frame is incomplete." }
        require(planes.size == 3) { "YUV_420_888 must expose Y, U, and V planes." }

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        validatePlane(yPlane, cropLeft, cropTop, width, height, "Y")
        validatePlane(
            uPlane,
            cropLeft / 2,
            cropTop / 2,
            width / 2,
            height / 2,
            "U"
        )
        validatePlane(
            vPlane,
            cropLeft / 2,
            cropTop / 2,
            width / 2,
            height / 2,
            "V"
        )

        for (y in 0 until height) {
            val sourceRow = y * width
            for (x in 0 until width) {
                val pixel = pixels[sourceRow + x]
                val red = (pixel ushr 16) and 0xFF
                val green = (pixel ushr 8) and 0xFF
                val blue = pixel and 0xFF
                putSample(
                    plane = yPlane,
                    x = cropLeft + x,
                    y = cropTop + y,
                    value = luma(red, green, blue)
                )
                if (x % 2 == 0 && y % 2 == 0) {
                    val chromaX = (cropLeft + x) / 2
                    val chromaY = (cropTop + y) / 2
                    putSample(uPlane, chromaX, chromaY, chromaU(red, green, blue))
                    putSample(vPlane, chromaX, chromaY, chromaV(red, green, blue))
                }
            }
        }
    }

    private fun validatePlane(
        plane: WritableYuvPlane,
        startX: Int,
        startY: Int,
        sampleWidth: Int,
        sampleHeight: Int,
        name: String
    ) {
        require(plane.rowStride > 0 && plane.pixelStride > 0) {
            "$name plane has invalid strides."
        }
        require(!plane.buffer.isReadOnly) { "$name plane is read-only." }
        val base = plane.buffer.position().toLong()
        val finalIndex = base +
            (startY + sampleHeight - 1L) * plane.rowStride.toLong() +
            (startX + sampleWidth - 1L) * plane.pixelStride.toLong()
        require(finalIndex in base until plane.buffer.limit().toLong()) {
            "$name plane is too small for the requested frame and crop."
        }
    }

    private fun putSample(
        plane: WritableYuvPlane,
        x: Int,
        y: Int,
        value: Int
    ) {
        val index = plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride
        plane.buffer.put(index, value.coerceIn(0, 255).toByte())
    }

    private fun luma(red: Int, green: Int, blue: Int): Int =
        (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
            .coerceIn(0, 255)

    private fun chromaU(red: Int, green: Int, blue: Int): Int =
        (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128)
            .coerceIn(0, 255)

    private fun chromaV(red: Int, green: Int, blue: Int): Int =
        (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128)
            .coerceIn(0, 255)
}
