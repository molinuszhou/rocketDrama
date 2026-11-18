package com.dramaflow.app.presentation

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dramaflow.app.R
import com.dramaflow.app.rewards.RewardsApiClient

/** A visual entry for the existing pending-episode reward; no new reward rule. */
internal fun DramaAppActivity.createRocketPiggyRewardCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
    background = rocketRewardCardDrawable()
    val row = LinearLayout(this@createRocketPiggyRewardCard).apply { gravity = Gravity.CENTER_VERTICAL }
    row.addView(ImageView(this@createRocketPiggyRewardCard).apply {
        setImageResource(R.drawable.ic_rocket_piggy)
        imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }, LinearLayout.LayoutParams(dp(36), dp(36)))
    row.addView(TextView(this@createRocketPiggyRewardCard).apply {
        text = t("reward_piggy_title"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_INK); maxLines = 2
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
    addView(row)
    addView(TextView(this@createRocketPiggyRewardCard).apply {
        text = formatCurrencyAmount(snapshot.wallet, snapshot.ring.rewardCash, snapshot.ring.rewardCashUsdReference, includeUsdReference = false)
        typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_REWARD_ICON); maxLines = 1
        includeFontPadding = false
        setAutoSizeTextTypeUniformWithConfiguration(20, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
    addView(TextView(this@createRocketPiggyRewardCard).apply {
        text = if (snapshot.ring.claimable) t("claim_after_ad") else t("keep_watching")
        textSize = 12f; maxLines = 2; setTextColor(COLOR_REWARD_MUTED)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    addView(TextView(this@createRocketPiggyRewardCard).apply {
        text = if (snapshot.ring.claimable) t("watch_ad_claim") else t("task_go_watch")
        textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(COLOR_REWARD_ACTION_TEXT); background = rewardActionDrawable()
        isClickable = true
        setOnClickListener {
            if (snapshot.ring.claimable) showEpisodeRewardClaimDialog()
            else { activeTab = DramaAppActivity.MainTab.HOME; renderActiveTab() }
        }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
}
