package com.integrapose.mobile.live

import android.content.Context
import android.os.Environment
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CameraX source-camera recorder. Audio is intentionally not requested. */
class RawCameraRecorder(private val context: Context) {
    private var recording: Recording? = null
    private var completion: CompletableDeferred<Result<File?>>? = null

    @Synchronized
    fun start(videoCapture: VideoCapture<Recorder>): File {
        check(recording == null) { "Raw camera recording is already running." }
        val file = createOutputFile()
        val deferred = CompletableDeferred<Result<File?>>()
        completion = deferred
        recording = videoCapture.output
            .prepareRecording(
                context,
                FileOutputOptions.Builder(file).build()
            )
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    val result = if (event.hasError()) {
                        Result.failure(
                            event.cause ?: IllegalStateException(
                                "Raw recording failed with CameraX error " +
                                    event.error
                            )
                        )
                    } else if (file.isFile && file.length() > 0L) {
                        Result.success(file)
                    } else {
                        Result.success(null)
                    }
                    deferred.complete(result)
                    synchronized(this) {
                        recording = null
                    }
                }
            }
        return file
    }

    suspend fun stop(): File? {
        val active: Recording?
        val pending: CompletableDeferred<Result<File?>>?
        synchronized(this) {
            active = recording
            pending = completion
        }
        active?.stop()
        val result = pending?.let {
            withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { it.await() }
                ?: throw IllegalStateException(
                    "Timed out finalizing the raw camera recording."
                )
        }
        synchronized(this) {
            completion = null
        }
        return result?.getOrThrow()
    }

    @Synchronized
    fun close() {
        recording?.close()
        recording = null
        completion?.cancel()
        completion = null
    }

    private fun createOutputFile(): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())
        return File(directory, "live_raw_" + stamp + ".mp4")
    }

    private companion object {
        const val FINALIZE_TIMEOUT_MS = 20_000L
    }
}
