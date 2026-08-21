package com.integrapose.mobile.media

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.Closeable
import java.io.File

/**
 * H.264 encoder that lets Android render directly into the codec input surface.
 * This avoids copying every bitmap into an IntArray and converting every pixel
 * from ARGB to YUV in Kotlin.
 */
class SurfaceCanvasVideoEncoder(
    outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    bitRate: Int = 5_000_000
) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val muxer = MediaMuxer(
        outputFile.absolutePath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    )
    private val inputSurface: Surface
    private val frameDurationUs = 1_000_000L / frameRate.coerceAtLeast(1)
    private var trackIndex = -1
    private var muxerStarted = false
    private var closed = false
    private var submittedFrames = 0
    var writtenFrames: Int = 0
        private set

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "H.264 output dimensions must be positive even numbers."
        }
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun encodeFrame(drawFrame: (Canvas) -> Unit) {
        check(!closed) { "Encoder is already closed." }
        val canvas = runCatching { inputSurface.lockHardwareCanvas() }
            .getOrElse { inputSurface.lockCanvas(null) }
        try {
            drawFrame(canvas)
        } finally {
            inputSurface.unlockCanvasAndPost(canvas)
        }
        submittedFrames += 1
        drainEncoder(endOfStream = false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) codec.signalEndOfInputStream()
        var idlePolls = 0
        loop@ while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break@loop
                    idlePolls += 1
                    if (idlePolls >= MAX_END_OF_STREAM_POLLS) {
                        error("Timed out finalizing the H.264 surface encoder.")
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "H.264 output format changed twice." }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    idlePolls = 0
                    val encodedData = requireNotNull(codec.getOutputBuffer(outputIndex)) {
                        "MediaCodec returned a null output buffer."
                    }
                    val isConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (bufferInfo.size > 0 && !isConfig && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        bufferInfo.presentationTimeUs = writtenFrames * frameDurationUs
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        writtenFrames += 1
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
            drainEncoder(endOfStream = true)
            check(writtenFrames == submittedFrames) {
                "H.264 encoder wrote $writtenFrames of $submittedFrames submitted frames."
            }
        } finally {
            runCatching { inputSurface.release() }
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private companion object {
        const val MIME_TYPE = "video/avc"
        const val TIMEOUT_US = 10_000L
        const val MAX_END_OF_STREAM_POLLS = 500
    }
}
