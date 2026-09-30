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
    val scroll = scrollPage { content ->
        content.setPadding(0, 0, 0, dp(86))
        val snapshot = rewardsSnapshot
        content.addView(createRewardsHeroHeader(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(244)))
        if (snapshot == null) content.addView(createInlineNotice(t("ledger_note")), matchParams(top = 10, bottom = 10))
        else {
            content.addView(createDailyBenefitsPanel(snapshot), matchParams(top = 10, bottom = 10))
            content.addView(createEpisodeTaskLine(snapshot), matchParams(bottom = 10))
        }
        FrameLayout(this@createTasksPage).also { host ->
            rewardCrownRoadHost = host
            host.addView(createCrownRoadPanel(snapshot), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(host, matchParams(bottom = 10))
        }
    }
    scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
        rewardsTasksScrollY = scrollY
        updateRewardsStickyAssetHeader(scrollY >= dp(148))
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
        createCrownRoadPanel(rewardsSnapshot),
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    return true
}

/** A compact, inset-aware hero: headline sits below the status bar and the image has no empty lower band. */
internal fun DramaAppActivity.createRewardsHeroHeader(snapshot: RewardsApiClient.Snapshot?): View = FrameLayout(this).apply {
    minimumHeight = dp(226)
    clipToOutline = true
    background = gradientDrawable(intArrayOf(COLOR_BACKGROUND, COLOR_CARD, COLOR_BACKGROUND), 0)
    val foreground = LinearLayout(this@createRewardsHeroHeader).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(48), dp(20), dp(4))
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("reward_center")
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            includeFontPadding = false
        })
        addView(TextView(this@createRewardsHeroHeader).apply {
            text = t("rewards_subtitle")
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(5), 0, dp(9))
        })
        val wallet = snapshot?.wallet
        val balanceBar = FrameLayout(this@createRewardsHeroHeader).apply {
            background = gradientDrawable(intArrayOf(0xFF292027.toInt(), COLOR_SURFACE), 22)
            clipToOutline = true
            addView(ImageView(this@createRewardsHeroHeader).apply {
                setImageResource(R.drawable.ic_cash_reward)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = 0.12f
                rotation = -12f
            }, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -dp(4) })
            addView(
                createRewardBalance(
                    t("wallet_balance"),
                    wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—",
                    isGem = false,
                ) { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)),
            )
        }
        addView(balanceBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
    }
    addView(foreground, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
        foreground.setPadding(dp(20), maxOf(dp(48), safeTop + dp(20)), dp(20), dp(4))
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

internal fun DramaAppActivity.createRewardBalance(label: String, value: String, isGem: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(16), dp(8), dp(16), dp(8))
    isClickable = true
    contentDescription = "$label $value"
    setOnClickListener { onClick() }
    val icon = ImageView(this@createRewardBalance).apply {
        setImageResource(if (isGem) R.drawable.ic_cash_reward else R.drawable.wallet_money)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    if (isGem) gemBalanceAnchor = icon else cashBalanceAnchor = icon
    addView(icon, LinearLayout.LayoutParams(dp(62), dp(62)))
    addView(LinearLayout(this@createRewardBalance).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(28), 0)
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
        if (isGem) gemBalanceLabel = valueView else cashBalanceLabel = valueView
        addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
}

/** Crown Road uses one readable row per level so all benefits fit without horizontal compression. */

/** Scrollable watch-episode task line: all generated nodes remain reachable while the current node is focused by default. */
internal fun DramaAppActivity.createEpisodeTaskLine(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(15))
    background = roundedDrawable(COLOR_CARD, 22)
    addView(TextView(this@createEpisodeTaskLine).apply {
        text = t("watch_task_title")
        textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY)
    })
    addView(TextView(this@createEpisodeTaskLine).apply {
        text = t("watch_task_subtitle")
        textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, dp(13))
    })
    val tasks = snapshot.tasks
    val current = tasks.firstOrNull { it.status != "CLAIMED" } ?: tasks.lastOrNull()
    val currentIndex = tasks.indexOf(current).coerceAtLeast(0)
    val track = LinearLayout(this@createEpisodeTaskLine).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(12), 0) }
    tasks.forEachIndexed { index, task ->
        val reached = task.status == "CLAIMED" || task.status == "CLAIMABLE"
        track.addView(TextView(this@createEpisodeTaskLine).apply {
            text = task.target.toString(); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (reached) Color.WHITE else COLOR_TEXT_MUTED)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (reached) COLOR_CORAL else 0xFF34343C.toInt()) }
        }, LinearLayout.LayoutParams(dp(31), dp(31)))
        if (index < tasks.lastIndex) track.addView(View(this@createEpisodeTaskLine).apply { setBackgroundColor(if (reached) COLOR_CORAL else 0xFF303037.toInt()) }, LinearLayout.LayoutParams(dp(22), dp(3)))
    }
    val scroller = HorizontalScrollView(this@createEpisodeTaskLine).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(track, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
    }
    addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
    scroller.post { scroller.scrollTo((currentIndex * dp(53) - dp(86)).coerceAtLeast(0), 0) }
    if (current != null) {
        val actionRow = LinearLayout(this@createEpisodeTaskLine).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(13), 0, 0) }
        actionRow.addView(LinearLayout(this@createEpisodeTaskLine).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@createEpisodeTaskLine).apply { text = t("watch_task_progress", current.progress, current.target); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createEpisodeTaskLine).apply { text = t("watch_task_random_redpacket", current.target); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(TextView(this@createEpisodeTaskLine).apply {
            text = when (current.status) { "CLAIMABLE" -> t("task_claim"); "CLAIMED" -> t("task_claimed"); else -> t("task_go_watch") }
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (current.status == "LOCKED") COLOR_TEXT_PRIMARY else Color.WHITE)
            background = roundedDrawable(if (current.status == "LOCKED") COLOR_SURFACE else COLOR_CORAL, 16)
            setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = current.status != "CLAIMED"
            setOnClickListener {
                when (current.status) {
                    "CLAIMABLE" -> claimEpisodeTaskCash(current.target)
                    "LOCKED" -> { activeTab = MainTab.HOME; renderActiveTab() }
                }
            }
        })
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
    background = roundedDrawable(COLOR_CARD, 24)
    addView(createRewardPanelTitle(t("crown_road"), t("crown_road_subtitle"), t("crown_accelerate"), R.drawable.ic_crown) {
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
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(10), dp(10), dp(10), dp(10))
    background = roundedDrawable(when {
        isCurrent -> 0xFF2B2112.toInt()
        level.earned -> 0xFF1C1716.toInt()
        else -> COLOR_SURFACE
    }, 18)
    val iconColumn = LinearLayout(this@createCrownLevelRow).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val iconFrame = FrameLayout(this@createCrownLevelRow).apply {
            background = roundedDrawable(COLOR_CARD, 20)
            addView(ImageView(this@createCrownLevelRow).apply { setImageResource(crownIconRes(level.code)); scaleType = ImageView.ScaleType.CENTER_INSIDE }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        }
        addView(iconFrame, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@createCrownLevelRow).apply {
            text = if (isCurrent) t("crown_current") else if (level.earned) t("crown_reached") else t("crown_locked")
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(if (isCurrent) COLOR_CORAL else COLOR_TEXT_MUTED); maxLines = 1; setPadding(0, dp(3), 0, 0)
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
            text = t("crown_episode_cash", level.episodeRewardCash)
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); setPadding(0, dp(3), 0, dp(2))
        })
        addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_completion_cash", level.cash)
            textSize = 11f; maxLines = 2; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, dp(5))
        })
        addView(ProgressBar(this@createCrownLevelRow, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = (level.progress * 100).toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(if (level.earned) COLOR_CORAL else COLOR_SUNSET)
            backgroundTintList = ColorStateList.valueOf(0x553F342B)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
        addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_effective_progress", level.rounds.current, level.rounds.target)
            textSize = 9f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0)
        })
        if (isCurrent) addView(TextView(this@createCrownLevelRow).apply {
            text = t("crown_progress_rule", level.episodeProgressPoints, level.rewardedAdProgressPoints)
            textSize = 9f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(COLOR_CORAL); setPadding(0, dp(2), 0, 0)
        })
    }
    addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
    val reward = LinearLayout(this@createCrownLevelRow).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@createCrownLevelRow).apply { setImageResource(R.drawable.ic_cash_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(TextView(this@createCrownLevelRow).apply { text = level.cash; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_CORAL); maxLines = 1 })
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
        val actionSlot = FrameLayout(this@createRewardPanelTitle).apply { clipChildren = false; clipToPadding = false }
        val actionView = TextView(this@createRewardPanelTitle).apply {
            text = "$action ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = roundedDrawable(COLOR_CORAL, 20); setPadding(dp(13), dp(9), dp(13), dp(9)); setOnClickListener { onClick() }; isClickable = true
        }
        val cooldownRing = ChargeCooldownRingView(this@createRewardPanelTitle).apply { visibility = View.GONE }
        crownSummaryChargeButtons.add(actionView); updateCrownSummaryChargeButton(actionView)
        crownChargeCooldownRings.add(cooldownRing); updateCrownChargeCooldownRing(cooldownRing)
        actionSlot.addView(actionView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.CENTER))
        actionSlot.addView(cooldownRing, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        addView(actionSlot, LinearLayout.LayoutParams(dp(122), dp(48)))
    }

internal fun DramaAppActivity.createPendingRewardAction(pending: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = roundedDrawable(COLOR_SURFACE, 18)
        addView(ImageView(this@createPendingRewardAction).apply { setImageResource(R.drawable.ic_cash_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(this@createPendingRewardAction).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
            addView(TextView(this@createPendingRewardAction).apply { text = t("pending_rewards"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createPendingRewardAction).apply { text = t("claim_after_ad"); textSize = 11f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createPendingRewardAction).apply { text = t("claim_all"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); isClickable = pending > 0; setOnClickListener { showEpisodeRewardClaimDialog() } })
    }

internal fun DramaAppActivity.createCoinMilestone(value: String, label: String, reward: Int = 0): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(7), dp(7), dp(7)); background = gradientDrawable(intArrayOf(0xFF2A2116.toInt(), COLOR_SURFACE), 16)
        addView(ImageView(this@createCoinMilestone).apply { setImageResource(R.drawable.ic_cash_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@createCoinMilestone).apply { text = value; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
        addView(TextView(this@createCoinMilestone).apply { text = if (reward > 0) "$label · +$reward" else label; textSize = 10f; maxLines = 2; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
    }

internal fun DramaAppActivity.createChallengeItem(title: String, coins: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(9), dp(6), dp(7)); background = roundedDrawable(COLOR_SURFACE, 16)
        addView(TextView(this@createChallengeItem).apply { text = title; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2 })
        val row = LinearLayout(this@createChallengeItem).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this@createChallengeItem).apply { setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(TextView(this@createChallengeItem).apply { text = coins; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); setPadding(dp(3), 0, 0, 0) })
        addView(row)
    }

internal fun DramaAppActivity.createDailyBenefitsPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(17), dp(18), dp(16))
    background = roundedDrawable(COLOR_CARD, 24)
    val header = LinearLayout(this@createDailyBenefitsPanel).apply { gravity = Gravity.CENTER_VERTICAL }
    header.addView(ImageView(this@createDailyBenefitsPanel).apply {
        setImageResource(R.drawable.ic_calendar_reward)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }, LinearLayout.LayoutParams(dp(40), dp(40)))
    header.addView(TextView(this@createDailyBenefitsPanel).apply {
        text = t("checkin_seven_day_earn")
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_PRIMARY)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(0, dp(42), 1f))
    header.addView(TextView(this@createDailyBenefitsPanel).apply {
        text = snapshot?.let(::formatSevenDayCheckinTotal) ?: "—"
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_SUNSET)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(18, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }, LinearLayout.LayoutParams(dp(128), dp(42)))
    addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(13) })
    addView(TextView(this@createDailyBenefitsPanel).apply {
        text = if (snapshot?.checkinSignedToday == true) t("checkin_done_message", snapshot.checkinStreak) else t("daily_benefits_subtitle")
        textSize = 12f
        maxLines = 2
        setTextColor(COLOR_TEXT_MUTED)
        setPadding(dp(2), dp(10), dp(2), dp(10))
    })
    addView(TextView(this@createDailyBenefitsPanel).apply {
        text = if (snapshot?.checkinSignedToday == true) t("calendar_reminder_action") else t("watch_ad_checkin")
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (snapshot?.checkinSignedToday == true) COLOR_TEXT_PRIMARY else Color.WHITE)
        background = roundedDrawable(if (snapshot?.checkinSignedToday == true) COLOR_SURFACE else COLOR_CORAL, 16)
        isClickable = snapshot != null
        setOnClickListener {
            if (snapshot?.checkinSignedToday == true) showCheckinCalendarReminderPrompt() else completeDailyCheckin()
        }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
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
