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
import com.dramaflow.app.local.LocalDramaApiClient
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.createTasksPage(): View {
    val scroll = scrollPage { content ->
        content.setPadding(0, 0, 0, dp(86))
        val snapshot = rewardsSnapshot
        content.addView(createRewardsHeroHeader(snapshot), matchParams())
        content.addView(createCrownRoadPanel(snapshot), matchParams(top = 12, bottom = 14))
        if (snapshot == null) content.addView(createInlineNotice(t("ledger_note")))
        else content.addView(createRewardChestPanel(snapshot), matchParams(bottom = 16))
        content.addView(createDailyBenefitsPanel(snapshot), matchParams(bottom = 14))
    }
    scroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateRewardsStickyAssetHeader(scrollY >= dp(148)) }
    return scroll
}

/** Reward hero keeps copy in the upper visual safe area and places both balances in one actionable glass bar. */
internal fun DramaAppActivity.createRewardsHeroHeader(snapshot: RewardsApiClient.Snapshot?): View = FrameLayout(this).apply {
    minimumHeight = dp(218)
    clipToOutline = true
    background = gradientDrawable(intArrayOf(0xFFFFCF9E.toInt(), 0xFFFFEAC8.toInt(), COLOR_WARM_PEACH), 0)
    addView(ImageView(this@createRewardsHeroHeader).apply {
        setImageResource(R.drawable.bg_rewards_hero); scaleType = ImageView.ScaleType.FIT_XY; alpha = 0.98f
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    addView(LinearLayout(this@createRewardsHeroHeader).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(12))
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("reward_center"); textSize = 27f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); includeFontPadding = false
        })
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("rewards_subtitle"); textSize = 13f; setTextColor(0xB819202B.toInt()); setPadding(0, dp(5), 0, dp(10))
        })
        val wallet = snapshot?.wallet
        val balanceBar = LinearLayout(this@createRewardsHeroHeader).apply {
            gravity = Gravity.CENTER_VERTICAL; background = roundedDrawable(0xF5FFFFFF.toInt(), 22); setPadding(dp(7), dp(6), dp(7), dp(6)); clipToOutline = true
            addView(createRewardBalance(t("gems"), wallet?.coins?.let(::formatGems) ?: "—", isGem = true) { showGemIncomeDetails() }, LinearLayout.LayoutParams(0, dp(72), 1f))
            addView(View(this@createRewardsHeroHeader).apply { setBackgroundColor(0x16202733) }, LinearLayout.LayoutParams(dp(1), dp(40)))
            addView(createRewardBalance(t("wallet_balance"), wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—", isGem = false) { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) }, LinearLayout.LayoutParams(0, dp(72), 1f))
        }
        addView(balanceBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("reward_balance_tap_hint"); textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xA619202B.toInt()); setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
}

internal fun DramaAppActivity.createRewardBalance(label: String, value: String, isGem: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(7), dp(6), dp(7), dp(6)); isClickable = true; contentDescription = "$label $value"; setOnClickListener { onClick() }
    val icon = ImageView(this@createRewardBalance).apply { setImageResource(if (isGem) R.drawable.reward_gem_crystal else R.drawable.ic_wallet); scaleType = ImageView.ScaleType.CENTER_INSIDE }
    if (isGem) gemBalanceAnchor = icon
    addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
    addView(LinearLayout(this@createRewardBalance).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), 0, 0, 0)
        val valueView = TextView(this@createRewardBalance).apply {
            text = value; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 1; includeFontPadding = false; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = -0.02f
            setAutoSizeTextTypeUniformWithConfiguration(11, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        if (isGem) gemBalanceLabel = valueView else cashBalanceLabel = valueView
        addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@createRewardBalance).apply { text = label; textSize = 11f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0); maxLines = 1 })
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

/** Five compact milestones stay inside the screen width; connection labels carry each level's requirements. */
internal fun DramaAppActivity.createCrownRoadPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(13)); background = roundedDrawable(Color.WHITE, 24)
    addView(createRewardPanelTitle(t("crown_road"), t("crown_road_subtitle"), t("crown_accelerate"), R.drawable.ic_crown) {
        snapshot?.crownProgress?.firstOrNull { !it.earned }?.let(::showCrownRewardedUpgradeDialog)
    })
    val crowns = snapshot?.crownProgress?.sortedBy { it.levelRank }.orEmpty()
    if (crowns.isEmpty()) addView(createInlineNotice(t("ledger_note")), matchParams(top = 12))
    else {
        val timeline = LinearLayout(this@createCrownRoadPanel).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        crowns.forEachIndexed { index, level ->
            if (index > 0) timeline.addView(createCrownConnector(level), LinearLayout.LayoutParams(dp(26), dp(100)))
            timeline.addView(createCrownMilestone(level), LinearLayout.LayoutParams(0, dp(108), 1f))
        }
        addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
    }
}

internal fun DramaAppActivity.createCrownConnector(level: RewardsApiClient.CrownProgress): View = FrameLayout(this).apply {
    addView(TextView(this@createCrownConnector).apply {
        text = "${level.rounds.current}/${level.rounds.target}"; textSize = 8f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); maxLines = 1
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18), Gravity.TOP))
    addView(View(this@createCrownConnector).apply { setBackgroundColor(if (level.earned) COLOR_CORAL else 0xFFFFD8CC.toInt()) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.CENTER))
    addView(TextView(this@createCrownConnector).apply {
        text = "${level.signins.target}d·${level.rewardedAds.target}ad"; textSize = 7f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); maxLines = 1
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(17), Gravity.BOTTOM))
}

internal fun DramaAppActivity.createCrownMilestone(level: RewardsApiClient.CrownProgress): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    val icon = FrameLayout(this@createCrownMilestone).apply {
        background = roundedDrawable(if (level.earned) 0xFFFFF0E5.toInt() else 0xFFFFF8F2.toInt(), 22)
        addView(ImageView(this@createCrownMilestone).apply { setImageResource(crownIconRes(level.code)); scaleType = ImageView.ScaleType.CENTER_INSIDE }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        addView(TextView(this@createCrownMilestone).apply { text = level.levelRank.toString(); textSize = 8f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY); background = roundedDrawable(Color.WHITE, 10) }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END))
    }
    addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
    addView(TextView(this@createCrownMilestone).apply { text = String.format(Locale.US, "%.2fx", level.episodeRewardMultiplier); textSize = 9f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_CORAL); maxLines = 1; setPadding(0, dp(1), 0, 0) })
    val reward = LinearLayout(this@createCrownMilestone).apply { gravity = Gravity.CENTER
        addView(ImageView(this@createCrownMilestone).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(13), dp(13)))
        addView(TextView(this@createCrownMilestone).apply { text = formatCompactGems(level.gems); textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); maxLines = 1; setPadding(dp(1), 0, 0, 0) })
    }
    addView(reward)
    addView(TextView(this@createCrownMilestone).apply { text = t(crownTitleKey(level.code)); textSize = 9f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 1; setPadding(0, dp(1), 0, 0) })
}

internal fun DramaAppActivity.crownIconRes(code: String): Int = when (code) {
    "BRONZE" -> R.drawable.ic_crown_bronze
    "SILVER" -> R.drawable.ic_crown_silver
    "GOLD" -> R.drawable.ic_crown_gold
    "DIAMOND" -> R.drawable.ic_crown_diamond
    else -> R.drawable.ic_crown_royal
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
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@createRewardPanelTitle).apply { setImageResource(iconRes); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(LinearLayout(this@createRewardPanelTitle).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(6), 0)
            addView(TextView(this@createRewardPanelTitle).apply { text = title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createRewardPanelTitle).apply { text = subtitle; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createRewardPanelTitle).apply { text = "$action ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(COLOR_CORAL, 20); setPadding(dp(13), dp(9), dp(13), dp(9)); setOnClickListener { onClick() }; isClickable = true })
    }

internal fun DramaAppActivity.createPendingRewardAction(pending: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = roundedDrawable(0xFFFFF4EF.toInt(), 18)
        addView(ImageView(this@createPendingRewardAction).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(this@createPendingRewardAction).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
            addView(TextView(this@createPendingRewardAction).apply { text = if (pending > 0) "+${formatGems(pending.toLong())} ${t("gems")}" else t("pending_rewards"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createPendingRewardAction).apply { text = t("claim_after_ad"); textSize = 11f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createPendingRewardAction).apply { text = t("claim_all"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); isClickable = pending > 0; setOnClickListener { showEpisodeRewardClaimDialog() } })
    }

internal fun DramaAppActivity.createCoinMilestone(value: String, label: String, reward: Int = 0): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(7), dp(7), dp(7)); background = gradientDrawable(intArrayOf(0xFFFFD7A3.toInt(), 0xFFFFA858.toInt()), 16)
        addView(ImageView(this@createCoinMilestone).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@createCoinMilestone).apply { text = value; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY) })
        addView(TextView(this@createCoinMilestone).apply { text = if (reward > 0) "$label · +$reward" else label; textSize = 10f; maxLines = 2; gravity = Gravity.CENTER; setTextColor(0xCC1C1C1C.toInt()); setPadding(0, dp(2), 0, 0) })
    }

internal fun DramaAppActivity.createChallengeItem(title: String, coins: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(9), dp(6), dp(7)); background = roundedDrawable(0xFFFFF6ED.toInt(), 16)
        addView(TextView(this@createChallengeItem).apply { text = title; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2 })
        val row = LinearLayout(this@createChallengeItem).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this@createChallengeItem).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(TextView(this@createChallengeItem).apply { text = coins; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); setPadding(dp(3), 0, 0, 0) })
        addView(row)
    }

internal fun DramaAppActivity.createDailyBenefitsPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(15)); background = roundedDrawable(Color.WHITE, 24)
        val heading = LinearLayout(this@createDailyBenefitsPanel).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this@createDailyBenefitsPanel).apply { setImageResource(R.drawable.ic_calendar_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(36), dp(36)))
        heading.addView(LinearLayout(this@createDailyBenefitsPanel).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(7), 0, 0, 0)
            addView(TextView(this@createDailyBenefitsPanel).apply { text = t("checkin"); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createDailyBenefitsPanel).apply { text = t("daily_benefits_subtitle"); textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(3), 0, 0) })
        })
        addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        val checkinText = snapshot?.let { t("today_day_reward", it.checkinTodayIndex, it.checkinNextRewardCoins) } ?: t("ledger_note")
        addView(createBenefitRow("", checkinText, if (snapshot?.checkinSignedToday == true) t("checked_in") else t("watch_ad_checkin"), R.drawable.ic_calendar_reward) { if (snapshot?.checkinSignedToday != true) completeDailyCheckin() })
        addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

internal fun DramaAppActivity.createBenefitRow(title: String, subtitle: String, action: String, iconRes: Int, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), 0, dp(6))
        addView(ImageView(this@createBenefitRow).apply { setImageResource(iconRes); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(50), dp(50)))
        addView(LinearLayout(this@createBenefitRow).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, dp(5), 0)
            if (title.isNotBlank()) addView(TextView(this@createBenefitRow).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createBenefitRow).apply { text = subtitle; textSize = 11f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, if (title.isNotBlank()) dp(3) else 0, 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createBenefitRow).apply { text = action; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(COLOR_CORAL, 18); setPadding(dp(11), dp(9), dp(11), dp(9)); setOnClickListener { onClick() }; isClickable = true })
    }

internal fun DramaAppActivity.createRewardChestPanel(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = roundedDrawable(Color.WHITE, 22)
        addView(ImageView(this@createRewardChestPanel).apply { setImageResource(R.drawable.ic_random_chest_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(76), dp(76)))
        addView(LinearLayout(this@createRewardChestPanel).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, 0, 0)
            addView(TextView(this@createRewardChestPanel).apply { text = t("chest"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createRewardChestPanel).apply { text = if (snapshot.chest.availableCount > 0) "${snapshot.chest.availableCount} ${t("chest")}" else t("no_chest"); textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, dp(7)) })
            addView(TextView(this@createRewardChestPanel).apply { text = if (snapshot.chest.availableCount > 0) t("open_chest") else t("go_watch"); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(COLOR_CORAL, 18); setPadding(dp(13), dp(8), dp(13), dp(8)); setOnClickListener { if (snapshot.chest.availableCount > 0) requestRandomChestOpen() else { activeTab = MainTab.HOME; renderActiveTab() } }; isClickable = true })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
