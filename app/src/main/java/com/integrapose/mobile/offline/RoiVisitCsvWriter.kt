package com.integrapose.mobile.offline

import android.content.Context
import android.os.Environment
import com.integrapose.mobile.analytics.RoiVisitSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RoiVisitCsvWriter {
    fun write(
        context: Context,
        visits: List<RoiVisitSummary>,
        prefix: String = "offline_roi_dwell_events"
    ): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, prefix + "_" + stamp + ".csv")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(
                "Track ID,Class ID,Behavior,ROI ID,ROI Name,Visit Index," +
                    "Start Frame,End Frame,Duration (Frames),Start Time (s)," +
                    "End Time (s),Duration (s),Observed Frames,Bridged Frames," +
                    "Observed Fraction,Maximum Bridged Gap (Frames)," +
                    "Maximum ROI Gap (Frames),Minimum ROI Dwell (Frames)," +
                    "Analysis FPS,ROI Entry Mode,ROI Entry Keypoint Index," +
                    "ROI Entry Threshold,ROI Exit Threshold," +
                    "Average Detection Confidence," +
                    "Average Anchor Confidence,ROI Left (Normalized)," +
                    "ROI Top (Normalized),ROI Right (Normalized)," +
                    "ROI Bottom (Normalized)"
            )
            writer.newLine()
            visits.forEach { visit ->
                writer.write(
                    listOf(
                        visit.trackId,
                        visit.classIndex,
                        escape(visit.className),
                        escape(visit.roi.id),
                        escape(visit.roi.name),
                        visit.visitIndex,
                        visit.entryFrame,
                        visit.endFrame,
                        visit.dwellFrames,
                        number(visit.entryTimeSeconds),
                        number(visit.endTimeSeconds),
                        number(visit.dwellSeconds),
                        visit.observedFrames,
                        visit.bridgedFrames,
                        number(visit.observedFraction),
                        visit.maximumBridgedGapFrames,
                        visit.maxGapFrames,
                        visit.minDwellFrames,
                        number(visit.analysisFps),
                        visit.anchorMode.contractName,
                        visit.anchorKeypointIndex ?: "",
                        number(visit.entryThreshold.toDouble()),
                        number(visit.exitThreshold.toDouble()),
                        number(visit.averageConfidence.toDouble()),
                        number(visit.averageAnchorConfidence.toDouble()),
                        number(visit.roi.left.toDouble()),
                        number(visit.roi.top.toDouble()),
                        number(visit.roi.right.toDouble()),
                        number(visit.roi.bottom.toDouble())
                    ).joinToString(",")
                )
                writer.newLine()
            }
        }
        return file
    }

    private fun escape(value: String): String {
        val quote = '"'
        return if (value.any { it == ',' || it == quote || it == '\n' || it == '\r' }) {
            quote.toString() +
                value.replace(quote.toString(), quote.toString().repeat(2)) +
                quote
        } else {
            value
        }
    }

    private fun number(value: Double): String =
        String.format(Locale.US, "%.6f", value)
}
