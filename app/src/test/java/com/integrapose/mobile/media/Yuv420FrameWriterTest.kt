package com.integrapose.mobile.media

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class Yuv420FrameWriterTest {
    @Test
    fun `writes padded planar rows without moving chroma`() {
        val padding = 0x55.toByte()
        val y = ByteBuffer.allocate(12).also { fill(it, padding) }
        val u = ByteBuffer.allocate(3).also { fill(it, padding) }
        val v = ByteBuffer.allocate(3).also { fill(it, padding) }
        val pixels = intArrayOf(
            RED, GREEN, BLUE, WHITE,
            BLACK, BLACK, BLACK, BLACK
        )

        Yuv420FrameWriter.writeArgb8888(
            pixels = pixels,
            width = 4,
            height = 2,
            planes = listOf(
                WritableYuvPlane(y, rowStride = 6, pixelStride = 1),
                WritableYuvPlane(u, rowStride = 3, pixelStride = 1),
                WritableYuvPlane(v, rowStride = 3, pixelStride = 1)
            )
        )

        assertEquals(luma(255, 0, 0), unsigned(y[0]))
        assertEquals(luma(0, 255, 0), unsigned(y[1]))
        assertEquals(luma(0, 0, 255), unsigned(y[2]))
        assertEquals(luma(255, 255, 255), unsigned(y[3]))
        assertEquals(unsigned(padding), unsigned(y[4]))
        assertEquals(unsigned(padding), unsigned(y[5]))
        assertEquals(luma(0, 0, 0), unsigned(y[6]))
        assertEquals(chromaU(255, 0, 0), unsigned(u[0]))
        assertEquals(chromaU(0, 0, 255), unsigned(u[1]))
        assertEquals(unsigned(padding), unsigned(u[2]))
        assertEquals(chromaV(255, 0, 0), unsigned(v[0]))
        assertEquals(chromaV(0, 0, 255), unsigned(v[1]))
        assertEquals(unsigned(padding), unsigned(v[2]))
    }

    @Test
    fun `respects interleaved chroma pixel strides`() {
        val padding = 0x33.toByte()
        val y = ByteBuffer.allocate(8).also { fill(it, padding) }
        val interleaved = ByteBuffer.allocate(8).also { fill(it, padding) }
        val u = interleaved.duplicate().apply {
            position(0)
            limit(interleaved.limit())
        }
        val v = interleaved.duplicate().apply {
            position(1)
            limit(interleaved.limit())
        }

        Yuv420FrameWriter.writeArgb8888(
            pixels = intArrayOf(
                RED, RED, BLUE, BLUE,
                RED, RED, BLUE, BLUE
            ),
            width = 4,
            height = 2,
            planes = listOf(
                WritableYuvPlane(y, rowStride = 4, pixelStride = 1),
                WritableYuvPlane(u, rowStride = 8, pixelStride = 2),
                WritableYuvPlane(v, rowStride = 8, pixelStride = 2)
            )
        )

        assertEquals(chromaU(255, 0, 0), unsigned(interleaved[0]))
        assertEquals(chromaV(255, 0, 0), unsigned(interleaved[1]))
        assertEquals(chromaU(0, 0, 255), unsigned(interleaved[2]))
        assertEquals(chromaV(0, 0, 255), unsigned(interleaved[3]))
        assertEquals(unsigned(padding), unsigned(interleaved[4]))
    }

    private fun fill(buffer: ByteBuffer, value: Byte) {
        for (index in 0 until buffer.limit()) buffer.put(index, value)
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private fun luma(red: Int, green: Int, blue: Int): Int =
        (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
            .coerceIn(0, 255)

    private fun chromaU(red: Int, green: Int, blue: Int): Int =
        (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128)
            .coerceIn(0, 255)

    private fun chromaV(red: Int, green: Int, blue: Int): Int =
        (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128)
            .coerceIn(0, 255)

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
