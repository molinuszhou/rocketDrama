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
import android.os.SystemClock
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
import com.dramaflow.app.analytics.AttributionExperienceStore
import com.dramaflow.app.analytics.MonetizationExperience
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.requestRewardedAction(
        placementId: String,
        targetId: String? = null,
        episodeIndex: Int? = null,
        onSettled: ((RewardsApiClient.RewardAction) -> Unit)? = null,
        onSettlementUnavailable: ((String) -> Unit)? = null,
        fallbackToInterstitialWhenUnavailable: Boolean = true,
        deferCashAnimation: Boolean = true,
    ) {
        if (fullscreenAdInProgress || fullscreenAdRequestInFlight) { toast(t("ad_loading")); return }
        when (consentStore.read()) {
            AdConsent.DECLINED -> { toast(t("enable_ads_to_continue")); return }
            AdConsent.UNKNOWN -> consentStore.save(AdConsent.GRANTED)
            AdConsent.GRANTED -> Unit
        }
        if (!rewardsApi.isConfigured) { toast(t("rewards_service_not_configured")); return }
        // Release A (AppsFlyer Organic) never asks a user to watch a rewarded creative for
        // cash-out-connected actions. Episode unlock remains the explicit rewarded exception.
        if (usesOrganicInterstitialIncentives() && placementId != "rv_episode_pass") {
            requestOrganicInterstitialIncentive(
                placementId = placementId,
                targetId = targetId,
                episodeIndex = episodeIndex,
                onSettled = onSettled,
                onSettlementUnavailable = onSettlementUnavailable,
                deferCashAnimation = deferCashAnimation,
            )
            return
        }
        fullscreenAdRequestInFlight = true
        var rewardedWasPresented = false
        val wasPlayingBeforeRewarded = wasPlaybackActiveForAd()
        // Full-screen ad loading is modal: pause the episode and prevent duplicate interaction
        // while mediation resolves its waterfall, then resume if no creative becomes available.
        resumeAfterFullscreenAd = wasPlayingBeforeRewarded
        pauseForFullscreenAd()
        resumeAfterFullscreenAd = wasPlayingBeforeRewarded
        showFullscreenAdLoadingOverlay()
        topOnAds.showRewarded(
            activity = this,
            onWillShow = {
                rewardedWasPresented = true
                fullscreenAdRequestInFlight = false
                fullscreenAdInProgress = true
                runOnUiThread { hideFullscreenAdLoadingOverlay() }
            },
        ) { result -> runOnUiThread {
            fullscreenAdRequestInFlight = false
            hideFullscreenAdLoadingOverlay()
            when (result) {
                TopOnAdsManager.RewardedResult.RewardEarned -> {
                    fullscreenAdInProgress = false
                    if (rewardedWasPresented && wasPlayingBeforeRewarded) { resumeAfterFullscreenAd = true; resumeAfterFullscreenAdIfNeeded() }
                    rewardsApi.completeRewarded(
                        userId = anonymousInstallationId(), placementId = placementId, targetId = targetId,
                        episodeIndex = episodeIndex, proof = "",
                        onSuccess = { action -> runOnUiThread {
                            fullscreenAdInProgress = false
                            playbackInterstitialShownAtElapsedMillis.clear()
                            playbackInterstitialShownAtElapsedMillis.add(SystemClock.elapsedRealtime())
                            handleSettledRewardAction(action, onSettled, deferCashAnimation)
                        } },
                        onFailure = { message -> runOnUiThread {
                            if (deferCashAnimation) deferNextCashFlight = false
                            fullscreenAdInProgress = false
                            if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(message) else {
                                episodeTransitionInProgress = false; resumeAfterFullscreenAdIfNeeded()
                                toast(t("reward_settlement_pending")); logStatus("广告奖励同步失败：$message")
                            }
                        } },
                    )
                }
                TopOnAdsManager.RewardedResult.Cancelled -> {
                    if (deferCashAnimation) deferNextCashFlight = false
                    fullscreenAdInProgress = false
                    recordRewardedSkipThenMaybeShowInterstitial(wasPlayingBeforeRewarded)
                }
                is TopOnAdsManager.RewardedResult.Unavailable -> {
                    if (deferCashAnimation) deferNextCashFlight = false
                    fullscreenAdInProgress = false
                    if (rewardedWasPresented) resumeAfterFullscreenAdIfNeeded()
                    val message = t("ad_loading_failed") + " ${result.reason}"
                    if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message, placementId, targetId, episodeIndex, onSettled, deferCashAnimation)
                    else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                }
                is TopOnAdsManager.RewardedResult.Failed -> {
                    if (deferCashAnimation) deferNextCashFlight = false
                    fullscreenAdInProgress = false
                    if (rewardedWasPresented) resumeAfterFullscreenAdIfNeeded()
                    val message = t("ad_loading_failed") + " (${result.code})"
                    if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message, placementId, targetId, episodeIndex, onSettled, deferCashAnimation)
                    else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                }
            }
        } }
    }

/** Organic A surface: a shown incentive interstitial, not a rewarded creative, settles the action once. */
internal fun DramaAppActivity.requestOrganicInterstitialIncentive(
    placementId: String,
    targetId: String?,
    episodeIndex: Int?,
    onSettled: ((RewardsApiClient.RewardAction) -> Unit)?,
    onSettlementUnavailable: ((String) -> Unit)?,
    deferCashAnimation: Boolean,
) {
    if (fullscreenAdInProgress || fullscreenAdRequestInFlight) { toast(t("ad_loading")); return }
    val shouldResume = exoPlayer?.isPlaying == true
    showPlaybackActionInterstitial(
        placementId = "int_incentive_action",
        episodeId = selectedEpisode()?.id,
        countTowardEpisodeLimit = false,
        bypassSharedFrequencyCaps = true,
        resumePlaybackAfter = shouldResume,
        onShown = {},
        onUnavailable = { reason ->
            if (deferCashAnimation) deferNextCashFlight = false
            if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(reason)
            else showRewardedUnavailableNotice(shouldResume, reason)
        },
        onRecorded = { interstitialEventId ->
            rewardsApi.settleInterstitialIncentive(
                userId = anonymousInstallationId(),
                interstitialEventId = interstitialEventId,
                originalPlacementId = placementId,
                targetId = targetId,
                episodeIndex = episodeIndex,
                onSuccess = { action -> runOnUiThread {
                    handleSettledRewardAction(action, onSettled, deferCashAnimation)
                } },
                onFailure = { reason -> runOnUiThread {
                    if (deferCashAnimation) deferNextCashFlight = false
                    logStatus("A 面插屏奖励结算失败：$reason")
                    if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(reason)
                    else showRewardedUnavailableNotice(shouldResume, reason)
                } },
            )
        },
    )
}

internal fun DramaAppActivity.usesOrganicInterstitialIncentives(): Boolean =
    BuildConfig.RELEASE_AB_ROUTING_ENABLED &&
        AttributionExperienceStore.get(this).current() == MonetizationExperience.ORGANIC_A

/** Applies every server-confirmed cash grant with the same receipt then wallet-flight sequence. */
internal fun DramaAppActivity.handleSettledRewardAction(
    action: RewardsApiClient.RewardAction,
    onSettled: ((RewardsApiClient.RewardAction) -> Unit)?,
    deferCashAnimation: Boolean = true,
) {
    val added = action.cashAdded.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
    val shouldCelebrate = !action.idempotent && added.signum() > 0
    if (shouldCelebrate && deferCashAnimation) deferNextCashFlight = true
    applyRewardsSnapshot(action.snapshot)
    val complete = {
        if (onSettled != null) onSettled.invoke(action)
        else {
            resumeAfterFullscreenAdIfNeeded()
            toast(t("cash_reward_added"))
        }
    }
    if (shouldCelebrate) {
        val amount = formatCurrencyAmount(
            action.snapshot.wallet,
            action.cashAdded,
            action.cashAddedUsdReference,
            includeUsdReference = false,
        )
        showCashRewardReceipt(amount, complete)
    } else {
        complete()
    }
}

/** A user-cancelled rewarded placement may fall back after the configured skip threshold. */
internal fun DramaAppActivity.recordRewardedSkipThenMaybeShowInterstitial(shouldResume: Boolean, context: String = "rewarded_cancelled") {
        val notice = t("rewarded_not_completed")
        if (!rewardsApi.isConfigured) { showRewardedUnavailableNotice(shouldResume, notice); return }
        rewardsApi.recordRewardedSkipped(anonymousInstallationId(), context, { snapshot -> runOnUiThread {
            applyRewardsSnapshot(snapshot)
            val policy = snapshot.interstitial
            if (policy.rewardedFallbackEnabled && (policy.rewardedSkipThreshold <= 0 || policy.rewardedSkipCount >= policy.rewardedSkipThreshold)) {
                showRewardedFallbackInterstitial(shouldResume, notice)
            } else {
                resumeAfterFullscreenAd = shouldResume; resumeAfterFullscreenAdIfNeeded(); toast(t("rewarded_not_completed"))
            }
        } }, { _ -> runOnUiThread { showRewardedUnavailableNotice(shouldResume, notice) } })
}

    /** Handles unavailable rewarded inventory when no fallback interstitial can be presented. */
internal fun DramaAppActivity.showRewardedUnavailableNotice(shouldResume: Boolean, message: String) {
        fullscreenAdInProgress = false
        fullscreenAdRequestInFlight = false
        episodeTransitionInProgress = false
        resumeAfterFullscreenAd = shouldResume
        resumeAfterFullscreenAdIfNeeded()
        showBrandedChoiceDialog(t("ad_unavailable_title"), "$message\n\n${t("ad_unavailable_message")}", t("done"))
    }

    /** A failed or skipped rewarded placement may attempt one verified fallback interstitial. */
internal fun DramaAppActivity.showRewardedFallbackInterstitial(
        shouldResume: Boolean,
        message: String,
        originalPlacementId: String? = null,
        targetId: String? = null,
        episodeIndex: Int? = null,
        onOriginalSettled: ((RewardsApiClient.RewardAction) -> Unit)? = null,
        deferCashAnimation: Boolean = true,
    ) {
        fullscreenAdInProgress = false
        fullscreenAdRequestInFlight = false
        episodeTransitionInProgress = false
        if (rewardsSnapshot?.interstitial?.rewardedFallbackEnabled != true) {
            resumeAfterFullscreenAd = shouldResume; resumeAfterFullscreenAdIfNeeded(); toast(message); return
        }
        // 失败回退是一条独立的例外插屏。只有该插屏实际展示并记录成功后，
        // 服务端才会按一次性账本结算原激励权益；加载失败绝不授予权益。
        resumeAfterFullscreenAd = shouldResume
        showPlaybackActionInterstitial(
            placementId = "int_rewarded_fallback", episodeId = selectedEpisode()?.id,
            countTowardEpisodeLimit = false,
            bypassSharedFrequencyCaps = true,
            resumePlaybackAfter = shouldResume,
            onShown = {},
            onUnavailable = { reason -> logStatus("激励回退插屏未展示：$reason"); toast(message) },
            onRecorded = { interstitialEventId ->
                if (originalPlacementId == null) {
                    toast(message)
                    return@showPlaybackActionInterstitial
                }
                rewardsApi.settleRewardedFallback(
                    userId = anonymousInstallationId(),
                    interstitialEventId = interstitialEventId,
                    originalPlacementId = originalPlacementId,
                    targetId = targetId,
                    episodeIndex = episodeIndex,
                    onSuccess = { action -> runOnUiThread {
                        handleSettledRewardAction(action, onOriginalSettled, deferCashAnimation)
                    } },
                    onFailure = { reason -> runOnUiThread {
                        logStatus("回退插屏奖励结算失败：$reason")
                        toast(message)
                    } },
                )
            },
        )
    }

/** Returns the real local or provider playback state without forcing an ad-request pause. */
internal fun DramaAppActivity.wasPlaybackActiveForAd(): Boolean =
    exoPlayer?.isPlaying == true || (sdkPlayerShown && sdkPlaybackActive && !playerIsPaused)

internal fun DramaAppActivity.showFullscreenAdLoadingOverlay() {
        if (isFinishing || isDestroyed) return
        val overlay = fullscreenAdLoadingOverlay ?: FrameLayout(this).apply {
            setBackgroundColor(0x72000000)
            isClickable = true
            isFocusable = true
            contentDescription = t("ad_loading")
            addView(LinearLayout(this@showFullscreenAdLoadingOverlay).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(20), dp(22), dp(18))
                background = roundedDrawable(0xCC000000.toInt(), 18)
                addView(ProgressBar(this@showFullscreenAdLoadingOverlay).apply {
                    indeterminateTintList = ColorStateList.valueOf(0xF2FFFFFF.toInt())
                    alpha = 0.92f
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(TextView(this@showFullscreenAdLoadingOverlay).apply {
                    text = t("ad_loading")
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(10), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(dp(158), dp(132), Gravity.CENTER))
        }.also { fullscreenAdLoadingOverlay = it }
        if (overlay.parent == null) {
            root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
    }

internal fun DramaAppActivity.hideFullscreenAdLoadingOverlay() {
        fullscreenAdLoadingOverlay?.visibility = View.GONE
    }

internal fun DramaAppActivity.pauseForFullscreenAd() {
        resumeAfterFullscreenAd = exoPlayer?.isPlaying == true
        // 第三方广告关闭按钮由广告网络渲染，不能由宿主移动或覆盖。展示前退出沉浸模式，
        // 并让广告 SDK 以有系统栏的安全窗口展示，避免弧形屏顶部边缘难以点击关闭按钮。
        restoreImmersiveAfterFullscreenAd = (playerShown || sdkPlayerShown) && playerInImmersiveMode
        if (playerInImmersiveMode) exitImmersivePlayerMode()
        navigationBackCallback?.isEnabled = false
        exoPlayer?.pause()
        if (sdkPlayerShown && sdkPlaybackActive) {
            resumeSdkPlaybackWhenForeground = true
            currentSdkPlayerFragment()?.pausePlay()
        }
        playerIsPaused = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

internal fun DramaAppActivity.resumeAfterFullscreenAdIfNeeded() {
        navigationBackCallback?.isEnabled = true
        if (resumeAfterFullscreenAd && !isFinishing && !isDestroyed) {
            ignoreVideoTapsUntilMillis = System.currentTimeMillis() + 750L
            exoPlayer?.play()
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (resumeSdkPlaybackWhenForeground && sdkPlayerShown && !isFinishing && !isDestroyed) {
            currentSdkPlayerFragment()?.startPlay()
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resumeSdkPlaybackWhenForeground = false
        }
        if (restoreImmersiveAfterFullscreenAd && !isFinishing && !isDestroyed && (playerShown || sdkPlayerShown)) {
            enterImmersivePlayerMode(announce = false)
        }
        restoreImmersiveAfterFullscreenAd = false
        resumeAfterFullscreenAd = false
        // The host Activity survived the ad hand-off, so a later cold launch must not reopen this
        // now-stale checkpoint instead of the user's normal catalog entry.
        clearExternalAdReturnCheckpoint()
        if (!isFinishing && !isDestroyed) {
            if (playerShown || sdkPlayerShown) configurePlayerWindow() else configureCatalogWindow()
            root.post {
                if (!isFinishing && !isDestroyed) {
                    if (playerShown || sdkPlayerShown) configurePlayerWindow() else configureCatalogWindow()
                    playerTopChrome?.let { chrome ->
                        chrome.requestLayout()
                        ViewCompat.requestApplyInsets(chrome)
                    }
                }
            }
        }
    }

    /** All playback interstitial placements share one server-backed hourly cap. */
internal fun DramaAppActivity.showPlaybackActionInterstitial(
        placementId: String,
        episodeId: String? = selectedEpisode()?.id,
        countTowardEpisodeLimit: Boolean = true,
        bypassSharedFrequencyCaps: Boolean = false,
        resumePlaybackAfter: Boolean? = null,
        onShown: () -> Unit,
        onUnavailable: (String) -> Unit,
        onRecorded: ((String) -> Unit)? = null,
    ) {
        val policy = rewardsSnapshot?.interstitial
        val episodeKey = episodeId?.let { "${selectedDrama?.id}:$it" }
        val now = SystemClock.elapsedRealtime()
        playbackInterstitialShownAtElapsedMillis.removeAll { now - it >= 60 * 60 * 1_000L }
        if (!bypassSharedFrequencyCaps && policy != null && policy.cooldownSeconds > 0 && playbackInterstitialShownAtElapsedMillis.lastOrNull()?.let { now - it < policy.cooldownSeconds * 1_000L } == true) {
            onUnavailable("插屏冷却期未结束"); return
        }
        if (!bypassSharedFrequencyCaps && policy != null && policy.globalHourlyLimit > 0 && playbackInterstitialShownAtElapsedMillis.size >= policy.globalHourlyLimit) {
            onUnavailable("本小时插屏展示已达总上限"); return
        }
        if (!bypassSharedFrequencyCaps && countTowardEpisodeLimit && policy != null && policy.episodeInterstitialLimit > 0 && episodeKey != null && (interstitialCountByEpisodeKey[episodeKey] ?: 0) >= policy.episodeInterstitialLimit) {
            onUnavailable("本集插屏次数已达上限"); return
        }
        if (fullscreenAdInProgress || fullscreenAdRequestInFlight) { onUnavailable("广告正在展示"); return }
        if (consentStore.read() != AdConsent.GRANTED) { onUnavailable("请先在广告与隐私中启用广告"); return }
        val shouldResume = resumePlaybackAfter ?: wasPlaybackActiveForAd()
        fullscreenAdRequestInFlight = true
        resumeAfterFullscreenAd = shouldResume
        pauseForFullscreenAd()
        resumeAfterFullscreenAd = shouldResume
        showFullscreenAdLoadingOverlay()
        topOnAds.showInterstitialForPlaybackAction(
            activity = this,
            bypassGlobalFrequencyCap = bypassSharedFrequencyCaps,
            onWillShow = {
                fullscreenAdRequestInFlight = false
                fullscreenAdInProgress = true
                resumeAfterFullscreenAd = shouldResume
                runOnUiThread { hideFullscreenAdLoadingOverlay() }
            },
        ) { result -> runOnUiThread {
            fullscreenAdRequestInFlight = false
            fullscreenAdInProgress = false
            hideFullscreenAdLoadingOverlay()
            when (result) {
                TopOnAdsManager.FullscreenResult.Shown -> {
                    resumeAfterFullscreenAdIfNeeded()
                    val interstitialEventId = "int-${UUID.randomUUID()}"
                    val settled = {
                        playbackInterstitialShownAtElapsedMillis.add(SystemClock.elapsedRealtime())
                        if (countTowardEpisodeLimit && episodeKey != null) interstitialCountByEpisodeKey[episodeKey] = (interstitialCountByEpisodeKey[episodeKey] ?: 0) + 1
                        onShown()
                    }
                    if (rewardsApi.isConfigured) {
                        rewardsApi.recordInterstitial(anonymousInstallationId(), placementId, episodeId,
                            onSuccess = { snapshot -> runOnUiThread { applyRewardsSnapshot(snapshot); settled(); onRecorded?.invoke(interstitialEventId) } },
                            // A fallback creative has already been shown. Continue with the same
                            // event ID so the server can persist a recovery audit record while
                            // granting the original reward rather than silently losing it.
                            onFailure = { message -> runOnUiThread { logStatus("插屏展示记录未同步：$message"); settled(); onRecorded?.invoke(interstitialEventId) } },
                            eventId = interstitialEventId,
                        )
                    } else settled()
                }
                is TopOnAdsManager.FullscreenResult.Skipped -> {
                    resumeAfterFullscreenAdIfNeeded()
                    onUnavailable(result.reason)
                }
                is TopOnAdsManager.FullscreenResult.Failed -> {
                    resumeAfterFullscreenAdIfNeeded()
                    onUnavailable("加载失败（${result.code}）")
                }
            }
        } }
    }

internal fun DramaAppActivity.showCashIncomeDetails() {
        val snapshot = rewardsSnapshot
        if (snapshot == null) { toast(t("wallet_syncing")); return }
        val wallet = snapshot.wallet
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(12)); background = roundedDrawable(COLOR_CARD, 24) }
        detail.addView(TextView(this@showCashIncomeDetails).apply { text = t("cash_income_details"); textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        detail.addView(TextView(this@showCashIncomeDetails).apply {
            text = t("cash_income_summary", formatWalletTotal(wallet, includeUsdReference = false), formatCurrencyAmount(wallet, wallet.availableBalance, wallet.availableBalanceUsdReference, includeUsdReference = false))
            textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(5), 0, dp(12))
        })
        val entries = snapshot.ledger.filter {
            (it.cashWalletDelta.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) > java.math.BigDecimal.ZERO ||
                (it.availableBalanceDelta.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) > java.math.BigDecimal.ZERO
        }
        if (entries.isEmpty()) detail.addView(TextView(this@showCashIncomeDetails).apply { text = t("ledger_empty"); textSize = 14f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), dp(0), dp(14)) })
        else entries.forEach { entry ->
            detail.addView(LinearLayout(this@showCashIncomeDetails).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
                addView(ImageView(this@showCashIncomeDetails).apply { setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(this@showCashIncomeDetails).apply { text = ledgerTitle(entry.type); textSize = 14f; setTextColor(COLOR_TEXT_PRIMARY); setPadding(dp(8), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@showCashIncomeDetails).apply {
                    val cashDelta = (entry.cashWalletDelta.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) +
                        (entry.availableBalanceDelta.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                    text = if (cashDelta.signum() > 0) "+${formatCurrencyAmount(wallet, cashDelta.toPlainString(), null, includeUsdReference = false)}" else "—"
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL)
                })
            })
        }
        detail.addView(TextView(this).apply {
            text = t("done"); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = roundedDrawable(COLOR_CORAL, 18); setPadding(0, dp(12), 0, dp(12)); isClickable = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        val dialog = AlertDialog.Builder(this).create()
        dialog.setView(detail)
        detail.getChildAt(detail.childCount - 1).setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT) }
    }

internal fun DramaAppActivity.ledgerTitle(type: String): String = when (type) {
        "WELCOME_BONUS" -> t("ledger_welcome"); "SIGN_IN" -> t("ledger_checkin"); "EPISODE_REWARD_CLAIM" -> t("ledger_episode"); "ACHIEVEMENT" -> t("ledger_crown"); else -> t("ledger_other")
    }

internal fun DramaAppActivity.showRewardLedgerHint() {
        val snapshot = rewardsSnapshot
        val message = snapshot?.let { t("reward_ledger_hint", it.ruleVersion) } ?: t("rewards_service_unavailable")
        showBrandedChoiceDialog(t("cash_income_details"), message, t("done"))
    }

/** The ordinary asset header appears only after balances in the reward hero have scrolled out of view. */
internal fun DramaAppActivity.updateRewardsStickyAssetHeader(visible: Boolean) {
    val header = rewardsStickyAssetHeader ?: return
    if (visible && header.visibility != View.VISIBLE) {
        header.visibility = View.VISIBLE
        header.animate().alpha(1f).translationY(0f).setDuration(160L).start()
    } else if (!visible && header.visibility == View.VISIBLE) {
        header.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(120L).withEndAction { header.visibility = View.INVISIBLE }.start()
    }
}
