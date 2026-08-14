package com.integrapose.mobile.live

import android.content.Context
import com.integrapose.mobile.analytics.BehaviorBoutTracker
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.analytics.BoutSettings
import com.integrapose.mobile.analytics.RoiAnalyticsSettings
import com.integrapose.mobile.analytics.RoiDwellTracker
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.offline.BoutCsvWriter
import com.integrapose.mobile.offline.RoiVisitCsvWriter
import com.integrapose.mobile.tracking.IoUTracker
import com.integrapose.mobile.tracking.IoUTrackerConfig
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
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

data class LiveMetricSelection(
    val detectionCsv: Boolean = true,
    val classBouts: Boolean = false,
    val roiVisits: Boolean = false,
    val assignTrackIds: Boolean = false
) {
    val any: Boolean get() = detectionCsv || classBouts || roiVisits
}

data class LiveMetricFiles(
    val detectionCsvPath: String? = null,
    val boutCsvPath: String? = null,
    val roiCsvPath: String? = null,
    val analyzedFrames: Int = 0,
    val droppedJournalFrames: Int = 0,
    val observedFrameRate: Double = 0.0,
    val analyticsDurationMs: Long = 0L
)

/**
 * Keeps behavior analytics out of the live inference hot path. Mapped inference
 * results are offered to a bounded IO queue at up to 30 Hz. Tracking, final CSV
 * output, bout construction, and ROI visits run only after recording stops.
 */
class LiveMetricsRecorder(context: Context) {
    private val context = context.applicationContext
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var droppedFrames = AtomicInteger(0)
    private var writerFailure = AtomicReference<Throwable?>(null)

    private var selection = LiveMetricSelection()
    private var modelType = ModelType.DETECTION
    private var rois = emptyList<BehaviorRoi>()
    private var boutSettings = BoutSettings()
    private var roiSettings = RoiAnalyticsSettings()
    private var trackerConfig = IoUTrackerConfig()
    private var frameChannel: Channel<JournalFrame>? = null
    private var writerJob: Job? = null
    private var journal: LiveInferenceJournal? = null
    private var journalFile: File? = null
    private var firstTimestampUs: Long? = null
    private var lastAcceptedTimestampUs: Long? = null
    private var lastFrameIndex = 0L
    private var active = false

    @Synchronized
    fun start(
        modelType: ModelType,
        selection: LiveMetricSelection,
        rois: List<BehaviorRoi>,
        boutSettings: BoutSettings,
        roiSettings: RoiAnalyticsSettings,
        trackerConfig: IoUTrackerConfig
    ) {
        closeActiveSession()
        this.modelType = modelType
        this.selection = selection
        this.rois = rois.map(BehaviorRoi::sanitized)
        this.boutSettings = boutSettings.sanitized()
        this.roiSettings = roiSettings.sanitized()
        this.trackerConfig = trackerConfig.sanitized()
        resetCounters()
        if (!selection.any) return

        val file = File.createTempFile("integrapose_live_", ".journal", context.cacheDir)
        val activeJournal = LiveInferenceJournal(file)
        val sessionDroppedFrames = droppedFrames
        val sessionWriterFailure = writerFailure
        val channel = Channel<JournalFrame>(
            capacity = JOURNAL_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { sessionDroppedFrames.incrementAndGet() }
        )
        journalFile = file
        journal = activeJournal
        frameChannel = channel
        writerJob = writerScope.launch {
            try {
                for (frame in channel) {
                    activeJournal.append(frame.frameIndex, frame.result)
                }
            } catch (error: Throwable) {
                sessionWriterFailure.set(error)
                channel.close(error)
            } finally {
                runCatching { activeJournal.close() }
            }
        }
        active = true
    }

    @Synchronized
    fun append(result: FrameInferenceResult) {
        if (!active) return
        val timestampUs = result.sourceTimestampUs
        val previousTimestamp = lastAcceptedTimestampUs
        if (
            previousTimestamp != null &&
            timestampUs - previousTimestamp < FRAME_INTERVAL_US
        ) {
            return
        }
        val first = firstTimestampUs ?: timestampUs
        val minimumNextIndex = if (previousTimestamp == null) 0L else lastFrameIndex + 1L
        val frameIndex = (
            (timestampUs - first).coerceAtLeast(0L).toDouble() /
                FRAME_INTERVAL_US.toDouble()
            ).roundToLong().coerceAtLeast(minimumNextIndex)
        val sent = frameChannel?.trySend(JournalFrame(frameIndex, result))
        if (sent?.isSuccess == true) {
            firstTimestampUs = first
            lastAcceptedTimestampUs = timestampUs
            lastFrameIndex = frameIndex
        } else {
            droppedFrames.incrementAndGet()
        }
    }

    suspend fun stop(): LiveMetricFiles {
        val state = synchronized(this) {
            active = false
            frameChannel?.close()
            StopState(
                selection = selection,
                modelType = modelType,
                rois = rois,
                boutSettings = boutSettings,
                roiSettings = roiSettings,
                trackerConfig = trackerConfig,
                journalFile = journalFile,
                writerJob = writerJob,
                droppedFrames = droppedFrames,
                writerFailure = writerFailure
            ).also {
                frameChannel = null
                writerJob = null
                journal = null
                journalFile = null
                firstTimestampUs = null
                lastAcceptedTimestampUs = null
            }
        }
        state.writerJob?.join()
        state.writerFailure.get()?.let { error ->
            state.journalFile?.delete()
            throw IllegalStateException("Could not record live inference results.", error)
        }
        val file = state.journalFile ?: return LiveMetricFiles()
        return withContext(Dispatchers.IO) {
            processJournal(state, file)
        }
    }

    @Synchronized
    fun close() {
        closeActiveSession()
    }

    private fun processJournal(state: StopState, file: File): LiveMetricFiles {
        val startedNs = System.nanoTime()
        val tracker = if (state.selection.assignTrackIds) {
            IoUTracker(state.trackerConfig)
        } else {
            null
        }
        val boutTracker = if (state.selection.classBouts) {
            BehaviorBoutTracker(state.boutSettings)
        } else {
            null
        }
        val roiTracker = if (state.selection.roiVisits && state.rois.isNotEmpty()) {
            RoiDwellTracker(state.rois, state.roiSettings)
        } else {
            null
        }
        var csvWriter: CsvSessionWriter? = if (state.selection.detectionCsv) {
            CsvSessionWriter(context).also { it.start(state.modelType, "live") }
        } else {
            null
        }
        var analyzedFrames = 0
        var finalFrameIndex = 0
        var firstTimestamp = UNSET_TIMESTAMP
        var lastTimestamp = UNSET_TIMESTAMP
        return try {
            LiveInferenceJournal.forEachFrame(file) { journalIndex, rawResult ->
                val frameIndex = journalIndex.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val result = tracker?.let {
                    rawResult.copy(
                        detections = it.update(rawResult.detections, frameIndex)
                    )
                } ?: rawResult
                csvWriter?.append(result, journalIndex)
                boutTracker?.onFrame(frameIndex, result.detections)
                roiTracker?.onFrame(
                    frameIndex = frameIndex,
                    imageWidth = result.imageWidth,
                    imageHeight = result.imageHeight,
                    detections = result.detections
                )
                if (firstTimestamp == UNSET_TIMESTAMP) {
                    firstTimestamp = result.sourceTimestampUs
                }
                lastTimestamp = result.sourceTimestampUs
                finalFrameIndex = frameIndex
                analyzedFrames += 1
            }
            val detectionFile = csvWriter?.close()
            csvWriter = null
            val boutFile = boutTracker?.let {
                BoutCsvWriter.write(
                    context = context,
                    bouts = it.finish(finalFrameIndex, RECORDING_FPS),
                    frameRate = RECORDING_FPS,
                    prefix = "live_detailed_bouts"
                )
            }
            val roiFile = roiTracker?.let {
                RoiVisitCsvWriter.write(
                    context = context,
                    visits = it.finish(finalFrameIndex, RECORDING_FPS),
                    prefix = "live_roi_dwell_events"
                )
            }
            LiveMetricFiles(
                detectionCsvPath = detectionFile?.absolutePath,
                boutCsvPath = boutFile?.absolutePath,
                roiCsvPath = roiFile?.absolutePath,
                analyzedFrames = analyzedFrames,
                droppedJournalFrames = state.droppedFrames.get(),
                observedFrameRate = observedRate(
                    analyzedFrames,
                    firstTimestamp,
                    lastTimestamp
                ),
                analyticsDurationMs =
                    (System.nanoTime() - startedNs) / 1_000_000L
            )
        } finally {
            runCatching { csvWriter?.close() }
            file.delete()
        }
    }

    private fun closeActiveSession() {
        active = false
        frameChannel?.close()
        writerJob?.cancel()
        runCatching { journal?.close() }
        journalFile?.delete()
        frameChannel = null
        writerJob = null
        journal = null
        journalFile = null
        firstTimestampUs = null
        lastAcceptedTimestampUs = null
        resetCounters()
    }

    private fun resetCounters() {
        droppedFrames = AtomicInteger(0)
        writerFailure = AtomicReference(null)
        lastFrameIndex = 0L
    }

    private fun observedRate(
        frames: Int,
        firstTimestampUs: Long,
        lastTimestampUs: Long
    ): Double {
        val durationUs = lastTimestampUs - firstTimestampUs
        return if (frames > 1 && durationUs > 0L) {
            (frames - 1) * 1_000_000.0 / durationUs
        } else {
            0.0
        }
    }

    private data class JournalFrame(
        val frameIndex: Long,
        val result: FrameInferenceResult
    )

    private data class StopState(
        val selection: LiveMetricSelection,
        val modelType: ModelType,
        val rois: List<BehaviorRoi>,
        val boutSettings: BoutSettings,
        val roiSettings: RoiAnalyticsSettings,
        val trackerConfig: IoUTrackerConfig,
        val journalFile: File?,
        val writerJob: Job?,
        val droppedFrames: AtomicInteger,
        val writerFailure: AtomicReference<Throwable?>
    )

    private companion object {
        const val RECORDING_FPS = 30.0
        const val FRAME_INTERVAL_US = 1_000_000L / 30L
        const val JOURNAL_QUEUE_CAPACITY = 256
        const val UNSET_TIMESTAMP = Long.MIN_VALUE
    }
}
