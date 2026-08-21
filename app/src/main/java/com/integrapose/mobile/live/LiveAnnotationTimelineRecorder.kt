package com.integrapose.mobile.live

import android.content.Context
import com.integrapose.mobile.inference.FrameInferenceResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal data class LiveAnnotationTimelineFile(
    val file: File,
    val inferenceSamples: Int,
    val droppedSamples: Int
)

/**
 * Records small, display-mapped inference results off the camera/inference path.
 * Pixel frames are deliberately not retained.
 */
internal class LiveAnnotationTimelineRecorder(context: Context) {
    private val context = context.applicationContext
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: Channel<JournalFrame>? = null
    private var writerJob: Job? = null
    private var journal: LiveInferenceJournal? = null
    private var journalFile: File? = null
    private var writerFailure = AtomicReference<Throwable?>(null)
    private var droppedSamples = AtomicInteger(0)
    private var acceptedSamples = AtomicInteger(0)
    private var nextFrameIndex = 0L
    private var active = false

    @Synchronized
    fun start() {
        close()
        writerFailure = AtomicReference(null)
        droppedSamples = AtomicInteger(0)
        acceptedSamples = AtomicInteger(0)
        nextFrameIndex = 0L
        val file = File.createTempFile(
            "integrapose_annotation_",
            ".journal",
            context.cacheDir
        )
        val activeJournal = LiveInferenceJournal(file)
        val sessionFailure = writerFailure
        val sessionDropped = droppedSamples
        val frameChannel = Channel<JournalFrame>(
            capacity = QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { sessionDropped.incrementAndGet() }
        )
        journalFile = file
        journal = activeJournal
        channel = frameChannel
        writerJob = writerScope.launch {
            try {
                for (frame in frameChannel) {
                    activeJournal.append(frame.frameIndex, frame.result)
                }
            } catch (error: Throwable) {
                sessionFailure.set(error)
                frameChannel.close(error)
            } finally {
                runCatching { activeJournal.close() }
            }
        }
        active = true
    }

    @Synchronized
    fun append(result: FrameInferenceResult) {
        if (!active) return
        val frame = JournalFrame(nextFrameIndex, result)
        if (channel?.trySend(frame)?.isSuccess == true) {
            nextFrameIndex += 1L
            acceptedSamples.incrementAndGet()
        } else {
            droppedSamples.incrementAndGet()
        }
    }

    suspend fun stop(): LiveAnnotationTimelineFile? {
        val state = synchronized(this) {
            if (!active) return null
            active = false
            channel?.close()
            StopState(
                file = journalFile,
                writerJob = writerJob,
                failure = writerFailure,
                accepted = acceptedSamples,
                dropped = droppedSamples
            ).also {
                channel = null
                writerJob = null
                journal = null
                journalFile = null
            }
        }
        state.writerJob?.join()
        state.failure.get()?.let { error ->
            state.file?.delete()
            throw IllegalStateException("Could not record the annotation timeline.", error)
        }
        val file = state.file?.takeIf { it.isFile && it.length() > 0L } ?: return null
        return LiveAnnotationTimelineFile(
            file = file,
            inferenceSamples = state.accepted.get(),
            droppedSamples = state.dropped.get()
        )
    }

    @Synchronized
    fun close() {
        active = false
        channel?.close()
        writerJob?.cancel()
        runCatching { journal?.close() }
        journalFile?.delete()
        channel = null
        writerJob = null
        journal = null
        journalFile = null
    }

    private data class JournalFrame(
        val frameIndex: Long,
        val result: FrameInferenceResult
    )

    private data class StopState(
        val file: File?,
        val writerJob: Job?,
        val failure: AtomicReference<Throwable?>,
        val accepted: AtomicInteger,
        val dropped: AtomicInteger
    )

    private companion object {
        const val QUEUE_CAPACITY = 256
    }
}
