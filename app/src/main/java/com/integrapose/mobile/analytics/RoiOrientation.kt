package com.integrapose.mobile.analytics

/** Converts an ROI drawn on the upright frame back to encoded source orientation. */
internal fun BehaviorRoi.toSourceOrientation(
    rotationDegrees: Int
): BehaviorRoi {
    val safe = sanitized()
    val corners = listOf(
        sourceNormalizedPoint(safe.left, safe.top, rotationDegrees),
        sourceNormalizedPoint(safe.right, safe.top, rotationDegrees),
        sourceNormalizedPoint(safe.right, safe.bottom, rotationDegrees),
        sourceNormalizedPoint(safe.left, safe.bottom, rotationDegrees)
    )
    return safe.copy(
        left = corners.minOf { it.first },
        top = corners.minOf { it.second },
        right = corners.maxOf { it.first },
        bottom = corners.maxOf { it.second }
    ).sanitized()
}

private fun sourceNormalizedPoint(
    uprightX: Float,
    uprightY: Float,
    rotationDegrees: Int
): Pair<Float, Float> = when (((rotationDegrees % 360) + 360) % 360) {
    90 -> uprightY to (1f - uprightX)
    180 -> (1f - uprightX) to (1f - uprightY)
    270 -> (1f - uprightY) to uprightX
    else -> uprightX to uprightY
}
