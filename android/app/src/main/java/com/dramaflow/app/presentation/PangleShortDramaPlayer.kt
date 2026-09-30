package com.dramaflow.app.presentation

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import com.bytedance.sdk.shortplay.api.PSSDK
import com.bytedance.sdk.shortplay.api.EpisodeData
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama

private const val SDK_PLAYER_FRAGMENT_TAG = "joyedrama.pssdk.player"

/**
 * Hosts the partner-owned PSSDK player inside the existing app Activity.
 *
 * The SDK owns catalog media URLs and playback. JoyeDrama retains only the shared entitlement,
 * anonymous rewards ledger, and navigation decisions; it never proxies or stores SDK video URLs.
 */
internal fun DramaAppActivity.showSdkDramaPlayer(
    catalogDrama: LocalDrama,
    card: ShortDramaSdkManager.DramaCard,
    startIndex: Int,
) {
    releasePlayer()
    // The provider fragment must share the same edge-to-edge window policy as the legacy player;
    // otherwise the status-bar inset leaves the JoyeDrama back/wallet controls visibly too low.
    configurePlayerWindow()
    supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG)?.let { fragment ->
        supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    selectedDrama = catalogDrama
    currentEpisodeIndex = startIndex.coerceIn(0, catalogDrama.episodes.lastIndex.coerceAtLeast(0))
    sdkPlayerShown = true
    sdkPlaybackActive = false
    contentWatchSecondsSinceMidroll = 0
    midrollInterstitialShownInSession = false
    lastMidrollSamplePositionSeconds = -1
    playerShown = false
    root.removeAllViews()
    root.setBackgroundColor(Color.BLACK)

    val container = FrameLayout(this).apply { id = View.generateViewId() }
    sdkPlayerContainerId = container.id
    root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val fragment = shortDramaSdk.createDetailFragment(
        card = card,
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
    // The provider owns video controls; JoyeDrama retains wallet, reward and savings-jar entry.
    root.addView(createPlayerTopBar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132), Gravity.TOP))
    root.addView(createFloatingRewardWheel(compact = true), FrameLayout.LayoutParams(dp(112), dp(96), Gravity.END or Gravity.BOTTOM).apply {
        marginEnd = dp(12)
        bottomMargin = dp(166)
    })
}

private fun DramaAppActivity.onSdkEpisodeStarted(episodeIndex: Int, episode: EpisodeData?) {
    sdkPlaybackActive = true
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    // Keep the local display model only as state; the PSSDK remains the authority for media info.
    episode?.let { logStatus("PSSDK 开始播放分集：${episodeIndex + 1}") }
}

private fun DramaAppActivity.onSdkEpisodeCompleted(episodeIndex: Int) {
    val safeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    val key = episodeKey(safeIndex)
    if (!completedEpisodeKeys.add(key)) return
    currentEpisodeIndex = safeIndex
    // Auto-play is disabled in the PSSDK configuration so JoyeDrama can run the exact same
    // completion, reward and interstitial rules before selecting the next provider episode.
    recordCompletedEpisodeForRewards(safeIndex) {
        startNextSdkEpisodeAfterCompletion(safeIndex)
    }
}

private fun DramaAppActivity.onSdkPlaybackProgress(episodeIndex: Int, rawProgress: Int, rawDuration: Int) {
    if (!sdkPlayerShown || fullscreenAdInProgress || fullscreenAdRequestInFlight) return
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    // PSSDK currently reports seconds; normalize defensively for integrations returning millis.
    val progress = if (rawDuration > 10_000) rawProgress.coerceAtLeast(0) / 1_000 else rawProgress.coerceAtLeast(0)
    val duration = if (rawDuration > 10_000) rawDuration.coerceAtLeast(0) / 1_000 else rawDuration.coerceAtLeast(0)
    sdkPlaybackActive = true
    playerIsPaused = false
    updatePlayerProgress(progress, duration)
    persistPlaybackProgressIfNeeded(progress)
    requestMidEpisodeInterstitialIfEligible(progress)
}

private fun DramaAppActivity.onSdkOverScroll(direction: Int) {
    // PSSDK keeps ordinary in-video swipes. It tells us only when the viewer reaches a feed
    // boundary, which is the safe point to hand off to the neighbouring JoyeDrama title.
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
    if (needsUnlock(nextIndex)) {
        val fragment = supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG) as? com.bytedance.sdk.shortplay.api.ShortPlayFragment
        requestSdkEpisodeUnlock(nextIndex, object : PSSDK.ShortPlayBlockResultListener {
            override fun onShortPlayUnlocked() { fragment?.startPlayIndex(nextIndex) }
        })
    } else {
        (supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG) as? com.bytedance.sdk.shortplay.api.ShortPlayFragment)?.startPlayIndex(nextIndex)
    }
}

private fun DramaAppActivity.requestSdkEpisodeUnlock(
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
