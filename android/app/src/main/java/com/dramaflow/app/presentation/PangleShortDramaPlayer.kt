package com.dramaflow.app.presentation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.bytedance.sdk.shortplay.api.EpisodeData
import com.bytedance.sdk.shortplay.api.PSSDK
import com.bytedance.sdk.shortplay.api.ShortPlayFragment
import com.ss.ttvideoengine.Resolution
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
    previewOnly: Boolean = false,
) {
    releasePlayer()
    if (previewOnly) configureCatalogWindow() else configurePlayerWindow()
    supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG)?.let { fragment ->
        supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    selectedDrama = catalogDrama
    sdkDiscoverPreviewActive = previewOnly
    playerReturnTab = if (previewOnly) DramaAppActivity.MainTab.DISCOVER else activeTab
    currentEpisodeIndex = startIndex.coerceIn(0, catalogDrama.episodes.lastIndex.coerceAtLeast(0))
    sdkPlayerShown = true
    sdkPlaybackActive = false
    resumeSdkPlaybackWhenForeground = false
    playerProgressSeconds = resumePositionSeconds.coerceAtLeast(0)
    playerDurationSeconds = 0
    playerIsSeeking = false
    playerIsPaused = false
    playerSpeed = 1.0f
    resetMidrollInterstitialSession()
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
        onItemSelected = { episodeIndex -> onSdkItemSelected(episodeIndex) },
        onPlaybackStateChanged = { episodeIndex, state -> onSdkPlaybackStateChanged(episodeIndex, state) },
        onEpisodeCompleted = ::onSdkEpisodeCompleted,
        onPlaybackProgress = { episodeIndex, progress, duration -> onSdkPlaybackProgress(episodeIndex, progress, duration) },
        onOverScroll = ::onSdkOverScroll,
        onVideoTap = { runOnUiThread { if (System.currentTimeMillis() >= ignoreVideoTapsUntilMillis) togglePlaybackFromVideoTap() } },
        onPlaybackFailure = { message -> onSdkPlaybackFailure(message) },
        onImmersiveChanged = { entering -> runOnUiThread { if (entering) enterImmersivePlayerMode(announce = false) else exitImmersivePlayerMode() } },
        onVideoInfoFetched = { episodeIndex, summary -> onSdkVideoInfoFetched(episodeIndex, summary) },
    )
    supportFragmentManager.beginTransaction()
        .replace(container.id, fragment, SDK_PLAYER_FRAGMENT_TAG)
        .commitNowAllowingStateLoss()
    // The provider view can restore system navigation during its own asynchronous initialization.
    // Re-apply the host window policy after that work settles so the gesture handle does not sit
    // beneath JoyeDrama's bottom dock.
    root.postDelayed({
        if (sdkPlayerShown && !playerInImmersiveMode) {
            if (previewOnly) configureCatalogWindow() else configurePlayerWindow()
        }
    }, 180L)

    // Preload is advisory: it reduces the chance of a first-frame stall, while a provider
    // network or entitlement failure still follows the ordinary playback error path.
    root.post {
        if (!sdkPlayerShown || supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG) !== fragment) return@post
        fragment.preLoadVideo(object : PSSDK.ActionResultListener {
            override fun onSuccess() {
                logStatus("PSSDK 首集预加载完成")
            }
            override fun onFail(error: PSSDK.ErrorInfo?) {
                logStatus("PSSDK 首集预加载未完成：${error?.code ?: -1} ${error?.msg.orEmpty()}")
            }
        })
    }

    if (previewOnly) {
        root.addView(createDiscoverPreviewTitle(catalogDrama), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginStart = dp(18)
            marginEnd = dp(18)
            bottomMargin = dp(82)
        })
        // Discover is browseable rather than immersive: keep normal app navigation visible.
        root.addView(createBottomNavigation(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM))
    } else {
        // PSSDK remains the actual player. These are JoyeDrama controls mapped only to public APIs.
        // Retain references and explicitly raise them after the Fragment completes its asynchronous
        // view creation; otherwise a provider-owned video surface can visually cover the lower host
        // controls on some devices.
        val topBar = createPlayerTopBar()
        playerTopChrome = topBar
        val controlDock = createSdkPlayerControlDock()
        playerControlsPanel = controlDock
        val controlsReveal = transparentPlayerTextButton(t("show_controls"), t("show_controls")) { setPlayerControlsVisible(true) }
        controlsReveal.visibility = View.GONE
        playerControlsRevealButton = controlsReveal
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
        root.addView(controlsReveal, FrameLayout.LayoutParams(dp(86), dp(38), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(14)
            bottomMargin = dp(16)
        })
        root.post {
            topBar.bringToFront()
            controlDock.bringToFront()
            controlsReveal.bringToFront()
            rewardWheel.bringToFront()
            ViewCompat.requestApplyInsets(topBar)
            root.invalidate()
        }
    }
    if (!previewOnly) showPlayerGestureCoachmarkIfNeeded()
}

/** Opens the provider's recommended first episode while preserving its native vertical episode browsing. */
internal fun DramaAppActivity.showSdkDiscoverPreview(drama: LocalDrama) {
    val card = sdkDramaCardsByCatalogId[drama.id] ?: run {
        logStatus("PSSDK 推荐预览缺少内容映射：${drama.id}")
        return
    }
    showSdkDramaPlayer(drama, card, startIndex = 0, resumePositionSeconds = 0, previewOnly = true)
}

private fun DramaAppActivity.createDiscoverPreviewTitle(drama: LocalDrama): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(11), dp(16), dp(11))
    background = gradientDrawable(intArrayOf(0xE611271B.toInt(), 0xE60D1E14.toInt()), 18)
    isClickable = true
    contentDescription = "${drama.title} ${t("discover_preview_open")}".trim()
    addView(TextView(this@createDiscoverPreviewTitle).apply {
        text = drama.title
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(Color.WHITE)
    })
    addView(TextView(this@createDiscoverPreviewTitle).apply {
        text = "${t("discover_preview")} · ${t("discover_preview_open")}"
        textSize = 12f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        // Keep this supporting copy neutral so a continuously swiped title never reads as a
        // green action affordance beside the warm product palette.
        setTextColor(0xFF8D8D93.toInt())
        setPadding(0, dp(4), 0, 0)
    })
    setOnClickListener { openDiscoverPreviewAsFullPlayer() }
}

private fun DramaAppActivity.openDiscoverPreviewAsFullPlayer() {
    if (!sdkDiscoverPreviewActive) return
    val drama = selectedDrama ?: return
    val card = sdkDramaCardsByCatalogId[drama.id] ?: return
    showSdkDramaPlayer(
        catalogDrama = drama,
        card = card,
        startIndex = currentEpisodeIndex,
        resumePositionSeconds = playerProgressSeconds,
        previewOnly = false,
    )
}

private fun DramaAppActivity.onSdkItemSelected(episodeIndex: Int) {
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    updateEpisodeStatus()
    logStatus("PSSDK 选择分集：${episodeIndex + 1}")
}

private fun DramaAppActivity.onSdkPlaybackStateChanged(episodeIndex: Int, state: Int) {
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    val playing = state == PSSDK.PLAYBACK_STATE_PLAY
    sdkPlaybackActive = playing
    playerIsPaused = !playing
    updatePauseOverlayVisibility(!playing)
}

internal fun DramaAppActivity.showSdkResolutionPicker() {
    val fragment = currentSdkPlayerFragment() ?: return
    // Resolution is part of PSSDK's public ShortPlayFragment contract. Calling it directly
    // keeps the compiler checking this integration rather than silently failing through reflection.
    showBrandedOptionsDialog(
        title = t("resolution"),
        items = listOf(t("resolution_data_saver"), t("resolution_hd")),
    ) { which ->
        val selected = if (which == 0) Resolution.Standard else Resolution.High
        runCatching { fragment.setResolution(selected) }
            .onSuccess { toast(if (which == 0) t("resolution_data_saver_set") else t("resolution_hd_set")) }
            .onFailure { toast(t("resolution_unavailable")) }
    }
}

private fun DramaAppActivity.showPlayerGestureCoachmarkIfNeeded() {
    if (getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("player_gesture_coachmark_seen", false)) return
    val coachmark = FrameLayout(this).apply {
        background = android.graphics.drawable.ColorDrawable(0xAE000000.toInt())
        isClickable = true
        setOnClickListener { finishPlayerGestureCoachmark(this) }
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(26), dp(22), dp(26), dp(20))
        background = gradientDrawable(intArrayOf(0xF312271B.toInt(), 0xF20D1E14.toInt()), 26)
        addView(TextView(this@showPlayerGestureCoachmarkIfNeeded).apply {
            text = "☝"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(COLOR_SUNSET)
        }.also { hand ->
            ObjectAnimator.ofFloat(hand, View.TRANSLATION_Y, dp(14).toFloat(), -dp(16).toFloat(), dp(14).toFloat()).apply {
                duration = 1_160L
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        addView(TextView(this@showPlayerGestureCoachmarkIfNeeded).apply {
            text = "↕     ↔"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(COLOR_PURPLE)
        })
        addView(TextView(this@showPlayerGestureCoachmarkIfNeeded).apply {
            text = t("player_gesture_coachmark_title")
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(9), 0, 0)
        })
        addView(TextView(this@showPlayerGestureCoachmarkIfNeeded).apply {
            text = t("player_gesture_coachmark")
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xD9FFFFFF.toInt())
            setPadding(0, dp(8), 0, 0)
        })
    }
    coachmark.addView(panel, FrameLayout.LayoutParams(dp(292), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    root.addView(coachmark, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    coachmark.alpha = 0f
    coachmark.animate().alpha(1f).setDuration(260L).start()
    root.postDelayed({ if (coachmark.parent != null) finishPlayerGestureCoachmark(coachmark) }, 5_000L)
}

private fun DramaAppActivity.finishPlayerGestureCoachmark(view: View) {
    (view.parent as? ViewGroup)?.removeView(view)
    getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("player_gesture_coachmark_seen", true).apply()
}

/** A small host-owned dock with the single authoritative player progress presentation. */
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
    // Episode transition restarts the watch clock but retains the session-level unavailable
    // attempt cap so an ad-network outage cannot prompt on every short episode.
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
    if (direction == 0 || !sdkPlayerShown) return
    val now = android.os.SystemClock.elapsedRealtime()
    if (sdkDiscoverPreviewActive) {
        // PSSDK does not consistently emit a boundary callback for both vertical directions across
        // devices. Prefer the raw gesture direction captured by the host, fall back to its callback
        // direction, and ignore the callback if the Activity fallback just replaced this Fragment.
        if (now - pssdkLastPreviewDramaSwitchAt < 800L) return
        val capturedDirectionIsFresh = now - pssdkLastVerticalSwipeAt < 1_300L
        val forward = if (capturedDirectionIsFresh) pssdkLastVerticalSwipeWasUp else direction > 0
        pssdkLastPreviewDramaSwitchAt = now
        switchDramaBySwipe(forward = forward, countTowardInterstitial = forward)
        return
    }
    // Normal player vertical gestures are provider-owned. At a title boundary the callback is the
    // safe point to move to a neighbouring JoyeDrama title while keeping existing ad policy intact.
    switchDramaBySwipe(forward = direction > 0)
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
    sdkDiscoverPreviewActive = false
    sdkPlaybackActive = false
    resumeSdkPlaybackWhenForeground = false
    sdkPlayerContainerId = View.NO_ID
}


/** Handles a provider playback error without falling back to an opaque SDK default dialog. */
private fun DramaAppActivity.onSdkPlaybackFailure(message: String): Boolean {
    sdkPlaybackActive = false
    playerIsPaused = true
    logStatus("PSSDK playback failed: $message")
    runOnUiThread {
        if (!sdkPlayerShown || isFinishing || isDestroyed) return@runOnUiThread
        showBrandedChoiceDialog(
            title = t("sdk_playback_unavailable_title"),
            message = t("sdk_playback_unavailable_message", message),
            primaryLabel = t("retry"),
            onPrimary = { currentSdkPlayerFragment()?.startPlayIndex(currentEpisodeIndex) },
            secondaryLabel = t("back"),
            onSecondary = ::showCatalog,
        )
    }
    return true
}

/** Captures provider readiness metadata for diagnostics without coupling reward flow to SDK internals. */
private fun DramaAppActivity.onSdkVideoInfoFetched(episodeIndex: Int, summary: String) {
    logStatus("PSSDK 视频信息就绪：第 ${episodeIndex + 1} 集${summary.takeIf { it.isNotBlank() }?.let { "，$it" }.orEmpty()}")
}
