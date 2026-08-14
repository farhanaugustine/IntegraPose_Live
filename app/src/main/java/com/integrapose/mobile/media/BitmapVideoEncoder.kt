package com.integrapose.mobile.media

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File

class BitmapVideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitRate: Int = 4_000_000,
    orientationHintDegrees: Int = 0
) : Closeable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val muxer: MediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val argbPixels = IntArray(width * height)
    private val frameDurationUs = 1_000_000L / frameRate.coerceAtLeast(1)
    private var nextPresentationTimeUs: Long = 0L
    private var lastPresentationTimeUs: Long = -1L
    private var trackIndex: Int = -1
    private var muxerStarted = false
    private var closed = false

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "H.264 output dimensions must be positive even numbers."
        }
        val normalizedOrientation = ((orientationHintDegrees % 360) + 360) % 360
        require(normalizedOrientation in setOf(0, 90, 180, 270)) {
            "MP4 orientation must be 0, 90, 180, or 270 degrees."
        }
        muxer.setOrientationHint(normalizedOrientation)
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encodeFrame(
        bitmap: android.graphics.Bitmap,
        presentationTimeUs: Long? = null
    ) {
        check(!closed) { "Encoder is already closed." }
        require(bitmap.width == width && bitmap.height == height) {
            "Frame is ${bitmap.width}x${bitmap.height}; encoder expects ${width}x$height."
        }
        val inputIndex = awaitInputBuffer()
        val queuedPresentationTimeUs = presentationTimeUs
            ?.coerceAtLeast(lastPresentationTimeUs + 1L)
            ?: nextPresentationTimeUs
        val inputCapacity = requireNotNull(codec.getInputBuffer(inputIndex)) {
            "MediaCodec returned a null input buffer."
        }.capacity()
        val inputImage = codec.getInputImage(inputIndex)
        if (inputImage == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, queuedPresentationTimeUs, 0)
            error(
                "The selected H.264 encoder does not expose writable YUV planes. " +
                    "Try the device's standard hardware encoder."
            )
        }
        try {
            require(inputImage.format == ImageFormat.YUV_420_888) {
                "The H.264 encoder returned unsupported input format ${inputImage.format}."
            }
            val crop = inputImage.cropRect
            require(crop.width() >= width && crop.height() >= height) {
                "The H.264 input image is smaller than the requested video frame."
            }
            bitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
            Yuv420FrameWriter.writeArgb8888(
                pixels = argbPixels,
                width = width,
                height = height,
                planes = inputImage.planes.map { plane ->
                    WritableYuvPlane(
                        buffer = plane.buffer,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride
                    )
                },
                cropLeft = crop.left,
                cropTop = crop.top
            )
            codec.queueInputBuffer(
                inputIndex,
                0,
                inputCapacity,
                queuedPresentationTimeUs,
                0
            )
        } catch (error: Throwable) {
            runCatching {
                codec.queueInputBuffer(inputIndex, 0, 0, queuedPresentationTimeUs, 0)
            }
            throw error
        }
        lastPresentationTimeUs = queuedPresentationTimeUs
        nextPresentationTimeUs = queuedPresentationTimeUs + frameDurationUs
        drainEncoder(false)
    }

    private fun awaitInputBuffer(): Int {
        repeat(MAX_INPUT_RETRIES) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            drainEncoder(false)
        }
        throw IllegalStateException("Timed out waiting for an H.264 encoder input buffer.")
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            val inputIndex = awaitInputBuffer()
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                nextPresentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }

        var idlePolls = 0
        loop@ while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break@loop
                    idlePolls += 1
                    if (idlePolls >= MAX_END_OF_STREAM_POLLS) {
                        throw IllegalStateException("Timed out finalizing the H.264 stream.")
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IllegalStateException("Format changed twice")
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    idlePolls = 0
                    val encodedData = requireNotNull(codec.getOutputBuffer(outputIndex)) {
                        "MediaCodec returned a null output buffer."
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break@loop
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            drainEncoder(true)
        } finally {
            codec.stop()
            codec.release()
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
        }
    }

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val TIMEOUT_US = 10_000L
        private const val MAX_INPUT_RETRIES = 100
        private const val MAX_END_OF_STREAM_POLLS = 500
    }
}
