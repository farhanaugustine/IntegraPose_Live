package com.integrapose.mobile.tracking

import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import kotlin.math.max
import kotlin.math.min

data class IoUTrackerConfig(
    val minimumConfidence: Float = 0.10f,
    val newTrackConfidence: Float = 0.25f,
    val matchIoU: Float = 0.30f,
    val maxMissingFrames: Int = 30
) {
    fun sanitized(): IoUTrackerConfig {
        val defaults = IoUTrackerConfig()
        val minimum = minimumConfidence
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: defaults.minimumConfidence
        val newTrack = newTrackConfidence
            .takeIf(Float::isFinite)
            ?.coerceIn(minimum, 1f)
            ?: defaults.newTrackConfidence.coerceAtLeast(minimum)
        return copy(
            minimumConfidence = minimum,
            newTrackConfidence = newTrack,
            matchIoU = matchIoU
                .takeIf(Float::isFinite)
                ?.coerceIn(0f, 1f)
                ?: defaults.matchIoU,
            maxMissingFrames = maxMissingFrames.coerceIn(0, MAX_MISSING_FRAMES)
        )
    }

    companion object {
        const val MAX_MISSING_FRAMES = 600
    }
}

/**
 * Lightweight, class-agnostic IoU tracking.
 * Behavior class is an observation and never part of physical track identity.
 * It intentionally is not labelled BoT-SORT because it has no ReID or motion model.
 */
class IoUTracker(config: IoUTrackerConfig = IoUTrackerConfig()) {
    private val config = config.sanitized()
    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1

    fun update(detections: List<DetectionResult>, frameIndex: Int): List<DetectionResult> {
        tracks.removeAll { frameIndex - it.lastFrame > config.maxMissingFrames }
        val available = tracks.toMutableSet()
        val assignments = mutableMapOf<Int, Track>()

        detections.indices
            .filter { detections[it].confidence >= config.minimumConfidence }
            .sortedByDescending { detections[it].confidence }
            .forEach { index ->
                val detection = detections[index]
                val best = available
                    .asSequence()
                    .map { it to intersectionOverUnion(it.box, detection.box) }
                    .filter { it.second >= config.matchIoU }
                    .maxByOrNull { it.second }
                    ?.first
                if (best != null) {
                    available.remove(best)
                    best.box = detection.box
                    best.lastFrame = frameIndex
                    assignments[index] = best
                }
            }

        detections.indices.forEach { index ->
            if (index in assignments) return@forEach
            val detection = detections[index]
            if (detection.confidence < config.newTrackConfidence) return@forEach
            val track = Track(
                id = nextTrackId++,
                box = detection.box,
                lastFrame = frameIndex
            )
            tracks += track
            assignments[index] = track
        }

        return detections.mapIndexed { index, detection ->
            detection.copy(trackId = assignments[index]?.id)
        }
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }

    private fun intersectionOverUnion(first: BoundingBox, second: BoundingBox): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val firstArea = max(0f, first.right - first.left) * max(0f, first.bottom - first.top)
        val secondArea = max(0f, second.right - second.left) * max(0f, second.bottom - second.top)
        val union = firstArea + secondArea - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private data class Track(
        val id: Int,
        var box: BoundingBox,
        var lastFrame: Int
    )
}
