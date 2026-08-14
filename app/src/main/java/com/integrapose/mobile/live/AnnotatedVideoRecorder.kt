package com.integrapose.mobile.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Environment
import android.os.SystemClock
import com.integrapose.mobile.media.BitmapVideoEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class AnnotatedRecordingResult(
    val file: File? = null,
    val attemptedFrames: Int = 0,
    val acceptedFrames: Int = 0,
    val encodedFrames: Int = 0,
    val queueDroppedFrames: Int = 0,
    val acceptedFrameRate: Double = 0.0
)

/** Asynchronous MediaCodec H.264 recorder. */
class AnnotatedVideoRecorder(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var encoderJob: Job? = null
    private var frameChannel: Channel<RecordedFrame>? = null
    private var outputFile: File? = null
    private var frameCropRect: Rect? = null
    private var frameIntervalUs = 1_000_000L / MAX_RECORDING_FPS
    private var firstSourceTimestampUs: Long? = null
    private var lastAcceptedTimestampUs: Long? = null
    private var attemptedFrames = AtomicInteger(0)
    private var acceptedFrames = AtomicInteger(0)
    private var encodedFrames = AtomicInteger(0)
    private var queueDroppedFrames = AtomicInteger(0)

    val isRecording: Boolean
        get() = encoderJob?.isActive == true

    @Synchronized
    fun start(
        width: Int,
        height: Int,
        fps: Int = MAX_RECORDING_FPS,
        orientationHintDegrees: Int = 0,
        cropRect: Rect? = null
    ): File {
        check(!isRecording) { "Annotated recording is already running." }
        outputFile = null
        val safeFps = fps.coerceIn(1, MAX_RECORDING_FPS)
        val requestedCrop = Rect(cropRect ?: Rect(0, 0, width, height)).also {
            require(it.intersect(0, 0, width, height)) {
                "The annotated recording crop does not intersect the source frame."
            }
        }
        val targetWidth = requestedCrop.width() - requestedCrop.width() % 2
        val targetHeight = requestedCrop.height() - requestedCrop.height() % 2
        require(targetWidth > 0 && targetHeight > 0) {
            "The annotated recording crop is too small."
        }
        val sessionCrop = Rect(
            requestedCrop.left,
            requestedCrop.top,
            requestedCrop.left + targetWidth,
            requestedCrop.top + targetHeight
        )
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "annotated_$stamp.mp4")
        attemptedFrames = AtomicInteger(0)
        acceptedFrames = AtomicInteger(0)
        encodedFrames = AtomicInteger(0)
        queueDroppedFrames = AtomicInteger(0)
        val sessionEncodedFrames = encodedFrames
        val sessionQueueDroppedFrames = queueDroppedFrames
        val channel = Channel<RecordedFrame>(
            capacity = 6,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        ) { dropped ->
            sessionQueueDroppedFrames.incrementAndGet()
            dropped.bitmap.recycle()
        }
        val encoder = BitmapVideoEncoder(
            outputFile = file,
            width = targetWidth,
            height = targetHeight,
            frameRate = safeFps,
            orientationHintDegrees = orientationHintDegrees
        )
        frameIntervalUs = 1_000_000L / safeFps
        firstSourceTimestampUs = null
        lastAcceptedTimestampUs = null
        frameChannel = channel
        frameCropRect = sessionCrop
        outputFile = file
        encoderJob = scope.launch {
            try {
                for (recordedFrame in channel) {
                    val bitmap = recordedFrame.bitmap
                    val frame = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
                        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                    } else {
                        bitmap
                    }
                    try {
                        encoder.encodeFrame(frame, recordedFrame.presentationTimeUs)
                        sessionEncodedFrames.incrementAndGet()
                    } finally {
                        if (frame !== bitmap) frame.recycle()
                        bitmap.recycle()
                    }
                }
            } finally {
                encoder.close()
            }
        }
        return file
    }

    @Synchronized
    fun enqueueFrame(
        bitmap: Bitmap,
        sourceTimestampUs: Long = SystemClock.elapsedRealtimeNanos() / 1_000L
    ): Boolean {
        val channel = frameChannel ?: return false
        attemptedFrames.incrementAndGet()
        val safeTimestampUs = if (sourceTimestampUs > 0L) {
            sourceTimestampUs
        } else {
            SystemClock.elapsedRealtimeNanos() / 1_000L
        }
        val lastTimestamp = lastAcceptedTimestampUs
        if (lastTimestamp != null && safeTimestampUs - lastTimestamp < frameIntervalUs) {
            return false
        }
        val firstTimestamp = firstSourceTimestampUs ?: safeTimestampUs
        val crop = requireNotNull(frameCropRect) {
            "Annotated recording crop is unavailable."
        }
        require(crop.left >= 0 && crop.top >= 0 &&
            crop.right <= bitmap.width && crop.bottom <= bitmap.height
        ) {
            "Frame ${bitmap.width}x${bitmap.height} does not contain crop $crop."
        }
        val copy = Bitmap.createBitmap(
            crop.width(),
            crop.height(),
            Bitmap.Config.ARGB_8888
        ).also { cropped ->
            Canvas(cropped).drawBitmap(
                bitmap,
                crop,
                Rect(0, 0, cropped.width, cropped.height),
                null
            )
        }
        val sent = channel.trySend(
            RecordedFrame(
                bitmap = copy,
                presentationTimeUs = (safeTimestampUs - firstTimestamp).coerceAtLeast(0L)
            )
        )
        if (sent.isFailure) copy.recycle()
        if (sent.isSuccess) {
            firstSourceTimestampUs = firstTimestamp
            lastAcceptedTimestampUs = safeTimestampUs
            acceptedFrames.incrementAndGet()
        }
        return sent.isSuccess
    }

    suspend fun stop(): AnnotatedRecordingResult = withContext(Dispatchers.IO) {
        val activeChannel = frameChannel
            ?: return@withContext AnnotatedRecordingResult()
        activeChannel.close()
        runCatching { encoderJob?.join() }.getOrThrow()
        val firstTimestamp = firstSourceTimestampUs
        val lastTimestamp = lastAcceptedTimestampUs
        val acceptedCount = acceptedFrames.get()
        val acceptedRate = if (
            acceptedCount > 1 && firstTimestamp != null &&
            lastTimestamp != null && lastTimestamp > firstTimestamp
        ) {
            (acceptedCount - 1) * 1_000_000.0 /
                (lastTimestamp - firstTimestamp).toDouble()
        } else {
            0.0
        }
        val result = AnnotatedRecordingResult(
            file = outputFile,
            attemptedFrames = attemptedFrames.get(),
            acceptedFrames = acceptedCount,
            encodedFrames = encodedFrames.get(),
            queueDroppedFrames = queueDroppedFrames.get(),
            acceptedFrameRate = acceptedRate
        )
        frameChannel = null
        encoderJob = null
        frameCropRect = null
        firstSourceTimestampUs = null
        lastAcceptedTimestampUs = null
        result
    }

    private data class RecordedFrame(
        val bitmap: Bitmap,
        val presentationTimeUs: Long
    )

    companion object {
        const val MAX_RECORDING_FPS = 30
    }
}
