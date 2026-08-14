package com.integrapose.mobile

import android.app.Application
import android.content.Intent

class IntegraPoseApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Older builds persisted every picker grant even though imported models and videos are
        // copied into app-owned disk storage. Release those obsolete grants so a document
        // provider cannot hit Android's persisted-permission quota after repeated tests.
        contentResolver.persistedUriPermissions.forEach { permission ->
            var flags = 0
            if (permission.isReadPermission) {
                flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            if (permission.isWritePermission) {
                flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            if (flags != 0) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        permission.uri,
                        flags
                    )
                }
            }
        }
    }
}
