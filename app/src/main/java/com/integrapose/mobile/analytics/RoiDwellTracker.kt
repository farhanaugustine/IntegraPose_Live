package com.integrapose.mobile.analytics

import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import com.integrapose.mobile.inference.Keypoint
import kotlin.math.max
import kotlin.math.min

data class BehaviorRoi(
    val id: String,
    val name: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun sanitized(): BehaviorRoi {
        val safeLeft = minOf(left, right).coerceIn(0f, 1f)
        val safeRight = maxOf(left, right).coerceIn(0f, 1f)
        val safeTop = minOf(top, bottom).coerceIn(0f, 1f)
        val safeBottom = maxOf(top, bottom).coerceIn(0f, 1f)
        return copy(
            name = name.trim().ifBlank { "ROI" },
            left = safeLeft,
            top = safeTop,
            right = safeRight,
            bottom = safeBottom
        )
    }

    fun contains(xFraction: Float, yFraction: Float): Boolean =
        xFraction in left..right && yFraction in top..bottom
}

data class RoiVisitSummary(
    val roi: BehaviorRoi,
    val trackId: Int,
    val classIndex: Int,
    val className: String,
    val visitIndex: Int,
    val entryFrame: Int,
    val endFrame: Int,
    val dwellFrames: Int,
    val entryTimeSeconds: Double,
    val endTimeSeconds: Double,
    val dwellSeconds: Double,
    val analysisFps: Double,
    val observedFrames: Int,
    val bridgedFrames: Int,
    val maximumBridgedGapFrames: Int,
    val observedFraction: Double,
    val averageConfidence: Float,
    val averageAnchorConfidence: Float,
    val anchorMode: RoiAnchorMode,
    val anchorKeypointIndex: Int?,
    val entryThreshold: Float,
    val exitThreshold: Float,
    val maxGapFrames: Int,
    val minDwellFrames: Int
)

/**
 * Regular ROI visits with bbox hysteresis, inclusive frame
 * intervals, gap closing, and minimum-dwell qualification.
 */
class RoiDwellTracker(
    rois: List<BehaviorRoi>,
    settings: RoiAnalyticsSettings = RoiAnalyticsSettings()
) {
    private val rois = rois.map(BehaviorRoi::sanitized)
    private val settings = settings.sanitized()
    private val states = mutableMapOf<RoiTrackKey, TrackRoiState>()
    private val finished = mutableListOf<RawVisit>()
    private val nextVisitIndex = mutableMapOf<RoiTrackKey, Int>()

    fun onFrame(
        frameIndex: Int,
        imageWidth: Int,
        imageHeight: Int,
        detections: List<DetectionResult>,
        rotationDegrees: Int = 0
    ) {
        if (imageWidth <= 0 || imageHeight <= 0 || rois.isEmpty()) return
        expireLongGaps(frameIndex)

        val tracked = detections
            .filter { it.trackId != null }
            .groupBy { requireNotNull(it.trackId) }
            .mapValues { (_, values) -> values.maxBy { it.confidence } }

        tracked.forEach { (trackId, detection) ->
            rois.forEach { roi ->
                val key = RoiTrackKey(roi.id, trackId)
                val state = states.getOrPut(key) { TrackRoiState() }
                val observation = resolveMembership(
                    detection = detection,
                    roi = roi,
                    state = state,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    rotationDegrees = rotationDegrees
                ) ?: return@forEach

                state.lastValidObservationFrame = frameIndex
                state.membershipInside = observation.inside
                if (observation.inside) {
                    addInsideObservation(
                        key = key,
                        roi = roi,
                        detection = detection,
                        anchorConfidence = observation.anchorConfidence,
                        frameIndex = frameIndex
                    )
                }
            }
        }
    }

    fun finish(lastFrameIndex: Int, frameRate: Double): List<RoiVisitSummary> {
        states.keys.toList().forEach(::closeVisit)
        states.clear()
        val fps = frameRate.coerceAtLeast(0.001)
        return finished
            .sortedWith(
                compareBy<RawVisit> { it.entryFrame }
                    .thenBy { it.roi.name }
                    .thenBy { it.trackId }
            )
            .mapNotNull { visit ->
                val endFrame = visit.lastInsideFrame.coerceAtMost(lastFrameIndex)
                val duration = endFrame - visit.entryFrame + 1
                if (duration < settings.minDwellFrames) return@mapNotNull null
                val bridged = (duration - visit.observedFrames).coerceAtLeast(0)
                RoiVisitSummary(
                    roi = visit.roi,
                    trackId = visit.trackId,
                    classIndex = visit.classIndex,
                    className = visit.className,
                    visitIndex = visit.visitIndex,
                    entryFrame = visit.entryFrame,
                    endFrame = endFrame,
                    dwellFrames = duration,
                    entryTimeSeconds = visit.entryFrame / fps,
                    endTimeSeconds = endFrame / fps,
                    dwellSeconds = duration / fps,
                    analysisFps = fps,
                    observedFrames = visit.observedFrames,
                    bridgedFrames = bridged,
                    maximumBridgedGapFrames = visit.maximumBridgedGapFrames,
                    observedFraction =
                        visit.observedFrames.toDouble() / duration.coerceAtLeast(1),
                    averageConfidence = (
                        visit.confidenceSum / visit.observedFrames.coerceAtLeast(1)
                        ).toFloat(),
                    averageAnchorConfidence = (
                        visit.anchorConfidenceSum /
                            visit.observedFrames.coerceAtLeast(1)
                        ).toFloat(),
                    anchorMode = settings.anchorMode,
                    anchorKeypointIndex = if (settings.anchorMode == RoiAnchorMode.KEYPOINT) {
                        settings.keypointIndex
                    } else {
                        null
                    },
                    entryThreshold = settings.entryThreshold,
                    exitThreshold = settings.exitThreshold,
                    maxGapFrames = settings.maxGapFrames,
                    minDwellFrames = settings.minDwellFrames
                )
            }
    }

    private fun expireLongGaps(frameIndex: Int) {
        states.keys.toList().forEach { key ->
            val state = states[key] ?: return@forEach
            val visit = state.activeVisit
            if (
                visit != null &&
                frameIndex - visit.lastInsideFrame - 1 > settings.maxGapFrames
            ) {
                closeVisit(key)
                state.membershipInside = false
            }
            if (
                state.activeVisit == null &&
                state.lastValidObservationFrame >= 0 &&
                frameIndex - state.lastValidObservationFrame - 1 >
                settings.maxGapFrames
            ) {
                states.remove(key)
            }
        }
    }

    private fun addInsideObservation(
        key: RoiTrackKey,
        roi: BehaviorRoi,
        detection: DetectionResult,
        anchorConfidence: Float,
        frameIndex: Int
    ) {
        val state = states.getValue(key)
        val existing = state.activeVisit
        if (existing == null) {
            val visitIndex = (nextVisitIndex[key] ?: 0) + 1
            nextVisitIndex[key] = visitIndex
            state.activeVisit = RawVisit(
                roi = roi,
                trackId = key.trackId,
                classIndex = detection.classIndex,
                className = detection.className,
                visitIndex = visitIndex,
                entryFrame = frameIndex,
                lastInsideFrame = frameIndex,
                observedFrames = 1,
                confidenceSum = detection.confidence.toDouble(),
                anchorConfidenceSum = anchorConfidence.toDouble(),
                maximumBridgedGapFrames = 0
            )
            return
        }

        val gapFrames = frameIndex - existing.lastInsideFrame - 1
        if (gapFrames > settings.maxGapFrames) {
            closeVisit(key)
            addInsideObservation(
                key,
                roi,
                detection,
                anchorConfidence,
                frameIndex
            )
            return
        }
        existing.classIndex = detection.classIndex
        existing.className = detection.className
        existing.lastInsideFrame = frameIndex
        existing.observedFrames += 1
        existing.confidenceSum += detection.confidence
        existing.anchorConfidenceSum += anchorConfidence
        existing.maximumBridgedGapFrames = maxOf(
            existing.maximumBridgedGapFrames,
            gapFrames.coerceAtLeast(0)
        )
    }

    private fun closeVisit(key: RoiTrackKey) {
        val state = states[key] ?: return
        state.activeVisit?.let(finished::add)
        state.activeVisit = null
    }

    private fun resolveMembership(
        detection: DetectionResult,
        roi: BehaviorRoi,
        state: TrackRoiState,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int
    ): MembershipObservation? {
        return when (settings.anchorMode) {
        RoiAnchorMode.KEYPOINT -> {
            val hasValidKeypoints = detection.keypoints.any(::isValidKeypoint)
            val point = detection.keypoints
                .getOrNull(settings.keypointIndex)
                ?.takeIf(::isValidKeypoint)
            if (!hasValidKeypoints) {
                resolveBoundingBoxMembership(
                    detection,
                    roi,
                    state,
                    imageWidth,
                    imageHeight,
                    rotationDegrees
                )
            } else if (point == null) {
                MembershipObservation(false, 0f)
            } else {
                val (x, y) = orientNormalizedPoint(
                    point.x / imageWidth,
                    point.y / imageHeight,
                    rotationDegrees
                )
                MembershipObservation(
                    inside = roi.contains(x, y),
                    anchorConfidence = point.confidence
                )
            }
        }

        RoiAnchorMode.BOUNDING_BOX_CENTER -> resolveBoundingBoxMembership(
            detection,
            roi,
            state,
            imageWidth,
            imageHeight,
            rotationDegrees
        )
        }
    }

    private fun resolveBoundingBoxMembership(
        detection: DetectionResult,
        roi: BehaviorRoi,
        state: TrackRoiState,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int
    ): MembershipObservation {
        val box = orientedNormalizedBox(
            detection.box,
            imageWidth,
            imageHeight,
            rotationDegrees
        )
        val centerInside = roi.contains(
            (box.left + box.right) * 0.5f,
            (box.top + box.bottom) * 0.5f
        )
        val overlap = overlapRatio(box, roi)
        val inside = if (state.membershipInside) {
            centerInside || overlap > settings.exitThreshold
        } else {
            centerInside || overlap >= settings.entryThreshold
        }
        return MembershipObservation(inside, detection.confidence)
    }

    private fun isValidKeypoint(point: Keypoint): Boolean =
        point.x.isFinite() && point.y.isFinite() &&
            point.confidence.isFinite() && point.confidence > 0f

    private data class RoiTrackKey(val roiId: String, val trackId: Int)

    private data class TrackRoiState(
        var membershipInside: Boolean = false,
        var lastValidObservationFrame: Int = -1,
        var activeVisit: RawVisit? = null
    )

    private data class MembershipObservation(
        val inside: Boolean,
        val anchorConfidence: Float
    )

    private data class RawVisit(
        val roi: BehaviorRoi,
        val trackId: Int,
        var classIndex: Int,
        var className: String,
        val visitIndex: Int,
        val entryFrame: Int,
        var lastInsideFrame: Int,
        var observedFrames: Int,
        var confidenceSum: Double,
        var anchorConfidenceSum: Double,
        var maximumBridgedGapFrames: Int
    )
}

private fun orientedNormalizedBox(
    box: BoundingBox,
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int
): BoundingBox {
    val corners = listOf(
        orientNormalizedPoint(box.left / imageWidth, box.top / imageHeight, rotationDegrees),
        orientNormalizedPoint(box.right / imageWidth, box.top / imageHeight, rotationDegrees),
        orientNormalizedPoint(box.left / imageWidth, box.bottom / imageHeight, rotationDegrees),
        orientNormalizedPoint(box.right / imageWidth, box.bottom / imageHeight, rotationDegrees)
    )
    return BoundingBox(
        left = corners.minOf { it.first }.coerceIn(0f, 1f),
        top = corners.minOf { it.second }.coerceIn(0f, 1f),
        right = corners.maxOf { it.first }.coerceIn(0f, 1f),
        bottom = corners.maxOf { it.second }.coerceIn(0f, 1f)
    )
}

private fun overlapRatio(box: BoundingBox, roi: BehaviorRoi): Float {
    val intersectionWidth = max(0f, min(box.right, roi.right) - max(box.left, roi.left))
    val intersectionHeight = max(0f, min(box.bottom, roi.bottom) - max(box.top, roi.top))
    val boxArea = max(0f, box.right - box.left) * max(0f, box.bottom - box.top)
    return if (boxArea > 0f) {
        (intersectionWidth * intersectionHeight / boxArea).coerceIn(0f, 1f)
    } else {
        0f
    }
}

internal fun orientNormalizedPoint(
    x: Float,
    y: Float,
    rotationDegrees: Int
): Pair<Float, Float> = when (((rotationDegrees % 360) + 360) % 360) {
    90 -> (1f - y) to x
    180 -> (1f - x) to (1f - y)
    270 -> y to (1f - x)
    else -> x to y
}
