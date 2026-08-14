package com.integrapose.mobile.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes an app-owned MP4 to the user's media library.
 *
 * Android 10+ does not require broad storage permission for media created by this app. On older
 * Android versions, a missing legacy storage permission is reported to the caller and the
 * app-owned original remains available for viewing and sharing.
 */
suspend fun publishVideoToMediaStore(context: Context, source: File): Uri =
    withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0L) {
            "Recorded video is not available."
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/IntegraPose Live"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ) {
            "The device media library did not create a video entry."
        }
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
            uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
