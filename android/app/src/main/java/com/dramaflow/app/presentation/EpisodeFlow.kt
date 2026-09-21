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

internal fun DramaAppActivity.toggleEpisodePicker() {
        episodePickerDialog?.let { active ->
            if (active.isShowing) {
                active.dismiss()
                return
            }
        }
        showEpisodePicker()
    }

internal fun DramaAppActivity.showEpisodePicker() {
        val drama = selectedDrama ?: return
        val sheet = AlertDialog.Builder(this).create()
        episodePickerDialog = sheet
        sheet.setCanceledOnTouchOutside(true)
        sheet.setOnDismissListener { if (episodePickerDialog === sheet) episodePickerDialog = null }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(22))
            background = topRoundedDrawable(COLOR_CARD, 26)
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = t("episodes")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "${t("collapse")} ⌄"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { sheet.dismiss() }
            isClickable = true
        })
        container.addView(titleRow)
        container.addView(TextView(this).apply {
            text = t("free_episode_message", rewardsSnapshot?.unlock?.freeEpisodesDefault ?: FREE_EPISODES)
            textSize = 13f
            setTextColor(Color.rgb(196, 181, 204))
            setPadding(0, dp(6), 0, dp(16))
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
        repeat(drama.total.coerceAtLeast(1)) { index ->
            val locked = needsUnlock(index)
            grid.addView(TextView(this).apply {
                text = if (locked) "🔒 ${index + 1}" else "${index + 1}"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedDrawable(if (index == currentEpisodeIndex) COLOR_ACCENT else COLOR_SURFACE, 12)
                setOnClickListener {
                    sheet.dismiss()
                    selectEpisode(index)
                }
                isClickable = true
            }, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = dp(44)
                columnSpec = android.widget.GridLayout.spec(index % 5, 1f)
                setMargins(if (index % 5 == 0) 0 else dp(5), 0, if (index % 5 == 4) 0 else dp(5), dp(8))
            })
        }
        val gridScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        container.addView(gridScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390)))
        sheet.setView(container)
        sheet.show()
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

internal fun DramaAppActivity.selectEpisode(index: Int) {
        if (playerShown && index != currentEpisodeIndex) persistPlaybackProgress()
        val drama = selectedDrama ?: return
        val episode = drama.episodes.getOrNull(index) ?: run {
            toast("第 ${index + 1} 集信息不存在")
            return
        }
        if (needsUnlock(index)) {
            unlockEpisodeThenAutoPlay(index)
            return
        }
        completedEpisodeKeys.remove(episodeKey(index))
        // A replay is a new viewing session: timing and per-episode ad capacity must not inherit prior runs.
        midEpisodeInterstitialRequestedEpisodeKeys.remove(episodeKey(index))
        interstitialRequestedEpisodeKeys.remove(episodeKey(index))
        actionInterstitialShownEpisodeKeys.remove("${selectedDrama?.id}:${episode.id}")
        interstitialCountByEpisodeKey.remove("${selectedDrama?.id}:${episode.id}")
        episodePlaybackStartedAtElapsedMillis = 0L
        episodePlaybackPausedAtElapsedMillis = 0L
        episodePlaybackPausedDurationMillis = 0L
        lastMidrollSamplePositionSeconds = -1
        exoPlayer?.pause()
        currentEpisodeIndex = index
        refreshFloatingRewardWheels()
        playerProgressSeconds = 0
        playerDurationSeconds = episode.durationSeconds
        playerIsSeeking = false
        playerIsPaused = false
        playerProgressSeekBar?.isEnabled = true
        updateEpisodeStatus()
        updatePlayerProgress(0, playerDurationSeconds)
        localDramaApi.createPlaybackSession(
            baseUrl = localContentBaseUrl(),
            episodeId = episode.id,
            region = CONTENT_REGION,
            userId = anonymousInstallationId(),
            deviceId = anonymousInstallationId(),
            onSuccess = { session -> runOnUiThread {
                if (selectedEpisode()?.id != session.episodeId) return@runOnUiThread
                val player = exoPlayer ?: return@runOnUiThread
                player.setMediaItem(MediaItem.fromUri(session.streamUrl))
                player.setPlaybackSpeed(playerSpeed)
                player.volume = if (playerMuted) 0f else 1f
                player.prepare()
                val resumeAtSeconds = pendingResumePositionSeconds
                if (resumeAtSeconds > 0) player.seekTo(resumeAtSeconds * 1_000L)
                pendingResumePositionSeconds = 0
                player.play()
                episodeTransitionInProgress = false
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } },
            onFailure = { message -> runOnUiThread {
                episodeTransitionInProgress = false
                logStatus("播放会话创建失败：$message")
                toast("播放地址获取失败：$message")
            } },
        )
    }

internal fun DramaAppActivity.selectedEpisode(): LocalEpisode? = selectedDrama?.episodes?.getOrNull(currentEpisodeIndex)

internal fun DramaAppActivity.playNextEpisode() {
        startNextEpisodeAutomatically(currentEpisodeIndex, userInitiated = true)
    }

internal fun DramaAppActivity.startNextEpisodeAutomatically(completedIndex: Int, userInitiated: Boolean = false) {
        if (episodeTransitionInProgress) return
        val drama = selectedDrama ?: return
        val nextIndex = completedIndex + 1
        if (nextIndex >= drama.total.coerceAtLeast(1)) {
            if (userInitiated) switchDramaBySwipe(forward = true) else toast("本剧已播放完成")
            return
        }
        if (needsUnlock(nextIndex)) {
            unlockEpisodeThenAutoPlay(nextIndex)
        } else {
            episodeTransitionInProgress = true
            if (!userInitiated) toast("正在进入第 ${nextIndex + 1} 集")
            selectEpisode(nextIndex)
        }
    }

    /** 免费集数用完后由用户明确确认；优先展示激励广告解锁。 */
internal fun DramaAppActivity.unlockEpisodeThenAutoPlay(
    index: Int,
    onUnlocked: () -> Unit = {},
    onDeclined: () -> Unit = {},
) {
        if (episodeTransitionInProgress) return
        selectedDrama?.episodes?.getOrNull(index) ?: return
        val unlockCount = rewardsSnapshot?.unlock?.episodesPerRewardedUnlock ?: 1
        showBrandedChoiceDialog(
            title = t("free_episodes_used"),
            message = if (unlockCount > 1) t("unlock_episode_bundle_message", index + 1, unlockCount)
                else t("unlock_episode_message", index + 1),
            primaryLabel = t("unlock_with_ad"),
            onPrimary = { startEpisodeUnlockAfterConfirmation(index, onUnlocked) },
            secondaryLabel = t("later"),
            onSecondary = {
                episodeTransitionInProgress = false
                exoPlayer?.pause()
                playerIsPaused = true
                updatePauseOverlayVisibility(true)
                recordRewardedSkipThenMaybeShowInterstitial(false, "episode_unlock_declined")
                onDeclined()
            },
        )
    }

    /** 用户确认后完整观看激励广告；服务端结算成功后才向内容服务申请本集解锁并自动播放。 */
internal fun DramaAppActivity.startEpisodeUnlockAfterConfirmation(index: Int, onUnlocked: () -> Unit = {}) {
        val episode = selectedDrama?.episodes?.getOrNull(index) ?: run {
            episodeTransitionInProgress = false
            return
        }
        if (rewardsSnapshot != null && !rewardsSnapshot!!.unlock.nextEpisodeRewardedEnabled) {
            episodeTransitionInProgress = false
            toast("下一集激励广告解锁当前未启用")
            return
        }
        episodeTransitionInProgress = true
        requestRewardedAction(
            placementId = "rv_episode_pass",
            targetId = episode.id,
            episodeIndex = index + 1,
            onSettled = { action ->
                applyRewardsSnapshot(action.snapshot)
                settleEpisodeUnlockAfterAd(index, unlockMethod = "AD_REWARDED", unlockCount = action.unlockEpisodeCount) {
                    selectEpisode(index)
                    onUnlocked()
                }
            },
            onSettlementUnavailable = { message ->
                episodeTransitionInProgress = false
                resumeAfterFullscreenAdIfNeeded()
                toast("广告已完整观看，但解锁结算暂未同步：$message")
            },
        )
    }

internal fun DramaAppActivity.handleEpisodeCompleted(index: Int) {
        val key = episodeKey(index)
        if (!completedEpisodeKeys.add(key)) return
        currentEpisodeIndex = index
        updateEpisodeStatus()
        updatePlayerProgress(playerDurationSeconds, playerDurationSeconds)
        persistNextEpisodeResume(index)
        recordCompletedEpisodeForRewards(index) {
            startNextEpisodeAutomatically(index)
        }
    }

internal fun DramaAppActivity.settleEpisodeUnlockAfterAd(
    index: Int,
    unlockMethod: String,
    unlockCount: Int = 1,
    onUnlocked: () -> Unit = {},
) {
        val episodes = selectedDrama?.episodes.orEmpty()
        val indexes = (index until (index + unlockCount.coerceAtLeast(1)).coerceAtMost(episodes.size)).toList()
        if (indexes.isEmpty()) {
            episodeTransitionInProgress = false
            return
        }

        fun finish(unlockedCount: Int) {
            indexes.take(unlockedCount).forEach { unlockedIndex -> sessionUnlockedEpisodeKeys.add(episodeKey(unlockedIndex)) }
            pendingUnlockIndex = -1
            updateEpisodeStatus()
            toast(if (unlockedCount > 1) "已连续解锁 $unlockedCount 集" else t("episode_unlocked", index + 1))
            onUnlocked()
        }

        fun unlockNext(position: Int) {
            val unlockedIndex = indexes[position]
            val episode = episodes[unlockedIndex]
            localDramaApi.unlockEpisode(
                baseUrl = localContentBaseUrl(),
                episodeId = episode.id,
                region = CONTENT_REGION,
                userId = anonymousInstallationId(),
                unlockMethod = unlockMethod,
                onSuccess = { runOnUiThread {
                    if (position + 1 < indexes.size) unlockNext(position + 1) else finish(indexes.size)
                } },
                onFailure = { message -> runOnUiThread {
                    // The rewarded placement has already settled. Preserve every content entitlement that was
                    // confirmed before a transient content-service error, and never block the first requested episode.
                    if (position > 0) {
                        logStatus("后续分集解锁未同步：$message")
                        finish(position)
                    } else {
                        episodeTransitionInProgress = false
                        if (unlockMethod == "AD_INTERSTITIAL" || unlockMethod == "AD_REWARDED") interstitialRequestedEpisodeKeys.remove(episodeKey(index))
                        logStatus("自有剧解锁失败：$message")
                        resumeAfterFullscreenAdIfNeeded()
                        toast("解锁服务暂不可用：$message")
                    }
                } },
            )
        }

        unlockNext(0)
    }

internal fun DramaAppActivity.unlockEpisodeForCurrentSession(index: Int, message: String) {
        sessionUnlockedEpisodeKeys.add(episodeKey(index))
        pendingUnlockIndex = -1
        updateEpisodeStatus()
        toast(message)
    }

internal fun DramaAppActivity.recordCompletedEpisodeForRewards(index: Int, onContinuePlayback: () -> Unit) {
        val drama = selectedDrama ?: run { onContinuePlayback(); return }
        val episode = drama.episodes.getOrNull(index) ?: run { onContinuePlayback(); return }
        val target = "${drama.id}:${episode.id}"
        if (!rewardWatchRecordedEpisodeKeys.add(target) || !rewardsApi.isConfigured) { onContinuePlayback(); return }
        rewardsApi.recordWatchEvent(
            userId = anonymousInstallationId(),
            dramaId = drama.id,
            episodeId = episode.id,
            validWatchSeconds = rewardsSnapshot?.unlock?.validWatchSeconds ?: 60,
            onSuccess = { reward -> runOnUiThread {
                applyRewardsSnapshot(reward.snapshot)
                if (reward.episodeRewardCoins > 0) {
                    toast(t("episode_reward_pending", reward.episodeRewardCoins))
                }
                // v1.6.4.7: random chests are replaced by the ad-gated watch-task redpacket line.
                onContinuePlayback()
            } },
            onFailure = { message -> runOnUiThread {
                logStatus("Watch reward sync failed: $message")
                onContinuePlayback()
            } },
        )
    }

/** A new chest never obscures the preferred next-episode unlock. It appears 15s after a successful unlock. */
internal fun DramaAppActivity.routeChestAfterEpisode(
    completedIndex: Int,
    createdCount: Int,
    availableCount: Int,
    onContinuePlayback: () -> Unit,
) {
    val nextIndex = completedIndex + 1
    val nextIsLocked = selectedDrama?.episodes?.indices?.contains(nextIndex) == true && needsUnlock(nextIndex)
    if (!nextIsLocked) {
        showRandomChestEarnedDialog(createdCount, availableCount, onContinuePlayback)
        return
    }
    unlockEpisodeThenAutoPlay(
        index = nextIndex,
        onUnlocked = {
            root.postDelayed({
                if (!isFinishing && !isDestroyed) showRandomChestEarnedDialog(createdCount, availableCount) {}
            }, 15_000L)
        },
        onDeclined = { showRandomChestEarnedDialog(createdCount, availableCount, onContinuePlayback) },
    )
}

internal fun DramaAppActivity.showRandomChestEarnedDialog(createdCount: Int, availableCount: Int, onContinuePlayback: () -> Unit) {
        val dialog = AlertDialog.Builder(this).create()
        dialog.setCancelable(false)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = gradientDrawable(intArrayOf(0xFFFFF0E6.toInt(), 0xFFFFFFFF.toInt()), 28)
        }
        val chestImage = ImageView(this).apply {
            setImageResource(com.dramaflow.app.R.drawable.wallet_money)
            contentDescription = t("chest")
            scaleX = 0.55f
            scaleY = 0.55f
            alpha = 0f
        }
        content.addView(chestImage, LinearLayout.LayoutParams(dp(126), dp(126)))
        content.addView(TextView(this).apply {
            text = if (createdCount > 0) t("chest_earned", createdCount) else t("open_chest")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        content.addView(TextView(this).apply {
            text = if (createdCount > 0) t("chest_earned_message") else "签到奖励已到账，打开宝箱领取额外宝石"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(8), 0, dp(18))
        })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(TextView(this).apply {
            text = t("continue_watching")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
            background = roundedDrawable(COLOR_SURFACE, 16)
            isClickable = true
            setOnClickListener { dialog.dismiss(); onContinuePlayback() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        actions.addView(TextView(this).apply {
            text = t("open_now")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(0xFFFFB72F.toInt(), 0xFFE45330.toInt()), 16)
            isClickable = true
            setOnClickListener { dialog.dismiss(); requestRandomChestOpen(onFinished = onContinuePlayback) }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setView(content)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        chestImage.animate().alpha(1f).scaleX(1.10f).scaleY(1.10f).setDuration(260L).withEndAction {
            chestImage.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        }.start()
    }

    /**
     * 暂停时仅在锁集阶段、达到最低有效播放时长且服务端允许时请求 Native 暂停广告。
     * 该卡片由应用提供“返回播放”按钮，因此用户可随时关闭，并从暂停前位置继续。
     */
internal fun DramaAppActivity.requestPauseInterstitialIfEligible() {
        val snapshot = rewardsSnapshot ?: return
        val policy = snapshot.interstitial
        if (!playerShown || fullscreenAdInProgress || pauseNativeAdInProgress) return
        if (currentEpisodeIndex + 1 <= snapshot.unlock.freeEpisodesDefault) return
        if (!policy.pauseAdEnabled || playerProgressSeconds < policy.pauseAdMinPlaySeconds) return
        if (consentStore.read() != AdConsent.GRANTED) return

        pauseNativeAdInProgress = true
        topOnAds.showPauseNativeAd(
            activity = this,
            overlayHost = root,
            onShown = {
                if (rewardsApi.isConfigured) {
                    rewardsApi.recordInterstitial(
                        anonymousInstallationId(),
                        "int_pause_native",
                        onSuccess = { updated -> runOnUiThread { applyRewardsSnapshot(updated) } },
                        onFailure = { message -> logStatus("暂停广告展示记录未写入：$message") },
                    )
                }
            },
            onClosed = {
                runOnUiThread {
                    pauseNativeAdInProgress = false
                    if (!isFinishing && !isDestroyed) {
                        playerIsPaused = true
                        updatePauseOverlayVisibility(true)
                    }
                }
            },
            onUnavailable = { message ->
                runOnUiThread {
                    pauseNativeAdInProgress = false
                    logStatus("暂停广告未展示：$message")
                    // 无填充或未配置 Native 广告位时，仍保持用户主动暂停状态，不强制恢复播放。
                }
            },
        )
    }

internal fun DramaAppActivity.needsUnlock(index: Int): Boolean {
        val drama = selectedDrama
        val episode = drama?.episodes?.getOrNull(index)
        val contentRequiresUnlock = if (episode != null) {
            episode.entitlement in setOf("AD_UNLOCK", "COIN_UNLOCK")
        } else {
            index >= (drama?.freeEpisodes ?: FREE_EPISODES)
        }
        val key = episodeKey(index)
        return contentRequiresUnlock && key !in unlockedEpisodeKeys && key !in sessionUnlockedEpisodeKeys
    }

internal fun DramaAppActivity.episodeKey(index: Int): String = "${selectedDrama?.id ?: 0}:${index + 1}"

internal fun DramaAppActivity.updateEpisodeStatus() {
        val drama = selectedDrama ?: return
        val lockText = if (needsUnlock(currentEpisodeIndex)) " · ${t("unlock_with_ad")}" else " · ${t("watchable")}"
        episodeStatusView?.text = t("episode_progress", currentEpisodeIndex + 1, drama.total.coerceAtLeast(1), lockText)
    }
