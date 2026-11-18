package com.dramaflow.app.presentation

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dramaflow.app.R
import com.dramaflow.app.rewards.RewardsApiClient

/** Configuration fallback for standalone rows; the task pair additionally checks measured width. */
internal fun DramaAppActivity.rocketRewardsUseSingleColumn(): Boolean =
    resources.configuration.screenWidthDp < 380 || resources.configuration.fontScale > 1.2f

internal fun DramaAppActivity.openRocketRewardsWallet() {
    val wallet = rewardsSnapshot?.wallet
    if (wallet == null) toast(t("wallet_syncing")) else {
        recordUiAction("WALLET_WITHDRAWAL_OPENED")
        showWithdrawalPage(wallet)
    }
}

private fun DramaAppActivity.selectRocketRewardsTab(progress: Boolean) {
    if (rocketRewardsShowProgress == progress) return
    rocketRewardsShowProgress = progress
    rewardsTasksScrollY = 0
    renderActiveTab()
}

internal fun DramaAppActivity.createRocketRewardsTabs(): View = LinearLayout(this).apply {
    gravity = Gravity.BOTTOM
    listOf(false to "rocket_rewards_today", true to "rocket_rewards_progress").forEach { (progress, key) ->
        val selected = rocketRewardsShowProgress == progress
        addView(LinearLayout(this@createRocketRewardsTabs).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@createRocketRewardsTabs).apply {
                text = t(key); textSize = 16f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (selected) COLOR_INK else COLOR_REWARD_MUTED)
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                setPadding(dp(8), dp(8), dp(8), dp(10))
                isSelected = selected; isClickable = true; isFocusable = true
                setOnClickListener { selectRocketRewardsTab(progress) }
            }, matchParams())
            addView(View(this@createRocketRewardsTabs).apply {
                background = roundedDrawable(if (selected) COLOR_ACCENT else COLOR_REWARD_OUTLINE, 3)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (selected) 3 else 1)))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}

internal fun DramaAppActivity.createRocketRewardsCrownContent(snapshot: RewardsApiClient.Snapshot?): View =
    if (rocketRewardsShowProgress) createCrownRoadPanel(snapshot) else createRocketRewardsAccelerator(snapshot)

/** Clicking the summary opens the complete, existing road; the ad button keeps its original gate. */
private fun DramaAppActivity.createRocketRewardsAccelerator(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
    orientation = if (rocketRewardsUseSingleColumn()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = rocketRewardCardDrawable(18)
    val next = snapshot?.crownProgress?.sortedBy { it.levelRank }?.firstOrNull { !it.earned }
    val summary = LinearLayout(this@createRocketRewardsAccelerator).apply {
        orientation = LinearLayout.VERTICAL
        addView(createRocketRewardsCardHeader(t("crown_accelerate"), R.drawable.ic_rocket_play), matchParams())
        addView(TextView(this@createRocketRewardsAccelerator).apply {
            text = when {
                snapshot == null -> t("ledger_note")
                next == null -> t("crown_complete_title")
                else -> t("crown_progress_rule", next.episodeProgressPoints, next.rewardedAdProgressPoints)
            }
            textSize = 11f; setTextColor(COLOR_REWARD_MUTED)
            setPadding(0, dp(4), dp(8), 0)
        }, matchParams())
        addView(TextView(this@createRocketRewardsAccelerator).apply {
            text = "${t("rocket_rewards_progress")} ›"
            textSize = 12f; setTextColor(COLOR_REWARD_ICON)
            minimumHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setOnClickListener { selectRocketRewardsTab(true) }
        }, matchParams())
    }
    addView(summary, if (orientation == LinearLayout.HORIZONTAL) {
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    } else matchParams())
    val action = createRocketRewardsAction(t("watch_ad_short")) { }
    crownSummaryChargeButtons.add(action)
    updateCrownSummaryChargeButton(action)
    val ring = ChargeCooldownRingView(this@createRocketRewardsAccelerator).apply { visibility = View.GONE }
    crownChargeCooldownRings.add(ring)
    updateCrownChargeCooldownRing(ring)
    val slot = FrameLayout(this@createRocketRewardsAccelerator).apply {
        minimumHeight = dp(48)
        addView(action, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(ring, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
    }
    addView(slot, if (orientation == LinearLayout.HORIZONTAL) {
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .65f).apply { marginStart = dp(10) }
    } else matchParams(top = 6))
}

internal fun DramaAppActivity.createRocketRewardsCardHeader(title: String, iconRes: Int): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    addView(ImageView(this@createRocketRewardsCardHeader).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(30), dp(30)))
    addView(TextView(this@createRocketRewardsCardHeader).apply {
        text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_INK)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
}

internal fun DramaAppActivity.createRocketRewardsAction(label: String, action: () -> Unit): TextView = TextView(this).apply {
    text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(COLOR_REWARD_ACTION_TEXT); background = rewardActionDrawable()
    minimumHeight = dp(46)
    setPadding(dp(10), dp(10), dp(10), dp(10))
    isClickable = true; isFocusable = true
    setOnClickListener { action() }
}

internal fun DramaAppActivity.createRocketRewardsTaskPair(snapshot: RewardsApiClient.Snapshot): View =
    RocketRewardsTaskPair(this, createRocketRewardsWatchCard(snapshot), createRocketPiggyRewardCard(snapshot))

/** MATCH_PARENT card heights are equalized by LinearLayout's uniform-height measurement pass. */
private class RocketRewardsTaskPair(
    private val activity: DramaAppActivity,
    watch: View,
    piggy: View,
) : LinearLayout(activity) {
    private var singleColumn: Boolean? = null
    init { addView(watch); addView(piggy) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val stack = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            availableWidth < activity.dp(348) || resources.configuration.fontScale > 1.2f
        if (singleColumn != stack) {
            singleColumn = stack
            orientation = if (stack) VERTICAL else HORIZONTAL
            for (index in 0 until childCount) {
                getChildAt(index).layoutParams = if (stack) {
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) topMargin = activity.dp(12)
                    }
                } else {
                    LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (index > 0) marginStart = activity.dp(10)
                    }
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

private fun DramaAppActivity.createRocketRewardsWatchCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), dp(14), dp(14), dp(14))
    minimumHeight = dp(190)
    background = rocketRewardCardDrawable(18)
    val tasks = snapshot.tasks
    val claimable = tasks.filter { it.status == "CLAIMABLE" }
    val next = tasks.firstOrNull { it.status == "LOCKED" }
    val focused = claimable.firstOrNull() ?: next ?: tasks.lastOrNull()
    val completed = maxOf(snapshot.wallet.watchedEpisodes, tasks.maxOfOrNull { it.progress } ?: 0)
    val body = LinearLayout(this@createRocketRewardsWatchCard).apply {
        orientation = LinearLayout.VERTICAL
        addView(createRocketRewardsCardHeader(t("watch_tasks"), R.drawable.ic_rocket_play), matchParams())
        addView(TextView(this@createRocketRewardsWatchCard).apply {
            text = when {
                claimable.isNotEmpty() -> t("watch_task_claimable_summary", completed, claimable.size)
                next != null -> t("watch_task_progress_summary", (next.target - completed).coerceAtLeast(0))
                tasks.isNotEmpty() -> t("watch_task_all_claimed")
                else -> t("watch_task_subtitle")
            }
            textSize = 12f; setTextColor(COLOR_REWARD_MUTED)
            setPadding(0, dp(8), 0, dp(10))
        }, matchParams())
        if (focused != null) addView(ProgressBar(this@createRocketRewardsWatchCard, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = focused.target.coerceAtLeast(1)
            progress = completed.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_REWARD_SURFACE)
            contentDescription = "$completed / ${focused.target}"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
    }
    addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(createRocketRewardsAction(if (claimable.isNotEmpty()) t("watch_ad_short") else t("task_go_watch")) {
        // Resolve the server-confirmed state again in case an in-place refresh occurred.
        val target = rewardsSnapshot?.tasks?.firstOrNull { it.status == "CLAIMABLE" }?.target
        if (target != null) {
            recordUiAction("REWARDS_EPISODE_TASK_CLAIM_CLICKED", mapOf("milestone" to target))
            claimEpisodeTaskCash(target)
        } else {
            recordUiAction("REWARDS_TASK_GO_WATCH_CLICKED")
            activeTab = DramaAppActivity.MainTab.HOME
            renderActiveTab()
        }
    }, matchParams(top = 14))
}

/** The real welcome offer remains visible in Hoje; only its typography/layout is replaced. */
internal fun DramaAppActivity.createRocketRewardsWelcomeCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
    background = rocketRewardCardDrawable(18)
    addView(createRocketRewardsCardHeader(t("newcomer_reward"), R.drawable.ic_rocket_gift), matchParams())
    addView(TextView(this@createRocketRewardsWelcomeCard).apply {
        text = formatWelcomeRewardCashValue(snapshot) ?: "—"
        textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK)
        setPadding(0, dp(8), 0, dp(12))
    }, matchParams())
    addView(createRocketRewardsAction(t("watch_ad_claim")) {
        recordUiAction("ONBOARDING_WELCOME_REWARD_AD_CLICKED")
        claimWelcomeBonus(showRewardCenterGuide = false)
    }, matchParams())
}

/** The same calendar geometry is used by the daily card and other light reward surfaces. */
internal fun DramaAppActivity.createRocketRewardsCheckinStrip(snapshot: RewardsApiClient.Snapshot?): View =
    createRocketLightCheckinStrip(snapshot)
