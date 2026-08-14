package com.integrapose.mobile.live

import com.integrapose.mobile.inference.BoundingBox
import com.integrapose.mobile.inference.DetectionResult
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.inference.Keypoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Compact temporary record of already-mapped inference results. The journal is
 * written off the inference thread and replayed only after recording stops.
 */
internal class LiveInferenceJournal(private val file: File) : Closeable {
    private var output: DataOutputStream? = DataOutputStream(
        BufferedOutputStream(FileOutputStream(file), BUFFER_SIZE)
    ).also { stream ->
        stream.writeInt(MAGIC)
        stream.writeInt(VERSION)
    }

    @Synchronized
    fun append(frameIndex: Long, result: FrameInferenceResult) {
        val stream = checkNotNull(output) { "Live inference journal is closed." }
        stream.writeLong(frameIndex)
        stream.writeLong(result.timestampMs)
        stream.writeLong(result.sourceTimestampUs)
        stream.writeInt(result.imageWidth)
        stream.writeInt(result.imageHeight)
        stream.writeLong(result.inferenceMs)
        stream.writeLong(result.preprocessingMs)
        stream.writeLong(result.postprocessingMs)
        stream.writeUTF(result.backend)
        stream.writeInt(result.modelInputWidth)
        stream.writeInt(result.modelInputHeight)
        stream.writeInt(result.detections.size)
        result.detections.forEach { detection ->
            stream.writeInt(detection.classIndex)
            stream.writeUTF(detection.className)
            stream.writeFloat(detection.confidence)
            stream.writeFloat(detection.box.left)
            stream.writeFloat(detection.box.top)
            stream.writeFloat(detection.box.right)
            stream.writeFloat(detection.box.bottom)
            stream.writeInt(detection.keypoints.size)
            detection.keypoints.forEach { keypoint ->
                stream.writeFloat(keypoint.x)
                stream.writeFloat(keypoint.y)
                stream.writeFloat(keypoint.confidence)
            }
            stream.writeBoolean(detection.trackId != null)
            detection.trackId?.let(stream::writeInt)
        }
    }

    @Synchronized
    override fun close() {
        val stream = output ?: return
        output = null
        stream.flush()
        stream.close()
    }

    companion object {
        private const val MAGIC = 0x49504A31
        private const val VERSION = 1
        private const val BUFFER_SIZE = 256 * 1_024
        private const val MAX_DETECTIONS_PER_FRAME = 5_000
        private const val MAX_KEYPOINTS_PER_DETECTION = 4_096

        fun forEachFrame(file: File, action: (Long, FrameInferenceResult) -> Unit) {
            DataInputStream(
                BufferedInputStream(FileInputStream(file), BUFFER_SIZE)
            ).use { input ->
                check(input.readInt() == MAGIC) { "Invalid live inference journal." }
                check(input.readInt() == VERSION) {
                    "Unsupported live inference journal version."
                }
                while (true) {
                    val frameIndex = try {
                        input.readLong()
                    } catch (_: EOFException) {
                        break
                    }
                    action(frameIndex, input.readFrame())
                }
            }
        }

        private fun DataInputStream.readFrame(): FrameInferenceResult {
            val timestampMs = readLong()
            val sourceTimestampUs = readLong()
            val imageWidth = readInt()
            val imageHeight = readInt()
            val inferenceMs = readLong()
            val preprocessingMs = readLong()
            val postprocessingMs = readLong()
            val backend = readUTF()
            val modelInputWidth = readInt()
            val modelInputHeight = readInt()
            val detectionCount = readInt()
            require(detectionCount in 0..MAX_DETECTIONS_PER_FRAME) {
                "Invalid detection count in live inference journal: $detectionCount"
            }
            val detections = List(detectionCount) { readDetection() }
            return FrameInferenceResult(
                timestampMs = timestampMs,
                sourceTimestampUs = sourceTimestampUs,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                detections = detections,
                inferenceMs = inferenceMs,
                preprocessingMs = preprocessingMs,
                postprocessingMs = postprocessingMs,
                backend = backend,
                modelInputWidth = modelInputWidth,
                modelInputHeight = modelInputHeight
            )
        }

        private fun DataInputStream.readDetection(): DetectionResult {
            val classIndex = readInt()
            val className = readUTF()
            val confidence = readFloat()
            val box = BoundingBox(readFloat(), readFloat(), readFloat(), readFloat())
            val keypointCount = readInt()
            require(keypointCount in 0..MAX_KEYPOINTS_PER_DETECTION) {
                "Invalid keypoint count in live inference journal: $keypointCount"
            }
            val keypoints = List(keypointCount) {
                Keypoint(readFloat(), readFloat(), readFloat())
            }
            val trackId = if (readBoolean()) readInt() else null
            return DetectionResult(
                classIndex = classIndex,
                className = className,
                confidence = confidence,
                box = box,
                keypoints = keypoints,
                trackId = trackId
            )
        }
    }
}
