package com.dramaflow.app.presentation

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.dramaflow.app.rewards.RewardsApiClient

/** Informational cycle marks. No click or reward logic is attached to a calendar cell. */
internal fun DramaAppActivity.createRocketLightCheckinStrip(snapshot: RewardsApiClient.Snapshot?): View {
    val today = ((snapshot?.checkinTodayIndex ?: 1).coerceAtLeast(1) - 1).coerceIn(0, 6)
    val completedDays = (snapshot?.checkinStreak ?: 0).coerceIn(0, 7)
    val signedToday = snapshot?.checkinSignedToday == true
    val tiers = snapshot?.checkinRewardTiers?.take(7).orEmpty()
    val fontScale = resources.configuration.fontScale.coerceAtLeast(1f)
    val widthDp = resources.configuration.screenWidthDp.takeIf { it > 0 }?.toFloat()
        ?: (resources.displayMetrics.widthPixels / resources.displayMetrics.density)
    // Parent card has 16dp inner padding and page has 16dp outer padding per side.
    val wideCells = RocketUiLayoutPolicy.calendarNeedsScroll(widthDp - 64f, fontScale)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    repeat(7) { index ->
        val isToday = snapshot != null && index == today
        val isDone = snapshot != null && (index < completedDays || (isToday && signedToday))
        val circleSize = RocketUiLayoutPolicy.calendarCircleDp(fontScale)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
            // Exact server-derived reward is accessible without squeezing a long currency
            // amount into one of seven narrow columns. The card displays the cycle total.
            contentDescription = "D${index + 1}: " + if (snapshot == null) t("syncing") else
                formatRewardUnitsAsCash(snapshot, (tiers.getOrNull(index) ?: tiers.lastOrNull() ?: 0).toLong())
            addView(TextView(this@createRocketLightCheckinStrip).apply {
                text = if (isDone) "✓" else (index + 1).toString()
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(if (isDone) COLOR_INK else if (isToday) COLOR_REWARD_ICON else COLOR_REWARD_MUTED)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isDone) COLOR_ACCENT else if (isToday) COLOR_REWARD_CARD else COLOR_REWARD_SURFACE)
                    setStroke(dp(if (isToday && !isDone) 2 else 1), if (isToday && !isDone) COLOR_ACCENT else COLOR_REWARD_OUTLINE)
                }
            }, LinearLayout.LayoutParams(dp(circleSize), dp(circleSize)))
            addView(TextView(this@createRocketLightCheckinStrip).apply {
                text = "D${index + 1}"
                textSize = 10f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(COLOR_REWARD_MUTED)
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.addView(cell, if (wideCells) LinearLayout.LayoutParams(dp(RocketUiLayoutPolicy.calendarCellDp(fontScale)), ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    if (!wideCells) return row
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        post { scrollTo((today * dp(RocketUiLayoutPolicy.calendarCellDp(fontScale)) - width / 2).coerceAtLeast(0), 0) }
    }
}
