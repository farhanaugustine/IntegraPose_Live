package com.integrapose.mobile.inference

enum class AnnotationColorPreset(
    val displayName: String,
    val argb: Int
) {
    MINT_GREEN("Mint green", 0xE656D6A5.toInt()),
    ORANGE("Orange", 0xEBFF8A4A.toInt()),
    SKY_BLUE("Sky blue", 0xEB4BC3FF.toInt()),
    YELLOW("Yellow", 0xEBFFD54F.toInt()),
    VERMILION("Vermilion", 0xEBE84A27.toInt()),
    MAGENTA("Magenta", 0xEBEC5BCE.toInt()),
    WHITE("White", 0xF2FFFFFF.toInt()),
    BLACK("Black", 0xE6000000.toInt());

    companion object {
        fun fromStoredName(value: String?, fallback: AnnotationColorPreset): AnnotationColorPreset =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}

enum class RoiLabelSize(
    val displayName: String,
    val textSizePx: Float,
    val nativeSizeCode: Int
) {
    OFF("Off", 0f, 0),
    SMALL("Small", 24f, 1),
    MEDIUM("Medium", 32f, 2),
    LARGE("Large", 42f, 3);

    companion object {
        fun fromStoredName(value: String?, fallback: RoiLabelSize): RoiLabelSize =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}

/**
 * High-contrast ROI colors are assigned from the persistent ROI identifier so
 * renaming an ROI does not change its appearance. The exact ARGB value is also
 * passed into the native video renderer, keeping preview and exported media in
 * agreement.
 */
object RoiAnnotationPalette {
    private val colors = intArrayOf(
        0xEBFFD54F.toInt(),
        0xEB4BC3FF.toInt(),
        0xEB56D6A5.toInt(),
        0xEBEC5BCE.toInt(),
        0xEBE84A27.toInt(),
        0xEBFF8A4A.toInt()
    )

    fun argbFor(stableRoiId: String): Int =
        colors[Math.floorMod(stableRoiId.hashCode(), colors.size)]
}

data class AnnotationStyle(
    val boundingBoxColor: AnnotationColorPreset = AnnotationColorPreset.MINT_GREEN,
    val keypointColor: AnnotationColorPreset = AnnotationColorPreset.ORANGE,
    val roiLabelSize: RoiLabelSize = RoiLabelSize.SMALL,
    val showClassIndex: Boolean = false
) {
    val boxArgb: Int get() = boundingBoxColor.argb
    val keypointArgb: Int get() = keypointColor.argb

    companion object {
        val Default = AnnotationStyle()
    }
}
