package com.dramaflow.app.presentation

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
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
import androidx.core.content.FileProvider
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
import com.dramaflow.app.ads.AdNetworkAuditLog
import com.dramaflow.app.ads.TopOnAdsManager
import com.dramaflow.app.ads.TopOnSettings
import com.dramaflow.app.analytics.ReportingAuditLog
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalDramaApiClient
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * JoyeDrama 的统一短剧入口。
 *
 * 测试阶段从局域网 FastAPI 服务获取自有剧目录、分集与短时播放 URL，使用 Media3 播放；
 * 目录卡片、播放控制、选集、收藏、广告解锁和奖励体验均由应用自己管理。
 */
class DramaAppActivity : FragmentActivity() {
    internal lateinit var root: FrameLayout
    internal var catalogPage: View? = null
    internal var catalogItems: List<LocalDrama> = emptyList()
    /** 当前测试由 Local Content API 提供；字段与合作方短剧 SDK 的 title/cover/summary/genres 模型对齐。 */
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
    internal var startupPosterPreloadPercent = 0
    internal var startupCatalogFailure: String? = null
    internal var startupProgressBar: ProgressBar? = null
    internal var startupProgressLabel: TextView? = null
    internal var startupProgressPercentLabel: TextView? = null
    internal var resumePlaybackWhenForeground = false
    internal var restorePlayerAfterCatalog = false
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
    internal var pauseNativeAdInProgress = false
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
    internal val topOnAds by lazy { TopOnAdsManager(applicationContext, TopOnSettings.fromBuildConfig()) }
    internal val rewardsApi by lazy { RewardsApiClient(baseUrlProvider = { rewardsBaseUrl() }) }
    internal val localDramaApi by lazy { LocalDramaApiClient() }
    internal val serverPreferences by lazy { getSharedPreferences(SERVER_PREFERENCES, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.SCREENSHOT_PROTECTION_ENABLED) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        restorePlayerAfterCatalog = savedInstanceState?.getBoolean(STATE_PLAYER_VISIBLE, false) == true
        restoredDramaId = savedInstanceState?.getString(STATE_DRAMA_ID)
        restoredEpisodeIndex = savedInstanceState?.getInt(STATE_EPISODE_INDEX, 0) ?: 0
        restoredPositionSeconds = savedInstanceState?.getInt(STATE_POSITION_SECONDS, 0) ?: 0
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
                    playerShown -> showCatalog()
                    else -> finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, requireNotNull(navigationBackCallback))
        beginStartupExperience()
    }

    /**
     * TopOn 开屏必须在 Activity 已进入前台后展示；在 onCreate 中立即展示会导致部分真机
     * 将广告请求视为不可展示，尤其是冷启动的 Demo/测试广告。
     */
    override fun onPostResume() {
        super.onPostResume()
        if (!startupContentInitializationStarted) maybeShowAppOpenAd()
        if (resumePlaybackWhenForeground && playerShown && !fullscreenAdInProgress && !pauseNativeAdInProgress) {
            exoPlayer?.play()
            playerIsPaused = false
            updatePauseOverlayVisibility(false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resumePlaybackWhenForeground = false
        }
    }

    override fun onStop() {
        // 后台不播放声音，但保留播放器实例、剧集和进度；回到前台后自动从原位置继续。
        persistPlaybackProgress()
        resumePlaybackWhenForeground = playerShown && exoPlayer?.isPlaying == true && !fullscreenAdInProgress
        if (resumePlaybackWhenForeground) exoPlayer?.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PLAYER_VISIBLE, playerShown)
        outState.putString(STATE_DRAMA_ID, selectedDrama?.id)
        outState.putInt(STATE_EPISODE_INDEX, currentEpisodeIndex)
        outState.putInt(STATE_POSITION_SECONDS, exoPlayer?.currentPosition?.div(1_000L)?.toInt() ?: playerProgressSeconds)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        taskRewardPromptRunnable?.let(root::removeCallbacks)
        taskRewardPromptRunnable = null
        releasePlayer()
        super.onDestroy()
    }

    /**
     * 启动时不再显示“开始追剧”确认框。首次安装默认启用广告请求状态，用户仍可在“我的—广告与隐私”中关闭。
     * 先显示 JoyeDrama Logo 加载页；开屏关闭、跳过或无填充后，再进入签到与内容初始化。
     */
    internal fun loadLocalCatalog() {
        renderLoading(t("loading_catalog"))
        localDramaApi.fetchFeed(
            baseUrl = localContentBaseUrl(),
            region = CONTENT_REGION,
            onSuccess = { items -> runOnUiThread {
                if (items.isEmpty()) renderFailure(t("catalog_empty")) else { catalogItems = items; renderActiveTab() }
            } },
            onFailure = { message -> runOnUiThread { renderFailure(t("catalog_load_failed", message)) } },
        )
    }

    internal enum class MainTab(val languageKey: String, val iconRes: Int) {
        HOME("home", R.drawable.ic_nav_home),
        DISCOVER("discover", R.drawable.ic_nav_discover),
        TASKS("rewards", R.drawable.ic_nav_rewards),
        PROFILE("profile", R.drawable.ic_nav_profile),
    }

    internal fun renderActiveTab() {
        if (catalogItems.isEmpty()) {
            renderLoading(t("home_greeting"))
            return
        }
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
        if (BuildConfig.WHITE_PACK) {
            // White builds are content-only: no wallet, reward balance or sticky reward header.
        } else if (activeTab != MainTab.TASKS) {
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
        if (!BuildConfig.WHITE_PACK) {
            root.addView(createFloatingRewardWheel(), FrameLayout.LayoutParams(dp(130), dp(112), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = 0
                bottomMargin = dp(80)
            })
        }
    }

    internal fun showLocalServerSettings() {
        val input = EditText(this).apply {
            setText(localContentBaseUrl())
            hint = "http://192.168.1.100:8000"
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        showBrandedInputDialog(
            title = t("content_service"),
            message = t("local_content_server_hint"),
            input = input,
            primaryLabel = t("save"),
            onPrimary = { value ->
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    toast(t("server_address_invalid"))
                } else {
                    serverPreferences.edit().putString(SERVER_BASE_URL_KEY, value.trimEnd('/')).apply()
                    catalogItems = emptyList()
                    loadLocalCatalog()
                }
            },
            secondaryLabel = t("restore_default"),
            onSecondary = {
                serverPreferences.edit().remove(SERVER_BASE_URL_KEY).apply()
                catalogItems = emptyList()
                loadLocalCatalog()
            },
        )
    }

    internal fun showRewardsServerSettings() {
        val input = EditText(this).apply {
            setText(rewardsBaseUrl())
            hint = "http://192.168.1.100:8100"
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        showBrandedInputDialog(
            title = t("rewards_service"),
            message = t("local_rewards_server_hint"),
            input = input,
            primaryLabel = t("save"),
            onPrimary = { value ->
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    toast(t("server_address_invalid"))
                } else {
                    serverPreferences.edit().putString(REWARDS_SERVER_BASE_URL_KEY, value.trimEnd('/')).apply()
                    refreshRewardsSnapshot()
                    toast(t("server_saved"))
                }
            },
            secondaryLabel = t("restore_default"),
            onSecondary = {
                serverPreferences.edit().remove(REWARDS_SERVER_BASE_URL_KEY).apply()
                refreshRewardsSnapshot()
            },
        )
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
                if (!granted) maybeShowAppOpenAd()
                toast(if (granted) t("ads_disabled") else t("ads_enabled"))
            },
            secondaryLabel = t("cancel"),
        )
    }

/** Opens the locally persisted TopOn ADN audit and shares its CSV without exposing App Key or user data. */
internal fun DramaAppActivity.showAdNetworkAuditLog() {
        val audit = AdNetworkAuditLog.get(this)
        val summary = audit.summary()
        val message = if (summary.eventCount == 0) {
            t("ad_network_audit_empty")
        } else {
            t("ad_network_audit_message", summary.eventCount, summary.lastRecordedAtUtc.orEmpty())
        }
        showBrandedChoiceDialog(
            title = t("ad_network_audit"),
            message = message,
            primaryLabel = t("ad_network_audit_share"),
            onPrimary = {
                runCatching {
                    val file = audit.exportFile()
                    val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.ad-audit-files", file)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("joyedrama_topon_adn_audit", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, t("ad_network_audit_share")))
                }.onFailure {
                    toast(t("ad_network_audit_share_failed"))
                }
            },
            secondaryLabel = t("ad_network_audit_clear"),
            onSecondary = {
                showBrandedChoiceDialog(
                    title = t("ad_network_audit_clear"),
                    message = t("ad_network_audit_clear_message"),
                    primaryLabel = t("ad_network_audit_clear"),
                    onPrimary = {
                        audit.clear()
                        toast(t("ad_network_audit_cleared"))
                    },
                    secondaryLabel = t("cancel"),
                )
            },
        )
    }

    /** Shares the locally persisted AppsFlyer/Firebase/Xuanyu submission audit CSV. */
    internal fun showReportingAuditLog() {
        val audit = ReportingAuditLog.get(this)
        val summary = audit.summary()
        val message = if (summary.eventCount == 0) t("reporting_audit_empty")
        else t("reporting_audit_message", summary.eventCount, summary.lastRecordedAtUtc.orEmpty())
        showBrandedChoiceDialog(
            title = t("reporting_audit"),
            message = message,
            primaryLabel = t("reporting_audit_share"),
            onPrimary = {
                runCatching {
                    val file = audit.exportFile()
                    val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.ad-audit-files", file)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("joyedrama_reporting_audit", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, t("reporting_audit_share")))
                }.onFailure { toast(t("reporting_audit_share_failed")) }
            },
            secondaryLabel = t("reporting_audit_clear"),
            onSecondary = {
                showBrandedChoiceDialog(
                    title = t("reporting_audit_clear"),
                    message = t("reporting_audit_clear_message"),
                    primaryLabel = t("reporting_audit_clear"),
                    onPrimary = { audit.clear(); toast(t("reporting_audit_cleared")) },
                    secondaryLabel = t("cancel"),
                )
            },
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
        playerShown = false
        activeTab = MainTab.HOME
        if (catalogItems.isNotEmpty()) renderActiveTab() else loadLocalCatalog()
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
            text = t("server_address")
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
            setTextColor(Color.WHITE)
            setOnClickListener { showLocalServerSettings() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(22) })
        column.addView(Button(this).apply {
            text = t("retry")
            isAllCaps = false
            setOnClickListener { loadLocalCatalog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        column.addView(TextView(this).apply {
            text = t("restore_default")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_ACCENT)
            setPadding(dp(12), dp(14), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                serverPreferences.edit().remove(SERVER_BASE_URL_KEY).apply()
                toast("已恢复默认地址：${BuildConfig.LOCAL_CONTENT_API_BASE_URL}")
                loadLocalCatalog()
            }
        })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 仅用于开发环境联调；正式构建必须由广告服务端回调/验签替代。 */
    internal fun testRewardProof(): String = if (BuildConfig.DEBUG) "TEST_TOPON_REWARD" else ""

    internal fun rewardsBaseUrl(): String {
        val saved = serverPreferences
            .getString(REWARDS_SERVER_BASE_URL_KEY, null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (saved != null) return saved

        val configured = BuildConfig.REWARDS_API_BASE_URL.trim().trimEnd('/')
        // Debug 真机常见情况：只配置或手动保存了 Content API，Rewards 地址仍保持模拟器默认
        // 10.0.2.2。此时复用已验证可访问的内容服务主机，仅替换为 Rewards 的 8100 端口。
        if (BuildConfig.DEBUG && configured.contains("10.0.2.2")) {
            val contentUri = runCatching { Uri.parse(localContentBaseUrl()) }.getOrNull()
            val host = contentUri?.host?.takeIf { it.isNotBlank() }
            if (host != null && host != "10.0.2.2") {
                val scheme = contentUri.scheme?.takeIf { it == "http" || it == "https" } ?: "http"
                return "$scheme://$host:8100"
            }
        }
        return configured
    }

    internal fun localContentBaseUrl(): String = serverPreferences
        .getString(SERVER_BASE_URL_KEY, null)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: BuildConfig.LOCAL_CONTENT_API_BASE_URL.trim().trimEnd('/')

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
        if (!playerShown || currentEpisodeIndex !in drama.episodes.indices) return
        val safePosition = positionSeconds.coerceAtLeast(0)
        playbackHistory.edit()
            .putInt(resumePreferenceKey(drama.id, "episode"), currentEpisodeIndex)
            .putInt(resumePreferenceKey(drama.id, "position"), safePosition)
            .apply()
        lastPersistedPlaybackSecond = safePosition
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
        val options = AppLanguage.options
        val selected = options.indexOfFirst { it.tag == AppLanguage.tag(this) }.coerceAtLeast(0)
        showBrandedOptionsDialog(t("language"), options.map { it.label }, selected) { which ->
            AppLanguage.save(this, options[which].tag)
            ShortDramaSdkManager.get(this).setContentLanguageForUi(options[which].tag)
            if (withdrawalPageShown) rewardsSnapshot?.wallet?.let(::showWithdrawalPage) else renderActiveTab()
        }
    }

    internal fun rewardsMarketCode(): String = Locale.getDefault().country.trim().uppercase(Locale.ROOT).ifBlank { CONTENT_REGION }

    internal fun t(key: String, vararg values: Any): String = AppLanguage.text(this, key, *values)
    internal fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    internal fun logStatus(message: String) = android.util.Log.i(TAG, message)
    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent = Intent(context, DramaAppActivity::class.java)
    }
}
