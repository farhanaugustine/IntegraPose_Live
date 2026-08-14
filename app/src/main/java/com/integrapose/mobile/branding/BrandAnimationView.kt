package com.integrapose.mobile.branding

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.DrawableRes
import com.integrapose.mobile.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BrandAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private val phone = loadBitmap(R.drawable.brand_phone)
    private val corners = listOf(
        BitmapLayer(loadBitmap(R.drawable.brand_corner_tl), 57f, 105f),
        BitmapLayer(loadBitmap(R.drawable.brand_corner_tr), 497f, 105f),
        BitmapLayer(loadBitmap(R.drawable.brand_corner_bl), 57f, 685f),
        BitmapLayer(loadBitmap(R.drawable.brand_corner_br), 497f, 685f)
    )
    private val recordingDot = BitmapLayer(loadBitmap(R.drawable.brand_recording_dot), 532f, 150f)
    private val speedStreaks = BitmapLayer(loadBitmap(R.drawable.brand_speed_streaks), 32f, 375f)
    private val mouse = BitmapLayer(loadBitmap(R.drawable.brand_mouse), 247f, 270f)
    private val wordmark = loadBitmap(R.drawable.brand_wordmark)
    private val tagline = loadBitmap(R.drawable.brand_tagline)

    private var animationFraction = 0f
    private var animation: ValueAnimator? = null
    private var animationStarted = false
    private var completionDelivered = false
    private val completionRunnable = Runnable(::deliverCompletion)

    var onAnimationFinished: (() -> Unit)? = null

    fun startAnimation() {
        if (animationStarted) return
        animationStarted = true

        if (!ValueAnimator.areAnimatorsEnabled()) {
            animationFraction = 1f
            invalidate()
            postDelayed(completionRunnable, REDUCED_MOTION_HOLD_MS)
            return
        }

        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BRAND_ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationFraction = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) deliverCompletion()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = calculateLayout()
        val frame = BrandAnimationTimeline.frameAt(animationFraction)

        canvas.save()
        canvas.translate(layout.symbolLeft, layout.symbolTop)
        canvas.scale(layout.symbolScale, layout.symbolScale)

        drawPhone(canvas, frame.phoneProgress)
        corners.forEachIndexed { index, corner ->
            val progress = frame.cornerProgress[index]
            drawCenteredLayer(
                canvas = canvas,
                layer = corner,
                alpha = progress,
                scale = 0.62f + 0.38f * progress
            )
        }

        drawStreaks(canvas, frame.streakProgress)
        drawCenteredLayer(
            canvas = canvas,
            layer = mouse,
            alpha = frame.mouseAlpha,
            scale = 0.84f + 0.16f * frame.mouseProgress,
            rotationDegrees = -7f * (1f - frame.mouseProgress),
            translateX = -190f * (1f - frame.mouseProgress),
            translateY = 52f * (1f - frame.mouseProgress)
        )
        drawCenteredLayer(
            canvas = canvas,
            layer = recordingDot,
            alpha = frame.dotProgress,
            scale = max(0.01f, frame.dotScale)
        )
        canvas.restore()

        drawIndependentLayer(
            canvas = canvas,
            bitmap = wordmark,
            left = layout.wordmarkLeft,
            top = layout.wordmarkTop + 28f * (1f - frame.wordmarkProgress),
            scale = layout.wordmarkScale,
            alpha = frame.wordmarkProgress
        )
        drawIndependentLayer(
            canvas = canvas,
            bitmap = tagline,
            left = layout.taglineLeft,
            top = layout.taglineTop + 20f * (1f - frame.taglineProgress),
            scale = layout.taglineScale,
            alpha = frame.taglineProgress
        )
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        animation = null
        removeCallbacks(completionRunnable)
        super.onDetachedFromWindow()
    }

    private fun drawPhone(canvas: Canvas, progress: Float) {
        if (progress <= 0f) return
        if (progress >= 0.999f) {
            drawBitmap(canvas, phone, 0f, 0f, 1f)
            return
        }

        val width = phone.width.toFloat()
        val height = phone.height.toFloat()
        val band = 95f
        val quarter = progress.coerceIn(0f, 1f) * 4f

        drawClippedBitmap(
            canvas,
            phone,
            0f,
            0f,
            width * min(1f, quarter),
            band
        )
        if (quarter > 1f) {
            val amount = min(1f, quarter - 1f)
            drawClippedBitmap(canvas, phone, width - band, band, width, band + (height - 2f * band) * amount)
        }
        if (quarter > 2f) {
            val amount = min(1f, quarter - 2f)
            drawClippedBitmap(canvas, phone, width * (1f - amount), height - band, width, height)
        }
        if (quarter > 3f) {
            val amount = min(1f, quarter - 3f)
            drawClippedBitmap(canvas, phone, 0f, band + (height - 2f * band) * (1f - amount), band, height - band)
        }
    }

    private fun drawStreaks(canvas: Canvas, progress: Float) {
        if (progress <= 0f) return
        val bitmap = speedStreaks.bitmap
        val revealLeft = speedStreaks.left + bitmap.width * (1f - progress.coerceIn(0f, 1f))
        canvas.save()
        canvas.clipRect(
            revealLeft,
            speedStreaks.top,
            speedStreaks.left + bitmap.width,
            speedStreaks.top + bitmap.height
        )
        drawBitmap(canvas, bitmap, speedStreaks.left, speedStreaks.top, progress)
        canvas.restore()
    }

    private fun drawCenteredLayer(
        canvas: Canvas,
        layer: BitmapLayer,
        alpha: Float,
        scale: Float,
        rotationDegrees: Float = 0f,
        translateX: Float = 0f,
        translateY: Float = 0f
    ) {
        if (alpha <= 0f) return
        val centerX = layer.left + layer.bitmap.width / 2f + translateX
        val centerY = layer.top + layer.bitmap.height / 2f + translateY
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(rotationDegrees)
        canvas.scale(scale, scale)
        drawBitmap(
            canvas,
            layer.bitmap,
            -layer.bitmap.width / 2f,
            -layer.bitmap.height / 2f,
            alpha
        )
        canvas.restore()
    }

    private fun drawIndependentLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        scale: Float,
        alpha: Float
    ) {
        if (alpha <= 0f) return
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        drawBitmap(canvas, bitmap, 0f, 0f, alpha)
        canvas.restore()
    }

    private fun drawClippedBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        if (right <= left || bottom <= top) return
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        drawBitmap(canvas, bitmap, 0f, 0f, 1f)
        canvas.restore()
    }

    private fun drawBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        alpha: Float
    ) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        canvas.drawBitmap(bitmap, left, top, paint)
        paint.alpha = 255
    }

    private fun calculateLayout(): BrandLayout {
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val availableWidth = max(1f, width - paddingLeft - paddingRight.toFloat())
        val availableHeight = max(1f, height - paddingTop - paddingBottom.toFloat())

        return if (availableHeight >= availableWidth * 1.15f) {
            calculatePortraitLayout(contentLeft, contentTop, availableWidth, availableHeight)
        } else {
            calculateLandscapeLayout(contentLeft, contentTop, availableWidth, availableHeight)
        }
    }

    private fun calculatePortraitLayout(
        contentLeft: Float,
        contentTop: Float,
        availableWidth: Float,
        availableHeight: Float
    ): BrandLayout {
        val symbolScale = min(availableWidth * 0.92f / SYMBOL_WIDTH, availableHeight * 0.61f / SYMBOL_HEIGHT)
        val wordmarkScale = min(availableWidth * 0.94f / WORDMARK_WIDTH, availableHeight * 0.105f / WORDMARK_HEIGHT)
        val taglineScale = min(availableWidth * 0.88f / TAGLINE_WIDTH, availableHeight * 0.055f / TAGLINE_HEIGHT)
        val gapAfterSymbol = max(dp(10f), availableHeight * 0.018f)
        val gapAfterWordmark = max(dp(6f), availableHeight * 0.010f)
        val blockHeight = SYMBOL_HEIGHT * symbolScale + gapAfterSymbol +
            WORDMARK_HEIGHT * wordmarkScale + gapAfterWordmark + TAGLINE_HEIGHT * taglineScale
        val blockTop = contentTop + max(0f, (availableHeight - blockHeight) * 0.46f)
        val wordmarkTop = blockTop + SYMBOL_HEIGHT * symbolScale + gapAfterSymbol
        val taglineTop = wordmarkTop + WORDMARK_HEIGHT * wordmarkScale + gapAfterWordmark

        return BrandLayout(
            symbolLeft = contentLeft + (availableWidth - SYMBOL_WIDTH * symbolScale) / 2f,
            symbolTop = blockTop,
            symbolScale = symbolScale,
            wordmarkLeft = contentLeft + (availableWidth - WORDMARK_WIDTH * wordmarkScale) / 2f,
            wordmarkTop = wordmarkTop,
            wordmarkScale = wordmarkScale,
            taglineLeft = contentLeft + (availableWidth - TAGLINE_WIDTH * taglineScale) / 2f,
            taglineTop = taglineTop,
            taglineScale = taglineScale
        )
    }

    private fun calculateLandscapeLayout(
        contentLeft: Float,
        contentTop: Float,
        availableWidth: Float,
        availableHeight: Float
    ): BrandLayout {
        val leftColumnWidth = availableWidth * 0.48f
        val rightColumnLeft = contentLeft + availableWidth * 0.50f
        val rightColumnWidth = availableWidth * 0.48f
        val symbolScale = min(leftColumnWidth * 0.92f / SYMBOL_WIDTH, availableHeight * 0.90f / SYMBOL_HEIGHT)
        val wordmarkScale = min(rightColumnWidth * 0.98f / WORDMARK_WIDTH, availableHeight * 0.24f / WORDMARK_HEIGHT)
        val taglineScale = min(rightColumnWidth * 0.90f / TAGLINE_WIDTH, availableHeight * 0.10f / TAGLINE_HEIGHT)
        val textBlockHeight = WORDMARK_HEIGHT * wordmarkScale + dp(10f) + TAGLINE_HEIGHT * taglineScale
        val textTop = contentTop + (availableHeight - textBlockHeight) / 2f

        return BrandLayout(
            symbolLeft = contentLeft + (leftColumnWidth - SYMBOL_WIDTH * symbolScale) / 2f,
            symbolTop = contentTop + (availableHeight - SYMBOL_HEIGHT * symbolScale) / 2f,
            symbolScale = symbolScale,
            wordmarkLeft = rightColumnLeft + (rightColumnWidth - WORDMARK_WIDTH * wordmarkScale) / 2f,
            wordmarkTop = textTop,
            wordmarkScale = wordmarkScale,
            taglineLeft = rightColumnLeft + (rightColumnWidth - TAGLINE_WIDTH * taglineScale) / 2f,
            taglineTop = textTop + WORDMARK_HEIGHT * wordmarkScale + dp(10f),
            taglineScale = taglineScale
        )
    }

    private fun deliverCompletion() {
        if (completionDelivered) return
        completionDelivered = true
        onAnimationFinished?.invoke()
    }

    private fun loadBitmap(@DrawableRes resourceId: Int): Bitmap =
        requireNotNull(BitmapFactory.decodeResource(resources, resourceId)) {
            "Missing brand drawable resource $resourceId"
        }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private data class BitmapLayer(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float
    )

    private data class BrandLayout(
        val symbolLeft: Float,
        val symbolTop: Float,
        val symbolScale: Float,
        val wordmarkLeft: Float,
        val wordmarkTop: Float,
        val wordmarkScale: Float,
        val taglineLeft: Float,
        val taglineTop: Float,
        val taglineScale: Float
    )

    private companion object {
        private const val SYMBOL_WIDTH = 746f
        private const val SYMBOL_HEIGHT = 955f
        private const val WORDMARK_WIDTH = 935f
        private const val WORDMARK_HEIGHT = 175f
        private const val TAGLINE_WIDTH = 920f
        private const val TAGLINE_HEIGHT = 120f
        private const val REDUCED_MOTION_HOLD_MS = 180L
    }
}
