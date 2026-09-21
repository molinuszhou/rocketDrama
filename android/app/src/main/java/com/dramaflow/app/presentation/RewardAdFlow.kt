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
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalDramaApiClient
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
    ) {
        if (fullscreenAdInProgress || fullscreenAdRequestInFlight) { toast(t("ad_loading")); return }
        if (consentStore.read() != AdConsent.GRANTED) { toast("请先在广告与隐私中启用广告"); return }
        if (!rewardsApi.isConfigured) { toast("独立积分服务尚未配置，广告奖励无法结算"); return }
        fullscreenAdRequestInFlight = true
        showFullscreenAdLoadingOverlay()
        pauseForFullscreenAd()
        var rewardedWasPresented = false
        val wasPlayingBeforeRewarded = resumeAfterFullscreenAd
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
                        episodeIndex = episodeIndex, proof = testRewardProof(),
                        onSuccess = { action -> runOnUiThread {
                            fullscreenAdInProgress = false; applyRewardsSnapshot(action.snapshot)
                            playbackInterstitialShownAtElapsedMillis.clear()
                            playbackInterstitialShownAtElapsedMillis.add(SystemClock.elapsedRealtime())
                            if (onSettled != null) onSettled.invoke(action) else { resumeAfterFullscreenAdIfNeeded(); toast("现金奖励已结算") }
                        } },
                        onFailure = { message -> runOnUiThread {
                            fullscreenAdInProgress = false
                            if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(message) else {
                                episodeTransitionInProgress = false; resumeAfterFullscreenAdIfNeeded()
                                toast("广告已完整观看，奖励状态暂未同步；请稍后在奖励中心查看。"); logStatus("广告奖励同步失败：$message")
                            }
                        } },
                    )
                }
                TopOnAdsManager.RewardedResult.Cancelled -> {
                    fullscreenAdInProgress = false
                    recordRewardedSkipThenMaybeShowInterstitial(wasPlayingBeforeRewarded)
                }
                is TopOnAdsManager.RewardedResult.Unavailable -> {
                    fullscreenAdInProgress = false
                    val message = t("ad_loading_failed") + " ${result.reason}"
                    if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message)
                    else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                }
                is TopOnAdsManager.RewardedResult.Failed -> {
                    fullscreenAdInProgress = false
                    val message = t("ad_loading_failed") + " (${result.code})"
                    if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message)
                    else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                }
            }
        } }
    }

/** A fallback interstitial is eligible only after the configured number of user-cancelled rewarded ads. */
internal fun DramaAppActivity.recordRewardedSkipThenMaybeShowInterstitial(shouldResume: Boolean, context: String = "rewarded_cancelled") {
        if (!rewardsApi.isConfigured) { showRewardedUnavailableNotice(shouldResume, "广告未完整观看，未获得奖励或解锁权益"); return }
        rewardsApi.recordRewardedSkipped(anonymousInstallationId(), context, { snapshot -> runOnUiThread {
            applyRewardsSnapshot(snapshot)
            val policy = snapshot.interstitial
            if (policy.rewardedFallbackEnabled && (policy.rewardedSkipThreshold <= 0 || policy.rewardedSkipCount >= policy.rewardedSkipThreshold)) {
                showRewardedFallbackInterstitial(shouldResume, "广告未完整观看，未获得奖励或解锁权益")
            } else {
                resumeAfterFullscreenAd = shouldResume; resumeAfterFullscreenAdIfNeeded(); toast("广告未完整观看，未获得奖励。")
            }
        } }, { _ -> runOnUiThread { showRewardedUnavailableNotice(shouldResume, "广告未完整观看，未获得奖励或解锁权益") } })
}

    /** 无激励广告资源时恢复内容并说明不会发放原激励权益。 */
internal fun DramaAppActivity.showRewardedUnavailableNotice(shouldResume: Boolean, message: String) {
        fullscreenAdInProgress = false
        fullscreenAdRequestInFlight = false
        episodeTransitionInProgress = false
        resumeAfterFullscreenAd = shouldResume
        resumeAfterFullscreenAdIfNeeded()
        showBrandedChoiceDialog(t("ad_unavailable_title"), "$message\n\n${t("ad_unavailable_message")}", t("done"))
    }

    /** 激励中途退出或加载失败时，只在后台开关允许时回退一次插屏；不会授予原激励权益。 */
internal fun DramaAppActivity.showRewardedFallbackInterstitial(shouldResume: Boolean, message: String) {
        fullscreenAdInProgress = false
        fullscreenAdRequestInFlight = false
        episodeTransitionInProgress = false
        if (rewardsSnapshot?.interstitial?.rewardedFallbackEnabled != true) {
            resumeAfterFullscreenAd = shouldResume; resumeAfterFullscreenAdIfNeeded(); toast(message); return
        }
        // 失败回退是一条独立的例外插屏：展示一次，但绝不替代原激励奖励或解锁权益。
        resumeAfterFullscreenAd = shouldResume
        showPlaybackActionInterstitial(
            placementId = "int_rewarded_fallback", episodeId = selectedEpisode()?.id,
            countTowardEpisodeLimit = false,
            bypassSharedFrequencyCaps = true,
            resumePlaybackAfter = shouldResume,
            onShown = { toast(message) },
            onUnavailable = { reason -> logStatus("激励回退插屏未展示：$reason"); toast(message) },
        )
    }

internal fun DramaAppActivity.showFullscreenAdLoadingOverlay() {
        if (isFinishing || isDestroyed) return
        val overlay = fullscreenAdLoadingOverlay ?: FrameLayout(this).apply {
            setBackgroundColor(0x72000000)
            isClickable = true
            isFocusable = true
            contentDescription = t("ad_loading")
            addView(ProgressBar(this@showFullscreenAdLoadingOverlay).apply {
                indeterminateTintList = ColorStateList.valueOf(0xF2FFFFFF.toInt())
                alpha = 0.92f
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
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
        restoreImmersiveAfterFullscreenAd = playerShown && playerInImmersiveMode
        if (playerInImmersiveMode) exitImmersivePlayerMode()
        navigationBackCallback?.isEnabled = false
        exoPlayer?.pause()
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
        if (restoreImmersiveAfterFullscreenAd && !isFinishing && !isDestroyed && playerShown) {
            enterImmersivePlayerMode(announce = false)
        }
        restoreImmersiveAfterFullscreenAd = false
        resumeAfterFullscreenAd = false
        if (!isFinishing && !isDestroyed) {
            if (playerShown) configurePlayerWindow() else configureCatalogWindow()
            root.post {
                if (!isFinishing && !isDestroyed) {
                    if (playerShown) configurePlayerWindow() else configureCatalogWindow()
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
        val shouldResume = resumePlaybackAfter ?: (exoPlayer?.isPlaying == true)
        fullscreenAdRequestInFlight = true
        showFullscreenAdLoadingOverlay()
        pauseForFullscreenAd()
        resumeAfterFullscreenAd = shouldResume
        topOnAds.showInterstitialForPlaybackAction(
            activity = this,
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
                    val settled = {
                        playbackInterstitialShownAtElapsedMillis.add(SystemClock.elapsedRealtime())
                        if (countTowardEpisodeLimit && episodeKey != null) interstitialCountByEpisodeKey[episodeKey] = (interstitialCountByEpisodeKey[episodeKey] ?: 0) + 1
                        onShown()
                    }
                    if (rewardsApi.isConfigured) {
                        rewardsApi.recordInterstitial(anonymousInstallationId(), placementId, episodeId,
                            onSuccess = { snapshot -> runOnUiThread { applyRewardsSnapshot(snapshot); settled() } },
                            onFailure = { message -> runOnUiThread { logStatus("插屏展示记录未同步：$message"); settled() } },
                        )
                    } else settled()
                }
                is TopOnAdsManager.FullscreenResult.Skipped -> { resumeAfterFullscreenAdIfNeeded(); onUnavailable(result.reason) }
                is TopOnAdsManager.FullscreenResult.Failed -> { resumeAfterFullscreenAdIfNeeded(); onUnavailable("加载失败（${result.code}）") }
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
