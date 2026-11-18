package com.dramaflow.app.presentation

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.dramaflow.app.R
import com.dramaflow.app.rewards.RewardsApiClient

/** A visual entry for the existing pending-episode reward; no new reward rule. */
internal fun DramaAppActivity.createRocketPiggyRewardCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), dp(14), dp(14), dp(14))
    minimumHeight = dp(190)
    background = rocketRewardCardDrawable(18)
    val body = LinearLayout(this@createRocketPiggyRewardCard).apply {
        orientation = LinearLayout.VERTICAL
        addView(createRocketRewardsCardHeader(t("reward_piggy_title"), R.drawable.ic_rocket_piggy), matchParams())
        addView(TextView(this@createRocketPiggyRewardCard).apply {
            text = if (snapshot.ring.claimable) t("pending_rewards") else t("keep_watching")
            textSize = 12f; setTextColor(COLOR_REWARD_MUTED)
            setPadding(0, dp(8), 0, dp(4))
        }, matchParams())
        addView(TextView(this@createRocketPiggyRewardCard).apply {
            text = formatCurrencyAmount(snapshot.wallet, snapshot.ring.rewardCash, snapshot.ring.rewardCashUsdReference, includeUsdReference = false)
            textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK)
            // Real amounts wrap naturally under a large accessibility font.
            setPadding(0, 0, 0, dp(2))
        }, matchParams())
    }
    addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(createRocketRewardsAction(if (snapshot.ring.claimable) t("watch_ad_claim") else t("task_go_watch")) {
        if (rewardsSnapshot?.ring?.claimable == true) showEpisodeRewardClaimDialog()
        else { activeTab = DramaAppActivity.MainTab.HOME; renderActiveTab() }
    }, matchParams(top = 14))
}
