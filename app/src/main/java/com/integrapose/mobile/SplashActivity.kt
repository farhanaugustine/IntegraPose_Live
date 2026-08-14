package com.integrapose.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.integrapose.mobile.branding.BrandAnimationView
import com.integrapose.mobile.branding.BRAND_ANIMATION_DURATION_MS

class SplashActivity : ComponentActivity() {
    private val fallbackHandler = Handler(Looper.getMainLooper())
    private var completed = false
    private val quotes = listOf(
        Quote("Nothing in life is to be feared; it is only to be understood.", "Marie Curie"),
        Quote("The important thing is not to stop questioning.", "Albert Einstein"),
        Quote("Somewhere, something incredible is waiting to be known.", "Carl Sagan"),
        Quote("What I cannot create, I do not understand.", "Richard Feynman"),
        Quote("If I have seen further it is by standing on the shoulders of giants.", "Isaac Newton"),
        Quote("Science is a way of thinking much more than it is a body of knowledge.", "Carl Sagan")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this)
        container.setBackgroundColor(getColor(R.color.brand_splash_navy))

        val brandView = BrandAnimationView(this).apply {
            contentDescription = getString(R.string.brand_animation_description)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onAnimationFinished = ::launchMain
        }

        val quoteView = TextView(this).apply {
            text = nextQuoteText()
            setTextColor(Color.parseColor("#D4E8FF"))
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(20), 0, dp(20), 0)
            setShadowLayer(10f, 0f, 0f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(34)
            }
        }

        container.addView(brandView)
        container.addView(quoteView)
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val statusTop = insets
                .getInsets(WindowInsetsCompat.Type.statusBars())
                .top
            val navigationBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            val layoutParams = quoteView.layoutParams as FrameLayout.LayoutParams
            val safeBottomMargin = maxOf(dp(34), navigationBottom + dp(20))
            if (layoutParams.bottomMargin != safeBottomMargin) {
                layoutParams.bottomMargin = safeBottomMargin
                quoteView.layoutParams = layoutParams
            }
            brandView.setPadding(
                dp(8),
                statusTop + dp(8),
                dp(8),
                safeBottomMargin + dp(104)
            )
            insets
        }
        setContentView(container)
        ViewCompat.requestApplyInsets(container)
        brandView.startAnimation()

        fallbackHandler.postDelayed({ launchMain() }, BRAND_ANIMATION_DURATION_MS + 500L)

        container.setOnClickListener { launchMain() }
    }

    override fun onDestroy() {
        super.onDestroy()
        fallbackHandler.removeCallbacksAndMessages(null)
    }

    private fun launchMain() {
        if (completed) return
        completed = true
        fallbackHandler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun nextQuoteText(): String {
        if (quotes.isEmpty()) return ""
        val prefs = getSharedPreferences("splash_quote_prefs", MODE_PRIVATE)
        val lastIndex = prefs.getInt("last_quote_index", -1)
        val nextIndex = (lastIndex + 1) % quotes.size
        prefs.edit().putInt("last_quote_index", nextIndex).apply()
        val quote = quotes[nextIndex]
        return "\"${quote.text}\" - ${quote.author}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Quote(
        val text: String,
        val author: String
    )
}
