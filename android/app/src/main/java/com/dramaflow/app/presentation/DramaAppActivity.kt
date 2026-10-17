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
import androidx.lifecycle.Lifecycle
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
import com.dramaflow.app.i18n.AppLanguage
import com.dramaflow.app.ads.AdConsent
import com.dramaflow.app.ads.AdConsentStore
import com.dramaflow.app.ads.AdMediationEventReporter
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

/**
 * JoyeDrama 的统一短剧入口。
 *
 * 正式内容由 Pangle/PSSDK 提供；应用统一管理目录展示、广告解锁和奖励体验。
 */
class DramaAppActivity : FragmentActivity() {
    internal lateinit var root: FrameLayout
    internal var catalogPage: View? = null
    internal var catalogItems: List<LocalDrama> = emptyList()
    /** Pangle SDK cards are mapped into a lightweight shared catalog display model. */
    internal val sdkDramaCardsByCatalogId = mutableMapOf<String, ShortDramaSdkManager.DramaCard>()
    internal var sdkCatalogCategories: List<ShortDramaSdkManager.Category> = emptyList()
    internal var categoryCatalogItems: List<LocalDrama> = emptyList()
    internal var selectedCatalogCategoryId: Long? = null
    /** Invalidates provider callbacks started before the user selected another content language. */
    internal var catalogLanguageRequestGeneration = 0
    internal var categoryNextPage = 1
    internal var categoryHasMorePages = false
    internal var categoryPageLoadInFlight = false
    internal var sdkPlayerShown = false
    internal var sdkPlayerContainerId = View.NO_ID
    /** Discover opens one provider-feed preview per tab visit; tapping its title expands into the full player. */
    internal var sdkDiscoverPreviewActive = false
    /** Compact playback indicator rendered below the Discover preview video. */
    internal var discoverPreviewProgressBar: ProgressBar? = null
    internal var discoverPreviewPresented = false
    internal var playerReturnTab: MainTab = MainTab.HOME
    internal var selectedCatalogGenre: String? = null
    internal var activeTab: MainTab = MainTab.HOME
    internal var withdrawalPageShown = false
    /** Profile history/favorites expands into a normal in-app secondary page. */
    internal var profileCollectionPageShown = false
    /** Distinguishes the two Profile secondary pages when restoring an external-ad return. */
    internal var profileCollectionShowsFavorites = false
    internal var rewardsSnapshot: RewardsApiClient.Snapshot? = null
    internal var rewardsLoading = false
    /** Invalidates an older bootstrap response after a newer server-confirmed reward action. */
    internal var rewardsSnapshotGeneration = 0L
    /** Keeps reward settlement single-flight after an ad closes but before the server replies. */
    internal var rewardSettlementInFlight = false
    /** Prevents a synchronous player callback from recursively consuming a pending unlock. */
    internal var settledEpisodeTargetReleaseInFlight = false
    internal var exoPlayer: ExoPlayer? = null
    internal var playerView: PlayerView? = null
    internal val progressHandler = Handler(Looper.getMainLooper())
    internal val progressTicker = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (playerShown && player != null) {
                val duration = player.duration.takeIf { it > 0 }?.div(1_000L)?.toInt() ?: playerDurationSeconds
                val position = player.currentPosition.coerceAtLeast(0L).div(1_000L).toInt()
                updatePlayerProgress(position, duration)
                persistPlaybackProgressIfNeeded(position)
                requestMidEpisodeInterstitialIfEligible(position)
                progressHandler.postDelayed(this, 500L)
            }
        }
    }
    internal var playerShown = false
    internal var appOpenAttempted = false
    internal var startupContentInitializationStarted = false
    internal var startupAdFinished = false
    internal var startupCatalogFinished = false
    internal var startupPosterPreloadFinished = false
    internal var startupRewardsFinished = false
    /** Keeps the branded loading view visible long enough to avoid a home-page flash. */
    internal var startupMinimumVisualFinished = false
    /** Bounded launch handoff that gives the first PSSDK catalogue request time to populate. */
    internal var startupContentHandoffFinished = false
    internal var startupPosterPreloadPercent = 0
    internal var startupCatalogFailure: String? = null
    /** Suppresses cold-start interstitial UI while restoring a recently backgrounded task. */
    internal var restoringBackgroundReturn = false
    /** PSSDK can fail its first remote bootstrap on a cold network; retries never block navigation. */
    internal var shortDramaRetryAttempt = 0
    internal var shortDramaRetryScheduled = false
    internal var shortDramaRetryGeneration = 0
    internal var pendingShortDramaDiagnostic: String? = null
    internal var shownShortDramaDiagnostic: String? = null
    internal var shortDramaDiagnosticDialogShowing = false
    internal var shortDramaDiagnosticNotBeforeElapsedMillis = 0L
    internal var shortDramaDiagnosticDialog: AlertDialog? = null
    internal var startupProgressBar: View? = null
    internal var startupProgressLabel: TextView? = null
    internal var startupProgressPercentLabel: TextView? = null
    internal var resumePlaybackWhenForeground = false
    /** Mirrors the foreground policy for the provider-owned PSSDK player. */
    internal var resumeSdkPlaybackWhenForeground = false
    internal var sdkPlaybackActive = false
    internal var restorePlayerAfterCatalog = false
    /** Prevents a second app-open ad while recovering from an ad click's external destination. */
    internal var restoringExternalAdReturn = false
    /** A mediation callback may arrive while the host is stopped behind Google Play or a browser. */
    internal var pendingFullscreenAdResult: (() -> Unit)? = null
    internal var restoredDramaId: String? = null
    internal var restoredEpisodeIndex = 0
    internal var restoredPositionSeconds = 0
    /** Remains set until the recreated PSSDK player reports progress at the requested seek point. */
    internal var pendingExternalAdRestoreEpisodeIndex: Int? = null
    internal var pendingExternalAdRestorePositionSeconds: Int? = null
    /** Protects a normal History/Favorites resume from PSSDK's first zero-second callback. */
    internal var pendingSdkResumeCheckpoint: SdkResumeCheckpoint? = null
    internal var pendingSdkResumeSeekApplied = false
    internal var startupCheckinPromptRequested = false
    internal var startupCheckinPromptShown = false
    /** A deliberate Reward Center check-in must never be followed by the delayed onboarding dialog. */
    internal var startupCheckinPromptSuppressed = false
    internal var startupWelcomePromptShown = false
    internal var selectedDrama: LocalDrama? = null
    internal var currentEpisodeIndex = 0
    internal var pendingUnlockIndex = -1
    internal var episodeStatusView: TextView? = null
    internal var playerProgressSeekBar: SeekBar? = null
    internal var playerProgressText: TextView? = null
    internal var speedButton: TextView? = null
    internal var favoriteButton: TextView? = null
    internal var playerControlsPanel: View? = null
    /** Optional manual reveal control for the host-owned playback dock. */
    internal var playerControlsRevealButton: View? = null
    internal var playerDescriptionView: TextView? = null
    internal var playerDescriptionContainer: View? = null
    internal var playerDescriptionExpanded = false
    internal var playerControlsVisible = true
    /** A user-requested dock remains visible through PLAY callbacks until explicitly hidden. */
    internal var playerControlsPinnedByUser = false
    /** Retained so ad recovery can ask the active local/PSSDK top bar to reapply its real insets. */
    internal var playerTopChrome: View? = null
    internal var playerProgressSeconds = 0
    internal var playerDurationSeconds = 0
    /** Mid-roll time is scoped to the current viewing session and excludes pauses, ads and background time. */
    internal var episodePlaybackStartedAtElapsedMillis = 0L
    internal var episodePlaybackPausedAtElapsedMillis = 0L
    internal var episodePlaybackPausedDurationMillis = 0L
    /** Actual content seconds accumulated across episodes since the last mid-roll attempt. */
    internal var contentWatchSecondsSinceMidroll = 0
    /** The first mid-roll uses its own threshold; all later mid-rolls use the repeat interval. */
    internal var midrollInterstitialShownInSession = false
    internal var lastMidrollSamplePositionSeconds = -1
    internal var midrollInterstitialRequestInFlight = false
    /** Unavailable mid-roll loads are capped for the current title/session to avoid a VPN no-fill loop. */
    internal var midrollInterstitialUnavailableAttempts = 0
    internal var midrollInterstitialSuppressedForSession = false
    internal var pendingResumePositionSeconds = 0
    internal var lastPersistedPlaybackSecond = -1
    internal var playerPauseOverlay: TextView? = null
    internal var playerIsSeeking = false
    internal var playerIsPaused = false
    internal var playerMuted = false
    internal var playerSpeed = 1.0f
    internal var lastSwipeNextAtMillis = 0L
    /** Set only while a full-screen creative is actually on screen. */
    internal var fullscreenAdInProgress = false
    /** Blocks duplicate ad requests while the network loads; playback continues until a creative is ready. */
    internal var fullscreenAdRequestInFlight = false
    /** Blocks the host while a full-screen ad request is resolving. */
    internal var fullscreenAdLoadingOverlay: View? = null
    internal var episodeTransitionInProgress = false
    internal var resumeAfterFullscreenAd = false
    /** Correlates a click-out with the subsequent foreground and playback recovery event. */
    internal var lastClickedAdBehaviorInteraction: AdBehaviorInteraction? = null
    internal var adClickReturnRecorded = false
    internal var adClickPlaybackResumeRecorded = false
    /** 广告展示前退出沉浸模式，关闭后仅在原本处于沉浸播放时恢复。 */
    internal var playerInImmersiveMode = false
    internal var restoreImmersiveAfterFullscreenAd = false
    internal var navigationBackCallback: OnBackPressedCallback? = null
    internal var episodePickerDialog: AlertDialog? = null
    internal val unlockedEpisodeKeys = mutableSetOf<String>()
    // 奖励服务不可达时仅保留本次进程内播放权益；不会伪造为已持久化的服务器权益。
    internal val sessionUnlockedEpisodeKeys = mutableSetOf<String>()
    internal val interstitialRequestedEpisodeKeys = mutableSetOf<String>()
    internal val midEpisodeInterstitialRequestedEpisodeKeys = mutableSetOf<String>()
    /** Player mid-roll ownership guard; unavailable ads never reserve the current playback slot. */
    internal val actionInterstitialShownEpisodeKeys = mutableSetOf<String>()
    /** 仅统计剧中和激励回退等受每集互斥约束的插屏；横滑切剧有独立频控。 */
    internal val interstitialCountByEpisodeKey = mutableMapOf<String, Int>()
    /** Client-side guard for the server-backed all-placement hourly interstitial cap. */
    internal val playbackInterstitialShownAtElapsedMillis = mutableListOf<Long>()
    internal var dramaSwitchSwipeCount = 0
    internal var catalogNextPage = 2
    internal var catalogHasMorePages = false
    internal var catalogPageLoadInFlight = false
    /** The activity observes (but never consumes) PSSDK video swipes, preserving provider controls. */
    internal var pssdkSwipeDownX = 0f
    internal var pssdkSwipeDownY = 0f
    internal var pssdkSwipeCandidate = false
    internal var pssdkLastHorizontalSwipeAt = 0L
    /** Last provider-owned vertical gesture, used only to resolve a subsequent boundary callback. */
    internal var pssdkLastVerticalSwipeAt = 0L
    internal var pssdkLastVerticalSwipeWasUp = false
    /** Prevents a just-replaced preview Fragment from firing a second title switch at its boundary. */
    internal var pssdkLastPreviewDramaSwitchAt = 0L
    internal val completedEpisodeKeys = mutableSetOf<String>()
    internal val rewardWatchRecordedEpisodeKeys = mutableSetOf<String>()
    internal val floatingWheelLabels = mutableSetOf<TextView>()
    internal val crownSummaryChargeButtons = mutableSetOf<TextView>()
    internal val floatingBoostButtons = mutableSetOf<TextView>()
    internal val playerWalletBoostButtons = mutableSetOf<TextView>()
    internal val walletHeaderBoostButtons = mutableSetOf<TextView>()
    internal val walletChargeButtons = mutableSetOf<TextView>()
    internal val walletChargeQuotaLabels = mutableSetOf<TextView>()
    internal val crownChargeCooldownBars = mutableSetOf<ProgressBar>()
    internal val crownChargeCooldownRings = mutableSetOf<ChargeCooldownRingView>()
    internal val walletChargeCooldownRings = mutableSetOf<ChargeCooldownRingView>()
    /** Server-backed cooldowns prevent back-to-back Crown and wallet rewarded placements. */
    internal var crownChargeCooldownUntilElapsedMillis = 0L
    internal var walletChargeCooldownUntilElapsedMillis = 0L
    internal var chargeCooldownTickerRunning = false
    /** 当前页面可见的宝石余额锚点。领取成功后，宝石飞入此位置。 */
    internal var gemBalanceAnchor: View? = null
    internal var gemBalanceLabel: TextView? = null
    internal var cashBalanceAnchor: View? = null
    internal var cashBalanceLabel: TextView? = null
    /** Reward Center hero remains visible while its later-created sticky header is still hidden. */
    internal var rewardHeroCashBalanceLabel: TextView? = null
    internal var lastConfirmedGemBalance: Long? = null
    internal var lastConfirmedCashBalance: String? = null
    internal var gemFlightInProgress = false
    /** Wallet ad-boost confirmation owns this flight so it begins only after the user acknowledges the reward. */
    internal var deferNextCashFlight = false
    internal var deferredCashFlight: Pair<String, String>? = null
    /** Avoid repeatedly interrupting the viewer with the same accumulated-gem prompt. */
    internal var lastFloatingClaimPromptedGems = 0
    /** A newly completed watch task waits briefly so it never collides with episode-unlock UI. */
    internal var taskRewardPromptEarliestAtElapsedMillis = 0L
    /** Only one deferred task prompt may be queued; snapshot refreshes replace rather than duplicate it. */
    internal var taskRewardPromptRunnable: Runnable? = null
    /** Rewards page exposes this only after the hero scrolls away. */
    internal var rewardsStickyAssetHeader: View? = null
    /** Re-rendering a live reward snapshot must retain the reader at Crown Road or the active task. */
    internal var rewardsTasksScrollY = 0
    /** Crown Road is refreshed in place so a claim or cooldown never returns the reader to page top. */
    internal var rewardCrownRoadHost: FrameLayout? = null
    internal var ignoreVideoTapsUntilMillis = 0L
    internal val favorites by lazy { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    internal val playbackHistory by lazy { getSharedPreferences(PLAYBACK_HISTORY_PREFERENCES, Context.MODE_PRIVATE) }
    internal val persistedDramaLibrary by lazy { getSharedPreferences(PERSISTED_DRAMA_LIBRARY_PREFERENCES, Context.MODE_PRIVATE) }
    internal val taskRewardPromptPreferences by lazy { getSharedPreferences("joyedrama.task_reward_prompts", Context.MODE_PRIVATE) }
    internal val userBehaviorReporter by lazy { com.dramaflow.app.analytics.UserBehaviorEventReporter.get(applicationContext) }

    internal val consentStore by lazy { AdConsentStore(applicationContext) }
    internal val adMediationReporter by lazy {
        AdMediationEventReporter(
            applicationContext,
            baseUrlProvider = { rewardsBaseUrl() },
            installationIdProvider = { anonymousInstallationId() },
            sessionTokenProvider = { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(SESSION_TOKEN_KEY, null) },
        )
    }
    internal val topOnAds by lazy {
        TopOnAdsManager(applicationContext, TopOnSettings.fromBuildConfig(), adMediationReporter = adMediationReporter)
    }
    internal val rewardsApi by lazy {
        RewardsApiClient(
            baseUrlProvider = { rewardsBaseUrl() },
            sessionTokenProvider = { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(SESSION_TOKEN_KEY, null) },
            onSessionTokenReceived = { token ->
                getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(SESSION_TOKEN_KEY, token).commit()
                // Early startup can flush the non-financial operations queue before bootstrap
                // issues a session token. Replay it once the signed token is safely available.
                adMediationReporter.flushPending()
                userBehaviorReporter.flushPending()
            },
            onNetworkRetryScheduled = { path, statusCode ->
                userBehaviorReporter.record(
                    "NETWORK",
                    "REWARDS_REQUEST_RETRY_SCHEDULED",
                    mapOf("endpoint" to path, "status_code" to (statusCode ?: 0)),
                )
            },
        ).also { api ->
            userBehaviorReporter.setSessionRefreshHook { failedToken ->
                api.recoverSession(
                    userId = anonymousInstallationId(),
                    region = rewardsMarketCode(),
                    failedToken = failedToken,
                    onReady = { userBehaviorReporter.flushPending() },
                    onFailure = { message -> runOnUiThread { logStatus("行为记录会话恢复等待重试：$message") } },
                )
            }
        }
    }
    internal val shortDramaSdk by lazy { ShortDramaSdkManager.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.SCREENSHOT_PROTECTION_ENABLED) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        val externalAdReturnCheckpoint = readExternalAdReturnCheckpoint()
        if (externalAdReturnCheckpoint != null) {
            restoreExternalAdReturnSurface(externalAdReturnCheckpoint)
            restoringExternalAdReturn = true
            externalAdReturnCheckpoint.adInteractionId
                ?.takeIf { !externalAdReturnCheckpoint.adPlacementId.isNullOrBlank() && !externalAdReturnCheckpoint.adFormat.isNullOrBlank() }
                ?.let { interactionId ->
                    lastClickedAdBehaviorInteraction = AdBehaviorInteraction(
                        interactionId = interactionId,
                        placementId = requireNotNull(externalAdReturnCheckpoint.adPlacementId),
                        adFormat = requireNotNull(externalAdReturnCheckpoint.adFormat),
                        earlyVideoRank = externalAdReturnCheckpoint.adEarlyVideoRank,
                        dramaId = externalAdReturnCheckpoint.dramaId,
                        // The persisted pre-click snapshot has the drama ID and episode index,
                        // but not the mutable catalog title or provider episode ID.
                        episodeId = null,
                        contentTitle = null,
                    )
                    adClickReturnRecorded = false
                    adClickPlaybackResumeRecorded = false
                }
            // A synchronous checkpoint is newer and more reliable than Android's saved Bundle:
            // external click-outs often destroy the process after the Bundle was last written.
            if (externalAdReturnCheckpoint.playerVisible) {
                restorePlayerAfterCatalog = true
                restoredDramaId = externalAdReturnCheckpoint.dramaId
                restoredEpisodeIndex = externalAdReturnCheckpoint.episodeIndex
                restoredPositionSeconds = externalAdReturnCheckpoint.positionSeconds
                logStatus("恢复广告外跳前的播放器：${externalAdReturnCheckpoint.dramaId} #${externalAdReturnCheckpoint.episodeIndex + 1}")
            } else {
                logStatus("恢复广告外跳前页面：${externalAdReturnCheckpoint.activeTabName}")
            }
        } else {
            val savedStateCheckpoint = savedInstanceState?.let(::checkpointFromSavedInstanceState)
            val checkpoint = savedStateCheckpoint?.takeIf { it.isFresh(System.currentTimeMillis()) }
                ?: readBackgroundReturnCheckpoint()
            checkpoint?.let {
                restoreBackgroundReturnSurface(it)
                restoringBackgroundReturn = true
                logStatus("恢复后台回收前页面：${activeTab.name}")
            }
        }
        root = FrameLayout(this).apply { background = pageCanvasDrawable() }
        setContentView(root)
        configureCatalogWindow()
        navigationBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    withdrawalPageShown -> {
                        withdrawalPageShown = false
                        renderActiveTab()
                    }
                    profileCollectionPageShown -> {
                        profileCollectionPageShown = false
                        profileCollectionShowsFavorites = false
                        activeTab = MainTab.PROFILE
                        renderActiveTab()
                    }
                    playerShown || sdkPlayerShown -> showCatalog()
                    else -> finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, requireNotNull(navigationBackCallback))
        adMediationReporter.flushPending()
        userBehaviorReporter.flushPending()
        if (restoringExternalAdReturn) {
            resumeFromExternalAdReturn()
        } else {
            beginStartupExperience()
        }
    }

    /**
     * TopOn 开屏必须在 Activity 已进入前台后展示；在 onCreate 中立即展示会导致部分真机
     * 将广告请求视为不可展示，尤其是冷启动的 Demo/测试广告。
     */
    override fun onPostResume() {
        super.onPostResume()
        userBehaviorReporter.flushPending()
        recordExternalAdReturnIfPending()
        if (!startupContentInitializationStarted) maybeShowAppOpenAd()
        pendingFullscreenAdResult?.let { pending ->
            pendingFullscreenAdResult = null
            pending.invoke()
        }
        if ((playerShown || sdkPlayerShown) && (fullscreenAdInProgress || fullscreenAdRequestInFlight)) {
            // Store/browser click-outs can return without the mediation activity forwarding a
            // close callback. Do not leave the player blocked behind that stale ad state.
            logStatus("检测到广告外跳返回，恢复现有播放器")
            fullscreenAdInProgress = false
            fullscreenAdRequestInFlight = false
            hideFullscreenAdLoadingOverlay()
            resumeAfterFullscreenAdIfNeeded()
        }
        if (resumePlaybackWhenForeground && playerShown && !fullscreenAdInProgress) {
            exoPlayer?.play()
            playerIsPaused = false
            updatePauseOverlayVisibility(false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resumePlaybackWhenForeground = false
            recordPlaybackResumedAfterAdClickIfPending()
        }
        if (resumeSdkPlaybackWhenForeground && sdkPlayerShown && !fullscreenAdInProgress) {
            currentSdkPlayerFragment()?.startPlay()
            sdkPlaybackActive = true
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resumeSdkPlaybackWhenForeground = false
            recordPlaybackResumedAfterAdClickIfPending()
        }
        // A live Activity already retains its View hierarchy. Clear the durable click-out marker
        // only after it is actually foregrounded again; ad-close callbacks can fire before Play or
        // a browser has taken over and must never erase the process-recreation recovery path.
        if (!restoringExternalAdReturn && hasFreshExternalAdReturnSession()) {
            root.postDelayed({
                if (!isFinishing && !isDestroyed && hasWindowFocus() &&
                    !restoringExternalAdReturn && hasFreshExternalAdReturnSession()
                ) {
                    clearExternalAdReturnCheckpoint()
                }
            }, 750L)
        }
    }

    /** Defers close/failure callbacks until the host has returned from an external full-screen surface. */
    internal fun runFullscreenResultWhenForeground(action: () -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action()
            else pendingFullscreenAdResult = action
        }
    }

    /**
     * PSSDK consumes its own View gestures, so the host observes raw touch events at the Activity
     * boundary without returning `true`. Full-player vertical gestures stay provider-owned; on the
     * Discover first-episode preview, either vertical direction switches title and each successful
     * title browse counts towards the same three-switch interstitial policy.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observePssdkSwipe(event)
        return super.dispatchTouchEvent(event)
    }

    private fun observePssdkSwipe(event: MotionEvent) {
        if (!sdkPlayerShown || fullscreenAdInProgress || fullscreenAdRequestInFlight) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pssdkSwipeDownX = event.rawX
                pssdkSwipeDownY = event.rawY
                val topReserved = dp(if (sdkDiscoverPreviewActive) 24 else 142).toFloat()
                val bottomReserved = (root.height - dp(if (sdkDiscoverPreviewActive) 170 else 150)).toFloat()
                pssdkSwipeCandidate = event.rawY in topReserved..bottomReserved
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> pssdkSwipeCandidate = false
            MotionEvent.ACTION_UP -> {
                if (!pssdkSwipeCandidate) return
                pssdkSwipeCandidate = false
                val dx = event.rawX - pssdkSwipeDownX
                val dy = event.rawY - pssdkSwipeDownY
                val now = android.os.SystemClock.elapsedRealtime()
                if (kotlin.math.abs(dx) >= dp(84) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f && now - pssdkLastHorizontalSwipeAt >= 650L) {
                    pssdkLastHorizontalSwipeAt = now
                    // Run after PSSDK receives the UP event; its current video view never loses the gesture.
                    root.post { if (sdkPlayerShown && !fullscreenAdInProgress) switchDramaBySwipe(forward = dx < 0f) }
                } else if (kotlin.math.abs(dy) >= dp(84) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.35f && now - pssdkLastVerticalSwipeAt >= 650L) {
                    val swipeWasUp = dy < 0f
                    pssdkLastVerticalSwipeAt = now
                    pssdkLastVerticalSwipeWasUp = swipeWasUp
                    if (sdkDiscoverPreviewActive && now - pssdkLastPreviewDramaSwitchAt >= 800L) {
                        // Browse preview titles in both directions. PSSDK still receives this gesture;
                        // the timestamp below suppresses its stale boundary callback after replacement.
                        pssdkLastPreviewDramaSwitchAt = now
                        root.post {
                            if (sdkPlayerShown && sdkDiscoverPreviewActive && !fullscreenAdInProgress) {
                                switchDramaBySwipe(
                                    forward = swipeWasUp,
                                    countTowardInterstitial = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Google Play may relaunch the launcher route after an ad click. LauncherActivity forwards
        // that intent to this single-task Activity with CLEAR_TOP/SINGLE_TOP, so retain the
        // existing player rather than starting a fresh loading experience.
        if (playerShown || sdkPlayerShown) {
            logStatus("已复用现有播放器 Activity 处理外部返回")
            if (fullscreenAdInProgress || fullscreenAdRequestInFlight) {
                // Returning via a fresh launcher intent can remove the mediation Activity without
                // delivering its normal close callback. Recover the host UI and the player here.
                fullscreenAdInProgress = false
                fullscreenAdRequestInFlight = false
                hideFullscreenAdLoadingOverlay()
                resumeAfterFullscreenAdIfNeeded()
            }
        }
    }

    override fun onStop() {
        // 后台不播放声音，但保留播放器实例、剧集和进度；回到前台后自动从原位置继续。
        persistPlaybackProgress()
        persistBackgroundReturnCheckpoint()
        resumePlaybackWhenForeground = playerShown && exoPlayer?.isPlaying == true && !fullscreenAdInProgress
        if (resumePlaybackWhenForeground) exoPlayer?.pause()
        // pauseForFullscreenAd already records whether PSSDK must resume when the ad closes.
        // Do not overwrite that intent when the mediation Activity drives this host through onStop.
        if (!fullscreenAdInProgress && !fullscreenAdRequestInFlight) {
            resumeSdkPlaybackWhenForeground = sdkPlayerShown && sdkPlaybackActive && !playerIsPaused
            if (resumeSdkPlaybackWhenForeground) currentSdkPlayerFragment()?.pausePlay()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PLAYER_VISIBLE, (playerShown || sdkPlayerShown) && !sdkDiscoverPreviewActive)
        outState.putString(STATE_DRAMA_ID, selectedDrama?.id)
        outState.putInt(STATE_EPISODE_INDEX, currentEpisodeIndex)
        outState.putInt(STATE_POSITION_SECONDS, exoPlayer?.currentPosition?.div(1_000L)?.toInt() ?: playerProgressSeconds)
        outState.putString(STATE_ACTIVE_TAB_NAME, activeTab.name)
        outState.putString(STATE_PLAYER_RETURN_TAB_NAME, playerReturnTab.name)
        outState.putInt(STATE_TASKS_SCROLL_Y, rewardsTasksScrollY.coerceAtLeast(0))
        outState.putLong(STATE_SAVED_AT_MILLIS, System.currentTimeMillis())
        persistBackgroundReturnCheckpoint()
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pendingFullscreenAdResult = null
        taskRewardPromptRunnable?.let(root::removeCallbacks)
        taskRewardPromptRunnable = null
        dismissSdkDramaPlayerIfShown()
        releasePlayer()
        super.onDestroy()
    }

    /**
     * 启动时不显示“开始追剧”确认框。只有用户先主动启用广告后才可能请求开屏。
     * 先显示 JoyeDrama Logo 加载页；开屏关闭、跳过或无填充后，再进入签到与内容初始化。
     */
    internal fun loadCatalog() {
        loadShortDramaSdkCatalog()
    }

    private fun loadShortDramaSdkCatalog() {
        renderLoading(t("loading_catalog"))
        shortDramaSdk.initializeIfEnabled { initialization -> runOnUiThread {
            when (initialization) {
                ShortDramaSdkManager.Initialization.Ready -> shortDramaSdk.loadDiscovery(
                    onSuccess = { discovery -> runOnUiThread {
                        val cards = (discovery.popular + discovery.latest)
                            .distinctBy { it.id }
                            .filter { it.title.isNotBlank() }
                        sdkDramaCardsByCatalogId.clear()
                        cards.forEach { card -> sdkDramaCardsByCatalogId[sdkCatalogId(card.id)] = card }
                        catalogItems = cards.map(::sdkCatalogDrama)
                        catalogNextPage = 2
                        catalogHasMorePages = discovery.hasMore
                        catalogPageLoadInFlight = false
                        refreshSdkCategories()
                        if (catalogItems.isEmpty()) renderFailure(t("catalog_empty")) else renderActiveTab()
                    } },
                    onFailure = { message -> runOnUiThread { renderFailure(t("catalog_load_failed", message)) } },
                )
                is ShortDramaSdkManager.Initialization.InvalidConfiguration -> renderFailure(t("catalog_load_failed", initialization.message))
                is ShortDramaSdkManager.Initialization.Failed -> renderFailure(t("catalog_load_failed", initialization.message))
                ShortDramaSdkManager.Initialization.Disabled -> renderFailure(t("catalog_load_failed", "Short-drama SDK is disabled in this build"))
            }
        } }
    }

    internal fun loadNextCatalogPage(restoreScrollY: Int) {
        val categoryId = selectedCatalogCategoryId
        if (categoryId != null) {
            loadNextSdkCategoryPage(categoryId, restoreScrollY)
            return
        }
        if (catalogPageLoadInFlight || !catalogHasMorePages || activeTab != MainTab.HOME) return
        catalogPageLoadInFlight = true
        shortDramaSdk.loadDiscoveryPage(catalogNextPage, onSuccess = { discovery -> runOnUiThread {
            val incoming = (discovery.popular + discovery.latest).filter { it.title.isNotBlank() }
            val merged = (sdkDramaCardsByCatalogId.values + incoming).distinctBy { it.id }
            incoming.forEach { sdkDramaCardsByCatalogId[sdkCatalogId(it.id)] = it }
            catalogItems = merged.map(::sdkCatalogDrama)
            catalogHasMorePages = discovery.hasMore && incoming.isNotEmpty()
            catalogNextPage += 1
            catalogPageLoadInFlight = false
            renderActiveTab()
            (catalogPage as? ScrollView)?.post { (catalogPage as? ScrollView)?.scrollTo(0, restoreScrollY) }
        } }, onFailure = { message -> runOnUiThread {
            catalogPageLoadInFlight = false
            catalogHasMorePages = false
            logStatus("PSSDK next catalog page unavailable: $message")
            renderActiveTab()
            (catalogPage as? ScrollView)?.post { (catalogPage as? ScrollView)?.scrollTo(0, restoreScrollY) }
        } })
    }

    internal fun selectSdkCatalogCategory(category: ShortDramaSdkManager.Category?) {
        selectedCatalogCategoryId = category?.id
        selectedCatalogGenre = category?.name
        categoryCatalogItems = emptyList()
        categoryNextPage = 1
        categoryHasMorePages = false
        categoryPageLoadInFlight = false
        if (category == null) {
            renderActiveTab()
            return
        }
        categoryPageLoadInFlight = true
        renderActiveTab()
        shortDramaSdk.loadCategoryPage(category.id, categoryNextPage, onSuccess = { page -> runOnUiThread {
            if (selectedCatalogCategoryId != category.id) return@runOnUiThread
            page.cards.forEach { sdkDramaCardsByCatalogId[sdkCatalogId(it.id)] = it }
            categoryCatalogItems = page.cards.distinctBy { it.id }.map(::sdkCatalogDrama)
            categoryHasMorePages = page.hasMore && page.cards.isNotEmpty()
            categoryNextPage = 2
            categoryPageLoadInFlight = false
            renderActiveTab()
        } }, onFailure = { message -> runOnUiThread {
            if (selectedCatalogCategoryId != category.id) return@runOnUiThread
            categoryPageLoadInFlight = false
            categoryHasMorePages = false
            logStatus("PSSDK category page unavailable: $message")
            renderActiveTab()
        } })
    }

    private fun loadNextSdkCategoryPage(categoryId: Long, restoreScrollY: Int) {
        if (categoryPageLoadInFlight || !categoryHasMorePages || activeTab != MainTab.HOME) return
        categoryPageLoadInFlight = true
        shortDramaSdk.loadCategoryPage(categoryId, categoryNextPage, onSuccess = { page -> runOnUiThread {
            if (selectedCatalogCategoryId != categoryId) return@runOnUiThread
            val incoming = page.cards.filter { it.title.isNotBlank() }
            incoming.forEach { sdkDramaCardsByCatalogId[sdkCatalogId(it.id)] = it }
            categoryCatalogItems = (categoryCatalogItems + incoming.map(::sdkCatalogDrama)).distinctBy { it.id }
            categoryHasMorePages = page.hasMore && incoming.isNotEmpty()
            categoryNextPage += 1
            categoryPageLoadInFlight = false
            renderActiveTab()
            (catalogPage as? ScrollView)?.post { (catalogPage as? ScrollView)?.scrollTo(0, restoreScrollY) }
        } }, onFailure = { message -> runOnUiThread {
            if (selectedCatalogCategoryId != categoryId) return@runOnUiThread
            categoryPageLoadInFlight = false
            categoryHasMorePages = false
            logStatus("PSSDK next category page unavailable: $message")
            renderActiveTab()
            (catalogPage as? ScrollView)?.post { (catalogPage as? ScrollView)?.scrollTo(0, restoreScrollY) }
        } })
    }

    internal fun refreshSdkCategories(expectedLanguageTag: String? = null, expectedGeneration: Int? = null) {
        shortDramaSdk.loadCategories(onSuccess = { categories -> runOnUiThread {
            if (expectedLanguageTag != null && AppLanguage.tag(this) != expectedLanguageTag) return@runOnUiThread
            if (expectedGeneration != null && catalogLanguageRequestGeneration != expectedGeneration) return@runOnUiThread
            sdkCatalogCategories = categories
            if (startupContentInitializationStarted && (activeTab == MainTab.HOME || activeTab == MainTab.DISCOVER)) renderActiveTab()
        } }, onFailure = { message -> logStatus("PSSDK categories unavailable: $message") })
    }

    /**
     * PSSDK applies [PSSDK.setContentLanguage] to new metadata requests only. Re-query the
     * browse feed and category metadata after a user changes their in-app language, without
     * reinitialising the SDK or disturbing an active provider player.
     */
    internal fun refreshSdkCatalogForContentLanguage(languageTag: String) {
        if (sdkPlayerShown || playerShown) return
        val generation = ++catalogLanguageRequestGeneration
        selectedCatalogCategoryId = null
        selectedCatalogGenre = null
        categoryCatalogItems = emptyList()
        categoryNextPage = 1
        categoryHasMorePages = false
        categoryPageLoadInFlight = false
        catalogPageLoadInFlight = true
        refreshSdkCategories(expectedLanguageTag = languageTag, expectedGeneration = generation)
        shortDramaSdk.loadDiscovery(onSuccess = { discovery -> runOnUiThread {
            if (catalogLanguageRequestGeneration != generation || AppLanguage.tag(this) != languageTag) return@runOnUiThread
            val cards = (discovery.popular + discovery.latest)
                .distinctBy { it.id }
                .filter { it.title.isNotBlank() }
            if (cards.isNotEmpty()) {
                sdkDramaCardsByCatalogId.clear()
                cards.forEach { card -> sdkDramaCardsByCatalogId[sdkCatalogId(card.id)] = card }
                catalogItems = cards.map(::sdkCatalogDrama)
                catalogNextPage = 2
                catalogHasMorePages = discovery.hasMore
            }
            catalogPageLoadInFlight = false
            if (activeTab == MainTab.HOME || activeTab == MainTab.DISCOVER) renderActiveTab()
        } }, onFailure = { message -> runOnUiThread {
            if (catalogLanguageRequestGeneration != generation || AppLanguage.tag(this) != languageTag) return@runOnUiThread
            catalogPageLoadInFlight = false
            logStatus("PSSDK language refresh unavailable: $message")
            if (activeTab == MainTab.HOME || activeTab == MainTab.DISCOVER) renderActiveTab()
        } })
    }

    internal fun sdkCatalogId(shortPlayId: Long): String = "pssdk-$shortPlayId"

    internal fun sdkCatalogDrama(card: ShortDramaSdkManager.DramaCard): LocalDrama = LocalDrama(
        id = sdkCatalogId(card.id),
        title = card.title,
        desc = card.summary,
        coverImage = card.coverUrl,
        total = card.totalEpisodes.coerceAtLeast(1),
        freeEpisodes = FREE_EPISODES,
        genres = card.genres,
        // PSSDK owns the media URLs and actual player. These lightweight episode records only
        // provide stable local IDs for the shared unlock, progress and reward state machines.
        episodes = (0 until card.totalEpisodes.coerceAtLeast(1)).map { index ->
            LocalEpisode(
                id = "pssdk-${card.id}-episode-${index + 1}",
                dramaId = sdkCatalogId(card.id),
                index = index,
                title = "Episode ${index + 1}",
                durationSeconds = 0,
                coverUrl = card.coverUrl,
                entitlement = if (index < FREE_EPISODES) "FREE" else "AD_UNLOCK",
            )
        },
    )

    internal enum class MainTab(val languageKey: String, val iconRes: Int) {
        HOME("home", R.drawable.ic_nav_home),
        DISCOVER("discover", R.drawable.ic_nav_discover),
        TASKS("rewards", R.drawable.ic_nav_rewards),
        PROFILE("profile", R.drawable.ic_nav_profile),
    }

    internal fun renderActiveTab() {
        withdrawalPageShown = false
        profileCollectionPageShown = false
        playerShown = false
        configureCatalogWindow()
        gemBalanceAnchor = null
        gemBalanceLabel = null
        cashBalanceAnchor = null
        cashBalanceLabel = null
        rewardHeroCashBalanceLabel = null
        val page = when (activeTab) {
            MainTab.HOME -> createHomePage()
            MainTab.DISCOVER -> createDiscoverPage()
            MainTab.TASKS -> createTasksPage()
            MainTab.PROFILE -> createProfilePage()
        }
        catalogPage = page
        root.removeAllViews()
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 常规页始终显示资产栏；奖励中心仅在 hero 已离开视口时淡入同款吸顶栏。
        rewardsStickyAssetHeader = null
        if (activeTab != MainTab.TASKS) {
            root.addView(createPersistentAssetHeader(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP))
        } else {
            createPersistentAssetHeader().also { header ->
                header.visibility = View.INVISIBLE
                header.alpha = 0f
                rewardsStickyAssetHeader = header
                root.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP))
            }
        }
        if (activeTab == MainTab.HOME) {
            root.addView(
                createHomeBrowseControls(),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112), Gravity.TOP).apply {
                    topMargin = dp(120)
                },
            )
        }
        root.addView(createBottomNavigation(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BOTTOM_NAV_HEIGHT_DP), Gravity.BOTTOM))
        root.addView(createFloatingRewardWheel(), FrameLayout.LayoutParams(dp(130), dp(112), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = 0
            bottomMargin = dp(BOTTOM_NAV_HEIGHT_DP + 12)
        })
    }

    internal fun showAdPrivacySettings() {
        val granted = consentStore.read() == AdConsent.GRANTED
        showBrandedChoiceDialog(
            title = t("ad_privacy"),
            message = if (granted) t("ad_privacy_enabled_message") else t("ad_privacy_disabled_message"),
            primaryLabel = if (granted) t("disable_ads") else t("enable_ads"),
            onPrimary = {
                consentStore.save(if (granted) AdConsent.DECLINED else AdConsent.GRANTED)
                appOpenAttempted = false
                toast(if (granted) t("ads_disabled") else t("ads_enabled"))
            },
            secondaryLabel = t("cancel"),
        )
    }

    internal fun toggleFavorite() {
        val drama = selectedDrama ?: return
        val dramaId = drama.id
        val key = "favorite_$dramaId"
        val nowFavorite = !favorites.getBoolean(key, false)
        favorites.edit().putBoolean(key, nowFavorite).apply()
        if (nowFavorite) saveDramaLibrarySnapshot(drama)
        favoriteButton?.text = if (nowFavorite) "♥" else "♡"
        toast(if (nowFavorite) t("favorite_added") else t("favorite_removed"))
    }

    internal fun isFavorite(): Boolean = selectedDrama?.id?.let { favorites.getBoolean("favorite_$it", false) } ?: false

    internal fun resetMidrollInterstitialSession() {
        contentWatchSecondsSinceMidroll = 0
        midrollInterstitialShownInSession = false
        midrollInterstitialRequestInFlight = false
        lastMidrollSamplePositionSeconds = -1
        midrollInterstitialUnavailableAttempts = 0
        midrollInterstitialSuppressedForSession = false
    }

    internal fun showCatalog() {
        exitImmersivePlayerMode()
        configureCatalogWindow()
        // releasePlayer persists resume state only while the player is still marked visible.
        // Keep this ordering so “My / Continue watching” always resumes the actual episode and time.
        releasePlayer()
        dismissSdkDramaPlayerIfShown()
        clearExternalAdReturnCheckpoint()
        playerShown = false
        activeTab = playerReturnTab
        if (activeTab == MainTab.DISCOVER) discoverPreviewPresented = false
        if (catalogItems.isNotEmpty()) renderActiveTab() else loadCatalog()
    }

    internal fun renderStartupLoading() {
        playerShown = false
        configureCatalogWindow()
        root.removeAllViews()
        root.setBackgroundColor(COLOR_BACKGROUND)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(44), dp(28), dp(44), dp(28))
        }
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.joyedrama_startup_icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "JoyeDrama"
        }, LinearLayout.LayoutParams(dp(198), dp(198)).apply { bottomMargin = dp(18) })
        startupProgressLabel = TextView(this).apply {
            text = t("startup_loading")
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
        }
        column.addView(startupProgressLabel)
        // Resource and ad callbacks finish independently; a numeric percentage can legitimately
        // move backwards when a late gate starts. Use a true indeterminate loader instead of a
        // misleading synthetic progress value.
        startupProgressPercentLabel = null
        startupProgressBar = WhiteLoadingRingView(this).apply { contentDescription = t("startup_loading") }
        column.addView(startupProgressBar, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18)
        })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    internal fun updateStartupProgress(progress: Int, label: String) {
        // Keep the diagnostic label available to accessibility and logs, but never expose a
        // percentage whose independent asynchronous gates can visually rewind.
        startupProgressBar?.contentDescription = label
        startupProgressLabel?.text = t("startup_loading")
        startupProgressLabel?.contentDescription = label
    }

    /** One window policy for all catalogue surfaces; prevents a player/ad window state leaking into pages. */
    internal fun configureCatalogWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        topOnAds.setHostEdgeToEdge(this, false)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Player controls are laid out inside the same transparent edge-to-edge canvas before and after ads. */
    internal fun configurePlayerWindow() {
        // Do not let the system navigation inset shrink the PSSDK fragment. Its media container
        // must receive the complete Activity canvas while controls are hidden during playback.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        topOnAds.setHostEdgeToEdge(this, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (playerInImmersiveMode) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                // Keep the status area available for the back and balance chrome, but hide the
                // persistent Android gesture handle under the player controls. A swipe still
                // reveals it transiently when the user needs system navigation.
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        root.post {
            if ((playerShown || sdkPlayerShown) && !playerInImmersiveMode) {
                WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        root.requestLayout()
        ViewCompat.requestApplyInsets(root)
    }

    internal fun renderLoading(message: String) {
        playerShown = false
        root.removeAllViews()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        column.addView(WhiteLoadingRingView(this), LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    internal fun renderFailure(message: String) {
        playerShown = false
        root.removeAllViews()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        column.addView(TextView(this).apply {
            text = t("server_unavailable")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        column.addView(TextView(this).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(12), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = t("server_hint")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(Button(this).apply {
            text = t("retry")
            isAllCaps = false
            setOnClickListener { loadCatalog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    internal fun rewardsBaseUrl(): String = BuildConfig.REWARDS_API_BASE_URL.trim().trimEnd('/')

    internal fun resumePreferenceKey(dramaId: String, suffix: String): String = "resume_${suffix}_${dramaId}"

    /** Saves renderable title metadata separately from PSSDK's transient paged discovery feed. */
    internal fun saveDramaLibrarySnapshot(drama: LocalDrama) {
        PersistedDramaLibrary.save(persistedDramaLibrary, drama)
    }

    /**
     * Returns current provider records plus durable metadata for titles not present in this launch's
     * first PSSDK page. Existing favorite and progress keys remain the source of truth for filters.
     */
    internal fun savedDramaLibraryItems(): List<LocalDrama> {
        catalogItems.asSequence()
            .filter { drama ->
                favorites.getBoolean("favorite_${drama.id}", false) ||
                    playbackHistory.contains(resumePreferenceKey(drama.id, "episode"))
            }
            .forEach(::saveDramaLibrarySnapshot)
        return PersistedDramaLibrary.mergeWithCurrentCatalog(
            currentCatalog = catalogItems,
            persistedSnapshots = PersistedDramaLibrary.snapshots(persistedDramaLibrary),
        )
    }

    /**
     * A return checkpoint stores a provider ID, not a transient discovery-page position. Prefer a
     * fresh provider card, then reuse the display metadata kept for History/Favorites so showPlayer
     * can invoke its existing provider-ID resolution path.
     */
    internal fun findRestorableDrama(dramaId: String): LocalDrama? =
        catalogItems.firstOrNull { it.id == dramaId }
            ?: PersistedDramaLibrary.snapshots(persistedDramaLibrary)
                .firstOrNull { it.id == dramaId }
                ?.toLocalDrama()

    internal fun savedResumeEpisodeIndex(drama: LocalDrama): Int = playbackHistory
        .getInt(resumePreferenceKey(drama.id, "episode"), 0)
        .coerceIn(0, drama.episodes.lastIndex.coerceAtLeast(0))

    internal fun savedResumePositionSeconds(drama: LocalDrama): Int = playbackHistory
        .getInt(resumePreferenceKey(drama.id, "position"), 0)
        .coerceAtLeast(0)

    internal fun persistPlaybackProgressIfNeeded(positionSeconds: Int) {
        if (positionSeconds == lastPersistedPlaybackSecond || positionSeconds % 3 != 0) return
        persistPlaybackProgress(positionSeconds)
    }

    internal fun persistPlaybackProgress(positionSeconds: Int = playerProgressSeconds) {
        val drama = selectedDrama ?: return
        // Discover is a browse-only feed preview. It must never turn a scrolled title into
        // Continue Watching history; history begins only after the user opens the full player.
        if (sdkDiscoverPreviewActive) return
        if ((!playerShown && !sdkPlayerShown) || currentEpisodeIndex !in drama.episodes.indices) return
        val safePosition = positionSeconds.coerceAtLeast(0)
        playbackHistory.edit()
            .putInt(resumePreferenceKey(drama.id, "episode"), currentEpisodeIndex)
            .putInt(resumePreferenceKey(drama.id, "position"), safePosition)
            .putLong(resumePreferenceKey(drama.id, "last_watched_at"), System.currentTimeMillis())
            .apply()
        saveDramaLibrarySnapshot(drama)
        lastPersistedPlaybackSecond = safePosition
    }

    /**
     * Persist synchronously only after TopOn reports an actual creative click. A shown-but-closed
     * creative must not turn a later ordinary launcher start into a false external-ad recovery.
     */
    internal fun markExternalAdReturnCheckpoint() {
        val savedAtMillis = System.currentTimeMillis()
        val playerCanResume = (playerShown || sdkPlayerShown) && !sdkDiscoverPreviewActive &&
            selectedDrama != null && currentEpisodeIndex in selectedDrama!!.episodes.indices
        val editor = playbackHistory.edit()
            .putLong(AD_RETURN_EXTERNAL_SAVED_AT_MILLIS, savedAtMillis)
            .putString(AD_RETURN_TAB_NAME, activeTab.name)
            .putString(AD_RETURN_PLAYER_RETURN_TAB_NAME, playerReturnTab.name)
            .putBoolean(AD_RETURN_PLAYER_VISIBLE, playerCanResume)
            .putInt(AD_RETURN_TASKS_SCROLL_Y, rewardsTasksScrollY.coerceAtLeast(0))
            .putBoolean(AD_RETURN_WITHDRAWAL_PAGE_SHOWN, withdrawalPageShown)
            .putBoolean(AD_RETURN_PROFILE_COLLECTION_PAGE_SHOWN, profileCollectionPageShown)
            .putBoolean(AD_RETURN_PROFILE_COLLECTION_SHOWS_FAVORITES, profileCollectionShowsFavorites)
            .putBoolean(AD_RETURN_HAS_CATALOG_CATEGORY_ID, selectedCatalogCategoryId != null)
        lastClickedAdBehaviorInteraction?.let { interaction ->
            editor
                .putString(AD_RETURN_BEHAVIOR_INTERACTION_ID, interaction.interactionId)
                .putString(AD_RETURN_BEHAVIOR_PLACEMENT_ID, interaction.placementId)
                .putString(AD_RETURN_BEHAVIOR_FORMAT, interaction.adFormat)
            interaction.earlyVideoRank?.let { editor.putInt(AD_RETURN_BEHAVIOR_EARLY_VIDEO_RANK, it) }
                ?: editor.remove(AD_RETURN_BEHAVIOR_EARLY_VIDEO_RANK)
        } ?: editor
            .remove(AD_RETURN_BEHAVIOR_INTERACTION_ID)
            .remove(AD_RETURN_BEHAVIOR_PLACEMENT_ID)
            .remove(AD_RETURN_BEHAVIOR_FORMAT)
            .remove(AD_RETURN_BEHAVIOR_EARLY_VIDEO_RANK)
        selectedCatalogCategoryId?.let { editor.putLong(AD_RETURN_CATALOG_CATEGORY_ID, it) }
            ?: editor.remove(AD_RETURN_CATALOG_CATEGORY_ID)
        selectedCatalogGenre?.let { editor.putString(AD_RETURN_CATALOG_GENRE, it) }
            ?: editor.remove(AD_RETURN_CATALOG_GENRE)
        val drama = selectedDrama
        if (!playerCanResume || drama == null) {
            editor
                .remove(AD_RETURN_DRAMA_ID)
                .remove(AD_RETURN_EPISODE_INDEX)
                .remove(AD_RETURN_POSITION_SECONDS)
            editor.commit()
            logStatus("已保存非播放器广告外跳会话")
            return
        }
        val positionSeconds = (exoPlayer?.currentPosition?.div(1_000L)?.toInt() ?: playerProgressSeconds)
            .coerceAtLeast(playerProgressSeconds)
            .coerceAtLeast(0)
        editor
            .putString(AD_RETURN_DRAMA_ID, drama.id)
            .putInt(AD_RETURN_EPISODE_INDEX, currentEpisodeIndex)
            .putInt(AD_RETURN_POSITION_SECONDS, positionSeconds)
            .putLong(AD_RETURN_SAVED_AT_MILLIS, savedAtMillis)
            .commit()
        logStatus("已保存广告外跳播放检查点：${drama.id} #${currentEpisodeIndex + 1} @${positionSeconds}s")
    }

    internal fun readExternalAdReturnCheckpoint(): ExternalAdReturnCheckpoint? {
        val hasCatalogCategoryId = playbackHistory.getBoolean(AD_RETURN_HAS_CATALOG_CATEGORY_ID, false)
        val checkpoint = ExternalAdReturnCheckpoint(
            activeTabName = playbackHistory.getString(AD_RETURN_TAB_NAME, null).orEmpty(),
            playerReturnTabName = playbackHistory.getString(AD_RETURN_PLAYER_RETURN_TAB_NAME, null).orEmpty(),
            playerVisible = playbackHistory.getBoolean(AD_RETURN_PLAYER_VISIBLE, false),
            dramaId = playbackHistory.getString(AD_RETURN_DRAMA_ID, null).orEmpty(),
            episodeIndex = playbackHistory.getInt(AD_RETURN_EPISODE_INDEX, -1),
            positionSeconds = playbackHistory.getInt(AD_RETURN_POSITION_SECONDS, -1),
            tasksScrollY = playbackHistory.getInt(AD_RETURN_TASKS_SCROLL_Y, 0).coerceAtLeast(0),
            withdrawalPageShown = playbackHistory.getBoolean(AD_RETURN_WITHDRAWAL_PAGE_SHOWN, false),
            profileCollectionPageShown = playbackHistory.getBoolean(AD_RETURN_PROFILE_COLLECTION_PAGE_SHOWN, false),
            profileCollectionShowsFavorites = playbackHistory.getBoolean(AD_RETURN_PROFILE_COLLECTION_SHOWS_FAVORITES, false),
            catalogCategoryId = if (hasCatalogCategoryId) playbackHistory.getLong(AD_RETURN_CATALOG_CATEGORY_ID, 0L) else null,
            catalogGenre = playbackHistory.getString(AD_RETURN_CATALOG_GENRE, null),
            adInteractionId = playbackHistory.getString(AD_RETURN_BEHAVIOR_INTERACTION_ID, null),
            adPlacementId = playbackHistory.getString(AD_RETURN_BEHAVIOR_PLACEMENT_ID, null),
            adFormat = playbackHistory.getString(AD_RETURN_BEHAVIOR_FORMAT, null),
            adEarlyVideoRank = playbackHistory.getInt(AD_RETURN_BEHAVIOR_EARLY_VIDEO_RANK, 0).takeIf { it in 1..5 },
            savedAtMillis = playbackHistory.getLong(AD_RETURN_EXTERNAL_SAVED_AT_MILLIS, 0L),
        )
        return checkpoint.takeIf { it.isFresh(System.currentTimeMillis()) }.also {
            if (it == null) clearExternalAdReturnCheckpoint()
        }
    }

    internal fun hasFreshExternalAdReturnSession(): Boolean {
        return readExternalAdReturnCheckpoint() != null
    }

    /** Restores the non-player surface exactly as it was when the external creative was clicked. */
    internal fun restoreExternalAdReturnSurface(checkpoint: ExternalAdReturnCheckpoint) {
        activeTab = MainTab.entries.firstOrNull { it.name == checkpoint.activeTabName } ?: MainTab.HOME
        playerReturnTab = MainTab.entries.firstOrNull { it.name == checkpoint.playerReturnTabName } ?: activeTab
        rewardsTasksScrollY = checkpoint.tasksScrollY
        withdrawalPageShown = checkpoint.withdrawalPageShown
        profileCollectionPageShown = checkpoint.profileCollectionPageShown
        profileCollectionShowsFavorites = checkpoint.profileCollectionShowsFavorites
        selectedCatalogCategoryId = checkpoint.catalogCategoryId
        selectedCatalogGenre = checkpoint.catalogGenre
        logStatus("恢复广告外跳前页面：${activeTab.name}")
    }

    /** Renders a recovered non-player route without converting it into a player or a default tab. */
    internal fun renderRecoveredExternalAdReturnSurface() {
        when {
            profileCollectionPageShown -> showProfileCollectionPage(profileCollectionShowsFavorites)
            withdrawalPageShown && rewardsSnapshot?.wallet != null -> showWithdrawalPage(requireNotNull(rewardsSnapshot).wallet)
            withdrawalPageShown -> {
                // Wait for the asynchronous wallet snapshot, while retaining the desired route.
                val restoreWithdrawal = withdrawalPageShown
                renderActiveTab()
                withdrawalPageShown = restoreWithdrawal
            }
            else -> renderActiveTab()
        }
    }

    internal fun clearExternalAdReturnCheckpoint() {
        playbackHistory.edit()
            .remove(AD_RETURN_DRAMA_ID)
            .remove(AD_RETURN_EPISODE_INDEX)
            .remove(AD_RETURN_POSITION_SECONDS)
            .remove(AD_RETURN_SAVED_AT_MILLIS)
            .remove(AD_RETURN_EXTERNAL_SAVED_AT_MILLIS)
            .remove(AD_RETURN_TAB_NAME)
            .remove(AD_RETURN_PLAYER_RETURN_TAB_NAME)
            .remove(AD_RETURN_PLAYER_VISIBLE)
            .remove(AD_RETURN_TASKS_SCROLL_Y)
            .remove(AD_RETURN_WITHDRAWAL_PAGE_SHOWN)
            .remove(AD_RETURN_PROFILE_COLLECTION_PAGE_SHOWN)
            .remove(AD_RETURN_PROFILE_COLLECTION_SHOWS_FAVORITES)
            .remove(AD_RETURN_HAS_CATALOG_CATEGORY_ID)
            .remove(AD_RETURN_CATALOG_CATEGORY_ID)
            .remove(AD_RETURN_CATALOG_GENRE)
            .remove(AD_RETURN_BEHAVIOR_INTERACTION_ID)
            .remove(AD_RETURN_BEHAVIOR_PLACEMENT_ID)
            .remove(AD_RETURN_BEHAVIOR_FORMAT)
            .remove(AD_RETURN_BEHAVIOR_EARLY_VIDEO_RANK)
            .apply()
    }

    private fun checkpointFromSavedInstanceState(state: Bundle): AppReturnCheckpoint = AppReturnCheckpoint(
        activeTabName = state.getString(STATE_ACTIVE_TAB_NAME).orEmpty(),
        playerReturnTabName = state.getString(STATE_PLAYER_RETURN_TAB_NAME).orEmpty(),
        playerVisible = state.getBoolean(STATE_PLAYER_VISIBLE, false),
        dramaId = state.getString(STATE_DRAMA_ID),
        episodeIndex = state.getInt(STATE_EPISODE_INDEX, -1),
        positionSeconds = state.getInt(STATE_POSITION_SECONDS, -1),
        tasksScrollY = state.getInt(STATE_TASKS_SCROLL_Y, 0).coerceAtLeast(0),
        savedAtMillis = state.getLong(STATE_SAVED_AT_MILLIS, 0L),
    )

    internal fun persistBackgroundReturnCheckpoint() {
        val playerCanResume = (playerShown || sdkPlayerShown) && !sdkDiscoverPreviewActive &&
            selectedDrama != null && currentEpisodeIndex >= 0
        val positionSeconds = (exoPlayer?.currentPosition?.div(1_000L)?.toInt() ?: playerProgressSeconds)
            .coerceAtLeast(playerProgressSeconds)
            .coerceAtLeast(0)
        val editor = playbackHistory.edit()
            .putString(APP_RETURN_ACTIVE_TAB_NAME, activeTab.name)
            .putString(APP_RETURN_PLAYER_RETURN_TAB_NAME, playerReturnTab.name)
            .putBoolean(APP_RETURN_PLAYER_VISIBLE, playerCanResume)
            .putInt(APP_RETURN_TASKS_SCROLL_Y, rewardsTasksScrollY.coerceAtLeast(0))
            .putLong(APP_RETURN_SAVED_AT_MILLIS, System.currentTimeMillis())
        if (playerCanResume) {
            editor
                .putString(APP_RETURN_DRAMA_ID, selectedDrama?.id)
                .putInt(APP_RETURN_EPISODE_INDEX, currentEpisodeIndex)
                .putInt(APP_RETURN_POSITION_SECONDS, positionSeconds)
        } else {
            editor
                .remove(APP_RETURN_DRAMA_ID)
                .remove(APP_RETURN_EPISODE_INDEX)
                .remove(APP_RETURN_POSITION_SECONDS)
        }
        editor.commit()
    }

    private fun readBackgroundReturnCheckpoint(): AppReturnCheckpoint? {
        val checkpoint = AppReturnCheckpoint(
            activeTabName = playbackHistory.getString(APP_RETURN_ACTIVE_TAB_NAME, null).orEmpty(),
            playerReturnTabName = playbackHistory.getString(APP_RETURN_PLAYER_RETURN_TAB_NAME, null).orEmpty(),
            playerVisible = playbackHistory.getBoolean(APP_RETURN_PLAYER_VISIBLE, false),
            dramaId = playbackHistory.getString(APP_RETURN_DRAMA_ID, null),
            episodeIndex = playbackHistory.getInt(APP_RETURN_EPISODE_INDEX, -1),
            positionSeconds = playbackHistory.getInt(APP_RETURN_POSITION_SECONDS, -1),
            tasksScrollY = playbackHistory.getInt(APP_RETURN_TASKS_SCROLL_Y, 0).coerceAtLeast(0),
            savedAtMillis = playbackHistory.getLong(APP_RETURN_SAVED_AT_MILLIS, 0L),
        )
        return checkpoint.takeIf { it.isFresh(System.currentTimeMillis()) }.also {
            if (it == null) clearBackgroundReturnCheckpoint()
        }
    }

    private fun restoreBackgroundReturnSurface(checkpoint: AppReturnCheckpoint) {
        activeTab = MainTab.entries.firstOrNull { it.name == checkpoint.activeTabName } ?: MainTab.HOME
        playerReturnTab = MainTab.entries.firstOrNull { it.name == checkpoint.playerReturnTabName } ?: activeTab
        rewardsTasksScrollY = checkpoint.tasksScrollY
        if (checkpoint.playerVisible) {
            restorePlayerAfterCatalog = true
            restoredDramaId = checkpoint.dramaId
            restoredEpisodeIndex = checkpoint.episodeIndex
            restoredPositionSeconds = checkpoint.positionSeconds
        }
    }

    private fun clearBackgroundReturnCheckpoint() {
        playbackHistory.edit()
            .remove(APP_RETURN_ACTIVE_TAB_NAME)
            .remove(APP_RETURN_PLAYER_RETURN_TAB_NAME)
            .remove(APP_RETURN_PLAYER_VISIBLE)
            .remove(APP_RETURN_DRAMA_ID)
            .remove(APP_RETURN_EPISODE_INDEX)
            .remove(APP_RETURN_POSITION_SECONDS)
            .remove(APP_RETURN_TASKS_SCROLL_Y)
            .remove(APP_RETURN_SAVED_AT_MILLIS)
            .apply()
    }

    /** Reserves a stable ID before the rewarded SDK can report a verification callback. */
    internal fun preparePendingRewardedSettlement(
        placementId: String,
        targetId: String?,
        episodeIndex: Int?,
    ): PendingRewardedSettlement {
        val checkpoint = PendingRewardedSettlement(
            eventId = "topon-${UUID.randomUUID()}",
            userId = anonymousInstallationId(),
            placementId = placementId,
            targetId = targetId?.takeIf(String::isNotBlank),
            episodeIndex = episodeIndex,
            savedAtMillis = System.currentTimeMillis(),
            stage = RewardedSettlementStage.PREPARED,
            dramaId = selectedDrama?.id,
        )
        writePendingRewardedSettlement(checkpoint)
        return checkpoint
    }

    /** The TopOn verification callback is made durable on its callback thread before UI dispatch. */
    internal fun markPendingRewardedSettlementVerified(
        checkpoint: PendingRewardedSettlement,
    ): PendingRewardedSettlement? = updatePendingRewardedSettlement(checkpoint.eventId) {
        it.copy(stage = RewardedSettlementStage.VERIFIED, savedAtMillis = System.currentTimeMillis())
    }

    /** Preserve a server-confirmed episode target until the restored PSSDK player consumes it once. */
    internal fun markPendingRewardedSettlementTargetPending(
        checkpoint: PendingRewardedSettlement,
    ): PendingRewardedSettlement? = updatePendingRewardedSettlement(checkpoint.eventId) {
        it.copy(stage = RewardedSettlementStage.SETTLED_TARGET_PENDING, savedAtMillis = System.currentTimeMillis())
    }

    private fun writePendingRewardedSettlement(checkpoint: PendingRewardedSettlement) {
        playbackHistory.edit()
            .putString(PENDING_REWARDED_EVENT_ID, checkpoint.eventId)
            .putString(PENDING_REWARDED_USER_ID, checkpoint.userId)
            .putString(PENDING_REWARDED_PLACEMENT_ID, checkpoint.placementId)
            .putString(PENDING_REWARDED_TARGET_ID, checkpoint.targetId)
            .putInt(PENDING_REWARDED_EPISODE_INDEX, checkpoint.episodeIndex ?: -1)
            .putLong(PENDING_REWARDED_SAVED_AT_MILLIS, checkpoint.savedAtMillis)
            .putString(PENDING_REWARDED_STAGE, checkpoint.stage.name)
            .putString(PENDING_REWARDED_DRAMA_ID, checkpoint.dramaId)
            .commit()
    }

    internal fun readPendingRewardedSettlement(): PendingRewardedSettlement? {
        val checkpoint = readStoredPendingRewardedSettlement() ?: return null
        if (checkpoint.stage == RewardedSettlementStage.PREPARED) {
            // The process died before TopOn supplied a verifiable reward callback. A reserved
            // event ID, presentation, or close callback is not reward evidence and must not be
            // replayed as a completion on the next launch.
            clearPendingRewardedSettlement(checkpoint.eventId)
            logStatus("已丢弃未验证激励预分配：缺少可追溯的奖励回调证据")
            return null
        }
        return checkpoint.takeIf { it.stage == RewardedSettlementStage.VERIFIED }
    }

    internal fun readSettledRewardedTarget(): PendingRewardedSettlement? {
        return readStoredPendingRewardedSettlement()?.takeIf(PendingRewardedSettlement::requiresTargetRelease)
    }

    private fun readStoredPendingRewardedSettlement(): PendingRewardedSettlement? {
        val storedEpisodeIndex = playbackHistory.getInt(PENDING_REWARDED_EPISODE_INDEX, -1)
        // Existing installs stored only verified callbacks. Treat an absent stage as that legacy
        // verified state; a preallocated ID is never upgraded without the callback below.
        val stage = playbackHistory.getString(PENDING_REWARDED_STAGE, null)
            ?.let { runCatching { RewardedSettlementStage.valueOf(it) }.getOrNull() }
            ?: RewardedSettlementStage.VERIFIED
        val checkpoint = PendingRewardedSettlement(
            eventId = playbackHistory.getString(PENDING_REWARDED_EVENT_ID, null).orEmpty(),
            userId = playbackHistory.getString(PENDING_REWARDED_USER_ID, null).orEmpty(),
            placementId = playbackHistory.getString(PENDING_REWARDED_PLACEMENT_ID, null).orEmpty(),
            targetId = playbackHistory.getString(PENDING_REWARDED_TARGET_ID, null)?.takeIf(String::isNotBlank),
            episodeIndex = storedEpisodeIndex.takeIf { it > 0 },
            savedAtMillis = playbackHistory.getLong(PENDING_REWARDED_SAVED_AT_MILLIS, 0L),
            stage = stage,
            dramaId = playbackHistory.getString(PENDING_REWARDED_DRAMA_ID, null)?.takeIf(String::isNotBlank),
        )
        return checkpoint.takeIf { it.isFresh(System.currentTimeMillis()) }.also {
            if (it == null) clearPendingRewardedSettlement()
        }
    }

    internal fun clearPreparedPendingRewardedSettlement(eventId: String) {
        val checkpoint = readStoredPendingRewardedSettlement() ?: return
        if (checkpoint.eventId == eventId && checkpoint.stage == RewardedSettlementStage.PREPARED) {
            clearPendingRewardedSettlement(eventId)
        }
    }

    private fun updatePendingRewardedSettlement(
        eventId: String,
        transform: (PendingRewardedSettlement) -> PendingRewardedSettlement,
    ): PendingRewardedSettlement? {
        val checkpoint = readStoredPendingRewardedSettlement() ?: return null
        if (checkpoint.eventId != eventId) return null
        return transform(checkpoint).also(::writePendingRewardedSettlement)
    }

    internal fun clearPendingRewardedSettlement(eventId: String? = null) {
        if (eventId != null && playbackHistory.getString(PENDING_REWARDED_EVENT_ID, null) != eventId) return
        playbackHistory.edit()
            .remove(PENDING_REWARDED_EVENT_ID)
            .remove(PENDING_REWARDED_USER_ID)
            .remove(PENDING_REWARDED_PLACEMENT_ID)
            .remove(PENDING_REWARDED_TARGET_ID)
            .remove(PENDING_REWARDED_EPISODE_INDEX)
            .remove(PENDING_REWARDED_SAVED_AT_MILLIS)
            .remove(PENDING_REWARDED_STAGE)
            .remove(PENDING_REWARDED_DRAMA_ID)
            .commit()
    }

    /**
     * Server settlement and PSSDK's block listener can arrive in either order after process death.
     * Once bootstrap has restored the server entitlement, select the original target exactly once.
     */
    internal fun releaseSettledRewardedTargetIfPossible(): Boolean {
        val checkpoint = readSettledRewardedTarget() ?: return false
        val targetDramaId = checkpoint.dramaId ?: return false
        val drama = selectedDrama ?: return false
        val targetIndex = (checkpoint.episodeIndex ?: return false) - 1
        if (!resumeSettledEpisodeTargetIfReady(targetDramaId, targetIndex)) return false
        clearPendingRewardedSettlement(checkpoint.eventId)
        logStatus("已恢复服务端确认的广告解锁：${drama.id} #${targetIndex + 1}")
        return true
    }

    internal fun persistNextEpisodeResume(completedIndex: Int) {
        val drama = selectedDrama ?: return
        // Keep provider-feed preview episodes out of both the history list and saved resume state.
        if (sdkDiscoverPreviewActive) return
        val nextIndex = (completedIndex + 1).coerceAtMost(drama.episodes.lastIndex.coerceAtLeast(0))
        playbackHistory.edit()
            .putInt(resumePreferenceKey(drama.id, "episode"), nextIndex)
            .putInt(resumePreferenceKey(drama.id, "position"), 0)
            .putLong(resumePreferenceKey(drama.id, "last_watched_at"), System.currentTimeMillis())
            .apply()
        saveDramaLibrarySnapshot(drama)
    }

    internal fun releasePlayer() {
        persistPlaybackProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressHandler.removeCallbacks(progressTicker)
        playerView?.player = null
        playerView = null
        playerPauseOverlay = null
        playerTopChrome = null
        playerControlsPanel = null
        playerControlsRevealButton = null
        exoPlayer?.release()
        exoPlayer = null
    }

    internal fun anonymousInstallationId(): String {
        return getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(INSTALLATION_KEY, null)
            ?: UUID.randomUUID().toString().also {
                getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(INSTALLATION_KEY, it).apply()
            }
    }

    internal fun pageCanvasDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(COLOR_BACKGROUND, COLOR_BACKGROUND),
    )

    internal fun gradientDrawable(colors: IntArray, radiusDp: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        colors,
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    /** All primary actions share the same clean, border-free neon-green to lime treatment. */
    internal fun primaryActionDrawable(): GradientDrawable = rewardActionDrawable()

    /** A single gradient face only: the reference uses no outline, inset frame, or button border. */
    internal fun rewardActionDrawable(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(COLOR_REWARD_ACTION_START, COLOR_REWARD_ACTION_END),
        ).apply {
            cornerRadius = dp(13).toFloat()
        }

    internal fun roundedDrawable(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    internal fun topRoundedDrawable(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
            dp(radiusDp).toFloat(), dp(radiusDp).toFloat(),
            dp(radiusDp).toFloat(), dp(radiusDp).toFloat(),
            0f, 0f, 0f, 0f
        )
        setColor(color)
    }

    internal fun posterFallbackDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF383A3E.toInt(), 0xFF26282C.toInt(), 0xFF151619.toInt())
    ).apply { cornerRadius = dp(10).toFloat() }

    internal fun showLanguageSettings() {
        // A delayed startup reward prompt is an onboarding event, never a consequence of opening language settings.
        if (startupCheckinPromptRequested && !startupCheckinPromptShown) {
            startupCheckinPromptRequested = false
            startupCheckinPromptShown = true
        }
        val options = AppLanguage.options
        val selected = options.indexOfFirst { it.tag == AppLanguage.tag(this) }.coerceAtLeast(0)
        showBrandedOptionsDialog(t("language"), options.map { it.label }, selected) { which ->
            val languageTag = options[which].tag
            AppLanguage.save(this, languageTag)
            ShortDramaSdkManager.get(this).setContentLanguageForUi(languageTag)
            refreshSdkCatalogForContentLanguage(languageTag)
            if (withdrawalPageShown) rewardsSnapshot?.wallet?.let(::showWithdrawalPage) else renderActiveTab()
        }
    }

    internal fun rewardsMarketCode(): String = Locale.getDefault().country.trim().uppercase(Locale.ROOT).ifBlank { "US" }

    internal fun t(key: String, vararg values: Any): String = AppLanguage.text(this, key, *values)
    internal fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    internal fun logStatus(message: String) = android.util.Log.i(TAG, message)
    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * LauncherActivity is recreated by some external destinations (including Google Play).
         * Route that return to the live player instead of pushing a second startup Activity.
         */
        fun intent(context: Context): Intent = Intent(context, DramaAppActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}
