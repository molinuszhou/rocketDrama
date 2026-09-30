package com.dramaflow.app.presentation

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.bytedance.sdk.shortplay.api.EpisodeData
import com.bytedance.sdk.shortplay.api.PSSDK
import com.bytedance.sdk.shortplay.api.ShortPlayFragment
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama

private const val SDK_PLAYER_FRAGMENT_TAG = "joyedrama.pssdk.player"

/**
 * Hosts Pangle/PSSDK's provider-owned media player.
 *
 * PSSDK does not expose drama media URLs. JoyeDrama therefore keeps the SDK as the media layer
 * and uses its public Fragment APIs for episode, speed, seek, lifecycle, reward, and ad control.
 */
internal fun DramaAppActivity.showSdkDramaPlayer(
    catalogDrama: LocalDrama,
    card: ShortDramaSdkManager.DramaCard,
    startIndex: Int,
    resumePositionSeconds: Int = 0,
) {
    releasePlayer()
    configurePlayerWindow()
    supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG)?.let { fragment ->
        supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    selectedDrama = catalogDrama
    currentEpisodeIndex = startIndex.coerceIn(0, catalogDrama.episodes.lastIndex.coerceAtLeast(0))
    sdkPlayerShown = true
    sdkPlaybackActive = false
    resumeSdkPlaybackWhenForeground = false
    playerProgressSeconds = resumePositionSeconds.coerceAtLeast(0)
    playerDurationSeconds = 0
    playerIsSeeking = false
    playerIsPaused = false
    playerSpeed = 1.0f
    contentWatchSecondsSinceMidroll = 0
    midrollInterstitialShownInSession = false
    lastMidrollSamplePositionSeconds = -1
    // This is a real JoyeDrama playback surface even though PSSDK owns the media engine.
    // Keeping the shared player flag true prevents a reward snapshot from rebuilding the
    // catalogue over the provider Fragment and permits shared history/reward chrome.
    playerShown = true
    root.removeAllViews()
    root.setBackgroundColor(Color.BLACK)

    val container = FrameLayout(this).apply { id = View.generateViewId() }
    sdkPlayerContainerId = container.id
    root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val fragment = shortDramaSdk.createDetailFragment(
        card = card,
        startEpisodeIndex = currentEpisodeIndex,
        startPositionSeconds = playerProgressSeconds,
        onClose = { runOnUiThread { showCatalog() } },
        needsEpisodeUnlock = ::needsUnlock,
        requestEpisodeUnlock = ::requestSdkEpisodeUnlock,
        onEpisodeStarted = { episodeIndex, episode -> onSdkEpisodeStarted(episodeIndex, episode) },
        onEpisodeCompleted = ::onSdkEpisodeCompleted,
        onPlaybackProgress = { episodeIndex, progress, duration -> onSdkPlaybackProgress(episodeIndex, progress, duration) },
        onOverScroll = ::onSdkOverScroll,
    )
    supportFragmentManager.beginTransaction()
        .replace(container.id, fragment, SDK_PLAYER_FRAGMENT_TAG)
        .commitNowAllowingStateLoss()

    // PSSDK remains the actual player. These are JoyeDrama controls mapped only to public APIs.
    // Retain references and explicitly raise them after the Fragment completes its asynchronous
    // view creation; otherwise a provider-owned video surface can visually cover the lower host
    // controls on some devices.
    val topBar = createPlayerTopBar()
    val controlDock = createSdkPlayerControlDock()
    val rewardWheel = createFloatingRewardWheel(compact = true)
    root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132), Gravity.TOP))
    root.addView(controlDock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(106), Gravity.BOTTOM).apply {
        marginStart = dp(12)
        marginEnd = dp(12)
        bottomMargin = dp(6)
    })
    root.addView(rewardWheel, FrameLayout.LayoutParams(dp(112), dp(96), Gravity.END or Gravity.BOTTOM).apply {
        marginEnd = 0
        bottomMargin = dp(166)
    })
    root.post {
        topBar.bringToFront()
        controlDock.bringToFront()
        rewardWheel.bringToFront()
        root.invalidate()
    }
}

/** A small host-owned dock; PSSDK's own progress presentation is still retained underneath. */
private fun DramaAppActivity.createSdkPlayerControlDock(): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(10), dp(4), dp(10), dp(6))
    background = roundedDrawable(0xA6000000.toInt(), 18)
    addView(createPlayerProgressControls(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
    addView(createPlayerActionRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
}

private fun DramaAppActivity.onSdkEpisodeStarted(episodeIndex: Int, episode: EpisodeData?) {
    sdkPlaybackActive = true
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    playerProgressSeconds = 0
    playerDurationSeconds = 0
    lastPersistedPlaybackSecond = -1
    contentWatchSecondsSinceMidroll = 0
    midrollInterstitialShownInSession = false
    midrollInterstitialRequestInFlight = false
    lastMidrollSamplePositionSeconds = -1
    playerIsPaused = false
    updateEpisodeStatus()
    updatePlayerProgress(0, 0)
    persistPlaybackProgress(0)
    episode?.let { logStatus("PSSDK 开始播放分集：${episodeIndex + 1}") }
}

private fun DramaAppActivity.onSdkEpisodeCompleted(episodeIndex: Int) {
    val safeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    val key = episodeKey(safeIndex)
    if (!completedEpisodeKeys.add(key)) return
    currentEpisodeIndex = safeIndex
    persistNextEpisodeResume(safeIndex)
    // PSSDK owns the actual transition and preloading. Server reward confirmation must not
    // block the next episode or race a second host-side startPlayIndex call.
    recordCompletedEpisodeForRewards(safeIndex) {}
}

private fun DramaAppActivity.onSdkPlaybackProgress(episodeIndex: Int, rawProgress: Int, rawDuration: Int) {
    if (!sdkPlayerShown || fullscreenAdInProgress || fullscreenAdRequestInFlight) return
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    // PSSDK 1.8 reports seconds. Keep this defensive normalization for provider updates returning ms.
    val progress = if (rawDuration > 10_000) rawProgress.coerceAtLeast(0) / 1_000 else rawProgress.coerceAtLeast(0)
    val duration = if (rawDuration > 10_000) rawDuration.coerceAtLeast(0) / 1_000 else rawDuration.coerceAtLeast(0)
    sdkPlaybackActive = true
    playerIsPaused = false
    updatePlayerProgress(progress, duration)
    persistPlaybackProgressIfNeeded(progress)
    requestMidEpisodeInterstitialIfEligible(progress)
}

private fun DramaAppActivity.onSdkOverScroll(direction: Int) {
    // Let PSSDK own normal in-drama vertical gestures. A boundary callback is the safe point
    // to change to the neighbouring JoyeDrama title and apply the existing switch policy.
    if (direction != 0) switchDramaBySwipe(forward = direction > 0)
}

private fun DramaAppActivity.startNextSdkEpisodeAfterCompletion(completedIndex: Int) {
    if (!sdkPlayerShown) return
    val drama = selectedDrama ?: return
    val nextIndex = completedIndex + 1
    if (nextIndex >= drama.total.coerceAtLeast(1)) {
        switchDramaBySwipe(forward = true)
        return
    }
    playSdkEpisodeAt(nextIndex)
}

/** Uses only the PSSDK public episode-selection interface and preserves the existing unlock flow. */
internal fun DramaAppActivity.playSdkEpisodeAt(index: Int) {
    val drama = selectedDrama ?: return
    val safeIndex = index.coerceIn(0, drama.episodes.lastIndex.coerceAtLeast(0))
    val fragment = supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG) as? ShortPlayFragment ?: return
    if (needsUnlock(safeIndex)) {
        requestSdkEpisodeUnlock(safeIndex, object : PSSDK.ShortPlayBlockResultListener {
            override fun onShortPlayUnlocked() {
                fragment.startPlayIndex(safeIndex)
            }
        })
    } else {
        fragment.startPlayIndex(safeIndex)
    }
}

internal fun DramaAppActivity.currentSdkPlayerFragment(): ShortPlayFragment? =
    supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG) as? ShortPlayFragment

internal fun DramaAppActivity.requestSdkEpisodeUnlock(
    episodeIndex: Int,
    listener: PSSDK.ShortPlayBlockResultListener?,
) {
    val safeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    if (!needsUnlock(safeIndex)) {
        listener?.onShortPlayUnlocked()
        return
    }
    val episode = selectedDrama?.episodes?.getOrNull(safeIndex) ?: return
    if (fullscreenAdInProgress || fullscreenAdRequestInFlight) {
        toast(t("ad_loading"))
        return
    }
    episodeTransitionInProgress = true
    requestRewardedAction(
        placementId = "rv_episode_pass",
        targetId = episode.id,
        episodeIndex = safeIndex + 1,
        onSettled = { action ->
            val unlockCount = action.unlockEpisodeCount.coerceAtLeast(1)
            (safeIndex until (safeIndex + unlockCount).coerceAtMost(selectedDrama?.episodes?.size ?: safeIndex + 1))
                .forEach { sessionUnlockedEpisodeKeys.add(episodeKey(it)) }
            episodeTransitionInProgress = false
            listener?.onShortPlayUnlocked()
        },
        onSettlementUnavailable = { message ->
            episodeTransitionInProgress = false
            logStatus("PSSDK 分集解锁结算失败：$message")
            toast(t("unlock_settlement_pending_generic"))
        },
    )
}

internal fun DramaAppActivity.dismissSdkDramaPlayerIfShown() {
    if (!sdkPlayerShown) return
    supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG)?.let { fragment ->
        supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    sdkPlayerShown = false
    sdkPlaybackActive = false
    resumeSdkPlaybackWhenForeground = false
    sdkPlayerContainerId = View.NO_ID
}
