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
        if (snapshot == null) content.addView(createInlineNotice(t("ledger_note")), matchParams(top = 12, bottom = 14))
        else {
            content.addView(createRewardChestPanel(snapshot), matchParams(top = 12, bottom = 12))
            content.addView(createReviewRewardPanel(snapshot), matchParams(bottom = 14))
        }
        content.addView(createDailyBenefitsPanel(snapshot), matchParams(bottom = 14))
        content.addView(createCrownRoadPanel(snapshot), matchParams(bottom = 14))
    }
    scroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateRewardsStickyAssetHeader(scrollY >= dp(148)) }
    return scroll
}

/** A compact, inset-aware hero: headline sits below the status bar and the image has no empty lower band. */
internal fun DramaAppActivity.createRewardsHeroHeader(snapshot: RewardsApiClient.Snapshot?): View = FrameLayout(this).apply {
    minimumHeight = dp(204)
    clipToOutline = true
    background = gradientDrawable(intArrayOf(0xFFFFCF9E.toInt(), 0xFFFFEAC8.toInt(), COLOR_WARM_PEACH), 0)
    addView(ImageView(this@createRewardsHeroHeader).apply {
        setImageResource(R.drawable.bg_rewards_hero)
        scaleType = ImageView.ScaleType.CENTER_CROP
        // Shift the crop upward slightly so the crystal remains visible while removing the lower empty area.
        translationY = -dp(12).toFloat()
        alpha = 0.98f
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = -dp(24) })
    val foreground = LinearLayout(this@createRewardsHeroHeader).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(48), dp(20), dp(2))
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("reward_center"); textSize = 27f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); includeFontPadding = false
        })
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("rewards_subtitle"); textSize = 13f; setTextColor(0xB819202B.toInt()); setPadding(0, dp(5), 0, dp(7))
        })
        val wallet = snapshot?.wallet
        val balanceBar = LinearLayout(this@createRewardsHeroHeader).apply {
            gravity = Gravity.CENTER_VERTICAL; background = roundedDrawable(0xF5FFFFFF.toInt(), 22); setPadding(dp(7), dp(4), dp(7), dp(4)); clipToOutline = true
            addView(createRewardBalance(t("gems"), wallet?.coins?.let(::formatGems) ?: "—", isGem = true) { showGemIncomeDetails() }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(View(this@createRewardsHeroHeader).apply { setBackgroundColor(0x16202733) }, LinearLayout.LayoutParams(dp(1), dp(36)))
            addView(createRewardBalance(t("wallet_balance"), wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—", isGem = false) { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) }, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        addView(balanceBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("reward_balance_tap_hint"); textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xA619202B.toInt()); setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    addView(foreground, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
        foreground.setPadding(dp(20), maxOf(dp(48), safeTop + dp(20)), dp(20), dp(2))
        insets
    }
    ViewCompat.requestApplyInsets(this)
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

/** Crown Road uses one readable row per level so all benefits fit without horizontal compression. */
/** A once-only store review reward. Amount and claim state are supplied by the rewards ledger. */
internal fun DramaAppActivity.createReviewRewardPanel(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(15), dp(14), dp(14), dp(14))
    background = gradientDrawable(intArrayOf(0xFFFFF5E8.toInt(), 0xFFFFE3C5.toInt()), 22)
    addView(ImageView(this@createReviewRewardPanel).apply {
        setImageResource(R.drawable.ic_rating_star)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    addView(LinearLayout(this@createReviewRewardPanel).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), 0, dp(8), 0)
        addView(TextView(this@createReviewRewardPanel).apply {
            text = t("review_reward_title")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        addView(TextView(this@createReviewRewardPanel).apply {
            text = if (snapshot.review.claimed) t("review_reward_thanks") else t("review_reward_subtitle", formatGems(snapshot.review.gems))
            textSize = 11f
            maxLines = 2
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(3), 0, 0)
        })
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(TextView(this@createReviewRewardPanel).apply {
        text = if (snapshot.review.claimed) t("review_reward_done") else "+${formatCompactGems(snapshot.review.gems)}"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (snapshot.review.claimed) COLOR_TEXT_MUTED else Color.WHITE)
        background = roundedDrawable(if (snapshot.review.claimed) 0x55FFFFFF else COLOR_CORAL, 17)
        setPadding(dp(11), dp(9), dp(11), dp(9))
        isClickable = snapshot.review.available
        alpha = if (snapshot.review.available) 1f else .72f
        setOnClickListener { if (snapshot.review.available) requestStoreReviewReward(snapshot.review.gems) }
    })
}

internal fun DramaAppActivity.requestStoreReviewReward(gems: Long) {
    showBrandedChoiceDialog(
        t("review_reward_title"),
        t("review_reward_message", formatGems(gems)),
        t("review_reward_action"),
        onPrimary = {
            val packageUri = Uri.parse("market://details?id=$packageName")
            val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            val launched = runCatching { startActivity(Intent(Intent.ACTION_VIEW, packageUri)) }
                .recoverCatching { startActivity(Intent(Intent.ACTION_VIEW, webUri)) }
                .isSuccess
            if (launched) pendingReviewRewardConfirmation = true
            else showBrandedChoiceDialog(t("review_reward_title"), t("review_store_unavailable"), t("done"))
        }, secondaryLabel = t("later"),
    )
}

internal fun DramaAppActivity.showReviewRewardConfirmation() {
    showBrandedChoiceDialog(
        t("review_reward_title"), t("review_reward_confirm"), t("review_reward_claim"),
        onPrimary = {
            rewardsApi.claimReviewReward(anonymousInstallationId(), { action -> runOnUiThread {
                applyRewardsSnapshot(action.snapshot)
                toast(if (action.idempotent) t("review_reward_done") else t("gems_claimed", action.gemsAdded))
            } }, { message -> runOnUiThread { showBrandedChoiceDialog(t("review_reward_title"), message, t("done")) } })
        }, secondaryLabel = t("later"),
    )
}

internal fun DramaAppActivity.createCrownRoadPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(13))
    background = roundedDrawable(Color.WHITE, 24)
    addView(createRewardPanelTitle(t("crown_road"), t("crown_road_subtitle"), t("crown_accelerate"), R.drawable.ic_crown) {
        snapshot?.crownProgress?.firstOrNull { !it.earned }?.let(::showCrownRewardedUpgradeDialog)
    })
    val crowns = snapshot?.crownProgress?.sortedBy { it.levelRank }.orEmpty()
    if (crowns.isEmpty()) addView(createInlineNotice(t("ledger_note")), matchParams(top = 12))
    else crowns.forEachIndexed { index, level ->
        addView(createCrownLevelRow(level), matchParams(top = if (index == 0) 14 else 8))
    }
}

internal fun DramaAppActivity.createCrownLevelRow(level: RewardsApiClient.CrownProgress): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(10), dp(10), dp(10), dp(10))
    background = roundedDrawable(if (level.earned) 0xFFFFF1E8.toInt() else 0xFFFFFAF6.toInt(), 18)
    val iconColumn = LinearLayout(this@createCrownLevelRow).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val iconFrame = FrameLayout(this@createCrownLevelRow).apply {
            background = roundedDrawable(Color.WHITE, 20)
            addView(ImageView(this@createCrownLevelRow).apply { setImageResource(crownIconRes(level.code)); scaleType = ImageView.ScaleType.CENTER_INSIDE }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        }
        addView(iconFrame, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_multiplier", String.format(Locale.US, "%.2f", level.episodeRewardMultiplier))
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_CORAL); maxLines = 1; setPadding(0, dp(3), 0, 0)
        })
    }
    addView(iconColumn, LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.WRAP_CONTENT))
    val middle = LinearLayout(this@createCrownLevelRow).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@createCrownLevelRow).apply {
            text = "${level.levelRank}. ${t(crownTitleKey(level.code))}"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY)
        })
        addView(TextView(this@createCrownLevelRow).apply {
            text = t(crownBenefitKey(level.code), formatCompactGems(level.gems))
            textSize = 11f; maxLines = 2; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, dp(5))
        })
        addView(ProgressBar(this@createCrownLevelRow, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = (level.progress * 100).toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(if (level.earned) COLOR_CORAL else COLOR_SUNSET)
            backgroundTintList = ColorStateList.valueOf(0x20FF8A62)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
        addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_level_progress", level.rounds.current, level.rounds.target, level.signins.current, level.signins.target, level.rewardedAds.current, level.rewardedAds.target)
            textSize = 9f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0)
        })
    }
    addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
    val reward = LinearLayout(this@createCrownLevelRow).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@createCrownLevelRow).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(TextView(this@createCrownLevelRow).apply { text = "+${formatCompactGems(level.gems)}"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_CORAL); maxLines = 1 })
        if (level.earned) addView(TextView(this@createCrownLevelRow).apply { text = "✓"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(COLOR_CORAL) })
    }
    addView(reward, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
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
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedDrawable(Color.WHITE, 22)
        val count = snapshot.chest.availableCount
        val top = LinearLayout(this@createRewardChestPanel).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this@createRewardChestPanel).apply { setImageResource(R.drawable.ic_random_chest_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(68), dp(68)))
        top.addView(LinearLayout(this@createRewardChestPanel).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, dp(8), 0)
            addView(TextView(this@createRewardChestPanel).apply { text = t("chest"); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createRewardChestPanel).apply {
                text = if (count > 0) t("chest_ready_detail", count, snapshot.chest.minCoins, snapshot.chest.maxCoins) else t("chest_watch_progress", snapshot.chest.episodesToNextChest.coerceAtLeast(1))
                textSize = 12f; maxLines = 2; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this@createRewardChestPanel).apply {
            text = if (count > 0) t("chest_ready_count", count) else t("chest_empty_short")
            textSize = if (count > 0) 16f else 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(if (count > 0) COLOR_CORAL else COLOR_TEXT_MUTED)
            background = roundedDrawable(if (count > 0) 0xFFFFEEE5.toInt() else COLOR_SURFACE, 14); setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        addView(top)
        addView(TextView(this@createRewardChestPanel).apply { text = t("chest_continue_hint"); textSize = 11f; setTextColor(COLOR_TEXT_MUTED); setPadding(dp(2), dp(10), 0, dp(9)) })
        val actions = LinearLayout(this@createRewardChestPanel).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(TextView(this@createRewardChestPanel).apply {
            text = t("keep_watching"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
            background = roundedDrawable(0xFFFFF1EB.toInt(), 16); isClickable = true; setOnClickListener { activeTab = MainTab.HOME; renderActiveTab() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        actions.addView(TextView(this@createRewardChestPanel).apply {
            text = if (count > 0) t("open_chest") else t("go_watch"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = roundedDrawable(COLOR_CORAL, 16); isClickable = true
            setOnClickListener { if (count > 0) requestRandomChestOpen() else { activeTab = MainTab.HOME; renderActiveTab() } }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(actions)
    }
