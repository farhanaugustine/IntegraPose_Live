package com.integrapose.mobile.offline

import android.content.Context
import android.os.Environment
import com.integrapose.mobile.analytics.BoutSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object BoutCsvWriter {
    fun write(
        context: Context,
        bouts: List<BoutSummary>,
        frameRate: Double,
        prefix: String = "offline_detailed_bouts"
    ): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "IntegraPose Live"
        ).also { it.mkdirs() }
        val stamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())
        val file = File(directory, prefix + "_" + stamp + ".csv")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(
                "Track ID,Class ID,Behavior,Start Frame,End Frame," +
                    "Duration (Frames),Observed Frames,Bridged Frames," +
                    "Observed Fraction,Maximum Bridged Gap (Frames)," +
                    "Resolved Class-Conflict Frames,Concurrent Class Frames," +
                    "Start Time (s),End Time (s),Duration (s)," +
                    "Interval Semantics,Bout Construction Semantics," +
                    "Behavior Bout Class Mode,Minimum Bout Basis," +
                    "Detection Max Gap (Frames),Detection Min Bout (Frames)," +
                    "Analysis FPS"
            )
            writer.newLine()
            bouts.forEach { bout ->
                writer.write(
                    listOf(
                        bout.trackId,
                        bout.classIndex,
                        escape(bout.className),
                        bout.startFrame,
                        bout.endFrame,
                        bout.durationFrames,
                        bout.detectionCount,
                        bout.bridgedFrames,
                        number(bout.observedFraction),
                        bout.maximumBridgedGapFrames,
                        bout.resolvedClassConflictFrames,
                        bout.concurrentClassFrames,
                        number(bout.startFrame / frameRate),
                        number(bout.endFrame / frameRate),
                        number(bout.durationSeconds),
                        bout.intervalSemantics,
                        bout.boutConstructionSemantics,
                        bout.behaviorBoutClassMode,
                        bout.minimumBoutBasis,
                        bout.maximumFrameGapFrames,
                        bout.minimumBoutDurationFrames,
                        number(bout.analysisFps)
                    ).joinToString(",")
                )
                writer.newLine()
            }
        }
        return file
    }

    private fun escape(value: String): String {
        val quote = '"'
        return if (value.any {
                it == ',' || it == quote || it == '\n' || it == '\r'
            }
        ) {
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
