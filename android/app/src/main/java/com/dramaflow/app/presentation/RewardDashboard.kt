package com.dramaflow.app.presentation

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.PlayerView
import coil.load
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.R
import com.dramaflow.app.presentation.DramaAppActivity.MainTab
import com.dramaflow.app.i18n.AppLanguage
import com.dramaflow.app.ads.AdConsent
import com.dramaflow.app.ads.AdConsentStore
import com.dramaflow.app.ads.TopOnAdsManager
import com.dramaflow.app.ads.TopOnSettings
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.createTasksPage(): View {
    var hero: View? = null
    val scroll = scrollPage { content ->
        content.setPadding(dp(16), 0, dp(16), dp(BOTTOM_NAV_HEIGHT_DP + 24))
        val snapshot = rewardsSnapshot
        hero = createRewardsHeroHeader(snapshot)
        content.addView(hero, matchParams())
        content.addView(createRocketRewardsTabs(), matchParams(bottom = 16))
        if (snapshot == null) {
            content.addView(createInlineNotice(t("ledger_note")), matchParams(bottom = 12))
        } else if (!rocketRewardsShowProgress) {
            content.addView(createDailyBenefitsPanel(snapshot), matchParams(bottom = 12))
            if (snapshot.welcome.available) {
                content.addView(createRocketRewardsWelcomeCard(snapshot), matchParams(bottom = 12))
            }
            content.addView(createRocketRewardsTaskPair(snapshot), matchParams(bottom = 12))
        } else {
            // Keep the complete milestone ladder reachable without crowding the daily overview.
            content.addView(createEpisodeTaskLine(snapshot), matchParams(bottom = 12))
        }
        FrameLayout(this@createTasksPage).also { host ->
            rewardCrownRoadHost = host
            crownSummaryChargeButtons.clear()
            crownChargeCooldownBars.clear()
            crownChargeCooldownRings.clear()
            host.addView(createRocketRewardsCrownContent(snapshot), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(host, matchParams(bottom = 12))
        }
        val showAmountDisclaimer = BuildConfig.DEBUG ||
            (snapshot?.wallet?.rewardProgressUnits ?: 0L) >= FIRST_DAMPING_TIER_NOTICE_THRESHOLD_UNITS
        if (showAmountDisclaimer) {
            content.addView(TextView(this@createTasksPage).apply {
                text = t("reward_amount_disclaimer")
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(COLOR_REWARD_MUTED)
                setPadding(dp(14), dp(4), dp(14), dp(10))
            }, matchParams(bottom = 8))
        }
    }
    scroll.background = android.graphics.drawable.ColorDrawable(COLOR_REWARD_PAGE)
    scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
        rewardsTasksScrollY = scrollY
        // Large-font hero height is measured rather than assuming a fixed screen coordinate.
        updateRewardsStickyAssetHeader(scrollY >= (hero?.bottom ?: dp(180)))
    }
    if (rewardsTasksScrollY > 0) {
        scroll.post { scroll.scrollTo(0, rewardsTasksScrollY) }
    }
    return scroll
}

/** Refresh only the Crown Road card after an ad, claim, or cooldown transition. */
internal fun DramaAppActivity.refreshCrownRoadPanelInPlace(): Boolean {
    val host = rewardCrownRoadHost ?: return false
    if (activeTab != MainTab.TASKS || !host.isAttachedToWindow) return false
    crownSummaryChargeButtons.clear()
    crownChargeCooldownBars.clear()
    crownChargeCooldownRings.clear()
    host.removeAllViews()
    host.addView(
        createRocketRewardsCrownContent(rewardsSnapshot),
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    return true
}

/** Plain white balance hierarchy; all wallet taps resolve the latest confirmed snapshot. */
internal fun DramaAppActivity.createRewardsHeroHeader(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(4), dp(18), dp(4), dp(18))
    addView(LinearLayout(this@createRewardsHeroHeader).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("reward_center"); textSize = 25f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@createRewardsHeroHeader).apply {
            setImageResource(R.drawable.ic_rocket_wallet)
            imageTintList = ColorStateList.valueOf(COLOR_INK)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = t("wallet")
            isClickable = true; isFocusable = true
            setOnClickListener { openRocketRewardsWallet() }
            cashBalanceAnchor = this
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
    }, matchParams(bottom = 20))
    addView(TextView(this@createRewardsHeroHeader).apply {
        text = t("wallet_total_label"); textSize = 13f; setTextColor(COLOR_REWARD_MUTED)
    })
    val wallet = snapshot?.wallet
    val balanceRow = LinearLayout(this@createRewardsHeroHeader).apply {
        orientation = if (rocketRewardsUseSingleColumn()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val amount = TextView(this@createRewardsHeroHeader).apply {
        text = wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—"
        textSize = 38f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK)
        // Wrapping is preferable to shrinking accessibility text or truncating a real balance.
        setPadding(0, dp(3), 0, dp(2))
        isClickable = true; isFocusable = true
        setOnClickListener { openRocketRewardsWallet() }
    }
    cashBalanceLabel = amount
    rewardHeroCashBalanceLabel = amount
    balanceRow.addView(amount, if (balanceRow.orientation == LinearLayout.HORIZONTAL) {
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    } else matchParams())
    balanceRow.addView(TextView(this@createRewardsHeroHeader).apply {
        text = "${t("wallet")} ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_REWARD_ICON); minimumHeight = dp(44)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), 0, dp(8))
        isClickable = true; isFocusable = true
        setOnClickListener { openRocketRewardsWallet() }
    }, if (balanceRow.orientation == LinearLayout.HORIZONTAL) {
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .55f)
    } else matchParams())
    addView(balanceRow, matchParams())
    val available = TextView(this@createRewardsHeroHeader).apply {
        text = t("wallet_available_inline", wallet?.let { formatCurrencyAmount(it, it.availableBalance, it.availableBalanceUsdReference, includeUsdReference = false) } ?: "—")
        textSize = 13f; setTextColor(COLOR_REWARD_MUTED)
        setPadding(0, dp(3), 0, 0)
    }
    rewardHeroAvailableBalanceLabel = available
    addView(available, matchParams())
}

internal fun DramaAppActivity.createRewardBalance(
    label: String,
    value: String,
    isGem: Boolean,
    isRewardsHeroBalance: Boolean = false,
    onClick: () -> Unit,
): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(16), dp(8), dp(16), dp(8))
    isClickable = true
    contentDescription = "$label $value"
    setOnClickListener { onClick() }
    val icon = ImageView(this@createRewardBalance).apply {
        setImageResource(if (isGem) R.drawable.ic_rocket_gift else R.drawable.ic_rocket_wallet)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    if (isGem) gemBalanceAnchor = icon else cashBalanceAnchor = icon
    addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
    addView(LinearLayout(this@createRewardBalance).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
        addView(TextView(this@createRewardBalance).apply {
            text = label
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            includeFontPadding = false
        })
        val valueView = TextView(this@createRewardBalance).apply {
            text = value
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 1
            includeFontPadding = false
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = -0.02f
            setAutoSizeTextTypeUniformWithConfiguration(22, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        if (isGem) {
            gemBalanceLabel = valueView
        } else {
            cashBalanceLabel = valueView
            if (isRewardsHeroBalance) rewardHeroCashBalanceLabel = valueView
        }
        addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
}

/** Crown Road uses one readable row per level so all benefits fit without horizontal compression. */

/** Scrollable watch-episode task line: all generated nodes remain reachable while the current node is focused by default. */
internal fun DramaAppActivity.createEpisodeTaskLine(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(15))
    background = rocketRewardCardDrawable(22)
    addView(TextView(this@createEpisodeTaskLine).apply {
        text = t("reward_tasks_title")
        textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK)
    })
    addView(TextView(this@createEpisodeTaskLine).apply {
        text = t("watch_task_subtitle")
        textSize = 12f; setTextColor(COLOR_REWARD_MUTED); setPadding(0, dp(4), 0, dp(13))
    })
    val tasks = snapshot.tasks
    val claimableTasks = tasks.filter { it.status == "CLAIMABLE" }
    val nextLockedTask = tasks.firstOrNull { it.status == "LOCKED" }
    // Progress is cumulative across the milestone ladder, so the largest task progress is the
    // completed-episode count presented to the viewer.
    val completedEpisodes = maxOf(snapshot.wallet.watchedEpisodes, tasks.maxOfOrNull { it.progress } ?: 0)
    val focusedTask = claimableTasks.firstOrNull() ?: nextLockedTask ?: tasks.lastOrNull()
    val focusedTaskIndex = tasks.indexOf(focusedTask).coerceAtLeast(0)
    val track = LinearLayout(this@createEpisodeTaskLine).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(12), 0) }
    tasks.forEachIndexed { index, task ->
        val reached = task.status == "CLAIMED" || task.status == "CLAIMABLE"
        track.addView(TextView(this@createEpisodeTaskLine).apply {
            text = task.target.toString(); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (reached) COLOR_INK else COLOR_REWARD_MUTED)
            minimumWidth = dp(31); minimumHeight = dp(31)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (reached) COLOR_CORAL else COLOR_REWARD_SURFACE) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (index < tasks.lastIndex) track.addView(View(this@createEpisodeTaskLine).apply { setBackgroundColor(if (reached) COLOR_CORAL else COLOR_REWARD_OUTLINE) }, LinearLayout.LayoutParams(dp(22), dp(3)))
    }
    val scroller = HorizontalScrollView(this@createEpisodeTaskLine).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(track, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    addView(scroller, matchParams())
    scroller.post { scroller.scrollTo(((track.getChildAt(focusedTaskIndex * 2)?.left ?: 0) - dp(44)).coerceAtLeast(0), 0) }
    if (focusedTask != null) {
        val hasClaimableRewards = claimableTasks.isNotEmpty()
        val taskStatusText = when {
            hasClaimableRewards -> t("watch_task_claimable_summary", completedEpisodes, claimableTasks.size)
            nextLockedTask != null -> t(
                "watch_task_progress_summary",
                (nextLockedTask.target - completedEpisodes).coerceAtLeast(0),
            )
            else -> t("watch_task_all_claimed")
        }
        val actionRow = LinearLayout(this@createEpisodeTaskLine).apply {
            orientation = if (rocketRewardsUseSingleColumn()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(13), 0, 0)
        }
        actionRow.addView(LinearLayout(this@createEpisodeTaskLine).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@createEpisodeTaskLine).apply {
                text = taskStatusText
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (hasClaimableRewards) COLOR_REWARD_ICON else COLOR_INK)
                setPadding(0, dp(2), dp(8), dp(2))
            })
        }, if (actionRow.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else matchParams(bottom = 8))
        actionRow.addView(TextView(this@createEpisodeTaskLine).apply {
            text = if (hasClaimableRewards) t("watch_ad_short") else t("task_go_watch")
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setTextColor(if (hasClaimableRewards) COLOR_REWARD_ACTION_TEXT else COLOR_INK)
            background = if (hasClaimableRewards) rewardActionDrawable() else roundedDrawable(COLOR_REWARD_SURFACE, 13)
            setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
            setOnClickListener {
                val claimTarget = claimableTasks.firstOrNull()?.target
                if (claimTarget != null) {
                    recordUiAction("REWARDS_EPISODE_TASK_CLAIM_CLICKED", mapOf("milestone" to claimTarget))
                    claimEpisodeTaskCash(claimTarget)
                } else {
                    recordUiAction("REWARDS_TASK_GO_WATCH_CLICKED")
                    activeTab = MainTab.HOME
                    renderActiveTab()
                }
            }
        }, if (actionRow.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .65f) else matchParams())
        addView(actionRow)
    }
}

internal fun DramaAppActivity.claimEpisodeTaskCash(milestone: Int) {
    requestRewardedAction(
        placementId = "rv_episode_task_claim",
        targetId = milestone.toString(),
        onSettled = { action ->
            if (action.idempotent) toast(t("task_already_claimed"))
        },
    )
}

internal fun DramaAppActivity.formatRewardUnitsAsCash(snapshot: RewardsApiClient.Snapshot, units: Long): String {
    val perTenThousand = snapshot.wallet.exchangeValuePer10000Gems.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
    val amount = perTenThousand.multiply(java.math.BigDecimal(units)).divide(java.math.BigDecimal(10_000), 6, java.math.RoundingMode.HALF_UP)
    return formatCurrencyAmount(snapshot.wallet, amount.toPlainString(), null, includeUsdReference = false)
}

internal fun DramaAppActivity.createCrownRoadPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(13))
    background = rocketRewardCardDrawable(24)
    addView(createRewardPanelTitle(t("crown_road"), "", t("crown_accelerate"), R.drawable.ic_rocket_crown) {
        snapshot?.crownProgress?.firstOrNull { !it.earned }?.let(::showCrownRewardedUpgradeDialog)
    })
    val crowns = snapshot?.crownProgress?.sortedBy { it.levelRank }.orEmpty()
    if (crowns.isEmpty()) addView(createInlineNotice(t("ledger_note")), matchParams(top = 12))
    else {
        val currentCode = crowns.firstOrNull { !it.earned }?.code
        crowns.forEachIndexed { index, level ->
            addView(createCrownLevelRow(level, level.code == currentCode), matchParams(top = if (index == 0) 14 else 8))
        }
    }
}

internal fun DramaAppActivity.createCrownLevelRow(level: RewardsApiClient.CrownProgress, isCurrent: Boolean = false): View = LinearLayout(this).apply {
    val stacked = rocketRewardsUseSingleColumn()
    orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(9), dp(9), dp(9), dp(9))
    background = roundedDrawable(when {
        isCurrent -> COLOR_CROWN_CURRENT_SURFACE
        level.earned -> COLOR_REWARD_SURFACE
        else -> COLOR_REWARD_SURFACE
    }, 18)
    val iconColumn = LinearLayout(this@createCrownLevelRow).apply {
        orientation = if (stacked) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = if (stacked) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        val iconFrame = FrameLayout(this@createCrownLevelRow).apply {
            // 3D rank assets include their own metal and gem shading. A card-colour circle here
            // reads as an unintended black halo against each row, so keep the container clear.
            background = null
            addView(ImageView(this@createCrownLevelRow).apply { setImageResource(crownIconRes(level.code)); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
        addView(iconFrame, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@createCrownLevelRow).apply {
            text = if (isCurrent) t("crown_current") else if (level.earned) t("crown_reached") else t("crown_locked")
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(if (isCurrent) COLOR_REWARD_ICON else COLOR_REWARD_MUTED); setPadding(0, dp(3), 0, 0)
        }, if (stacked) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    addView(iconColumn, LinearLayout.LayoutParams(if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else dp(62), ViewGroup.LayoutParams.WRAP_CONTENT))
    val middle = LinearLayout(this@createCrownLevelRow).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@createCrownLevelRow).apply {
            text = "${level.levelRank}. ${t(crownTitleKey(level.code))}"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK)
        })
        addView(TextView(this@createCrownLevelRow).apply {
            val wallet = rewardsSnapshot?.wallet
            val amount = wallet?.let { formatCurrencyAmount(it, level.episodeRewardCash, null, includeUsdReference = false) } ?: level.episodeRewardCash
            text = t("crown_episode_cash", amount)
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CROWN_CASH); setPadding(0, dp(3), 0, dp(6))
        })
        addView(ProgressBar(this@createCrownLevelRow, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = (level.progress * 100).toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(if (level.earned) COLOR_CORAL else COLOR_SUNSET)
            backgroundTintList = ColorStateList.valueOf(0x553F342B)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
        addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_progress_short", level.rounds.current, level.rounds.target)
            textSize = 11f; setTextColor(COLOR_REWARD_MUTED); setPadding(0, dp(4), 0, 0)
        })
        if (isCurrent) addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_progress_rule", level.episodeProgressPoints, level.rewardedAdProgressPoints)
            textSize = 11f; setTextColor(COLOR_REWARD_ICON); setPadding(0, dp(2), 0, 0)
        })
    }
    addView(middle, if (stacked) matchParams(top = 6, bottom = 8) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
    val reward = LinearLayout(this@createCrownLevelRow).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@createCrownLevelRow).apply { setImageResource(R.drawable.ic_rocket_gift); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_completion_reward_label")
            textSize = 10f; gravity = Gravity.CENTER; setTextColor(COLOR_REWARD_ICON)
        })
        addView(TextView(this@createCrownLevelRow).apply {
            val wallet = rewardsSnapshot?.wallet
            text = wallet?.let { formatCurrencyAmount(it, level.cash, null, includeUsdReference = false) } ?: level.cash
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_REWARD_ICON)
        }, matchParams())
        if (level.earned) addView(TextView(this@createCrownLevelRow).apply { text = "✓"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(COLOR_REWARD_ICON) })
    }
    addView(reward, LinearLayout.LayoutParams(if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else dp(76), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (!stacked) marginStart = dp(4) })
}

internal fun DramaAppActivity.crownIconRes(code: String): Int = when (code) {
    "BRONZE" -> R.drawable.ic_crown_bronze
    "SILVER" -> R.drawable.ic_crown_silver
    "GOLD" -> R.drawable.ic_crown_gold
    "DIAMOND" -> R.drawable.ic_crown_diamond
    else -> R.drawable.ic_rocket_crown
}

internal fun DramaAppActivity.formatCompactGems(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0).removeSuffix(".0M") + if (value % 1_000_000L == 0L) "M" else ""
    value >= 1_000L -> String.format(Locale.US, "%.0fK", value / 1_000.0)
    else -> value.toString()
}

internal fun DramaAppActivity.crownTitleKey(code: String): String = when (code) {
    "BRONZE" -> "crown_bronze"; "SILVER" -> "crown_silver"; "GOLD" -> "crown_gold"; "DIAMOND" -> "crown_diamond"; else -> "crown_crown"
}

internal fun DramaAppActivity.crownBenefitKey(code: String): String = when (code) {
    "BRONZE" -> "crown_bronze_benefit"; "SILVER" -> "crown_silver_benefit"; "GOLD" -> "crown_gold_benefit"; "DIAMOND" -> "crown_diamond_benefit"; else -> "crown_crown_benefit"
}

internal fun DramaAppActivity.createRewardPanelTitle(title: String, subtitle: String, action: String, iconRes: Int, onClick: () -> Unit): View = LinearLayout(this).apply {
        val stacked = rocketRewardsUseSingleColumn()
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val heading = LinearLayout(this@createRewardPanelTitle).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@createRewardPanelTitle).apply { setImageResource(iconRes); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(LinearLayout(this@createRewardPanelTitle).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(6), 0)
            addView(TextView(this@createRewardPanelTitle).apply { text = title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK) })
            if (subtitle.isNotBlank()) addView(TextView(this@createRewardPanelTitle).apply { text = subtitle; textSize = 12f; setTextColor(COLOR_REWARD_MUTED); setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(heading, if (stacked) matchParams(bottom = 8) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actionSlot = FrameLayout(this@createRewardPanelTitle).apply { clipChildren = false; clipToPadding = false; minimumHeight = dp(48) }
        val actionView = TextView(this@createRewardPanelTitle).apply {
            text = "$action ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setTextColor(COLOR_REWARD_ACTION_TEXT); background = rewardActionDrawable(); setPadding(dp(10), dp(9), dp(10), dp(9)); setOnClickListener { onClick() }; isClickable = true; isFocusable = true
        }
        val cooldownRing = ChargeCooldownRingView(this@createRewardPanelTitle).apply { visibility = View.GONE }
        crownSummaryChargeButtons.add(actionView); updateCrownSummaryChargeButton(actionView)
        crownChargeCooldownRings.add(cooldownRing); updateCrownChargeCooldownRing(cooldownRing)
        actionSlot.addView(actionView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        actionSlot.addView(cooldownRing, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        addView(actionSlot, if (stacked) matchParams() else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .7f).apply { marginStart = dp(8) })
    }

internal fun DramaAppActivity.createPendingRewardAction(pending: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = roundedDrawable(COLOR_REWARD_SURFACE, 18)
        addView(ImageView(this@createPendingRewardAction).apply { setImageResource(R.drawable.ic_rocket_gift); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(this@createPendingRewardAction).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
            addView(TextView(this@createPendingRewardAction).apply { text = t("pending_rewards"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK) })
            addView(TextView(this@createPendingRewardAction).apply { text = t("claim_after_ad"); textSize = 11f; setTextColor(COLOR_REWARD_MUTED); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createPendingRewardAction).apply { text = t("claim_all"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_REWARD_ICON); isClickable = pending > 0; setOnClickListener { showEpisodeRewardClaimDialog() } })
    }

internal fun DramaAppActivity.createCoinMilestone(value: String, label: String, reward: Int = 0): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(7), dp(7), dp(7)); background = gradientDrawable(intArrayOf(COLOR_REWARD_SURFACE, COLOR_REWARD_SURFACE), 16)
        addView(ImageView(this@createCoinMilestone).apply { setImageResource(R.drawable.ic_rocket_gift); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@createCoinMilestone).apply { text = value; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_INK) })
        addView(TextView(this@createCoinMilestone).apply { text = if (reward > 0) "$label · +$reward" else label; textSize = 10f; maxLines = 2; gravity = Gravity.CENTER; setTextColor(COLOR_REWARD_MUTED); setPadding(0, dp(2), 0, 0) })
    }

internal fun DramaAppActivity.createChallengeItem(title: String, coins: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(9), dp(6), dp(7)); background = roundedDrawable(COLOR_REWARD_SURFACE, 16)
        addView(TextView(this@createChallengeItem).apply { text = title; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK); maxLines = 2 })
        val row = LinearLayout(this@createChallengeItem).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this@createChallengeItem).apply { setImageResource(R.drawable.ic_rocket_wallet); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(TextView(this@createChallengeItem).apply { text = coins; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_REWARD_ICON); setPadding(dp(3), 0, 0, 0) })
        addView(row)
    }

internal fun DramaAppActivity.createDailyBenefitsPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
    background = rocketRewardCardDrawable(18)
    addView(createRocketRewardsCardHeader(t("reward_daily_title"), R.drawable.ic_rocket_calendar), matchParams())
    addView(TextView(this@createDailyBenefitsPanel).apply {
        text = if (snapshot?.checkinSignedToday == true) t("checkin_done_message", snapshot.checkinStreak) else t("daily_benefits_subtitle")
        textSize = 12f
        setTextColor(COLOR_REWARD_MUTED)
        setPadding(0, dp(6), 0, dp(8))
    }, matchParams())
    addView(createRocketRewardsCheckinStrip(snapshot), matchParams(top = 4, bottom = 8))
    addView(TextView(this@createDailyBenefitsPanel).apply {
        text = snapshot?.let { t("checkin_seven_day_total", formatSevenDayCheckinTotal(it)) } ?: t("checkin_syncing")
        textSize = 11f; setTextColor(COLOR_REWARD_ICON)
        setPadding(0, 0, 0, dp(10))
    }, matchParams())
    addView(createRocketRewardsAction(if (snapshot?.checkinSignedToday == true) t("calendar_reminder_action") else t("watch_ad_checkin")) {
        if (snapshot?.checkinSignedToday == true) showCheckinCalendarReminderPrompt()
        else completeDailyCheckin(suppressStartupPrompt = true)
    }.apply { isClickable = snapshot != null }, matchParams())
}

internal fun DramaAppActivity.createBenefitRow(title: String, subtitle: String, action: String, iconRes: Int, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), 0, dp(6))
        addView(ImageView(this@createBenefitRow).apply { setImageResource(iconRes); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(COLOR_REWARD_ICON) }, LinearLayout.LayoutParams(dp(50), dp(50)))
        addView(LinearLayout(this@createBenefitRow).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, dp(5), 0)
            if (title.isNotBlank()) addView(TextView(this@createBenefitRow).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK) })
            addView(TextView(this@createBenefitRow).apply { text = subtitle; textSize = 11f; setTextColor(COLOR_REWARD_MUTED); setPadding(0, if (title.isNotBlank()) dp(3) else 0, 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createBenefitRow).apply { text = action; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_REWARD_ACTION_TEXT); background = rewardActionDrawable(); setPadding(dp(11), dp(9), dp(11), dp(9)); setOnClickListener { onClick() }; isClickable = true })
    }
