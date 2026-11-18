package com.dramaflow.app.presentation

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.dramaflow.app.R
import com.dramaflow.app.local.LocalDrama
import kotlin.math.abs
import kotlin.math.min

/** UI-only carousel: provider order, covers and the existing playback/analytics entry point stay intact. */
internal fun DramaAppActivity.createRocketHomeCarousel(items: List<LocalDrama>): View =
    RocketHomeCarousel(this, items.take(4))

/**
 * Every card has an explicitly measured image followed by normal-flow, WRAP_CONTENT controls.
 * The title/button can grow with font scale without being positioned over a face or clipped by
 * a fixed-height poster. End padding keeps both the first and last card centreable/reachable.
 */
private class RocketHomeCarousel(
    private val host: DramaAppActivity,
    items: List<LocalDrama>,
) : HorizontalScrollView(host) {
    private val row = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val cards = items.map { RocketHomePosterCard(host, it) }
    private var centredIndex = 0
    private var previousViewportWidth = 0
    private var centreAfterLayout = true

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        clipToPadding = false
        // Keep normal HorizontalScrollView touch, keyboard and accessibility scrolling; no
        // autoplay, timer, scroll hijacking or duplicate/fabricated provider items.
        cards.forEachIndexed { index, card ->
            row.addView(card, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = host.dp(12)
            })
        }
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val offeredWidth = View.MeasureSpec.getSize(widthMeasureSpec)
            .takeIf { it > 0 } ?: host.dp(resources.configuration.screenWidthDp.coerceAtLeast(1))
        val viewportWidth = (offeredWidth - paddingLeft - paddingRight).coerceAtLeast(1)
        // A phone shows an adjacent preview; a tablet/landscape never stretches a poster
        // into a giant card. There is deliberately no fixed minimum that could overflow.
        val cardWidth = min((viewportWidth * 0.76f).toInt().coerceAtLeast(1), host.dp(340))
        val sideInset = ((viewportWidth - cardWidth) / 2).coerceAtLeast(0)
        row.setPadding(sideInset, 0, sideInset, 0)
        val copyHeight = cards.maxOfOrNull { it.measureCopyHeight(cardWidth) } ?: 0
        val viewportHeight = host.root.height.takeIf { it > 0 }
            ?: host.dp(resources.configuration.screenHeightDp.coerceAtLeast(1))
        // Reserve the agreed home chrome plus navigation and breathing room. On very
        // short windows/large fonts shrink only the image; the page can scroll the copy.
        val imageBudget = (viewportHeight - host.dp(112 + 94 + 24) - copyHeight)
            .coerceAtLeast(host.dp(80))
        val posterHeight = min((cardWidth * 1.38f).toInt().coerceAtLeast(1), imageBudget)
        cards.forEach { card ->
            card.sizeForViewport(cardWidth, posterHeight, copyHeight)
        }
        if (previousViewportWidth != viewportWidth) {
            previousViewportWidth = viewportWidth
            centreAfterLayout = true
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (centreAfterLayout && cards.isNotEmpty()) {
            centreAfterLayout = false
            val card = cards[centredIndex.coerceIn(cards.indices)]
            val target = card.left + card.width / 2 - width / 2
            scrollTo(target.coerceIn(0, (row.width - width).coerceAtLeast(0)), 0)
        }
        updateCardEmphasis()
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        updateCardEmphasis()
    }

    private fun updateCardEmphasis() {
        if (cards.isEmpty() || width == 0) return
        val centre = scrollX + width / 2
        centredIndex = cards.indices.minByOrNull { index ->
            abs(cards[index].left + cards[index].width / 2 - centre)
        } ?: 0
        cards.forEach { card ->
            val distance = abs(card.left + card.width / 2 - centre).toFloat()
            val fraction = (distance / (card.width + host.dp(12)).coerceAtLeast(1)).coerceIn(0f, 1f)
            card.alpha = 1f - fraction * 0.16f
        }
    }
}

private class RocketHomePosterCard(
    private val host: DramaAppActivity,
    drama: LocalDrama,
) : LinearLayout(host) {
    private val cover = ImageView(host).apply {
        // Preserve the complete real poster even when a short window reduces image height.
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(COLOR_BACKGROUND)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        load(drama.coverImage) {
            crossfade(true)
            placeholder(host.posterFallbackDrawable())
            error(host.posterFallbackDrawable())
        }
    }
    private val copy = LinearLayout(host).apply {
        orientation = VERTICAL
        setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(14))
    }

    init {
        orientation = VERTICAL
        background = host.roundedDrawable(COLOR_CARD, 20)
        clipToOutline = true
        isClickable = true
        isFocusable = true
        val open = {
            host.recordUiAction("HOME_FEATURED_CONTENT_SELECTED", mapOf("drama_id" to drama.id, "content_title" to drama.title))
            host.showPlayer(drama, 0)
        }
        setOnClickListener { open() }
        addView(cover, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        copy.addView(TextView(host).apply {
            text = drama.title.ifBlank { host.t("drama_fallback") }
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        copy.addView(TextView(host).apply {
            text = host.t("episodes_count", drama.total.coerceAtLeast(1))
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(5)
        })
        copy.addView(TextView(host).apply {
            text = host.t("watch_now")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
            background = host.roundedDrawable(COLOR_ACCENT, 24)
            minimumHeight = host.dp(48)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            val glyph = host.getDrawable(R.drawable.ic_rocket_play)?.mutate()
            glyph?.setTint(COLOR_INK)
            glyph?.setBounds(0, 0, host.dp(20), host.dp(20))
            setCompoundDrawablesRelative(glyph, null, null, null)
            compoundDrawablePadding = host.dp(8)
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
            contentDescription = "${host.t("watch_now")}: ${drama.title.ifBlank { host.t("drama_fallback") }}"
            isClickable = true
            isFocusable = true
            setOnClickListener { open() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(10)
        })
        addView(copy, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun measureCopyHeight(cardWidth: Int): Int {
        copy.minimumHeight = 0
        copy.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return copy.measuredHeight
    }

    fun sizeForViewport(cardWidth: Int, posterHeight: Int, copyHeight: Int) {
        // All four cards share the tallest measured footer so previews stay aligned.
        copy.minimumHeight = copyHeight
        (cover.layoutParams as LayoutParams).height = posterHeight
        layoutParams.width = cardWidth
        layoutParams.height = posterHeight + copyHeight
    }
}
