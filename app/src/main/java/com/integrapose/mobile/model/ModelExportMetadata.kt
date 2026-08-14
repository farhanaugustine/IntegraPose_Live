package com.integrapose.mobile.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Export-time values that change how a detection or pose graph must be decoded. */
@Serializable
data class ModelExportMetadata(
    val endToEnd: Boolean? = null,
    val embeddedNms: Boolean? = null,
    @SerialName("exportMaxDetections")
    val exportDetectionCount: Int? = null
) {
    val detectionCountLocked: Boolean
        get() = exportDetectionCount != null

    val postProcessingLabel: String
        get() = when {
            endToEnd == true -> "end-to-end final detections"
            embeddedNms == true -> "exported NMS final detections"
            endToEnd == false || embeddedNms == false -> "mobile NMS"
            else -> "auto-detected post-processing"
        }
}
