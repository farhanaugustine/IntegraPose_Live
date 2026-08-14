package com.integrapose.mobile.analytics

import com.integrapose.mobile.inference.DetectionResult

data class BoutSummary(
    val trackId: Int,
    val classIndex: Int,
    val className: String,
    val startFrame: Int,
    val endFrame: Int,
    val durationFrames: Int,
    val durationSeconds: Double,
    val detectionCount: Int,
    val bridgedFrames: Int,
    val maximumBridgedGapFrames: Int,
    val observedFraction: Double,
    val averageConfidence: Float,
    val resolvedClassConflictFrames: Int,
    val concurrentClassFrames: Int,
    val maximumFrameGapFrames: Int,
    val minimumBoutDurationFrames: Int,
    val analysisFps: Double,
    val behaviorBoutClassMode: String = "mutually_exclusive",
    val intervalSemantics: String = "inclusive_start_and_end_frames",
    val boutConstructionSemantics: String =
        "mutually_exclusive_observed_states_with_missing_frame_gap_fill",
    val minimumBoutBasis: String = "inclusive_post_gap_span"
)

/**
 * Streaming, mutually-exclusive class-bout tracker.
 *
 * At most one behavior is accepted for each track and frame (the
 * highest-confidence detection). Missing frames are bridged only when the
 * same class returns within [BoutSettings.maxGapFrames]. An explicitly
 * observed different class always ends the previous bout.
 */
class BehaviorBoutTracker(settings: BoutSettings = BoutSettings()) {
    private val settings = settings.sanitized()
    private val active = mutableMapOf<Int, ActiveBout>()
    private val finished = mutableListOf<RawBout>()

    fun onFrame(frameIndex: Int, detections: List<DetectionResult>) {
        val grouped = detections
            .filter { it.trackId != null }
            .groupBy { requireNotNull(it.trackId) }
        val observed = grouped.mapValues { (_, values) -> values.maxBy { it.confidence } }
        val classConflicts = grouped.mapValues { (_, values) ->
            values.asSequence().map { it.classIndex }.distinct().count() > 1
        }

        observed.forEach { (trackId, detection) ->
            val existing = active[trackId]
            if (existing == null) {
                active[trackId] = detection.startBout(
                    trackId,
                    frameIndex,
                    classConflicts[trackId] == true
                )
                return@forEach
            }

            val gapFrames = frameIndex - existing.lastObservedFrame - 1
            if (
                detection.classIndex != existing.classIndex ||
                gapFrames > settings.maxGapFrames
            ) {
                record(existing)
                active[trackId] = detection.startBout(
                    trackId,
                    frameIndex,
                    classConflicts[trackId] == true
                )
            } else {
                existing.lastObservedFrame = frameIndex
                existing.detectionCount += 1
                existing.confidenceSum += detection.confidence
                existing.maximumBridgedGapFrames = maxOf(
                    existing.maximumBridgedGapFrames,
                    gapFrames.coerceAtLeast(0)
                )
                existing.className = detection.className
                if (classConflicts[trackId] == true) {
                    existing.resolvedClassConflictFrames += 1
                }
            }
        }

        active.keys.toList().forEach { trackId ->
            if (trackId in observed) return@forEach
            val bout = active[trackId] ?: return@forEach
            if (frameIndex - bout.lastObservedFrame > settings.maxGapFrames) {
                record(bout)
                active.remove(trackId)
            }
        }
    }

    fun finish(lastFrameIndex: Int, frameRate: Double): List<BoutSummary> {
        active.values.toList().forEach(::record)
        active.clear()
        val fps = frameRate.coerceAtLeast(0.001)
        return finished
            .sortedWith(
                compareBy<RawBout> { it.startFrame }
                    .thenBy { it.trackId }
                    .thenBy { it.classIndex }
            )
            .map { raw ->
                val durationFrames = raw.endFrame - raw.startFrame + 1
                val bridgedFrames =
                    (durationFrames - raw.detectionCount).coerceAtLeast(0)
                BoutSummary(
                    trackId = raw.trackId,
                    classIndex = raw.classIndex,
                    className = raw.className,
                    startFrame = raw.startFrame,
                    endFrame = raw.endFrame.coerceAtMost(lastFrameIndex),
                    durationFrames = durationFrames,
                    durationSeconds = durationFrames / fps,
                    detectionCount = raw.detectionCount,
                    bridgedFrames = bridgedFrames,
                    maximumBridgedGapFrames = raw.maximumBridgedGapFrames,
                    observedFraction =
                        raw.detectionCount.toDouble() / durationFrames.coerceAtLeast(1),
                    averageConfidence = (
                        raw.confidenceSum / raw.detectionCount.coerceAtLeast(1)
                        ).toFloat(),
                    resolvedClassConflictFrames = raw.resolvedClassConflictFrames,
                    concurrentClassFrames = 0,
                    maximumFrameGapFrames = settings.maxGapFrames,
                    minimumBoutDurationFrames = settings.minBoutFrames,
                    analysisFps = fps
                )
            }
    }

    private fun record(bout: ActiveBout) {
        val duration = bout.lastObservedFrame - bout.startFrame + 1
        if (duration < settings.minBoutFrames) return
        finished += RawBout(
            trackId = bout.trackId,
            classIndex = bout.classIndex,
            className = bout.className,
            startFrame = bout.startFrame,
            endFrame = bout.lastObservedFrame,
            detectionCount = bout.detectionCount,
            confidenceSum = bout.confidenceSum,
            maximumBridgedGapFrames = bout.maximumBridgedGapFrames,
            resolvedClassConflictFrames = bout.resolvedClassConflictFrames
        )
    }

    private fun DetectionResult.startBout(
        trackId: Int,
        frameIndex: Int,
        classConflict: Boolean
    ): ActiveBout = ActiveBout(
        trackId = trackId,
        classIndex = classIndex,
        className = className,
        startFrame = frameIndex,
        lastObservedFrame = frameIndex,
        detectionCount = 1,
        confidenceSum = confidence.toDouble(),
        maximumBridgedGapFrames = 0,
        resolvedClassConflictFrames = if (classConflict) 1 else 0
    )

    private data class ActiveBout(
        val trackId: Int,
        val classIndex: Int,
        var className: String,
        val startFrame: Int,
        var lastObservedFrame: Int,
        var detectionCount: Int,
        var confidenceSum: Double,
        var maximumBridgedGapFrames: Int,
        var resolvedClassConflictFrames: Int
    )

    private data class RawBout(
        val trackId: Int,
        val classIndex: Int,
        val className: String,
        val startFrame: Int,
        val endFrame: Int,
        val detectionCount: Int,
        val confidenceSum: Double,
        val maximumBridgedGapFrames: Int,
        val resolvedClassConflictFrames: Int
    )
}
