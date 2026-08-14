package com.integrapose.mobile.model

import kotlinx.serialization.Serializable

/** Output layouts accepted by the detection and pose postprocessor. */
@Serializable
enum class ModelOutputFormat {
    AUTO,
    /** Raw bbox/class rows with optional keypoint triplets. */
    RAW_PREDICTIONS,
    END_TO_END,

    /** Retained only so registries written by experimental builds remain readable. */
    @Deprecated("Experimental heatmap packages are not supported by release builds.")
    HEATMAP_POSE
}

@Serializable
enum class ModelCoordinateFormat {
    AUTO,
    MODEL_PIXELS,
    NORMALIZED
}
