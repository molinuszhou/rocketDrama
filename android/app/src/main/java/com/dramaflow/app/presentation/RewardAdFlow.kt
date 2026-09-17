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

internal fun DramaAppActivity.requestRewardedAction(
        placementId: String,
        targetId: String? = null,
        episodeIndex: Int? = null,
        onSettled: ((RewardsApiClient.RewardAction) -> Unit)? = null,
        onSettlementUnavailable: ((String) -> Unit)? = null,
        fallbackToInterstitialWhenUnavailable: Boolean = false,
    ) {
        if (fullscreenAdInProgress) { toast("广告正在展示，请稍候"); return }
        if (consentStore.read() != AdConsent.GRANTED) { toast("请先在广告与隐私中启用广告"); return }
        if (!rewardsApi.isConfigured) { toast("独立积分服务尚未配置，广告奖励无法结算"); return }
        fullscreenAdInProgress = true
        pauseForFullscreenAd()
        val wasPlayingBeforeRewarded = resumeAfterFullscreenAd
        topOnAds.showRewarded(this) { result ->
            when (result) {
                TopOnAdsManager.RewardedResult.RewardEarned -> {
                    if (wasPlayingBeforeRewarded) { resumeAfterFullscreenAd = true; resumeAfterFullscreenAdIfNeeded() }
                    rewardsApi.completeRewarded(
                        userId = anonymousInstallationId(), placementId = placementId, targetId = targetId,
                        episodeIndex = episodeIndex, proof = testRewardProof(),
                        onSuccess = { action -> runOnUiThread {
                            fullscreenAdInProgress = false; applyRewardsSnapshot(action.snapshot)
                            if (onSettled != null) onSettled.invoke(action) else { resumeAfterFullscreenAdIfNeeded(); toast("已结算 ${action.gemsAdded} 宝石") }
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
                TopOnAdsManager.RewardedResult.Cancelled -> showRewardedFallbackInterstitial(
                    wasPlayingBeforeRewarded, "广告未完整观看，未获得奖励或解锁权益"
                )
                is TopOnAdsManager.RewardedResult.Unavailable -> {
                    val message = t("ad_loading_failed") + " ${result.reason}"
                    if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message)
                    else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                }
                is TopOnAdsManager.RewardedResult.Failed -> {
                    val message = t("ad_loading_failed") + " (${result.code})"
                    if (fallbackToInterstitialWhenUnavailable) showRewardedFallbackInterstitial(wasPlayingBeforeRewarded, message)
                    else showRewardedUnavailableNotice(wasPlayingBeforeRewarded, message)
                }
            }
        }
    }

    /** 无激励广告资源时直接在内容层展示说明，不再触发会遮挡说明的插屏回退。 */
internal fun DramaAppActivity.showRewardedUnavailableNotice(shouldResume: Boolean, message: String) {
        fullscreenAdInProgress = false
        episodeTransitionInProgress = false
        resumeAfterFullscreenAd = shouldResume
        resumeAfterFullscreenAdIfNeeded()
        showBrandedChoiceDialog(t("ad_unavailable_title"), "$message\n\n${t("ad_unavailable_message")}", t("done"))
    }

    /** 激励中途退出或加载失败时，只在后台开关允许时回退一次插屏；不会授予原激励权益。 */
internal fun DramaAppActivity.showRewardedFallbackInterstitial(shouldResume: Boolean, message: String) {
        fullscreenAdInProgress = false
        episodeTransitionInProgress = false
        if (rewardsSnapshot?.interstitial?.rewardedFallbackEnabled != true) {
            resumeAfterFullscreenAd = shouldResume; resumeAfterFullscreenAdIfNeeded(); toast(message); return
        }
        showPlaybackActionInterstitial(
            placementId = "int_rewarded_fallback", episodeId = selectedEpisode()?.id,
            resumePlaybackAfter = shouldResume,
            onShown = { toast(message) },
            onUnavailable = { reason -> logStatus("激励回退插屏未展示：$reason"); toast(message) },
        )
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
        // TopOn temporarily enables decor fitting for the advertisement safe band. Restore the player canvas
        // before recalculating controls, otherwise the status-bar inset is applied twice after an ad closes.
        if (playerShown && !playerInImmersiveMode) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        }
        root.requestLayout()
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 所有播放行为插屏统一入口。剧中、拖动、倍速及激励回退按每集上限互斥；
     * 横向切剧使用独立频控，不占用当前分集的插屏额度。
     */
internal fun DramaAppActivity.showPlaybackActionInterstitial(
        placementId: String,
        episodeId: String? = selectedEpisode()?.id,
        countTowardEpisodeLimit: Boolean = true,
        resumePlaybackAfter: Boolean? = null,
        onShown: () -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        val policy = rewardsSnapshot?.interstitial
        val episodeKey = episodeId?.let { "${selectedDrama?.id}:$it" }
        val isSessionActionPlacement = placementId in setOf("int_seek", "int_speed", "int_episode_midroll")
        if (isSessionActionPlacement && episodeKey != null && episodeKey in actionInterstitialShownEpisodeKeys) {
            onUnavailable("本次观看已展示插屏"); return
        }
        if (countTowardEpisodeLimit && policy != null && policy.episodeInterstitialLimit > 0 && episodeKey != null && (interstitialCountByEpisodeKey[episodeKey] ?: 0) >= policy.episodeInterstitialLimit) {
            onUnavailable("本集插屏次数已达上限"); return
        }
        if (fullscreenAdInProgress) { onUnavailable("广告正在展示"); return }
        if (consentStore.read() != AdConsent.GRANTED) { onUnavailable("请先在广告与隐私中启用广告"); return }
        val shouldResume = resumePlaybackAfter ?: (exoPlayer?.isPlaying == true)
        fullscreenAdInProgress = true
        pauseForFullscreenAd()
        resumeAfterFullscreenAd = shouldResume
        topOnAds.showInterstitialForPlaybackAction(this) { result -> runOnUiThread {
            fullscreenAdInProgress = false
            when (result) {
                TopOnAdsManager.FullscreenResult.Shown -> {
                    resumeAfterFullscreenAdIfNeeded()
                    val settled = {
                        if (isSessionActionPlacement && episodeKey != null) actionInterstitialShownEpisodeKeys.add(episodeKey)
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

internal fun DramaAppActivity.showGemIncomeDetails() {
        val snapshot = rewardsSnapshot
        if (snapshot == null) { toast(t("wallet_syncing")); return }
        val wallet = snapshot.wallet
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(12)); background = roundedDrawable(COLOR_CARD, 24) }
        detail.addView(TextView(this@showGemIncomeDetails).apply { text = t("gem_income_details"); textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        detail.addView(TextView(this@showGemIncomeDetails).apply { text = t("gem_income_summary", formatGems(wallet.coins), formatGems(wallet.totalGemsEarned)); textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(5), 0, dp(12)) })
        val entries = snapshot.ledger.filter { it.gemsDelta > 0 }
        if (entries.isEmpty()) detail.addView(TextView(this@showGemIncomeDetails).apply { text = t("ledger_empty"); textSize = 14f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), dp(0), dp(14)) })
        else entries.forEach { entry ->
            detail.addView(LinearLayout(this@showGemIncomeDetails).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
                addView(ImageView(this@showGemIncomeDetails).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(this@showGemIncomeDetails).apply { text = ledgerTitle(entry.type); textSize = 14f; setTextColor(COLOR_TEXT_PRIMARY); setPadding(dp(8), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@showGemIncomeDetails).apply { text = "+${formatGems(entry.gemsDelta)}"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL) })
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
        showBrandedChoiceDialog(t("gem_income_details"), message, t("done"))
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
