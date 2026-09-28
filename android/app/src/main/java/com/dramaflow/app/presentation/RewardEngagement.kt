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

internal fun DramaAppActivity.createFloatingRewardWheel(compact: Boolean = false): View {
        val gemSize = if (compact) dp(62) else dp(68)
        val gem = ImageView(this).apply {
            setImageResource(R.drawable.savings_jar)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0.84f
            contentDescription = t("claim_all")
            isClickable = true
            setOnClickListener {
                if (rewardsSnapshot?.ring?.claimable == true) showEpisodeRewardClaimDialog() else {
                    exoPlayer?.pause(); playerIsPaused = true; activeTab = MainTab.TASKS; showCatalog()
                }
            }
        }
        ObjectAnimator.ofFloat(gem, View.TRANSLATION_Y, 0f, -dp(4).toFloat(), 0f).apply {
            duration = 1_700L; repeatCount = ObjectAnimator.INFINITE; interpolator = AccelerateDecelerateInterpolator(); start()
        }
        val label = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = if (compact) 10f else 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFF3BF.toInt()); setPadding(dp(10), dp(5), dp(10), dp(5)); background = gradientDrawable(intArrayOf(0xDB6B8D30.toInt(), 0xD0244B1F.toInt()), 14)
            alpha = 0.84f
            isClickable = true; setOnClickListener { gem.performClick() }
        }
        floatingWheelLabels.add(label); updateFloatingRewardWheel(label)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false
            addView(FrameLayout(this@createFloatingRewardWheel).apply {
                clipChildren = false; clipToPadding = false
                addView(gem, FrameLayout.LayoutParams(gemSize, gemSize, Gravity.CENTER))
            }, LinearLayout.LayoutParams(gemSize, gemSize))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply { topMargin = dp(2) })
            if (playerShown && rewardsSnapshot?.unlock?.episodeRewardBoostEnabled == true) {
                val boostButton = TextView(this@createFloatingRewardWheel).apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)) }
                floatingBoostButtons.add(boostButton); updateFloatingRewardBoostButton(boostButton)
                addView(boostButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { topMargin = dp(7) })
            }
        }
    }

internal fun DramaAppActivity.refreshFloatingRewardWheels() {
        floatingWheelLabels.removeAll { !it.isAttachedToWindow }
        floatingWheelLabels.forEach { updateFloatingRewardWheel(it) }
        crownSummaryChargeButtons.removeAll { !it.isAttachedToWindow }
        crownSummaryChargeButtons.forEach { updateCrownSummaryChargeButton(it) }
        crownChargeCooldownBars.removeAll { !it.isAttachedToWindow }
        crownChargeCooldownBars.forEach { updateFloatingCrownChargeCooldownBar(it) }
        crownChargeCooldownRings.removeAll { !it.isAttachedToWindow }
        crownChargeCooldownRings.forEach { updateCrownChargeCooldownRing(it) }
        walletChargeCooldownRings.removeAll { !it.isAttachedToWindow }
        walletChargeCooldownRings.forEach { updateWalletChargeCooldownRing(it) }
        floatingBoostButtons.removeAll { !it.isAttachedToWindow }
        floatingBoostButtons.forEach { updateFloatingRewardBoostButton(it) }
        playerWalletBoostButtons.removeAll { !it.isAttachedToWindow }
        playerWalletBoostButtons.forEach { updatePlayerWalletBoostButton(it) }
        walletHeaderBoostButtons.removeAll { !it.isAttachedToWindow }
        walletHeaderBoostButtons.forEach { updateWalletHeaderBoostButton(it) }
        walletChargeQuotaLabels.removeAll { !it.isAttachedToWindow }
        walletChargeQuotaLabels.forEach { updateWalletChargeQuotaLabel(it) }
        walletChargeButtons.removeAll { !it.isAttachedToWindow }
        walletChargeButtons.forEach { updateWalletChargeButton(it) }
    }

internal fun DramaAppActivity.updateFloatingCrownChargeCooldownBar(bar: ProgressBar) {
        val crowns = rewardsSnapshot?.crownProgress.orEmpty()
        val cooldown = crownChargeCooldownRemainingSeconds()
        val duration = crowns.maxOfOrNull { it.chargeCooldownSeconds }?.coerceAtLeast(1) ?: 1
        val elapsedRatio = ((duration - cooldown).coerceIn(0, duration).toFloat() / duration)
        bar.visibility = if (cooldown > 0) View.VISIBLE else View.GONE
        bar.progress = when (bar.tag) {
            "crown-cooldown-first" -> (elapsedRatio.coerceIn(0f, 0.5f) * 200f).toInt()
            "crown-cooldown-second" -> ((elapsedRatio - 0.5f).coerceIn(0f, 0.5f) * 200f).toInt()
            else -> (elapsedRatio * 100f).toInt()
        }.coerceIn(0, 100)
    }

internal fun DramaAppActivity.updateCrownChargeCooldownRing(ring: ChargeCooldownRingView) {
        val crowns = rewardsSnapshot?.crownProgress.orEmpty()
        val cooldown = crownChargeCooldownRemainingSeconds()
        val duration = crowns.maxOfOrNull { it.chargeCooldownSeconds }?.coerceAtLeast(1) ?: 30
        ring.visibility = if (cooldown > 0) View.VISIBLE else View.GONE
        ring.chargeProgress = (duration - cooldown).coerceIn(0, duration).toFloat() / duration
    }

internal fun DramaAppActivity.updateCrownSummaryChargeButton(button: TextView) {
        val next = rewardsSnapshot?.crownProgress?.sortedBy { it.levelRank }?.firstOrNull { !it.earned }
        val cooldown = crownChargeCooldownRemainingSeconds()
        val available = next != null && cooldown <= 0
        button.text = if (cooldown > 0) t("cooling") else t("crown_accelerate")
        button.setTextColor(if (available) Color.WHITE else COLOR_TEXT_MUTED)
        button.background = if (available) gradientDrawable(intArrayOf(COLOR_CORAL, 0xFFC42048.toInt()), 13) else roundedDrawable(COLOR_SURFACE, 13)
        button.alpha = if (available) 1f else 0.65f
        button.isClickable = available
        button.setOnClickListener { next?.takeIf { crownChargeCooldownRemainingSeconds() <= 0 }?.let(::showCrownRewardedUpgradeDialog) }
    }

internal fun DramaAppActivity.crownChargeCooldownRemainingSeconds(): Int =
    ((crownChargeCooldownUntilElapsedMillis - android.os.SystemClock.elapsedRealtime() + 999L) / 1_000L).toInt().coerceAtLeast(0)

internal fun DramaAppActivity.walletChargeCooldownRemainingSeconds(): Int =
    ((walletChargeCooldownUntilElapsedMillis - android.os.SystemClock.elapsedRealtime() + 999L) / 1_000L).toInt().coerceAtLeast(0)

internal fun DramaAppActivity.updateWalletChargeCooldownRing(ring: ChargeCooldownRingView) {
        val release = rewardsSnapshot?.wallet?.release
        val cooldown = walletChargeCooldownRemainingSeconds()
        val duration = release?.chargeCooldownSeconds?.coerceAtLeast(1) ?: 30
        ring.visibility = if (cooldown > 0) View.VISIBLE else View.GONE
        ring.chargeProgress = (duration - cooldown).coerceIn(0, duration).toFloat() / duration
    }

internal fun DramaAppActivity.updatePlayerWalletBoostButton(button: TextView) {
        val wallet = rewardsSnapshot?.wallet
        val cooldown = walletChargeCooldownRemainingSeconds()
        val available = wallet?.release?.boostAvailable == true && cooldown <= 0
        button.visibility = if (wallet == null) View.GONE else View.VISIBLE
        button.text = if (cooldown > 0) t("charge_cooldown", cooldown) else t("extra_reward")
        button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_wallet, 0, 0, 0)
        button.compoundDrawablePadding = dp(4)
        button.setTextColor(if (available) COLOR_CORAL else COLOR_TEXT_MUTED)
        button.background = roundedDrawable(if (available) 0x22FF6D4A else COLOR_SURFACE, 13)
        button.alpha = if (available) 1f else 0.72f
        button.isClickable = available
        button.setOnClickListener { if (available) showWalletReleaseBoostDialog() }
    }

internal fun DramaAppActivity.updateWalletHeaderBoostButton(button: TextView) {
        val wallet = rewardsSnapshot?.wallet
        val cooldown = walletChargeCooldownRemainingSeconds()
        val available = wallet?.release?.boostAvailable == true && cooldown <= 0
        button.visibility = if (wallet == null) View.GONE else View.VISIBLE
        button.text = if (cooldown > 0) t("charge_cooldown", cooldown) else t("extra_reward")
        button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_wallet, 0, 0, 0)
        button.compoundDrawablePadding = dp(4)
        button.setTextColor(if (available) COLOR_CORAL else COLOR_TEXT_MUTED)
        button.background = roundedDrawable(if (available) 0x22FF6D4A else COLOR_SURFACE, 14)
        button.alpha = if (available) 1f else 0.72f
        button.isClickable = available
        button.setOnClickListener { if (available) showWalletReleaseBoostDialog() }
    }

internal fun DramaAppActivity.updateWalletChargeQuotaLabel(label: TextView) {
        val release = rewardsSnapshot?.wallet?.release
        if (release == null) {
            label.text = "—"
            return
        }
        val cap = release.boostCap.coerceAtLeast(0)
        val remaining = if (cap > 0) (cap - release.boostUsed).coerceAtLeast(0) else 0
        label.text = t("wallet_charge", remaining, cap)
    }

internal fun DramaAppActivity.updateWalletChargeButton(button: TextView) {
        val wallet = rewardsSnapshot?.wallet
        val cooldown = walletChargeCooldownRemainingSeconds()
        val available = wallet?.release?.boostAvailable == true && cooldown <= 0
        button.text = if (cooldown > 0) t("charge_cooldown", cooldown) else t("wallet_boost_action")
        button.setTextColor(if (available) Color.WHITE else COLOR_TEXT_MUTED)
        button.background = roundedDrawable(if (available) COLOR_ACCENT else COLOR_SURFACE, 15)
        button.alpha = if (available) 1f else 0.72f
        button.isClickable = available
        button.setOnClickListener { if (available) showWalletReleaseBoostDialog() }
    }

internal fun DramaAppActivity.updateFloatingRewardBoostButton(button: TextView) {
        if (rewardsSnapshot?.unlock?.episodeRewardBoostEnabled != true) {
            button.visibility = View.GONE
            button.isClickable = false
            return
        }
        val episodeId = selectedEpisode()?.id
        val boosted = episodeId != null && isEpisodeRewardBoosted(episodeId)
        if (boosted) {
            button.visibility = View.GONE
            button.isClickable = false
            return
        }
        button.visibility = View.VISIBLE
        button.text = t("floating_boost_short")
        button.setTextColor(Color.WHITE)
        button.background = gradientDrawable(intArrayOf(COLOR_CORAL, COLOR_SUNSET), 14)
        button.isClickable = episodeId != null
        button.alpha = 1f
        button.setOnClickListener { if (episodeId == null) toast(t("episode_not_loaded")) else requestEpisodeRewardBoost(episodeId) }
    }

internal fun DramaAppActivity.updateFloatingRewardWheel(label: TextView) {
        val ring = rewardsSnapshot?.ring
        val wallet = rewardsSnapshot?.wallet
        label.text = when {
            ring == null || wallet == null -> "…"
            ring.claimable -> formatCurrencyAmount(wallet, ring.rewardCash, null, false)
            else -> formatCurrencyAmount(wallet, "0", null, false)
        }
    }

internal fun DramaAppActivity.createRingCard(): View {
        val ring = rewardsSnapshot?.ring
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedDrawable(COLOR_CARD, 18)
            val circle = TextView(this@createRingCard).apply {
                text = if (ring?.claimable == true) rewardsSnapshot?.wallet?.let { formatCurrencyAmount(it, ring.rewardCash, null, false) } ?: "…" else t("watch_to_earn")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT_PRIMARY)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(5), if (ring?.claimable == true) COLOR_ACCENT else COLOR_PURPLE); setColor(COLOR_SURFACE) }
            }
            addView(circle, LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(LinearLayout(this@createRingCard).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@createRingCard).apply { text = t("watch_tasks"); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
                addView(TextView(this@createRingCard).apply {
                    text = ring?.let { if (it.claimable) rewardsSnapshot?.wallet?.let { wallet -> "待领取 ${formatCurrencyAmount(wallet, it.rewardCash, it.rewardCashUsdReference, true)} 现金奖励" } ?: t("wallet_syncing") else "完成剧集后可领取现金奖励" } ?: t("wallet_syncing")
                    textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@createRingCard).apply {
                text = if (ring?.claimable == true) t("claim_all") else t("keep_watching")
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
                background = roundedDrawable(if (ring?.claimable == true) COLOR_ACCENT else COLOR_SURFACE, 14)
                setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true
                setOnClickListener { if (ring?.claimable == true) showEpisodeRewardClaimDialog() else { activeTab = MainTab.DISCOVER; renderActiveTab() } }
            })
        }
    }

internal fun DramaAppActivity.currentEpisodePendingReward(): RewardsApiClient.EpisodeReward? {
        val pending = rewardsSnapshot?.pendingEpisodeRewards.orEmpty()
        if (playerShown) {
            val currentId = selectedEpisode()?.id
            return pending.firstOrNull { it.episodeId == currentId }
        }
        return pending.lastOrNull()
    }

internal fun DramaAppActivity.isEpisodeRewardBoosted(episodeId: String): Boolean =
        episodeId in rewardsSnapshot?.preboostedEpisodeIds.orEmpty() ||
            rewardsSnapshot?.pendingEpisodeRewards?.firstOrNull { it.episodeId == episodeId }?.boosted == true

internal fun DramaAppActivity.showEpisodeRewardClaimDialog() {
        val snapshot = rewardsSnapshot ?: return
        val ring = snapshot.ring
        if (!ring.claimable || ring.rewardCoins <= 0) { toast(t("no_pending_cash_reward")); return }
        showBrandedChoiceDialog(
            title = t("claim_episode_rewards"),
            message = t("claim_episode_rewards_message"),
            primaryLabel = t("watch_ad_claim"),
            onPrimary = { requestRewardedClaimAllEpisodeRewards() },
            secondaryLabel = t("later"),
        )
    }

/** Tapping the boost starts the rewarded request immediately; successful settlement removes the control. */
internal fun DramaAppActivity.requestEpisodeRewardBoost(episodeId: String) {
        requestRewardedAction("rv_episode_reward_boost", targetId = episodeId, onSettled = { action ->
            resumeAfterFullscreenAdIfNeeded()
            toast(t("reward_double_activated"))
        })
    }

internal fun DramaAppActivity.requestRewardedClaimAllEpisodeRewards() {
        requestRewardedAction("rv_episode_rewards_claim", onSettled = { authorization ->
            val adEventId = authorization.adEventId
            if (adEventId.isNullOrBlank()) {
                resumeAfterFullscreenAdIfNeeded()
                toast(t("claim_authorization_missing"))
                return@requestRewardedAction
            }
            rewardsApi.claimAllEpisodeRewards(anonymousInstallationId(), adEventId, { action -> runOnUiThread {
                handleSettledRewardAction(action, {
                    resumeAfterFullscreenAdIfNeeded()
                    if (it.idempotent) toast(t("no_pending_gems"))
                })
            } }, { message -> runOnUiThread {
                resumeAfterFullscreenAdIfNeeded()
                toast(t("claim_failed", message))
            } })
        }, fallbackToInterstitialWhenUnavailable = true)
    }

internal fun DramaAppActivity.createCheckinCard(): View {
        val snapshot = rewardsSnapshot
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedDrawable(COLOR_CARD, 18)
            addView(TextView(this@createCheckinCard).apply { text = t("checkin_rewards"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createCheckinCard).apply {
                text = snapshot?.let {
                    if (it.checkinSignedToday) t("checkin_done_message", it.checkinStreak)
                    else t("today_day_reward", it.checkinTodayIndex, it.checkinNextRewardCoins)
                } ?: t("checkin_syncing")
                textSize = 13f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(6), 0, dp(13))
            })
            addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
            val row = LinearLayout(this@createCheckinCard).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this@createCheckinCard).apply {
                text = if (snapshot?.checkinSignedToday == true) t("checked_in") else t("watch_ad_checkin")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT_PRIMARY)
                background = roundedDrawable(if (snapshot?.checkinSignedToday == true) COLOR_SURFACE else COLOR_ACCENT, 14)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { if (snapshot?.checkinSignedToday != true) completeDailyCheckin() }
                isClickable = snapshot != null && snapshot.checkinSignedToday != true
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(row)
        }
    }

internal fun DramaAppActivity.createWelcomeRewardCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = gradientDrawable(intArrayOf(COLOR_CARD, 0xFF2A2116.toInt()), 20)
        addView(TextView(this@createWelcomeRewardCard).apply { text = t("newcomer_reward"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        addView(TextView(this@createWelcomeRewardCard).apply {
            text = "新人现金奖励 ${formatWelcomeRewardCashValue(snapshot) ?: "—"}"
            textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(6), 0, dp(13))
        })
        addView(TextView(this@createWelcomeRewardCard).apply {
            text = t("watch_ad_claim")
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = roundedDrawable(COLOR_CORAL, 14); setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
            setOnClickListener { claimWelcomeBonus() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    }

internal fun DramaAppActivity.claimWelcomeBonus(onCompleted: (() -> Unit)? = null) {
        requestRewardedAction("rv_welcome_bonus", onSettled = { action ->
            resumeAfterFullscreenAdIfNeeded()
            if (action.idempotent) {
                toast(t("welcome_claimed"))
                onCompleted?.invoke()
            } else {
                showBrandedChoiceDialog(
                    t("welcome_rewards_guide_title"),
                    t("welcome_rewards_guide_message"),
                    t("welcome_rewards_guide_action"),
                    {
                        activeTab = MainTab.TASKS
                        if (playerShown) showCatalog() else if (catalogItems.isNotEmpty()) renderActiveTab()
                        onCompleted?.invoke()
                    },
                    cancelable = false,
                )
            }
        })
    }

internal fun DramaAppActivity.createRewardsWalletSummary(): View {
        val wallet = rewardsSnapshot?.wallet
        if (wallet == null) return createInlineNotice("正在同步奖励账本，连接成功后将展示现金钱包余额。")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(16))
            background = gradientDrawable(intArrayOf(COLOR_CARD, COLOR_SURFACE), 22)
            addView(TextView(this@createRewardsWalletSummary).apply { text = t("wallet"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            val balances = LinearLayout(this@createRewardsWalletSummary).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(t("wallet_balance") to formatWalletTotal(wallet, includeUsdReference = false)).forEachIndexed { index, item ->
                balances.addView(LinearLayout(this@createRewardsWalletSummary).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(10), dp(12)); background = roundedDrawable(COLOR_SURFACE, 14)
                    addView(TextView(this@createRewardsWalletSummary).apply { text = item.first; textSize = 12f; setTextColor(COLOR_TEXT_MUTED) })
                    addView(TextView(this@createRewardsWalletSummary).apply { text = item.second; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(0, dp(4), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = false; setAutoSizeTextTypeUniformWithConfiguration(12, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(9) })
            }
            addView(balances)
            val actions = LinearLayout(this@createRewardsWalletSummary).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
            actions.addView(TextView(this@createRewardsWalletSummary).apply {
                text = t("withdraw"); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
                background = roundedDrawable(COLOR_SURFACE, 14); setOnClickListener { showWithdrawalPage(wallet) }; isClickable = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            addView(actions)
        }
    }

internal fun DramaAppActivity.showWithdrawalDetailsDialog(wallet: RewardsApiClient.Wallet) {
        val release = wallet.release
        val details = "${t("wallet_balance")}：${formatWalletTotal(wallet, includeUsdReference = false)}\n\n${rewardsSnapshot?.withdrawal?.reason.orEmpty()}\n\n${t("wallet_boost_description")}"
        showBrandedChoiceDialog(t("withdrawal_details"), details, t("ad_boost"), { showWalletReleaseBoostDialog() }, t("close"))
}

internal fun DramaAppActivity.showWalletReleaseBoostDialog() {
        val wallet = rewardsSnapshot?.wallet ?: return
        val cooldown = walletChargeCooldownRemainingSeconds()
        if (cooldown > 0) {
            toast(t("charge_cooldown", cooldown))
            return
        }
        if (!wallet.release.boostAvailable) {
            toast(t("wallet_boost_already_settled"))
            return
        }
        showBrandedChoiceDialog(t("ad_boost"), t("wallet_boost_description"), t("wallet_boost_action"), { requestWalletReleaseBoost() }, t("not_now"))
}

internal fun DramaAppActivity.requestWalletReleaseBoost() {
        requestRewardedAction("rv_release_boost", onSettled = { action ->
            resumeAfterFullscreenAdIfNeeded()
            if (action.idempotent) {
                deferredCashFlight = null
                toast(t("wallet_boost_already_settled"))
            }
        })
    }

/** Acknowledge the earned amount first; the wallet flight begins only after this confirmation. */
internal fun DramaAppActivity.showCashRewardReceipt(amount: String, onConfirmed: () -> Unit = {}) {
    val dialog = AlertDialog.Builder(this).create()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(20))
        background = gradientDrawable(intArrayOf(COLOR_CARD, 0xFF2A2116.toInt()), 28)
    }
    card.addView(TextView(this).apply {
        text = t("cash_reward_received_title")
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT_PRIMARY)
    })
    val rewardIcon = ImageView(this).apply {
        setImageResource(R.drawable.wallet_money)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        alpha = 0f
        scaleX = 0.55f
        scaleY = 0.55f
    }
    card.addView(rewardIcon, LinearLayout.LayoutParams(dp(92), dp(92)).apply { topMargin = dp(12) })
    val value = TextView(this).apply {
        text = amount
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(COLOR_SUNSET)
        alpha = 0f
        setPadding(0, dp(3), 0, dp(2))
    }
    card.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    card.addView(TextView(this).apply {
        text = t("cash_reward_confirm_hint")
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT_MUTED)
        setPadding(0, dp(2), 0, dp(18))
    })
    card.addView(TextView(this).apply {
        text = t("done")
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = gradientDrawable(intArrayOf(COLOR_CORAL, COLOR_SUNSET), 18)
        setPadding(0, dp(13), 0, dp(13))
        isClickable = true
        setOnClickListener {
            dialog.dismiss()
            deferredCashFlight?.let { (previous, current) ->
                deferredCashFlight = null
                root.post { animateClaimedCash(previous, current) }
            }
            onConfirmed()
        }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
    dialog.setView(card)
    dialog.setCancelable(false)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        decorView.setBackgroundColor(Color.TRANSPARENT)
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes.apply { dimAmount = 0f }
        setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    rewardIcon.animate().alpha(1f).scaleX(1.14f).scaleY(1.14f).setDuration(260L).withEndAction {
        rewardIcon.animate().scaleX(1f).scaleY(1f).setDuration(170L).start()
    }.start()
    value.animate().alpha(1f).translationY(-dp(4).toFloat()).setStartDelay(130L).setDuration(260L).start()
}

internal fun DramaAppActivity.showGemsExchangeDialog(wallet: RewardsApiClient.Wallet) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = t("minimum_gems", wallet.minimumExchangeGems)
            setTextColor(COLOR_TEXT_PRIMARY); setHintTextColor(COLOR_TEXT_MUTED)
        }
        showBrandedInputDialog(t("exchange_gems"), t("exchange_gems_message"), input, t("done"), { value ->
            val gems = value.toLongOrNull()
            if (gems == null) { toast(t("valid_cash_amount")); return@showBrandedInputDialog }
            rewardsApi.exchangeGems(anonymousInstallationId(), gems, { snapshot -> runOnUiThread { applyRewardsSnapshot(snapshot); toast(t("exchange_recorded")) } }, { message -> runOnUiThread { toast(message) } })
        })
    }

internal fun DramaAppActivity.showWalletRulesDialog(wallet: RewardsApiClient.Wallet) {
        showBrandedChoiceDialog(t("cashout_rules"), t("wallet_rules_message", rewardsSnapshot?.walletEligibilityCopy.orEmpty()), t("done"))
    }

internal fun DramaAppActivity.createChestCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = roundedDrawable(COLOR_CARD, 18)
        addView(TextView(this@createChestCard).apply { text = t("chest_title"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        addView(TextView(this@createChestCard).apply {
            text = when {
                snapshot.chest.availableCount > 0 -> t("chest_available_detail", snapshot.chest.availableCount, formatCurrencyAmount(snapshot.wallet, snapshot.chest.minCash, null, false))
                snapshot.chest.episodesToNextChest > 0 -> t("chest_progress_detail", snapshot.chest.episodesPerChest, snapshot.chest.episodesToNextChest)
                else -> t("chest_empty_detail", snapshot.chest.episodesPerChest)
            }
            textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(6), 0, dp(12))
        })
        addView(TextView(this@createChestCard).apply {
            text = if (snapshot.chest.availableCount > 0) t("chest_open_one") else t("chest_empty_short")
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
            background = roundedDrawable(if (snapshot.chest.availableCount > 0) COLOR_ACCENT else COLOR_SURFACE, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
            setOnClickListener {
                if (snapshot.chest.availableCount > 0) requestRandomChestOpen() else {
                    activeTab = MainTab.HOME
                    renderActiveTab()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    }

internal fun DramaAppActivity.requestRandomChestOpen(onFinished: () -> Unit = {}) {
        requestRewardedAction(
            placementId = "rv_random_chest_open",
            onSettled = { action ->
                resumeAfterFullscreenAdIfNeeded()
                onFinished()
            },
            onSettlementUnavailable = { message ->
                resumeAfterFullscreenAdIfNeeded()
                val noChest = message.contains("没有可开启") || message.contains("没有可用")
                toast(if (noChest) t("chest_settlement_missing") else t("chest_settlement_failed", message))
                onFinished()
            },
        )
    }

internal fun DramaAppActivity.createAchievementSummary(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedDrawable(COLOR_CARD, 18)
        val crowns = snapshot.crownProgress.sortedBy { it.levelRank }
        val current = crowns.filter { it.earned }.maxByOrNull { it.levelRank }
        val next = crowns.firstOrNull { !it.earned }
        if (next == null) {
            addView(TextView(this@createAchievementSummary).apply { text = t("crown_complete_title"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFDB6A.toInt()) })
            addView(TextView(this@createAchievementSummary).apply { text = t("crown_complete_detail"); textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(7), 0, 0) })
        } else {
            addView(TextView(this@createAchievementSummary).apply {
                text = t("crown_summary_title", current?.let { t(crownTitleKey(it.code)) } ?: t("crown_new"))
                textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY)
            })
            addView(TextView(this@createAchievementSummary).apply {
                text = t(
                    "crown_summary_detail",
                    String.format(Locale.US, "%.2f", current?.episodeRewardMultiplier ?: 1f),
                    t(crownTitleKey(next.code)),
                    String.format(Locale.US, "%.2f", next.episodeRewardMultiplier),
                    "2.50",
                )
                textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(6), 0, dp(10))
            })
            addView(ProgressBar(this@createAchievementSummary, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = (next.progress * 100).toInt().coerceIn(0, 100)
                progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
                backgroundTintList = ColorStateList.valueOf(0x33333333)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
            addView(TextView(this@createAchievementSummary).apply {
                text = t("crown_progress_rule", next.episodeProgressPoints, next.rewardedAdProgressPoints)
                textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(10), 0, dp(9))
            })
            val crownAction = TextView(this@createAchievementSummary).apply {
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            crownSummaryChargeButtons.add(crownAction); updateCrownSummaryChargeButton(crownAction)
            val crownActionRow = LinearLayout(this@createAchievementSummary).apply { gravity = Gravity.CENTER_VERTICAL }
            crownActionRow.addView(crownAction, LinearLayout.LayoutParams(0, dp(42), 1f))
            val crownCooldownRings = FrameLayout(this@createAchievementSummary)
            listOf(
                "crown-cooldown-first" to 0xFFFFC83D.toInt(),
                "crown-cooldown-second" to 0xFFFF8A3D.toInt(),
            ).forEachIndexed { index, (tag, color) ->
                val ring = ProgressBar(this@createAchievementSummary, null, android.R.attr.progressBarStyleLarge).apply {
                    this.tag = tag; max = 100; isIndeterminate = false; progressTintList = ColorStateList.valueOf(color); backgroundTintList = ColorStateList.valueOf(0x553C3C46)
                }
                crownChargeCooldownBars.add(ring); updateFloatingCrownChargeCooldownBar(ring)
                val size = if (index == 0) dp(38) else dp(26)
                crownCooldownRings.addView(ring, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }
            crownActionRow.addView(crownCooldownRings, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(10) })
            addView(crownActionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(4) })
        }
    }

internal fun DramaAppActivity.showCrownRewardedUpgradeDialog(crown: RewardsApiClient.CrownProgress) {
        val cooldown = crownChargeCooldownRemainingSeconds()
        if (cooldown > 0) {
            toast(t("charge_cooldown", cooldown))
            return
        }
        val remainingToday = if (crown.rewardedAdsDailyLimit > 0) crown.rewardedAdsDailyLimit - crown.rewardedAdsToday else crown.adsPerEpisode
        if (remainingToday <= 0) {
            toast(t("crown_daily_limit_reached"))
            return
        }
        showBrandedChoiceDialog(t("crown_accelerate"), t("crown_accelerate_message", crown.rewardedAdProgressPoints), t("watch_ad_claim"), { requestCrownRewardedUpgrade(crown.code) }, t("not_now"))
}

internal fun DramaAppActivity.requestCrownRewardedUpgrade(code: String) {
        requestRewardedAction("rv_crown_upgrade", onSettled = { action ->
            resumeAfterFullscreenAdIfNeeded()
            toast(t("crown_progress_increased", t(crownTitleKey(code))))
        })
    }

    /** 当前仅为前端展示演示；活动配置、进度验证与点数账本将在后续运营后台阶段接入。 */
internal fun DramaAppActivity.createActivityRewardDemo(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = gradientDrawable(intArrayOf(COLOR_CARD, COLOR_SURFACE), 18)
        addView(TextView(this@createActivityRewardDemo).apply {
            text = "新剧追更挑战"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        addView(TextView(this@createActivityRewardDemo).apply {
            text = "活动期间完成指定短剧 5 集有效观看，可获得 120 活动点数"
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(6), 0, dp(12))
        })
        val row = LinearLayout(this@createActivityRewardDemo).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this@createActivityRewardDemo).apply {
            text = "进度 0/5 · 即将开放"
            textSize = 13f
            setTextColor(COLOR_TEXT_PRIMARY)
            background = roundedDrawable(COLOR_SURFACE, 12)
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(createPill("+120 点数", COLOR_PURPLE))
        addView(row)
    }
