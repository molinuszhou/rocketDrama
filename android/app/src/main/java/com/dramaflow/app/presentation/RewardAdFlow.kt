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
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

internal fun DramaAppActivity.requestRewardedAction(
        placementId: String,
        targetId: String? = null,
        episodeIndex: Int? = null,
        onSettled: ((RewardsApiClient.RewardAction) -> Unit)? = null,
        onSettlementUnavailable: ((String) -> Unit)? = null,
        onAdUnavailable: ((String) -> Unit)? = null,
        fallbackToInterstitialWhenUnavailable: Boolean = true,
        deferCashAnimation: Boolean = true,
    ) {
        if (readSettledRewardedTarget() != null ||
            readPendingInterstitialIncentive()?.requiresTargetRelease() == true
        ) {
            releaseSettledRewardedTargetIfPossible()
            releasePendingInterstitialIncentiveTargetIfPossible()
            toast(t("reward_settlement_pending"))
            return
        }
        if (readPendingRewardedSettlement() != null) {
            retryPendingRewardedSettlement(onSettled)
            toast(t("reward_settlement_pending"))
            return
        }
        if (fullscreenAdInProgress || fullscreenAdRequestInFlight || rewardSettlementInFlight) { toast(t("ad_loading")); return }
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
        // This reservation is not a completion record. It supplies the stable ID that the
        // verification callback upgrades synchronously before any main-thread work can be lost.
        val preparedRewardedSettlement = preparePendingRewardedSettlement(placementId, targetId, episodeIndex)
        val behaviorInteraction = beginAdBehaviorInteraction(placementId, "rewarded")
        fullscreenAdRequestInFlight = true
        var rewardedWasPresented = false
        var rewardSettlementStarted = false
        val verifiedRewardedSettlement = AtomicReference<PendingRewardedSettlement?>(null)
        val wasPlayingBeforeRewarded = wasPlaybackActiveForAd()
        // Full-screen ad loading is modal: pause the episode and prevent duplicate interaction
        // while mediation resolves its waterfall, then resume if no creative becomes available.
        resumeAfterFullscreenAd = wasPlayingBeforeRewarded
        pauseForFullscreenAd()
        resumeAfterFullscreenAd = wasPlayingBeforeRewarded
        showFullscreenAdLoadingOverlay()
        fun settleVerifiedReward(checkpoint: PendingRewardedSettlement) {
            if (rewardSettlementStarted) return
            rewardSettlementStarted = true
            rewardSettlementInFlight = true
            rewardsApi.completeRewarded(
                userId = checkpoint.userId,
                placementId = checkpoint.placementId,
                targetId = checkpoint.targetId,
                episodeIndex = checkpoint.episodeIndex,
                proof = "",
                adEventId = checkpoint.eventId,
                onSuccess = { action ->
                    val targetReleasePending = if (checkpoint.isEpisodeUnlockTarget()) {
                        markPendingRewardedSettlementTargetPending(checkpoint) != null
                    } else {
                        false
                    }
                    runOnUiThread {
                    fullscreenAdInProgress = false
                    rewardSettlementInFlight = false
                    playbackInterstitialShownAtElapsedMillis.clear()
                    playbackInterstitialShownAtElapsedMillis.add(SystemClock.elapsedRealtime())
                    finishVerifiedRewardedSettlement(
                        checkpoint = checkpoint,
                        targetReleasePending = targetReleasePending,
                        action = action,
                        onSettled = onSettled,
                        deferCashAnimation = deferCashAnimation,
                    )
                } },
                onFailure = { message -> runOnUiThread {
                    // Preserve the checkpoint and event ID. A later bootstrap can retry exactly
                    // this completion without granting the same reward twice on the server.
                    if (deferCashAnimation) deferNextCashFlight = false
                    fullscreenAdInProgress = false
                    rewardSettlementInFlight = false
                    if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(message) else {
                        episodeTransitionInProgress = false
                        resumeAfterFullscreenAdIfNeeded()
                        toast(t("reward_settlement_pending"))
                        logStatus("广告奖励同步待重试：$message")
                    }
                } },
            )
        }
        topOnAds.showRewarded(
            activity = this,
            logicalPlacement = placementId,
            onWillShow = {
                recordAdBehavior(behaviorInteraction, "AD_SHOWN")
                rewardedWasPresented = true
                fullscreenAdRequestInFlight = false
                fullscreenAdInProgress = true
                runOnUiThread { hideFullscreenAdLoadingOverlay() }
            },
            onAdClicked = { recordAdClickAndCheckpoint(behaviorInteraction) },
            onRewardVerified = {
                // TopOn may invoke this off-main. commit() must finish before a click-out or
                // process reclaim can win the race with runOnUiThread.
                val checkpoint = markPendingRewardedSettlementVerified(preparedRewardedSettlement)
                recordAdBehavior(behaviorInteraction, "AD_REWARD_VERIFIED")
                if (checkpoint == null) {
                    logStatus("激励验证回调未能持久化，不能安全结算")
                } else {
                    verifiedRewardedSettlement.set(checkpoint)
                    runOnUiThread { settleVerifiedReward(checkpoint) }
                }
            },
        ) { result -> runFullscreenResultWhenForeground {
            fullscreenAdRequestInFlight = false
            hideFullscreenAdLoadingOverlay()
            when (result) {
                TopOnAdsManager.RewardedResult.RewardEarned -> {
                    recordAdBehavior(behaviorInteraction, "AD_COMPLETED")
                    fullscreenAdInProgress = false
                    if (rewardedWasPresented && wasPlayingBeforeRewarded) { resumeAfterFullscreenAd = true; resumeAfterFullscreenAdIfNeeded() }
                    val checkpoint = verifiedRewardedSettlement.get()
                    if (checkpoint != null) {
                        settleVerifiedReward(checkpoint)
                    } else {
                        // A close/result callback is not proof of a verified reward. There is no
                        // SDK/SSV evidence to replay, so retain no false entitlement and record
                        // the operational limitation for diagnosis.
                        clearPreparedPendingRewardedSettlement(preparedRewardedSettlement.eventId)
                        recordAdBehavior(behaviorInteraction, "AD_REWARD_UNVERIFIED")
                        logStatus("激励广告已关闭但未收到可验证奖励回调，无法追溯结算")
                        val message = t("reward_settlement_pending")
                        if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(message)
                        else showRewardedUnavailableNotice(
                            wasPlayingBeforeRewarded,
                            message,
                            preserveEpisodeUnlockTransition = placementId == "rv_episode_pass",
                        )
                    }
                }
                TopOnAdsManager.RewardedResult.Cancelled -> {
                    recordAdBehavior(behaviorInteraction, "AD_CANCELLED")
                    if (deferCashAnimation) deferNextCashFlight = false
                    fullscreenAdInProgress = false
                    verifiedRewardedSettlement.get()?.let(::settleVerifiedReward) ?: run {
                        clearPreparedPendingRewardedSettlement(preparedRewardedSettlement.eventId)
                        recordRewardedSkipThenMaybeShowInterstitial(
                            shouldResume = wasPlayingBeforeRewarded,
                            preserveEpisodeUnlockTransition = placementId == "rv_episode_pass",
                        )
                    }
                }
                is TopOnAdsManager.RewardedResult.Unavailable -> {
                    recordAdBehavior(behaviorInteraction, "AD_UNAVAILABLE", mapOf("outcome" to "unavailable"))
                    if (deferCashAnimation) deferNextCashFlight = false
                    fullscreenAdInProgress = false
                    if (rewardedWasPresented) resumeAfterFullscreenAdIfNeeded()
                    val message = t("ad_loading_failed") + " ${result.reason}"
                    verifiedRewardedSettlement.get()?.let(::settleVerifiedReward) ?: run {
                        clearPreparedPendingRewardedSettlement(preparedRewardedSettlement.eventId)
                        if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message, placementId, targetId, episodeIndex, onSettled, deferCashAnimation, onAdUnavailable)
                        else if (onAdUnavailable != null) onAdUnavailable.invoke(message) else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                    }
                }
                is TopOnAdsManager.RewardedResult.Failed -> {
                    recordAdBehavior(behaviorInteraction, "AD_LOAD_FAILED", mapOf("error_code" to result.code))
                    if (deferCashAnimation) deferNextCashFlight = false
                    fullscreenAdInProgress = false
                    if (rewardedWasPresented) resumeAfterFullscreenAdIfNeeded()
                    val message = t("ad_loading_failed") + " (${result.code})"
                    verifiedRewardedSettlement.get()?.let(::settleVerifiedReward) ?: run {
                        clearPreparedPendingRewardedSettlement(preparedRewardedSettlement.eventId)
                        if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message, placementId, targetId, episodeIndex, onSettled, deferCashAnimation, onAdUnavailable)
                        else if (onAdUnavailable != null) onAdUnavailable.invoke(message) else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                    }
                }
            }
        } }
    }

/** Replays a reward completion that TopOn confirmed before an external store/browser hand-off. */
internal fun DramaAppActivity.retryPendingRewardedSettlement(
    onSettled: ((RewardsApiClient.RewardAction) -> Unit)? = null,
) {
    val checkpoint = readPendingRewardedSettlement() ?: run {
        releaseSettledRewardedTargetIfPossible()
        return
    }
    if (!rewardsApi.isConfigured || rewardSettlementInFlight) return
    rewardSettlementInFlight = true
    logStatus("重试已完成广告奖励结算：${checkpoint.placementId}")
    rewardsApi.completeRewarded(
        userId = checkpoint.userId,
        placementId = checkpoint.placementId,
        targetId = checkpoint.targetId,
        episodeIndex = checkpoint.episodeIndex,
        proof = "",
        adEventId = checkpoint.eventId,
        onSuccess = { action ->
            val targetReleasePending = if (checkpoint.isEpisodeUnlockTarget()) {
                markPendingRewardedSettlementTargetPending(checkpoint) != null
            } else {
                false
            }
            runOnUiThread {
            rewardSettlementInFlight = false
            playbackInterstitialShownAtElapsedMillis.clear()
            playbackInterstitialShownAtElapsedMillis.add(SystemClock.elapsedRealtime())
            finishVerifiedRewardedSettlement(
                checkpoint = checkpoint,
                targetReleasePending = targetReleasePending,
                action = action,
                onSettled = onSettled,
                deferCashAnimation = true,
            )
        } },
        onFailure = { message -> runOnUiThread {
            rewardSettlementInFlight = false
            logStatus("广告奖励重试仍未完成：$message")
        } },
    )
}

private fun PendingRewardedSettlement.isEpisodeUnlockTarget(): Boolean =
    placementId == "rv_episode_pass" && !dramaId.isNullOrBlank() && episodeIndex != null

/** Applies a server-confirmed action, retaining an unlock target until its original player consumes it. */
private fun DramaAppActivity.finishVerifiedRewardedSettlement(
    checkpoint: PendingRewardedSettlement,
    targetReleasePending: Boolean,
    action: RewardsApiClient.RewardAction,
    onSettled: ((RewardsApiClient.RewardAction) -> Unit)?,
    deferCashAnimation: Boolean,
) {
    if (!targetReleasePending) {
        clearPendingRewardedSettlement(checkpoint.eventId)
        handleSettledRewardAction(action, onSettled, deferCashAnimation)
        return
    }
    handleSettledRewardAction(
        action = action,
        onSettled = { settledAction ->
            if (onSettled != null) {
                onSettled.invoke(settledAction)
                clearPendingRewardedSettlement(checkpoint.eventId)
            } else {
                releaseSettledRewardedTargetIfPossible()
            }
        },
        deferCashAnimation = deferCashAnimation,
    )
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
    val pendingIncentive = preparePendingInterstitialIncentive(
        kind = InterstitialIncentiveKind.ORGANIC_INTERSTITIAL,
        interstitialPlacementId = "int_incentive_action",
        originalPlacementId = placementId,
        targetId = targetId,
        episodeIndex = episodeIndex,
        episodeId = selectedEpisode()?.id,
    )
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
        pendingIncentive = pendingIncentive,
        onRecorded = {
            settlePendingInterstitialIncentive(
                pending = pendingIncentive,
                onSettled = onSettled,
                deferCashAnimation = deferCashAnimation,
                onFailure = { reason ->
                    if (deferCashAnimation) deferNextCashFlight = false
                    logStatus("A 面插屏奖励结算失败：$reason")
                    if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(reason)
                    else showRewardedUnavailableNotice(shouldResume, reason)
                },
            )
        },
        onRecordFailed = { reason ->
            if (deferCashAnimation) deferNextCashFlight = false
            logStatus("A 面插屏展示记录待恢复：$reason")
            if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(reason)
            else showRewardedUnavailableNotice(shouldResume, reason)
        },
    )
}

/** Replays a shown incentive in strict display-record -> settlement order with its original ID. */
internal fun DramaAppActivity.retryPendingInterstitialIncentive() {
    val pending = readPendingInterstitialIncentive() ?: return
    if (!rewardsApi.isConfigured || rewardSettlementInFlight) return
    when {
        pending.requiresTargetRelease() -> releasePendingInterstitialIncentiveTargetIfPossible()
        pending.requiresSettlementRecovery() -> settlePendingInterstitialIncentive(
            pending = pending,
            onSettled = null,
            deferCashAnimation = true,
            onFailure = { reason -> logStatus("插屏奖励结算重试仍未完成：$reason") },
        )
        pending.requiresRecordRecovery() -> {
            rewardSettlementInFlight = true
            logStatus("重试插屏展示记录：${pending.interstitialPlacementId}")
            rewardsApi.recordInterstitial(
                userId = pending.userId,
                placementId = pending.interstitialPlacementId,
                episodeId = pending.episodeId,
                eventId = pending.eventId,
                onSuccess = { snapshot ->
                    val recorded = markPendingInterstitialRecorded(pending)
                    runOnUiThread {
                        rewardSettlementInFlight = false
                        applyRewardsSnapshot(snapshot)
                        if (recorded != null) retryPendingInterstitialIncentive()
                    }
                },
                onFailure = { reason -> runOnUiThread {
                    rewardSettlementInFlight = false
                    logStatus("插屏展示记录重试仍未完成：$reason")
                } },
            )
        }
        else -> {
            // PREPARED means that no creative was confirmed shown. Without a verified callback
            // or server record there is deliberately no reward to reconstruct.
            logStatus("插屏激励未确认展示，缺少可追溯证据，已放弃未完成预分配")
            clearPendingInterstitialIncentive(pending.eventId)
        }
    }
}

private fun PendingInterstitialIncentive.isEpisodeUnlockTarget(): Boolean =
    originalPlacementId == "rv_episode_pass" && !dramaId.isNullOrBlank() && episodeIndex != null

private fun DramaAppActivity.settlePendingInterstitialIncentive(
    pending: PendingInterstitialIncentive,
    onSettled: ((RewardsApiClient.RewardAction) -> Unit)?,
    deferCashAnimation: Boolean,
    onFailure: (String) -> Unit,
) {
    if (rewardSettlementInFlight || !rewardsApi.isConfigured) return
    rewardSettlementInFlight = true
    val onSuccess: (RewardsApiClient.RewardAction) -> Unit = { action ->
        val targetReleasePending = if (pending.isEpisodeUnlockTarget()) {
            markPendingInterstitialSettled(pending) != null
        } else {
            false
        }
        runOnUiThread {
            rewardSettlementInFlight = false
            finishPendingInterstitialIncentiveSettlement(
                pending = pending,
                targetReleasePending = targetReleasePending,
                action = action,
                onSettled = onSettled,
                deferCashAnimation = deferCashAnimation,
            )
        }
    }
    val onNetworkFailure: (String) -> Unit = { reason -> runOnUiThread {
        rewardSettlementInFlight = false
        onFailure(reason)
    } }
    when (pending.kind) {
        InterstitialIncentiveKind.ORGANIC_INTERSTITIAL -> rewardsApi.settleInterstitialIncentive(
            userId = pending.userId,
            interstitialEventId = pending.eventId,
            originalPlacementId = pending.originalPlacementId,
            targetId = pending.targetId,
            episodeIndex = pending.episodeIndex,
            onSuccess = onSuccess,
            onFailure = onNetworkFailure,
        )
        InterstitialIncentiveKind.REWARDED_FALLBACK -> rewardsApi.settleRewardedFallback(
            userId = pending.userId,
            interstitialEventId = pending.eventId,
            originalPlacementId = pending.originalPlacementId,
            targetId = pending.targetId,
            episodeIndex = pending.episodeIndex,
            onSuccess = onSuccess,
            onFailure = onNetworkFailure,
        )
    }
}

private fun DramaAppActivity.finishPendingInterstitialIncentiveSettlement(
    pending: PendingInterstitialIncentive,
    targetReleasePending: Boolean,
    action: RewardsApiClient.RewardAction,
    onSettled: ((RewardsApiClient.RewardAction) -> Unit)?,
    deferCashAnimation: Boolean,
) {
    if (!targetReleasePending) {
        clearPendingInterstitialIncentive(pending.eventId)
        handleSettledRewardAction(action, onSettled, deferCashAnimation)
        return
    }
    handleSettledRewardAction(
        action = action,
        onSettled = { settledAction ->
            if (onSettled != null) {
                onSettled.invoke(settledAction)
                clearPendingInterstitialIncentive(pending.eventId)
            } else {
                releasePendingInterstitialIncentiveTargetIfPossible()
            }
        },
        deferCashAnimation = deferCashAnimation,
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
internal fun DramaAppActivity.recordRewardedSkipThenMaybeShowInterstitial(
    shouldResume: Boolean,
    context: String = "rewarded_cancelled",
    preserveEpisodeUnlockTransition: Boolean = false,
) {
        val notice = t("rewarded_not_completed")
        if (!rewardsApi.isConfigured) {
            showRewardedUnavailableNotice(shouldResume, notice, preserveEpisodeUnlockTransition)
            return
        }
        rewardsApi.recordRewardedSkipped(anonymousInstallationId(), context, { snapshot -> runOnUiThread {
            applyRewardsSnapshot(snapshot)
            val policy = snapshot.interstitial
            if (policy.rewardedFallbackEnabled && (policy.rewardedSkipThreshold <= 0 || policy.rewardedSkipCount >= policy.rewardedSkipThreshold)) {
                showRewardedFallbackInterstitial(
                    shouldResume = shouldResume,
                    message = notice,
                    preserveEpisodeUnlockTransition = preserveEpisodeUnlockTransition,
                )
            } else {
                // No fallback is going to settle this cancelled unlock attempt. Resume once
                // under the existing guard, then release it for a later deliberate retry
                // without reopening the just-cancelled request.
                resumeAfterFullscreenAd = shouldResume
                resumeAfterFullscreenAdIfNeeded()
                if (preserveEpisodeUnlockTransition) episodeTransitionInProgress = false
                toast(t("rewarded_not_completed"))
            }
        } }, { _ -> runOnUiThread { showRewardedUnavailableNotice(shouldResume, notice, preserveEpisodeUnlockTransition) } })
}

    /** Handles unavailable rewarded inventory when no fallback interstitial can be presented. */
internal fun DramaAppActivity.showRewardedUnavailableNotice(
    shouldResume: Boolean,
    message: String,
    preserveEpisodeUnlockTransition: Boolean = false,
) {
        fullscreenAdInProgress = false
        fullscreenAdRequestInFlight = false
        if (!preserveEpisodeUnlockTransition) episodeTransitionInProgress = false
        resumeAfterFullscreenAd = shouldResume
        resumeAfterFullscreenAdIfNeeded()
        // Resume under the transition guard so PSSDK's synchronous showAdIfNeed callback is
        // absorbed. The current attempt is finished now, therefore a later user action may retry.
        if (preserveEpisodeUnlockTransition) episodeTransitionInProgress = false
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
        onUnavailable: ((String) -> Unit)? = null,
        preserveEpisodeUnlockTransition: Boolean = false,
    ) {
        fullscreenAdInProgress = false
        fullscreenAdRequestInFlight = false
        // An episode unlock stays pending until the fallback creative is recorded and its
        // original unlock settlement has completed. PSSDK may synchronously call showAdIfNeed
        // when playback resumes after the interstitial; clearing this guard here would restart
        // the whole rewarded -> fallback -> resume loop and eventually overflow the stack.
        val retainEpisodeUnlockTransition = originalPlacementId == "rv_episode_pass" || preserveEpisodeUnlockTransition
        if (!retainEpisodeUnlockTransition) episodeTransitionInProgress = false
        if (rewardsSnapshot?.interstitial?.rewardedFallbackEnabled != true) {
            if (onUnavailable != null) {
                // The caller will complete the server-authorized unavailable-ad unlock and
                // release this guard on success/failure. Do not permit another PSSDK request
                // between that authorization request and its callback.
                onUnavailable.invoke(message)
            } else {
                resumeAfterFullscreenAd = shouldResume
                resumeAfterFullscreenAdIfNeeded()
                if (retainEpisodeUnlockTransition) episodeTransitionInProgress = false
                toast(message)
            }
            return
        }
        // 失败回退是一条独立的例外插屏。只有该插屏实际展示并记录成功后，
        // 服务端才会按一次性账本结算原激励权益；加载失败绝不授予权益。
        resumeAfterFullscreenAd = shouldResume
        val pendingIncentive = originalPlacementId?.let { originalPlacement ->
            preparePendingInterstitialIncentive(
                kind = InterstitialIncentiveKind.REWARDED_FALLBACK,
                interstitialPlacementId = "int_rewarded_fallback",
                originalPlacementId = originalPlacement,
                targetId = targetId,
                episodeIndex = episodeIndex,
                episodeId = selectedEpisode()?.id,
            )
        }
        showPlaybackActionInterstitial(
            placementId = "int_rewarded_fallback", episodeId = selectedEpisode()?.id,
            countTowardEpisodeLimit = false,
            bypassSharedFrequencyCaps = true,
            resumePlaybackAfter = shouldResume,
            onShown = {},
            onUnavailable = { reason ->
                logStatus("激励回退插屏未展示：$reason")
                if (onUnavailable != null) {
                    // Keep the transition guard until the unavailable-ad authorization has
                    // either unlocked the episode or reported its failure.
                    onUnavailable.invoke(message)
                } else {
                    if (retainEpisodeUnlockTransition) episodeTransitionInProgress = false
                    toast(message)
                }
            },
            pendingIncentive = pendingIncentive,
            onRecorded = {
                if (originalPlacementId == null) {
                    // A user-cancelled reward attempt does not grant the original reward.
                    // The PSSDK resume callback has already been absorbed by the retained
                    // guard, so it is now safe to allow a future deliberate retry.
                    if (retainEpisodeUnlockTransition) episodeTransitionInProgress = false
                    toast(message)
                    return@showPlaybackActionInterstitial
                }
                val pending = pendingIncentive ?: return@showPlaybackActionInterstitial
                settlePendingInterstitialIncentive(
                    pending = pending,
                    onSettled = onOriginalSettled,
                    deferCashAnimation = deferCashAnimation,
                    onFailure = { reason ->
                        // The shown creative has no confirmed server settlement yet. Preserve the
                        // journal for bootstrap recovery but do not grant an unlock locally.
                        if (retainEpisodeUnlockTransition) episodeTransitionInProgress = false
                        logStatus("回退插屏奖励结算失败：$reason")
                        toast(t("reward_settlement_pending"))
                    },
                )
            },
            onRecordFailed = { reason ->
                // Do not route this through ad-unavailable authorization: a creative was shown,
                // but the mandatory display record is not yet server-confirmed.
                if (retainEpisodeUnlockTransition) episodeTransitionInProgress = false
                logStatus("回退插屏展示记录待恢复：$reason")
                toast(t("reward_settlement_pending"))
            },
        )
    }

/** Returns the real local or provider playback state without forcing an ad-request pause. */
internal fun DramaAppActivity.wasPlaybackActiveForAd(): Boolean =
    exoPlayer?.isPlaying == true || (sdkPlayerShown && sdkPlaybackActive && !playerIsPaused)

internal fun DramaAppActivity.showFullscreenAdLoadingOverlay() {
        if (isFinishing || isDestroyed) return
        val overlay = fullscreenAdLoadingOverlay ?: FrameLayout(this).apply {
            // Keep the request modal blocking while leaving the current drama perceptible beneath it.
            setBackgroundColor(0x4D000000)
            isClickable = true
            isFocusable = true
            contentDescription = t("ad_loading")
            addView(LinearLayout(this@showFullscreenAdLoadingOverlay).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(20), dp(22), dp(18))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xB8404148.toInt(), 0xA81F2024.toInt())).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), 0x66FFFFFF)
                }
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
        var playbackResumed = false
        if (resumeAfterFullscreenAd && !isFinishing && !isDestroyed) {
            ignoreVideoTapsUntilMillis = System.currentTimeMillis() + 750L
            exoPlayer?.play()
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            playbackResumed = true
        }
        if (resumeSdkPlaybackWhenForeground && sdkPlayerShown && !isFinishing && !isDestroyed) {
            currentSdkPlayerFragment()?.startPlay()
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resumeSdkPlaybackWhenForeground = false
            playbackResumed = true
        }
        if (playbackResumed) recordPlaybackResumedAfterAdClickIfPending()
        if (restoreImmersiveAfterFullscreenAd && !isFinishing && !isDestroyed && (playerShown || sdkPlayerShown)) {
            enterImmersivePlayerMode(announce = false)
        }
        restoreImmersiveAfterFullscreenAd = false
        resumeAfterFullscreenAd = false
        // Do not clear the external-click checkpoint here. Some mediation SDKs send the close
        // callback before Google Play or a browser takes over; the checkpoint is cleared only
        // after this Activity is demonstrably foregrounded again or its saved route is restored.
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
        pendingIncentive: PendingInterstitialIncentive? = null,
        onRecorded: ((String) -> Unit)? = null,
        onRecordFailed: ((String) -> Unit)? = null,
    ) {
        val policy = rewardsSnapshot?.interstitial
        val episodeKey = episodeId?.let { "${selectedDrama?.id}:$it" }
        val now = SystemClock.elapsedRealtime()
        fun unavailable(reason: String) {
            pendingIncentive?.let { clearPreparedInterstitialIncentive(it.eventId) }
            onUnavailable(reason)
        }
        playbackInterstitialShownAtElapsedMillis.removeAll { now - it >= 60 * 60 * 1_000L }
        if (!bypassSharedFrequencyCaps && policy != null && policy.cooldownSeconds > 0 && playbackInterstitialShownAtElapsedMillis.lastOrNull()?.let { now - it < policy.cooldownSeconds * 1_000L } == true) {
            unavailable("插屏冷却期未结束"); return
        }
        if (!bypassSharedFrequencyCaps && policy != null && policy.globalHourlyLimit > 0 && playbackInterstitialShownAtElapsedMillis.size >= policy.globalHourlyLimit) {
            unavailable("本小时插屏展示已达总上限"); return
        }
        if (!bypassSharedFrequencyCaps && countTowardEpisodeLimit && policy != null && policy.episodeInterstitialLimit > 0 && episodeKey != null && (interstitialCountByEpisodeKey[episodeKey] ?: 0) >= policy.episodeInterstitialLimit) {
            unavailable("本集插屏次数已达上限"); return
        }
        if (fullscreenAdInProgress || fullscreenAdRequestInFlight) { unavailable("广告正在展示"); return }
        if (consentStore.read() != AdConsent.GRANTED) { unavailable("请先在广告与隐私中启用广告"); return }
        val shouldResume = resumePlaybackAfter ?: wasPlaybackActiveForAd()
        val behaviorInteraction = beginAdBehaviorInteraction(placementId, "interstitial")
        fullscreenAdRequestInFlight = true
        resumeAfterFullscreenAd = shouldResume
        pauseForFullscreenAd()
        resumeAfterFullscreenAd = shouldResume
        showFullscreenAdLoadingOverlay()
        var interstitialActuallyShown = false
        var confirmedShowRecorded = false
        fun recordConfirmedShow() {
            if (confirmedShowRecorded) return
            confirmedShowRecorded = true
            val persistedShown = pendingIncentive?.let(::markPendingInterstitialShown)
            if (pendingIncentive != null && persistedShown == null) {
                logStatus("插屏激励展示阶段未能持久化，不能安全结算")
                onRecordFailed?.invoke("插屏展示记录未能持久化")
                return
            }
            val interstitialEventId = pendingIncentive?.eventId ?: "int-${UUID.randomUUID()}"
            val settled = {
                playbackInterstitialShownAtElapsedMillis.add(SystemClock.elapsedRealtime())
                if (countTowardEpisodeLimit && episodeKey != null) interstitialCountByEpisodeKey[episodeKey] = (interstitialCountByEpisodeKey[episodeKey] ?: 0) + 1
                onShown()
            }
            if (rewardsApi.isConfigured) {
                rewardsApi.recordInterstitial(pendingIncentive?.userId ?: anonymousInstallationId(), placementId, episodeId,
                    onSuccess = { snapshot ->
                        val recorded = pendingIncentive?.let(::markPendingInterstitialRecorded)
                        runOnUiThread {
                            applyRewardsSnapshot(snapshot)
                            if (pendingIncentive == null || recorded != null) {
                                settled()
                                onRecorded?.invoke(interstitialEventId)
                            } else {
                                logStatus("插屏展示记录阶段未能持久化，已保留原始事件等待恢复")
                                onRecordFailed?.invoke("插屏展示记录阶段未能持久化")
                            }
                        }
                    },
                    onFailure = { message -> runOnUiThread {
                        logStatus("插屏展示记录未同步：$message")
                        if (pendingIncentive != null) {
                            // A shown incentive may recover only by replaying this exact
                            // record. It must not fall through to settlement on failure.
                            onRecordFailed?.invoke(message)
                        } else {
                            settled()
                            onRecorded?.invoke(interstitialEventId)
                        }
                    } },
                    eventId = interstitialEventId,
                )
            } else settled()
        }
        topOnAds.showInterstitialForPlaybackAction(
            activity = this,
            logicalPlacement = placementId,
            bypassGlobalFrequencyCap = bypassSharedFrequencyCaps,
            onWillShow = {
                fullscreenAdRequestInFlight = false
                fullscreenAdInProgress = true
                resumeAfterFullscreenAd = shouldResume
                runOnUiThread { hideFullscreenAdLoadingOverlay() }
            },
            onShown = {
                interstitialActuallyShown = true
                recordAdBehavior(behaviorInteraction, "AD_SHOWN")
                // Persist the only evidence that survives a click-out or process reclaim. The
                // normal close path still performs the display-record -> settlement sequence.
                pendingIncentive?.let { pending ->
                    if (markPendingInterstitialShown(pending) == null) {
                        logStatus("插屏激励展示阶段未能持久化，等待关闭时重试")
                    }
                }
            },
            onAdClicked = { recordAdClickAndCheckpoint(behaviorInteraction) },
        ) { result -> runFullscreenResultWhenForeground {
            fullscreenAdRequestInFlight = false
            fullscreenAdInProgress = false
            hideFullscreenAdLoadingOverlay()
            when (result) {
                TopOnAdsManager.FullscreenResult.Shown -> {
                    recordAdBehavior(behaviorInteraction, "AD_CLOSED")
                    resumeAfterFullscreenAdIfNeeded()
                    if (pendingIncentive == null || interstitialActuallyShown) {
                        recordConfirmedShow()
                    } else {
                        logStatus("插屏关闭未携带展示确认，不能安全结算激励")
                        onRecordFailed?.invoke("插屏展示未确认")
                    }
                }
                is TopOnAdsManager.FullscreenResult.Skipped -> {
                    if (pendingIncentive != null && interstitialActuallyShown) {
                        recordAdBehavior(behaviorInteraction, "AD_POST_SHOW_CALLBACK_SKIPPED", mapOf("reason" to result.reason))
                        resumeAfterFullscreenAdIfNeeded()
                        recordConfirmedShow()
                        return@runFullscreenResultWhenForeground
                    }
                    recordAdBehavior(behaviorInteraction, "AD_UNAVAILABLE", mapOf("outcome" to "skipped"))
                    resumeAfterFullscreenAdIfNeeded()
                    pendingIncentive?.let { clearPreparedInterstitialIncentive(it.eventId) }
                    onUnavailable(result.reason)
                }
                is TopOnAdsManager.FullscreenResult.Failed -> {
                    if (pendingIncentive != null && interstitialActuallyShown) {
                        recordAdBehavior(behaviorInteraction, "AD_POST_SHOW_CALLBACK_FAILED", mapOf("error_code" to result.code))
                        resumeAfterFullscreenAdIfNeeded()
                        recordConfirmedShow()
                        return@runFullscreenResultWhenForeground
                    }
                    recordAdBehavior(behaviorInteraction, "AD_LOAD_FAILED", mapOf("error_code" to result.code))
                    resumeAfterFullscreenAdIfNeeded()
                    pendingIncentive?.let { clearPreparedInterstitialIncentive(it.eventId) }
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
            text = t("done"); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_REWARD_ACTION_TEXT)
            background = primaryActionDrawable(); setPadding(0, dp(12), 0, dp(12)); isClickable = true
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
