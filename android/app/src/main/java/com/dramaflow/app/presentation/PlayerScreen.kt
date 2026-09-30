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
import android.os.SystemClock
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
import androidx.media3.ui.AspectRatioFrameLayout
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
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.showPlayer(drama: LocalDrama, startIndex: Int, resumePositionSeconds: Int = 0) {
        val card = sdkDramaCardsByCatalogId[drama.id]
        if (card == null) {
            logStatus("PSSDK 目录映射缺失：${drama.id}")
            renderFailure(t("player_not_ready"))
            return
        }
        showSdkDramaPlayer(drama, card, startIndex)
    }

private fun DramaAppActivity.renderLegacyPlayer(drama: LocalDrama, startIndex: Int, resumePositionSeconds: Int = 0) {
        releasePlayer()
        // One transparent edge-to-edge policy is reapplied before player creation and after every ad close.
        configurePlayerWindow()
        selectedDrama = drama
        currentEpisodeIndex = startIndex.coerceIn(0, drama.episodes.lastIndex.coerceAtLeast(0))
        pendingResumePositionSeconds = resumePositionSeconds.coerceAtLeast(0)
        lastPersistedPlaybackSecond = -1
        playerProgressSeconds = pendingResumePositionSeconds
        playerDurationSeconds = 0
        playerIsSeeking = false
        playerIsPaused = false
        playerMuted = false
        playerSpeed = 1.0f
        playerDescriptionExpanded = false
        playerControlsVisible = true
        contentWatchSecondsSinceMidroll = 0
        midrollInterstitialShownInSession = false
        lastMidrollSamplePositionSeconds = -1
        playerShown = true
        root.removeAllViews()
        root.setBackgroundColor(Color.BLACK)

        val player = ExoPlayer.Builder(this).build().also { exoPlayer = it }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.BLACK)
            this.player = player
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerIsPaused = !isPlaying && player.playbackState == Player.STATE_READY
                updatePauseOverlayVisibility(playerIsPaused && !fullscreenAdInProgress)
                if (isPlaying) {
                    val now = SystemClock.elapsedRealtime()
                    if (episodePlaybackStartedAtElapsedMillis == 0L) episodePlaybackStartedAtElapsedMillis = now
                    if (episodePlaybackPausedAtElapsedMillis > 0L) {
                        episodePlaybackPausedDurationMillis += now - episodePlaybackPausedAtElapsedMillis
                        episodePlaybackPausedAtElapsedMillis = 0L
                    }
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    if (episodePlaybackStartedAtElapsedMillis > 0L && episodePlaybackPausedAtElapsedMillis == 0L) {
                        episodePlaybackPausedAtElapsedMillis = SystemClock.elapsedRealtime()
                    }
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playerDurationSeconds = player.duration.takeIf { it > 0 }?.div(1_000L)?.toInt()
                        ?: selectedEpisode()?.durationSeconds.orZero()
                } else if (playbackState == Player.STATE_ENDED) {
                    handleEpisodeCompleted(currentEpisodeIndex)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpFailure = generateSequence(error as Throwable?) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                val diagnostic = httpFailure?.let { "HTTP ${it.responseCode}" } ?: error.errorCodeName
                logStatus("局域网视频播放失败：$diagnostic ${error.message.orEmpty()}")
                playerIsPaused = true
                episodeTransitionInProgress = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                playerProgressSeekBar?.isEnabled = false
                val userMessage = when (httpFailure?.responseCode) {
                    404 -> t("player_video_not_found")
                    403 -> t("player_playback_expired")
                    402 -> t("player_unlock_required")
                    500, 503 -> t("player_media_service_unavailable")
                    else -> t("player_playback_failed")
                }
                toast(t("player_error_reload_hint", userMessage))
            }
        })
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createPlayerChrome(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createFloatingRewardWheel(compact = true), FrameLayout.LayoutParams(dp(112), dp(96), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(12)
            bottomMargin = dp(166)
        })
        selectEpisode(currentEpisodeIndex)
        showPlayerGestureHintOnce()
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.post(progressTicker)
    }

internal fun DramaAppActivity.showPlayerGestureHintOnce() {
    val preferences = getSharedPreferences("joyedrama_player_hint", Context.MODE_PRIVATE)
    if (preferences.getBoolean("gesture_hint_seen", false)) return
    preferences.edit().putBoolean("gesture_hint_seen", true).apply()
    val hint = TextView(this).apply {
        text = t("player_gesture_hint_short")
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(15), dp(9), dp(15), dp(9))
        background = roundedDrawable(0xDC1D1D22.toInt(), 18)
        alpha = 0f
    }
    root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
        bottomMargin = dp(148)
    })
    hint.animate().alpha(1f).setStartDelay(520L).setDuration(240L).withEndAction {
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(300L).withEndAction { (hint.parent as? ViewGroup)?.removeView(hint) }.start() }, 3_300L)
    }.start()
}

private fun Int?.orZero(): Int = this ?: 0

internal fun DramaAppActivity.createPlayerChrome(): View {
        val chrome = FrameLayout(this).apply { isClickable = false }
        chrome.addView(createPlayerGestureLayer(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerPauseOverlay = TextView(this).apply {
            text = "▶\n${t("resume_playback")}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
                setStroke(dp(2), 0xCCFFDB6A.toInt())
            }
            visibility = View.GONE
            contentDescription = t("resume_playback")
            isClickable = true
            setOnClickListener { resumePlaybackFromPauseOverlay() }
        }
        chrome.addView(playerPauseOverlay, FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER))
        chrome.addView(createPlayerTopBar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = topRoundedDrawable(0x9E0D0B12.toInt(), 22)
            isClickable = true
        }
        playerControlsPanel = controls
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = selectedDrama?.title.orEmpty()
            textSize = 16f
            maxLines = 1
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(transparentPlayerTextButton(t("description"), t("expand_description")) { togglePlayerDescription() }, LinearLayout.LayoutParams(dp(64), dp(32)))
        controls.addView(titleRow)
        playerDescriptionView = TextView(this).apply {
            text = selectedDrama?.desc?.trim().orEmpty().ifBlank { t("no_description") }
            textSize = 12f
            maxLines = Int.MAX_VALUE
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(7), 0, dp(6))
            visibility = View.VISIBLE
        }
        val descriptionScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            visibility = View.GONE
            addView(playerDescriptionView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        playerDescriptionContainer = descriptionScroll
        controls.addView(descriptionScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(144)))
        episodeStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(208, 195, 216))
            setPadding(0, dp(4), 0, 0)
        }
        controls.addView(episodeStatusView)
        controls.addView(createPlayerProgressControls())
        controls.addView(createPlayerActionRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(4) })
        chrome.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(chrome) { _, insets ->
            val safeBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            (controls.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                if (params.bottomMargin != safeBottom) {
                    params.bottomMargin = safeBottom
                    controls.layoutParams = params
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(chrome)

        playerControlsRevealButton = transparentPlayerTextButton(t("show_controls"), t("show_controls")) { setPlayerControlsVisible(true) }.also { it.visibility = View.GONE }
        chrome.addView(playerControlsRevealButton, FrameLayout.LayoutParams(dp(74), dp(38), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(14)
            marginEnd = dp(14)
        })
        updateEpisodeStatus()
        updatePlayerProgress(0, 0)
        return chrome
    }

internal fun DramaAppActivity.createPlayerTopBar(): View {
        val shell = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = roundedDrawable(0x720D0B12, 17)
            contentDescription = t("back")
            isClickable = true
            setOnClickListener { showCatalog() }
        }
        val assets = createPlayerAssetStrip()
        // Both controls share the true system safe inset. This avoids the fixed 14dp
        // offset that could overlap a notch/status region on first opening the player.
        shell.addView(back, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { topMargin = dp(8); marginStart = dp(8) })
        shell.addView(assets, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(26) })
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val margin = safeTop + dp(8)
            (back.layoutParams as FrameLayout.LayoutParams).also { params -> params.topMargin = margin; back.layoutParams = params }
            (assets.layoutParams as FrameLayout.LayoutParams).also { params -> params.topMargin = margin + dp(18); assets.layoutParams = params }
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        return shell
    }

internal fun DramaAppActivity.createPlayerGestureLayer(): View {
        var downX = 0f
        var downY = 0f
        val swipeThreshold = dp(56).toFloat()
        val tapThreshold = dp(12).toFloat()
        return View(this).apply {
            contentDescription = t("player_gesture_hint")
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; true }
                    MotionEvent.ACTION_UP -> {
                        val deltaX = event.x - downX; val deltaY = event.y - downY
                        when {
                            kotlin.math.abs(deltaX) >= swipeThreshold && kotlin.math.abs(deltaY) < swipeThreshold -> {
                                switchDramaBySwipe(forward = deltaX < 0f)
                            }
                            deltaY <= -swipeThreshold && kotlin.math.abs(deltaX) < swipeThreshold -> triggerSwipeNextEpisode()
                            deltaY >= swipeThreshold && kotlin.math.abs(deltaX) < swipeThreshold -> triggerSwipePreviousEpisode()
                            kotlin.math.abs(deltaX) <= tapThreshold && kotlin.math.abs(deltaY) <= tapThreshold && System.currentTimeMillis() >= ignoreVideoTapsUntilMillis -> togglePlaybackFromVideoTap()
                        }; true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
    }

internal fun DramaAppActivity.triggerSwipeNextEpisode() {
        val now = System.currentTimeMillis(); if (now - lastSwipeNextAtMillis < 700L) return
        lastSwipeNextAtMillis = now; startNextEpisodeAutomatically(currentEpisodeIndex, userInitiated = true)
    }

internal fun DramaAppActivity.triggerSwipePreviousEpisode() {
        val now = System.currentTimeMillis(); if (now - lastSwipeNextAtMillis < 700L) return
        lastSwipeNextAtMillis = now
        if (currentEpisodeIndex <= 0) switchDramaBySwipe(forward = false) else selectEpisode(currentEpisodeIndex - 1)
    }

    /** 横滑切剧每累计三次都尝试独立插屏；拖动和倍速则永远不触发插屏。 */
internal fun DramaAppActivity.switchDramaBySwipe(forward: Boolean = true) {
        if (catalogItems.size < 2 || fullscreenAdInProgress) return
        val current = selectedDrama ?: return
        val currentIndex = catalogItems.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: return
        val nextDrama = catalogItems[(currentIndex + if (forward) 1 else catalogItems.lastIndex) % catalogItems.size]
        dramaSwitchSwipeCount += 1
        val policy = rewardsSnapshot?.interstitial
        val shouldShow = policy?.dramaSwitchEnabled == true && dramaSwitchSwipeCount % policy.dramaSwitchEveryCount == 0
        val switch = { showPlayer(nextDrama, 0) }
        if (shouldShow) {
            showPlaybackActionInterstitial(
                placementId = "int_drama_switch",
                episodeId = selectedEpisode()?.id,
                countTowardEpisodeLimit = false,
                bypassSharedFrequencyCaps = true,
                onShown = switch,
                onUnavailable = { reason -> logStatus("切剧插屏未展示：$reason"); switch() },
            )
        } else switch()
    }

/** Accumulates actual watch time across short episodes; seek jumps never advance the mid-roll clock. */
internal fun DramaAppActivity.requestMidEpisodeInterstitialIfEligible(positionSeconds: Int) {
        val snapshot = rewardsSnapshot ?: return
        val policy = snapshot.interstitial
        if (!playerShown || playerIsPaused || fullscreenAdInProgress || !policy.midEpisodeEnabled) return
        val previousPosition = lastMidrollSamplePositionSeconds
        lastMidrollSamplePositionSeconds = positionSeconds
        if (previousPosition < 0) return
        // The ticker runs twice per second. A larger jump is a seek and has its own ad candidate, so it is excluded.
        val delta = positionSeconds - previousPosition
        if (delta in 1..2) contentWatchSecondsSinceMidroll += delta
        val requiredWatchSeconds = if (midrollInterstitialShownInSession) {
            policy.midEpisodeRepeatSeconds.coerceAtLeast(1)
        } else {
            policy.midEpisodeTriggerSeconds.coerceAtLeast(1)
        }
        if (contentWatchSecondsSinceMidroll < requiredWatchSeconds || midrollInterstitialRequestInFlight) return
        midrollInterstitialRequestInFlight = true
        // Mid-rolls are governed exclusively by the real-playback schedule.
        // They must not be consumed by a separate same-episode action-ad cap.
        showPlaybackActionInterstitial(
            "int_episode_midroll",
            episodeId = selectedEpisode()?.id,
            countTowardEpisodeLimit = false,
            onShown = {
            contentWatchSecondsSinceMidroll = 0
            midrollInterstitialShownInSession = true
            midrollInterstitialRequestInFlight = false
        }, onUnavailable = { message ->
            // A failed candidate should not retry every ticker; the next candidate occurs after another interval.
            contentWatchSecondsSinceMidroll = 0
            midrollInterstitialRequestInFlight = false
            logStatus("Mid-roll unavailable: $message")
        })
    }

internal fun DramaAppActivity.completeSeekAndResume(targetSeconds: Int) {
        val player = exoPlayer
        if (player == null || player.playbackState != Player.STATE_READY) {
            playerIsSeeking = false
            toast(t("player_not_ready"))
            return
        }
        player.seekTo(targetSeconds * 1_000L)
        playerProgressSeconds = targetSeconds
        playerIsSeeking = false
        player.play()
        playerIsPaused = false
    }

internal fun DramaAppActivity.createPlayerProgressControls(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val timeRow = LinearLayout(this@createPlayerProgressControls).apply { gravity = Gravity.CENTER_VERTICAL }
        playerProgressText = TextView(this@createPlayerProgressControls).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            text = "00:00 / --:--"
        }
        timeRow.addView(playerProgressText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(TextView(this@createPlayerProgressControls).apply {
            text = t("seek_to")
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
        })
        addView(timeRow)
        playerProgressSeekBar = SeekBar(this@createPlayerProgressControls).apply {
            max = 1
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) playerProgressText?.text = "${formatPlaybackTime(progress)} / ${formatPlaybackTime(playerDurationSeconds)}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { playerIsSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val targetSeconds = seekBar?.progress ?: return
                    // Seeking must be immediate. Interstitials are now requested only from
                    // measured real playback time, never from a progress-bar interaction.
                    completeSeekAndResume(targetSeconds)
                }
            })
        }
        addView(playerProgressSeekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
    }

internal fun DramaAppActivity.createPlayerActionRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val actions = listOf(
            Triple(t("episodes"), t("episodes")) { toggleEpisodePicker() },
            Triple("1x", t("speed")) { showPlaybackSpeedPicker() },
            Triple(t("more"), t("more")) { showPlaybackMoreControls() },
            Triple(if (isFavorite()) "♥" else "♡", t("favorite")) { toggleFavorite() },
            Triple(t("hide"), t("hide")) { setPlayerControlsVisible(false) },
        )
        actions.forEachIndexed { index, action ->
            val button = if (action.second == t("episodes") || action.second == t("more") || action.second == t("hide")) {
                transparentPlayerTextButton(action.first, action.second, action.third)
            } else {
                transparentPlayerIcon(action.first, action.second, action.third)
            }
            if (action.first == "1x") speedButton = button
            if (action.second == t("favorite")) favoriteButton = button
            addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) marginStart = dp(2)
            })
        }
    }

internal fun DramaAppActivity.togglePlayerDescription() {
        playerDescriptionExpanded = !playerDescriptionExpanded
        playerDescriptionContainer?.visibility = if (playerDescriptionExpanded) View.VISIBLE else View.GONE
    }

internal fun DramaAppActivity.transparentPlayerTextButton(label: String, description: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedDrawable(0x26000000, 14)
        contentDescription = description
        setOnClickListener { onClick() }
        isClickable = true
    }

internal fun DramaAppActivity.setPlayerControlsVisible(visible: Boolean) {
        playerControlsVisible = visible
        playerControlsPanel?.visibility = if (visible) View.VISIBLE else View.GONE
        playerControlsRevealButton?.visibility = if (visible) View.GONE else View.VISIBLE
    }

    /** 视频区域单击是唯一的播放/暂停操作；Media3 会同步停止画面、音频和播放时钟。 */
internal fun DramaAppActivity.updatePauseOverlayVisibility(visible: Boolean) {
        playerPauseOverlay?.visibility = if (visible) View.VISIBLE else View.GONE
    }

internal fun DramaAppActivity.resumePlaybackFromPauseOverlay() {
        val player = exoPlayer ?: return
        if (player.playbackState == Player.STATE_IDLE) {
            selectEpisode(currentEpisodeIndex)
            return
        }
        player.play()
        playerIsPaused = false
        updatePauseOverlayVisibility(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

internal fun DramaAppActivity.togglePlaybackFromVideoTap() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playerIsPaused = true
            updatePauseOverlayVisibility(true)
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                selectEpisode(currentEpisodeIndex)
                return
            }
            player.play()
            playerIsPaused = false
            updatePauseOverlayVisibility(false)
        }
    }

internal fun DramaAppActivity.transparentPlayerIcon(label: String, description: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (label.length > 1) 12f else 21f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedDrawable(0x26000000, 14)
        contentDescription = description
        setOnClickListener { onClick() }
        isClickable = true
    }

internal fun DramaAppActivity.showPlaybackSpeedPicker() {
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { "${it}x" }
        val selected = speeds.indexOfFirst { it == playerSpeed }.coerceAtLeast(0)
        showBrandedOptionsDialog(t("playback_speed"), labels, selected) { which ->
            val applySpeed = {
                playerSpeed = speeds[which]
                exoPlayer?.setPlaybackSpeed(playerSpeed)
                speedButton?.text = labels[which]
                resumeAfterFullscreenAdIfNeeded()
            }
            // Playback speed is an immediate accessibility/control action; it never requests an ad.
            applySpeed()
        }
    }

internal fun DramaAppActivity.showPlaybackMoreControls() {
        val muteLabel = if (playerMuted) t("restore_sound") else t("mute")
        val options = listOf(muteLabel, t("increase_brightness"), t("restore_brightness"), t("immersive_mode"), t("reload_episode"))
        showBrandedOptionsDialog(t("playback_settings"), options) { which ->
            when (which) {
                0 -> { playerMuted = !playerMuted; exoPlayer?.volume = if (playerMuted) 0f else 1f; toast(if (playerMuted) t("muted") else t("sound_restored")) }
                1 -> { window.attributes = window.attributes.apply { screenBrightness = 1f }; toast(t("brightness_increased")) }
                2 -> { window.attributes = window.attributes.apply { screenBrightness = -1f }; toast(t("brightness_restored")) }
                3 -> enterImmersivePlayerMode()
                4 -> selectEpisode(currentEpisodeIndex)
            }
        }
    }

internal fun DramaAppActivity.enterImmersivePlayerMode(announce: Boolean = true) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        playerInImmersiveMode = true
        setPlayerControlsVisible(false)
        if (announce) toast(t("immersive_hint"))
    }

internal fun DramaAppActivity.exitImmersivePlayerMode() {
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        playerInImmersiveMode = false
        if (playerShown) configurePlayerWindow() else configureCatalogWindow()
    }

internal fun DramaAppActivity.updatePlayerProgress(progressSeconds: Int, durationSeconds: Int) {
        if (playerIsPaused) return
        playerProgressSeconds = progressSeconds.coerceAtLeast(0)
        if (durationSeconds > 0) playerDurationSeconds = durationSeconds
        if (!playerIsSeeking) {
            playerProgressSeekBar?.apply {
                max = playerDurationSeconds.coerceAtLeast(1)
                progress = playerProgressSeconds.coerceAtMost(max)
            }
            playerProgressText?.text = "${formatPlaybackTime(playerProgressSeconds)} / ${if (playerDurationSeconds > 0) formatPlaybackTime(playerDurationSeconds) else "--:--"}"
        }
    }

internal fun DramaAppActivity.formatPlaybackTime(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d".format(Locale.US, safe / 60, safe % 60)
    }

internal fun DramaAppActivity.actionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedDrawable(COLOR_SURFACE, 14)
        setOnClickListener { onClick() }
        isClickable = true
    }
