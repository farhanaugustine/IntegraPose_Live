package com.integrapose.mobile.importing

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Copies a provider-backed video into the durable, app-owned IntegraPose Live disk library.
 *
 * Google Drive and other document providers are import sources only. All decoding, inference,
 * benchmarking, and seeking use the completed local copy, and the provider URI is never modified.
 */
object StagedVideoSource {
    suspend fun prepare(context: Context, sourceUri: Uri): Uri =
        withContext(Dispatchers.IO) {
            if (sourceUri.authority == "${context.packageName}.files") {
                return@withContext sourceUri
            }

            val details = queryDetails(context, sourceUri)
            val libraryRoot = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            val libraryDirectory = File(
                libraryRoot,
                "$LIBRARY_DIRECTORY/$IMPORTED_VIDEO_DIRECTORY"
            ).also {
                check(it.exists() || it.mkdirs()) {
                    "Could not create the IntegraPose Live video library."
                }
            }
            removeAbandonedPartialCopies(libraryDirectory)

            val safeAvailable = (libraryDirectory.usableSpace - FREE_SPACE_RESERVE_BYTES)
                .coerceAtLeast(0L)
            details.sizeBytes?.let { expectedBytes ->
                require(expectedBytes <= safeAvailable) {
                    "Not enough app storage to prepare this video. " +
                        "Free space or select a smaller clip."
                }
            }

            val safeName = details.displayName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(MAX_FILENAME_LENGTH)
                .ifBlank { "selected_video.mp4" }
            val destination = File(
                libraryDirectory,
                "${UUID.randomUUID()}_$safeName"
            )
            val partial = File(libraryDirectory, destination.name + PARTIAL_SUFFIX)

            try {
                context.contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) {
                        "The selected video could not be opened. Refresh the cloud provider " +
                            "and select it again."
                    }
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var totalBytes = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            require(totalBytes + count <= safeAvailable) {
                                "Not enough app storage to finish the local video copy. " +
                                    "Free space or select a smaller clip."
                            }
                            output.write(buffer, 0, count)
                            totalBytes += count
                        }
                        output.fd.sync()
                        require(totalBytes > 0L) { "The selected video is empty." }
                        details.sizeBytes?.let { expectedBytes ->
                            require(totalBytes == expectedBytes) {
                                "The cloud video download was incomplete. Refresh the provider " +
                                    "and select the video again."
                            }
                        }
                    }
                }
                check(partial.renameTo(destination)) {
                    "Could not finish the local video copy."
                }
            } catch (error: Throwable) {
                partial.delete()
                destination.delete()
                throw error
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                destination
            )
        }

    private fun queryDetails(context: Context, uri: Uri): SourceDetails {
        var displayName: String? = null
        var sizeBytes: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).takeIf { it > 0L }
                }
            }
        }
        return SourceDetails(
            displayName = displayName ?: uri.lastPathSegment ?: "selected_video.mp4",
            sizeBytes = sizeBytes
        )
    }

    private fun removeAbandonedPartialCopies(directory: File) {
        val cutoff = System.currentTimeMillis() - PARTIAL_MAX_AGE_MS
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(PARTIAL_SUFFIX) }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach(File::delete)
    }

    private data class SourceDetails(
        val displayName: String,
        val sizeBytes: Long?
    )

    private const val LIBRARY_DIRECTORY = "IntegraPose Live"
    private const val IMPORTED_VIDEO_DIRECTORY = "Imported Videos"
    private const val PARTIAL_SUFFIX = ".partial"
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val MAX_FILENAME_LENGTH = 96
    private const val FREE_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L
    private const val PARTIAL_MAX_AGE_MS = 60L * 60L * 1_000L
}
