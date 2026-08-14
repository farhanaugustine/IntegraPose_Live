package com.integrapose.mobile.branding

import kotlin.math.PI
import kotlin.math.sin

internal const val BRAND_ANIMATION_DURATION_MS = 3_200L

internal data class BrandAnimationFrame(
    val phoneProgress: Float,
    val cornerProgress: List<Float>,
    val streakProgress: Float,
    val mouseProgress: Float,
    val mouseAlpha: Float,
    val dotProgress: Float,
    val dotScale: Float,
    val wordmarkProgress: Float,
    val taglineProgress: Float
)

internal object BrandAnimationTimeline {
    private const val DURATION_SECONDS = 1.2f

    fun frameAt(fraction: Float): BrandAnimationFrame {
        val timeSeconds = fraction.coerceIn(0f, 1f) * DURATION_SECONDS
        val phone = phase(timeSeconds, 0.02f, 0.34f, ::easeOutCubic)
        val corners = listOf(0.14f, 0.19f, 0.24f, 0.29f).map { start ->
            phase(timeSeconds, start, start + 0.18f, ::easeOutBack).coerceIn(0f, 1f)
        }
        val streak = phase(timeSeconds, 0.30f, 0.59f, ::easeOutCubic)
        val mouse = phase(timeSeconds, 0.27f, 0.66f, ::easeOutBack)
        val mouseAlpha = phase(timeSeconds, 0.27f, 0.42f, ::easeOutCubic)
        val dot = phase(timeSeconds, 0.61f, 0.82f, ::easeOutBack).coerceAtLeast(0f)
        val pulsePhase = phase(timeSeconds, 0.80f, 1.08f, ::smoothStep)
        val pulseScale = if (pulsePhase > 0f && pulsePhase < 1f) {
            1f + 0.12f * sin(PI.toFloat() * pulsePhase)
        } else {
            1f
        }

        return BrandAnimationFrame(
            phoneProgress = phone,
            cornerProgress = corners,
            streakProgress = streak,
            mouseProgress = mouse,
            mouseAlpha = mouseAlpha,
            dotProgress = dot.coerceIn(0f, 1f),
            dotScale = (dot * pulseScale).coerceAtLeast(0f),
            wordmarkProgress = phase(timeSeconds, 0.69f, 0.98f, ::easeOutCubic),
            taglineProgress = phase(timeSeconds, 0.82f, 1.08f, ::easeOutCubic)
        )
    }

    private fun phase(
        timeSeconds: Float,
        startSeconds: Float,
        endSeconds: Float,
        easing: (Float) -> Float
    ): Float {
        if (endSeconds <= startSeconds) return 1f
        return easing(((timeSeconds - startSeconds) / (endSeconds - startSeconds)).coerceIn(0f, 1f))
    }

    private fun smoothStep(value: Float): Float {
        val bounded = value.coerceIn(0f, 1f)
        return bounded * bounded * (3f - 2f * bounded)
    }

    private fun easeOutCubic(value: Float): Float {
        val bounded = value.coerceIn(0f, 1f)
        return 1f - (1f - bounded) * (1f - bounded) * (1f - bounded)
    }

    private fun easeOutBack(value: Float): Float {
        val bounded = value.coerceIn(0f, 1f)
        val overshoot = 1.70158f
        val shifted = bounded - 1f
        return 1f + (overshoot + 1f) * shifted * shifted * shifted +
            overshoot * shifted * shifted
    }
}
