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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.bytedance.sdk.shortplay.api.EpisodeData
import com.bytedance.sdk.shortplay.api.PSSDK
import com.bytedance.sdk.shortplay.api.ShortPlayFragment
import com.ss.ttvideoengine.Resolution
import com.dramaflow.app.R
import com.dramaflow.app.content.PssdkEpisodeIndexMapper
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
    playbackServiceVerified: Boolean = false,
) {
    // Directory data can remain browsable during a provider playback outage, but creating the
    // provider Fragment without SUPPORT leaves users in a dead player surface. Re-check at every
    // new player entry because support may change with network/region while the app stays alive.
    if (!playbackServiceVerified) {
        shortDramaSdk.checkPlaybackService { status -> runOnUiThread {
            if (status.supported) {
                showSdkDramaPlayer(
                    catalogDrama = catalogDrama,
                    card = card,
                    startIndex = startIndex,
                    resumePositionSeconds = resumePositionSeconds,
                    previewOnly = previewOnly,
                    playbackServiceVerified = true,
                )
            } else if (!isFinishing && !isDestroyed) {
                logStatus("PSSDK 播放服务不可用：${status.reason}")
                showBrandedChoiceDialog(
                    title = t("sdk_playback_unavailable_title"),
                    message = t("sdk_playback_unavailable_message", status.reason),
                    primaryLabel = t("retry"),
                    onPrimary = {
                        showSdkDramaPlayer(
                            catalogDrama = catalogDrama,
                            card = card,
                            startIndex = startIndex,
                            resumePositionSeconds = resumePositionSeconds,
                            previewOnly = previewOnly,
                        )
                    },
                    secondaryLabel = t("back"),
                    onSecondary = ::showCatalog,
                )
            }
        } }
        return
    }
    releasePlayer()
    if (previewOnly) configureCatalogWindow() else configurePlayerWindow()
    supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG)?.let { fragment ->
        supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    selectedDrama = catalogDrama
    sdkDiscoverPreviewActive = previewOnly
    playerReturnTab = if (previewOnly) DramaAppActivity.MainTab.DISCOVER else activeTab
    currentEpisodeIndex = startIndex.coerceIn(0, catalogDrama.episodes.lastIndex.coerceAtLeast(0))
    val requestedResumeSeconds = resumePositionSeconds.coerceAtLeast(0)
    val restoringExternalAd = pendingExternalAdRestoreEpisodeIndex == currentEpisodeIndex &&
        (pendingExternalAdRestorePositionSeconds ?: 0) > 0
    pendingSdkResumeCheckpoint = if (!previewOnly && !restoringExternalAd && requestedResumeSeconds > 0) {
        SdkResumeCheckpoint(currentEpisodeIndex, requestedResumeSeconds)
    } else {
        null
    }
    pendingSdkResumeSeekApplied = false
    sdkPlayerShown = true
    sdkPlaybackActive = false
    resumeSdkPlaybackWhenForeground = false
    playerProgressSeconds = requestedResumeSeconds
    playerDurationSeconds = 0
    playerIsSeeking = false
    playerIsPaused = false
    playerControlsVisible = false
    playerControlsPinnedByUser = false
    playerSpeed = 1.0f
    resetMidrollInterstitialSession()
    // This is a real JoyeDrama playback surface even though PSSDK owns the media engine.
    // Keeping the shared player flag true prevents a reward snapshot from rebuilding the
    // catalogue over the provider Fragment and permits shared history/reward chrome.
    playerShown = true
    root.removeAllViews()
    root.setBackgroundColor(Color.BLACK)
    discoverPreviewProgressBar = null

    val container = FrameLayout(this).apply { id = View.generateViewId() }
    sdkPlayerContainerId = container.id
    root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
        // Discover only reserves the persistent Tab bar. Its host information panel overlays the
        // lower video area so the PSSDK viewport keeps its full, natural browsing height.
        if (previewOnly) bottomMargin = dp(BOTTOM_NAV_HEIGHT_DP)
    })

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
    // Bootstrap can settle an unlock before, during, or after this Fragment is recreated. The
    // durable target is consumed only after the public player controls are available.
    root.post {
        if (sdkPlayerShown && supportFragmentManager.findFragmentByTag(SDK_PLAYER_FRAGMENT_TAG) === fragment) {
            releaseSettledRewardedTargetIfPossible()
            releasePendingInterstitialIncentiveTargetIfPossible()
        }
    }
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
        // The title, synopsis and episode entry float over the lower edge of the provider video;
        // their translucent gradient protects readability without reducing video height.
        root.addView(
            createDiscoverDramaInfoPanel(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(152), Gravity.BOTTOM).apply {
                bottomMargin = dp(BOTTOM_NAV_HEIGHT_DP)
            },
        )
        root.addView(
            createDiscoverEpisodeFloatButton(),
            FrameLayout.LayoutParams(dp(82), dp(72), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(10)
                bottomMargin = dp(BOTTOM_NAV_HEIGHT_DP + 170)
            },
        )
        root.addView(createBottomNavigation(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BOTTOM_NAV_HEIGHT_DP), Gravity.BOTTOM))
    } else {
        // PSSDK remains the actual player. These are JoyeDrama controls mapped only to public APIs.
        // Retain references and explicitly raise them after the Fragment completes its asynchronous
        // view creation; otherwise a provider-owned video surface can visually cover the lower host
        // controls on some devices.
        val topBar = createPlayerTopBar()
        playerTopChrome = topBar
        val controlDock = createSdkPlayerControlDock()
        controlDock.visibility = View.GONE
        playerControlsPanel = controlDock
        val controlsReveal = transparentPlayerTextButton(t("show_controls"), t("show_controls")) {
            showPlayerControlsFromUser()
        }
        controlsReveal.visibility = View.VISIBLE
        playerControlsRevealButton = controlsReveal
        val rewardWheel = createFloatingRewardWheel(compact = true)
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132), Gravity.TOP))
        root.addView(controlDock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(106), Gravity.BOTTOM).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            // GONE while playing. When paused it overlays the media instead of reserving a
            // permanent bottom band beneath the provider's full-height video fragment.
            bottomMargin = 0
        })
        root.addView(controlsReveal, FrameLayout.LayoutParams(dp(86), dp(38), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(14)
            bottomMargin = dp(16)
        })
        root.addView(rewardWheel, FrameLayout.LayoutParams(dp(112), dp(96), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = 0
            bottomMargin = dp(166)
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
    showGestureCoachmarkIfNeeded(previewOnly)
}

/** Opens the provider's recommended first episode while preserving its native vertical episode browsing. */
internal fun DramaAppActivity.showSdkDiscoverPreview(drama: LocalDrama) {
    val card = sdkDramaCardsByCatalogId[drama.id] ?: run {
        logStatus("PSSDK 推荐预览缺少内容映射：${drama.id}")
        return
    }
    showSdkDramaPlayer(drama, card, startIndex = 0, resumePositionSeconds = 0, previewOnly = true)
}

/** Opens the existing preview at the current episode without duplicating PSSDK's title metadata. */
private fun DramaAppActivity.openDiscoverPreviewEpisodes() {
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

private fun DramaAppActivity.createDiscoverDramaInfoPanel(): View = LinearLayout(this).apply {
    val drama = selectedDrama
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(10), dp(18), dp(4))
    background = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x08000000, 0xD9000000.toInt(), 0xF2000000.toInt()),
    )
    val openEpisodes = { openDiscoverPreviewEpisodes() }
    addView(TextView(this@createDiscoverDramaInfoPanel).apply {
        text = drama?.title?.takeIf { it.isNotBlank() } ?: t("watch_now")
        textSize = 19f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(Color.WHITE)
        isClickable = true
        contentDescription = t("discover_open_episodes")
        setOnClickListener { openEpisodes() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
    addView(TextView(this@createDiscoverDramaInfoPanel).apply {
        text = drama?.desc?.trim().orEmpty()
        textSize = 13f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(0xB3FFFFFF.toInt())
        setPadding(0, dp(2), 0, dp(4))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
    val progress = ProgressBar(this@createDiscoverDramaInfoPanel, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1_000
        this.progress = 0
        progressTintList = ColorStateList.valueOf(COLOR_SUNSET)
        backgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
    }
    discoverPreviewProgressBar = progress
    addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
        topMargin = dp(2)
        bottomMargin = dp(4)
    })
    addView(LinearLayout(this@createDiscoverDramaInfoPanel).apply {
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        contentDescription = t("discover_open_episodes")
        addView(ImageView(this@createDiscoverDramaInfoPanel).apply {
            setImageResource(R.drawable.ic_episode_stack)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(27), dp(27)))
        addView(TextView(this@createDiscoverDramaInfoPanel).apply {
            text = t("discover_all_episodes", drama?.total ?: 0, drama?.title.orEmpty())
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(dp(10), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createDiscoverDramaInfoPanel).apply {
            text = "›"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(0xE6FFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(28), dp(32)))
        setOnClickListener { openEpisodes() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
}

/** Secondary direct entry, visually floating over the preview like a native episode action. */
private fun DramaAppActivity.createDiscoverEpisodeFloatButton(): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    alpha = 0.96f
    isClickable = true
    contentDescription = t("discover_open_episodes")
    addView(ImageView(this@createDiscoverEpisodeFloatButton).apply {
        setImageResource(R.drawable.ic_episode_stack)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }, LinearLayout.LayoutParams(dp(32), dp(32)))
    addView(TextView(this@createDiscoverEpisodeFloatButton).apply {
        text = t("episodes")
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(Color.WHITE)
        setPadding(0, dp(4), 0, 0)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
    setOnClickListener { openDiscoverPreviewEpisodes() }
}

private fun DramaAppActivity.onSdkItemSelected(episodeIndex: Int) {
    recordUiAction("PSSDK_EPISODE_SELECTED", mapOf("episode_index" to episodeIndex + 1))
    currentEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    if (pendingSdkResumeCheckpoint?.matches(currentEpisodeIndex) == false) {
        pendingSdkResumeCheckpoint = null
        pendingSdkResumeSeekApplied = false
    }
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
    recordUiAction("PLAYER_RESOLUTION_PICKER_OPENED")
    // Resolution is part of PSSDK's public ShortPlayFragment contract. Calling it directly
    // keeps the compiler checking this integration rather than silently failing through reflection.
    showBrandedOptionsDialog(
        title = t("resolution"),
        items = listOf(t("resolution_data_saver"), t("resolution_hd")),
    ) { which ->
        val selected = if (which == 0) Resolution.Standard else Resolution.High
        recordUiAction("PLAYER_RESOLUTION_SELECTED", mapOf("resolution" to if (which == 0) "standard" else "high"))
        runCatching { fragment.setResolution(selected) }
            .onSuccess { toast(if (which == 0) t("resolution_data_saver_set") else t("resolution_hd_set")) }
            .onFailure { toast(t("resolution_unavailable")) }
    }
}

private fun DramaAppActivity.showGestureCoachmarkIfNeeded(discoverPreview: Boolean) {
    val preferenceKey = if (discoverPreview) "discover_gesture_coachmark_seen" else "player_gesture_coachmark_seen"
    if (getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(preferenceKey, false)) return
    val coachmark = FrameLayout(this).apply {
        // The guide is intentionally translucent grey: it informs without turning the content
        // behind it into a harsh black interruption.
        background = android.graphics.drawable.ColorDrawable(0x66000000)
        isClickable = true
        setOnClickListener { finishGestureCoachmark(this, preferenceKey, "tap") }
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(26), dp(22), dp(26), dp(20))
        background = gradientDrawable(intArrayOf(0xDE505158.toInt(), 0xD934353A.toInt()), 26)
        addView(TextView(this@showGestureCoachmarkIfNeeded).apply {
            text = "☝"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(COLOR_FLOATING_GOLD)
        }.also { hand ->
            ObjectAnimator.ofFloat(hand, View.TRANSLATION_Y, dp(14).toFloat(), -dp(16).toFloat(), dp(14).toFloat()).apply {
                duration = 1_160L
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        addView(TextView(this@showGestureCoachmarkIfNeeded).apply {
            text = if (discoverPreview) "↕" else "↕     ↔"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFFE2E2E5.toInt())
        })
        addView(TextView(this@showGestureCoachmarkIfNeeded).apply {
            text = t(if (discoverPreview) "discover_gesture_coachmark_title" else "player_gesture_coachmark_title")
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(9), 0, 0)
        })
        addView(TextView(this@showGestureCoachmarkIfNeeded).apply {
            text = t(if (discoverPreview) "discover_gesture_coachmark" else "player_gesture_coachmark")
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xD9FFFFFF.toInt())
            setPadding(0, dp(8), 0, 0)
        })
    }
    coachmark.addView(panel, FrameLayout.LayoutParams(dp(292), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    root.addView(coachmark, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    userBehaviorReporter.record(
        "ONBOARDING",
        "ONBOARDING_GESTURE_GUIDE_SHOWN",
        mapOf("surface" to if (discoverPreview) "discover" else "player"),
    )
    coachmark.alpha = 0f
    coachmark.animate().alpha(1f).setDuration(260L).start()
    root.postDelayed({ if (coachmark.parent != null) finishGestureCoachmark(coachmark, preferenceKey, "timeout") }, 5_000L)
}

private fun DramaAppActivity.finishGestureCoachmark(view: View, preferenceKey: String, method: String) {
    (view.parent as? ViewGroup)?.removeView(view)
    getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(preferenceKey, true).apply()
    userBehaviorReporter.record("ONBOARDING", "ONBOARDING_GESTURE_GUIDE_COMPLETED", mapOf("method" to method))
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
    val safeEpisodeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    val restoringExternalPosition = pendingExternalAdRestoreEpisodeIndex == safeEpisodeIndex &&
        (pendingExternalAdRestorePositionSeconds ?: 0) > 0
    val historyResume = pendingSdkResumeCheckpoint?.takeIf { it.matches(safeEpisodeIndex) }
    if (pendingSdkResumeCheckpoint != null && historyResume == null) {
        pendingSdkResumeCheckpoint = null
        pendingSdkResumeSeekApplied = false
    }
    currentEpisodeIndex = safeEpisodeIndex
    playerProgressSeconds = if (restoringExternalPosition || historyResume != null) {
        maxOf(
            playerProgressSeconds,
            pendingExternalAdRestorePositionSeconds ?: 0,
            historyResume?.positionSeconds ?: 0,
        )
    } else {
        0
    }
    playerDurationSeconds = 0
    lastPersistedPlaybackSecond = -1
    // Episode transition restarts the watch clock but retains the session-level unavailable
    // attempt cap so an ad-network outage cannot prompt on every short episode.
    contentWatchSecondsSinceMidroll = 0
    midrollInterstitialShownInSession = false
    midrollInterstitialRequestInFlight = false
    lastMidrollSamplePositionSeconds = -1
    playerIsPaused = false
    updatePauseOverlayVisibility(false)
    updateEpisodeStatus()
    updatePlayerProgress(playerProgressSeconds, 0)
    if (sdkDiscoverPreviewActive) discoverPreviewProgressBar?.progress = 0
    if (!restoringExternalPosition && historyResume == null) persistPlaybackProgress(0)
    if (historyResume != null && !pendingSdkResumeSeekApplied) {
        pendingSdkResumeSeekApplied = true
        // PSSDK's first started callback commonly precedes its Builder start-time seek. Applying
        // the public seek API once the active item exists prevents that early zero callback from
        // turning a valid Continue Watching resume into a restart from the first second.
        root.post {
            if (sdkPlayerShown && pendingSdkResumeCheckpoint == historyResume) {
                currentSdkPlayerFragment()?.setCurrentPlayTimeSeconds(historyResume.positionSeconds)
            }
        }
    }
    if (pendingExternalAdRestoreEpisodeIndex == currentEpisodeIndex &&
        (pendingExternalAdRestorePositionSeconds ?: 0) == 0
    ) {
        pendingExternalAdRestoreEpisodeIndex = null
        pendingExternalAdRestorePositionSeconds = null
        clearExternalAdReturnCheckpoint()
        logStatus("广告外跳恢复分集已确认：第 ${currentEpisodeIndex + 1} 集")
    }
    recordEarlyVideoStarted(provider = "PSSDK", episodeIndex = safeEpisodeIndex)
    episode?.let { logStatus("PSSDK 开始播放分集：${episodeIndex + 1}") }
}

private fun DramaAppActivity.onSdkEpisodeCompleted(episodeIndex: Int) {
    val safeIndex = episodeIndex.coerceIn(0, selectedDrama?.episodes?.lastIndex ?: 0)
    val key = episodeKey(safeIndex)
    if (!completedEpisodeKeys.add(key)) return
    currentEpisodeIndex = safeIndex
    persistNextEpisodeResume(safeIndex)
    recordEarlyVideoCompleted(provider = "PSSDK", episodeIndex = safeIndex)
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
    updatePauseOverlayVisibility(false)
    val historyResume = pendingSdkResumeCheckpoint?.takeIf { it.matches(currentEpisodeIndex) }
    val displayedProgress = historyResume?.displayedProgress(progress) ?: progress
    updatePlayerProgress(displayedProgress, duration)
    val expectedEpisode = pendingExternalAdRestoreEpisodeIndex
    val expectedPosition = pendingExternalAdRestorePositionSeconds
    if (expectedEpisode == currentEpisodeIndex && expectedPosition != null && progress >= expectedPosition) {
        pendingExternalAdRestoreEpisodeIndex = null
        pendingExternalAdRestorePositionSeconds = null
        clearExternalAdReturnCheckpoint()
        logStatus("广告外跳恢复进度已确认：第 ${currentEpisodeIndex + 1} 集 @${progress}s")
    }
    if (historyResume != null && historyResume.isConfirmedBy(progress)) {
        pendingSdkResumeCheckpoint = null
        pendingSdkResumeSeekApplied = false
        logStatus("历史续播进度已确认：第 ${currentEpisodeIndex + 1} 集 @${progress}s")
    }
    if (sdkDiscoverPreviewActive && duration > 0) {
        discoverPreviewProgressBar?.progress = (displayedProgress * 1_000 / duration).coerceIn(0, 1_000)
    }
    if (pendingExternalAdRestoreEpisodeIndex == null && pendingSdkResumeCheckpoint == null) {
        persistPlaybackProgressIfNeeded(displayedProgress)
    }
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
        switchDramaBySwipe(forward = forward, countTowardInterstitial = true)
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
                fragment.startPlayIndex(PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(safeIndex))
            }
        })
    } else {
        fragment.startPlayIndex(PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(safeIndex))
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
    // PSSDK synchronously asks again when startPlay() follows an ad close. Keep the original
    // unlock transaction authoritative until it settles, otherwise an unavailable rewarded ad
    // can recurse through fallback interstitial -> resume -> showAdIfNeed indefinitely.
    if (episodeTransitionInProgress) {
        logStatus("PSSDK 分集解锁已在处理中：${safeIndex + 1}")
        return
    }
    if (selectedDrama?.episodes?.getOrNull(safeIndex) == null) return
    if (fullscreenAdInProgress || fullscreenAdRequestInFlight) {
        toast(t("ad_loading"))
        return
    }
    episodeTransitionInProgress = true
    requestRewardedAction(
        placementId = "rv_episode_pass",
        targetId = episodeKey(safeIndex),
        episodeIndex = safeIndex + 1,
        onSettled = { action ->
            val unlockCount = action.unlockEpisodeCount.coerceAtLeast(1)
            (safeIndex until (safeIndex + unlockCount).coerceAtMost(selectedDrama?.episodes?.size ?: safeIndex + 1))
                .forEach {
                    unlockedEpisodeKeys.add(episodeKey(it))
                    sessionUnlockedEpisodeKeys.remove(episodeKey(it))
                }
            episodeTransitionInProgress = false
            listener?.onShortPlayUnlocked()
        },
        onSettlementUnavailable = { message ->
            episodeTransitionInProgress = false
            logStatus("PSSDK 分集解锁结算失败：$message")
            toast(t("unlock_settlement_pending_generic"))
        },
        onAdUnavailable = {
            allowEpisodeUnlockAfterAdUnavailable(safeIndex) { listener?.onShortPlayUnlocked() }
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
    recordEarlyVideoPlaybackFailure(provider = "PSSDK", episodeIndex = currentEpisodeIndex, reason = message)
    logStatus("PSSDK playback failed: $message")
    runOnUiThread {
        if (!sdkPlayerShown || isFinishing || isDestroyed) return@runOnUiThread
        showBrandedChoiceDialog(
            title = t("sdk_playback_unavailable_title"),
            message = t("sdk_playback_unavailable_message", message),
            primaryLabel = t("retry"),
            onPrimary = {
                shortDramaSdk.checkPlaybackService { status -> runOnUiThread {
                    if (status.supported) currentSdkPlayerFragment()?.startPlayIndex(
                        PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(currentEpisodeIndex),
                    )
                    else logStatus("PSSDK 重试前服务检查未通过：${status.reason}")
                } }
            },
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
