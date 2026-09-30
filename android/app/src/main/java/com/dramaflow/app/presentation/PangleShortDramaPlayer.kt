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
    supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG)?.let { fragment ->
        supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    selectedDrama = catalogDrama
    currentEpisodeIndex = startIndex.coerceIn(0, catalogDrama.episodes.lastIndex.coerceAtLeast(0))
    sdkPlayerShown = true
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
    )
    supportFragmentManager.beginTransaction()
        .replace(container.id, fragment, SDK_PLAYER_FRAGMENT_TAG)
        .commitNowAllowingStateLoss()
    if (!com.dramaflow.app.BuildConfig.WHITE_PACK) {
        // The provider owns media controls; JoyeDrama keeps only the existing wallet/reward entry.
        root.addView(createPlayerTopBar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132), Gravity.TOP))
    }
}

private fun DramaAppActivity.onSdkEpisodeStarted(episodeIndex: Int, episode: EpisodeData?) {
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    // Keep the local display model only as state; the PSSDK remains the authority for media info.
    episode?.let { logStatus("PSSDK 开始播放分集：${episodeIndex + 1}") }
}

private fun DramaAppActivity.onSdkEpisodeCompleted(episodeIndex: Int) {
    val safeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    val key = episodeKey(safeIndex)
    if (!completedEpisodeKeys.add(key)) return
    currentEpisodeIndex = safeIndex
    recordCompletedEpisodeForRewards(safeIndex) { }
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
    sdkPlayerContainerId = View.NO_ID
}
