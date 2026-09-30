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
    internal var sdkPlayerShown = false
    internal var sdkPlayerContainerId = View.NO_ID
    internal var selectedCatalogGenre: String? = null
    internal var activeTab: MainTab = MainTab.HOME
    internal var withdrawalPageShown = false
    internal var rewardsSnapshot: RewardsApiClient.Snapshot? = null
    internal var rewardsLoading = false
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
    /** PSSDK can fail its first remote bootstrap on a cold network; retries never block navigation. */
    internal var shortDramaRetryAttempt = 0
    internal var shortDramaRetryScheduled = false
    internal var shortDramaRetryGeneration = 0
    internal var pendingShortDramaDiagnostic: String? = null
    internal var shownShortDramaDiagnostic: String? = null
    internal var shortDramaDiagnosticDialogShowing = false
    internal var shortDramaDiagnosticNotBeforeElapsedMillis = 0L
    internal var shortDramaDiagnosticDialog: AlertDialog? = null
    internal var startupProgressBar: ProgressBar? = null
    internal var startupProgressLabel: TextView? = null
    internal var startupProgressPercentLabel: TextView? = null
    internal var resumePlaybackWhenForeground = false
    /** Mirrors the foreground policy for the provider-owned PSSDK player. */
    internal var resumeSdkPlaybackWhenForeground = false
    internal var sdkPlaybackActive = false
    internal var restorePlayerAfterCatalog = false
    /** Prevents a second app-open ad while recovering from an ad click's external destination. */
    internal var restoringExternalAdReturn = false
    internal var restoredDramaId: String? = null
    internal var restoredEpisodeIndex = 0
    internal var restoredPositionSeconds = 0
    internal var startupCheckinPromptRequested = false
    internal var startupCheckinPromptShown = false
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
    internal var playerControlsRevealButton: View? = null
    internal var playerDescriptionView: TextView? = null
    internal var playerDescriptionContainer: View? = null
    internal var playerDescriptionExpanded = false
    internal var playerControlsVisible = true
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
    /** Blocks duplicate taps while an ad is loading and pauses playback behind the loading overlay. */
    internal var fullscreenAdRequestInFlight = false
    /** A semi-transparent input-blocking indicator remains until the ad creative takes over or fails. */
    internal var fullscreenAdLoadingOverlay: View? = null
    internal var episodeTransitionInProgress = false
    internal var resumeAfterFullscreenAd = false
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
    internal val taskRewardPromptPreferences by lazy { getSharedPreferences("joyedrama.task_reward_prompts", Context.MODE_PRIVATE) }

    internal val consentStore by lazy { AdConsentStore(applicationContext) }
    internal val adMediationReporter by lazy {
        AdMediationEventReporter(
            applicationContext,
            baseUrlProvider = { rewardsBaseUrl() },
            installationIdProvider = { anonymousInstallationId() },
        )
    }
    internal val topOnAds by lazy {
        TopOnAdsManager(applicationContext, TopOnSettings.fromBuildConfig(), adMediationReporter = adMediationReporter)
    }
    internal val rewardsApi by lazy { RewardsApiClient(baseUrlProvider = { rewardsBaseUrl() }) }
    internal val shortDramaSdk by lazy { ShortDramaSdkManager.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.SCREENSHOT_PROTECTION_ENABLED) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        restorePlayerAfterCatalog = savedInstanceState?.getBoolean(STATE_PLAYER_VISIBLE, false) == true
        restoredDramaId = savedInstanceState?.getString(STATE_DRAMA_ID)
        restoredEpisodeIndex = savedInstanceState?.getInt(STATE_EPISODE_INDEX, 0) ?: 0
        restoredPositionSeconds = savedInstanceState?.getInt(STATE_POSITION_SECONDS, 0) ?: 0
        readExternalAdReturnCheckpoint()?.let { checkpoint ->
            restoringExternalAdReturn = true
            if (!restorePlayerAfterCatalog) {
                restorePlayerAfterCatalog = true
                restoredDramaId = checkpoint.dramaId
                restoredEpisodeIndex = checkpoint.episodeIndex
                restoredPositionSeconds = checkpoint.positionSeconds
                logStatus("恢复广告外跳前的播放检查点：${checkpoint.dramaId} #${checkpoint.episodeIndex + 1}")
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
                    playerShown || sdkPlayerShown -> showCatalog()
                    else -> finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, requireNotNull(navigationBackCallback))
        adMediationReporter.flushPending()
        beginStartupExperience()
    }

    /**
     * TopOn 开屏必须在 Activity 已进入前台后展示；在 onCreate 中立即展示会导致部分真机
     * 将广告请求视为不可展示，尤其是冷启动的 Demo/测试广告。
     */
    override fun onPostResume() {
        super.onPostResume()
        if (!startupContentInitializationStarted) maybeShowAppOpenAd()
        if (resumePlaybackWhenForeground && playerShown && !fullscreenAdInProgress) {
            exoPlayer?.play()
            playerIsPaused = false
            updatePauseOverlayVisibility(false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resumePlaybackWhenForeground = false
        }
        if (resumeSdkPlaybackWhenForeground && sdkPlayerShown && !fullscreenAdInProgress) {
            currentSdkPlayerFragment()?.startPlay()
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resumeSdkPlaybackWhenForeground = false
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
        if (fullscreenAdInProgress || fullscreenAdRequestInFlight) saveExternalAdReturnCheckpoint()
        resumePlaybackWhenForeground = playerShown && exoPlayer?.isPlaying == true && !fullscreenAdInProgress
        if (resumePlaybackWhenForeground) exoPlayer?.pause()
        resumeSdkPlaybackWhenForeground = sdkPlayerShown && sdkPlaybackActive && !fullscreenAdInProgress
        if (resumeSdkPlaybackWhenForeground) currentSdkPlayerFragment()?.pausePlay()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PLAYER_VISIBLE, playerShown || sdkPlayerShown)
        outState.putString(STATE_DRAMA_ID, selectedDrama?.id)
        outState.putInt(STATE_EPISODE_INDEX, currentEpisodeIndex)
        outState.putInt(STATE_POSITION_SECONDS, exoPlayer?.currentPosition?.div(1_000L)?.toInt() ?: playerProgressSeconds)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
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
        playerShown = false
        configureCatalogWindow()
        gemBalanceAnchor = null
        gemBalanceLabel = null
        cashBalanceAnchor = null
        cashBalanceLabel = null
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
        root.addView(createBottomNavigation(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM))
        root.addView(createFloatingRewardWheel(), FrameLayout.LayoutParams(dp(130), dp(112), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = 0
            bottomMargin = dp(80)
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
        val dramaId = selectedDrama?.id ?: return
        val key = "favorite_$dramaId"
        val nowFavorite = !favorites.getBoolean(key, false)
        favorites.edit().putBoolean(key, nowFavorite).apply()
        favoriteButton?.text = if (nowFavorite) "♥" else "♡"
        toast(if (nowFavorite) t("favorite_added") else t("favorite_removed"))
    }

    internal fun isFavorite(): Boolean = selectedDrama?.id?.let { favorites.getBoolean("favorite_$it", false) } ?: false

    internal fun showCatalog() {
        exitImmersivePlayerMode()
        configureCatalogWindow()
        // releasePlayer persists resume state only while the player is still marked visible.
        // Keep this ordering so “My / Continue watching” always resumes the actual episode and time.
        releasePlayer()
        dismissSdkDramaPlayerIfShown()
        clearExternalAdReturnCheckpoint()
        playerShown = false
        activeTab = MainTab.HOME
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
        startupProgressPercentLabel = TextView(this).apply {
            text = "0%"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_CORAL)
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(startupProgressPercentLabel)
        startupProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(COLOR_CORAL)
            progressBackgroundTintList = ColorStateList.valueOf(0x22FF8A62)
        }
        column.addView(startupProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply {
            topMargin = dp(18)
        })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    internal fun updateStartupProgress(progress: Int, label: String) {
        val safe = progress.coerceIn(0, 100)
        startupProgressBar?.progress = safe
        startupProgressPercentLabel?.text = "$safe%"
        startupProgressLabel?.contentDescription = label
    }

    /** One window policy for all catalogue surfaces; prevents a player/ad window state leaking into pages. */
    internal fun configureCatalogWindow() {
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
        topOnAds.setHostEdgeToEdge(this, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
        root.requestLayout()
        ViewCompat.requestApplyInsets(root)
    }

    internal fun renderLoading(message: String) {
        playerShown = false
        root.removeAllViews()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        column.addView(ProgressBar(this))
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
        if ((!playerShown && !sdkPlayerShown) || currentEpisodeIndex !in drama.episodes.indices) return
        val safePosition = positionSeconds.coerceAtLeast(0)
        playbackHistory.edit()
            .putInt(resumePreferenceKey(drama.id, "episode"), currentEpisodeIndex)
            .putInt(resumePreferenceKey(drama.id, "position"), safePosition)
            .apply()
        lastPersistedPlaybackSecond = safePosition
    }

    /**
     * Persist synchronously before an ad opens an external store/browser. Android may reclaim the
     * process while the user is outside the app, so Bundle-only state is insufficient here.
     */
    internal fun saveExternalAdReturnCheckpoint() {
        val drama = selectedDrama ?: return
        if ((!playerShown && !sdkPlayerShown) || currentEpisodeIndex !in drama.episodes.indices) return
        val positionSeconds = (exoPlayer?.currentPosition?.div(1_000L)?.toInt() ?: playerProgressSeconds)
            .coerceAtLeast(playerProgressSeconds)
            .coerceAtLeast(0)
        playbackHistory.edit()
            .putString(AD_RETURN_DRAMA_ID, drama.id)
            .putInt(AD_RETURN_EPISODE_INDEX, currentEpisodeIndex)
            .putInt(AD_RETURN_POSITION_SECONDS, positionSeconds)
            .putLong(AD_RETURN_SAVED_AT_MILLIS, System.currentTimeMillis())
            .commit()
        logStatus("已保存广告外跳播放检查点：${drama.id} #${currentEpisodeIndex + 1} @${positionSeconds}s")
    }

    internal fun readExternalAdReturnCheckpoint(): PlaybackReturnCheckpoint? {
        val checkpoint = PlaybackReturnCheckpoint(
            dramaId = playbackHistory.getString(AD_RETURN_DRAMA_ID, null).orEmpty(),
            episodeIndex = playbackHistory.getInt(AD_RETURN_EPISODE_INDEX, -1),
            positionSeconds = playbackHistory.getInt(AD_RETURN_POSITION_SECONDS, -1),
            savedAtMillis = playbackHistory.getLong(AD_RETURN_SAVED_AT_MILLIS, 0L),
        )
        return checkpoint.takeIf { it.isFresh(System.currentTimeMillis()) }.also {
            if (it == null) clearExternalAdReturnCheckpoint()
        }
    }

    internal fun clearExternalAdReturnCheckpoint() {
        playbackHistory.edit()
            .remove(AD_RETURN_DRAMA_ID)
            .remove(AD_RETURN_EPISODE_INDEX)
            .remove(AD_RETURN_POSITION_SECONDS)
            .remove(AD_RETURN_SAVED_AT_MILLIS)
            .apply()
    }

    internal fun persistNextEpisodeResume(completedIndex: Int) {
        val drama = selectedDrama ?: return
        val nextIndex = (completedIndex + 1).coerceAtMost(drama.episodes.lastIndex.coerceAtLeast(0))
        playbackHistory.edit()
            .putInt(resumePreferenceKey(drama.id, "episode"), nextIndex)
            .putInt(resumePreferenceKey(drama.id, "position"), 0)
            .apply()
    }

    internal fun releasePlayer() {
        persistPlaybackProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressHandler.removeCallbacks(progressTicker)
        playerView?.player = null
        playerView = null
        playerPauseOverlay = null
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
        intArrayOf(COLOR_ACCENT, COLOR_PURPLE, COLOR_BACKGROUND)
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
            AppLanguage.save(this, options[which].tag)
            ShortDramaSdkManager.get(this).setContentLanguageForUi(options[which].tag)
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
