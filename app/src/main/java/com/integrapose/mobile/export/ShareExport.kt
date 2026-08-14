package com.integrapose.mobile.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun shareExport(context: Context, filePath: String, mimeType: String) {
    val file = File(filePath)
    require(file.isFile) { "Export file is not available: $filePath" }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share IntegraPose Live export"))
}

fun viewExport(context: Context, filePath: String, mimeType: String) {
    val file = File(filePath)
    require(file.isFile) { "Export file is not available: $filePath" }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "View IntegraPose Live export"))
}
