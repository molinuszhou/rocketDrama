package com.dramaflow.app.partner

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
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.R
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

/**
 * JoyeDrama 的统一短剧入口。
 *
 * 测试阶段从局域网 FastAPI 服务获取自有剧目录、分集与短时播放 URL，使用 Media3 播放；
 * 目录卡片、播放控制、选集、收藏、广告解锁和奖励体验均由应用自己管理。
 */
class PartnerDramaActivity : FragmentActivity() {
    private lateinit var root: FrameLayout
    private var catalogPage: View? = null
    private var catalogItems: List<LocalDrama> = emptyList()
    /** 当前测试由 Local Content API 提供；字段与合作方短剧 SDK 的 title/cover/summary/genres 模型对齐。 */
    private var selectedCatalogGenre: String? = null
    private var activeTab: MainTab = MainTab.HOME
    private var withdrawalPageShown = false
    private var rewardsSnapshot: RewardsApiClient.Snapshot? = null
    private var rewardsLoading = false
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
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
    private var playerShown = false
    private var appOpenAttempted = false
    private var startupContentInitializationStarted = false
    private var startupAdFinished = false
    private var startupCatalogFinished = false
    private var startupCatalogFailure: String? = null
    private var startupProgressBar: ProgressBar? = null
    private var startupProgressLabel: TextView? = null
    private var resumePlaybackWhenForeground = false
    private var restorePlayerAfterCatalog = false
    private var restoredDramaId: String? = null
    private var restoredEpisodeIndex = 0
    private var restoredPositionSeconds = 0
    private var startupCheckinPromptRequested = false
    private var startupCheckinPromptShown = false
    private var startupWelcomePromptShown = false
    private var selectedDrama: LocalDrama? = null
    private var currentEpisodeIndex = 0
    private var pendingUnlockIndex = -1
    private var episodeStatusView: TextView? = null
    private var playerProgressSeekBar: SeekBar? = null
    private var playerProgressText: TextView? = null
    private var speedButton: TextView? = null
    private var favoriteButton: TextView? = null
    private var playerControlsPanel: View? = null
    private var playerControlsRevealButton: View? = null
    private var playerDescriptionView: TextView? = null
    private var playerDescriptionContainer: View? = null
    private var playerDescriptionExpanded = false
    private var playerControlsVisible = true
    private var playerProgressSeconds = 0
    private var playerDurationSeconds = 0
    private var pendingResumePositionSeconds = 0
    private var lastPersistedPlaybackSecond = -1
    private var playerPauseOverlay: TextView? = null
    private var playerIsSeeking = false
    private var playerIsPaused = false
    private var playerMuted = false
    private var playerSpeed = 1.0f
    private var lastSwipeNextAtMillis = 0L
    private var fullscreenAdInProgress = false
    private var episodeTransitionInProgress = false
    private var resumeAfterFullscreenAd = false
    /** 广告展示前退出沉浸模式，关闭后仅在原本处于沉浸播放时恢复。 */
    private var playerInImmersiveMode = false
    private var restoreImmersiveAfterFullscreenAd = false
    private var pauseNativeAdInProgress = false
    private lateinit var navigationBackCallback: OnBackPressedCallback
    private var episodePickerDialog: AlertDialog? = null
    private val unlockedEpisodeKeys = mutableSetOf<String>()
    // 奖励服务不可达时仅保留本次进程内播放权益；不会伪造为已持久化的服务器权益。
    private val sessionUnlockedEpisodeKeys = mutableSetOf<String>()
    private val interstitialRequestedEpisodeKeys = mutableSetOf<String>()
    private val midEpisodeInterstitialRequestedEpisodeKeys = mutableSetOf<String>()
    private var seekInterstitialInProgress = false
    /** 仅统计“剧中/拖动/倍速/激励回退”等受每集互斥约束的插屏；横滑切剧有独立频控。 */
    private val interstitialCountByEpisodeKey = mutableMapOf<String, Int>()
    private var dramaSwitchSwipeCount = 0
    private val completedEpisodeKeys = mutableSetOf<String>()
    private val rewardWatchRecordedEpisodeKeys = mutableSetOf<String>()
    private val floatingWheelLabels = mutableSetOf<TextView>()
    private val floatingBoostButtons = mutableSetOf<TextView>()
    /** 当前页面可见的宝石余额锚点。领取成功后，宝石飞入此位置。 */
    private var gemBalanceAnchor: View? = null
    private var gemBalanceLabel: TextView? = null
    private var cashBalanceLabel: TextView? = null
    private var lastConfirmedGemBalance: Long? = null
    private var gemFlightInProgress = false
    private var ignoreVideoTapsUntilMillis = 0L
    private val favorites by lazy { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val playbackHistory by lazy { getSharedPreferences(PLAYBACK_HISTORY_PREFERENCES, Context.MODE_PRIVATE) }

    private val consentStore by lazy { AdConsentStore(applicationContext) }
    private val topOnAds by lazy { TopOnAdsManager(applicationContext, TopOnSettings.fromBuildConfig()) }
    private val rewardsApi by lazy { RewardsApiClient(baseUrlProvider = { rewardsBaseUrl() }) }
    private val localDramaApi by lazy { LocalDramaApiClient() }
    private val serverPreferences by lazy { getSharedPreferences(SERVER_PREFERENCES, Context.MODE_PRIVATE) }

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
        onBackPressedDispatcher.addCallback(this, navigationBackCallback)
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
        releasePlayer()
        super.onDestroy()
    }

    /**
     * 启动时不再显示“开始追剧”确认框。首次安装默认启用广告请求状态，用户仍可在“我的—广告与隐私”中关闭。
     * 先显示 JoyeDrama Logo 加载页；开屏关闭、跳过或无填充后，再进入签到与内容初始化。
     */
    private fun beginStartupExperience() {
        if (startupContentInitializationStarted) return
        if (consentStore.read() == AdConsent.UNKNOWN) {
            consentStore.save(AdConsent.GRANTED)
        }
        startupCheckinPromptRequested = true
        startupCheckinPromptShown = false
        startupAdFinished = false
        startupCatalogFinished = false
        startupCatalogFailure = null
        renderStartupLoading()
        updateStartupProgress(20, t("startup_preparing"))
        refreshRewardsSnapshot()
        preloadLocalCatalog()
        // 等待 onPostResume 后再发起开屏请求，确保窗口已就绪；目录和开屏并行预热。
    }

    private fun maybeShowAppOpenAd() {
        if (appOpenAttempted) return
        appOpenAttempted = true
        if (consentStore.read() != AdConsent.GRANTED) {
            finishStartupAfterAppOpen("ads_disabled")
            return
        }
        // Activity 已进入前台后请求；TopOnAdsManager 会等待 SDK 初始化成功回调再加载开屏。
        topOnAds.showAppOpenIfEligible(this) { result ->
            when (result) {
                TopOnAdsManager.FullscreenResult.Shown -> logStatus("开屏广告已关闭")
                is TopOnAdsManager.FullscreenResult.Failed -> logStatus("开屏广告加载失败：${result.code} ${result.message}")
                is TopOnAdsManager.FullscreenResult.Skipped -> logStatus("开屏广告未展示：${result.reason}")
            }
            runOnUiThread { finishStartupAfterAppOpen(result.javaClass.simpleName) }
        }
    }

    private fun finishStartupAfterAppOpen(reason: String) {
        if (startupContentInitializationStarted || isFinishing || isDestroyed) return
        startupAdFinished = true
        logStatus("开屏流程结束：$reason")
        updateStartupProgress(72, t("startup_ad_ready"))
        completeStartupWhenReady()
    }

    private fun preloadLocalCatalog() {
        localDramaApi.fetchFeed(
            baseUrl = localContentBaseUrl(),
            region = CONTENT_REGION,
            onSuccess = { items -> runOnUiThread {
                startupCatalogFinished = true
                if (items.isEmpty()) startupCatalogFailure = t("catalog_empty") else catalogItems = items
                updateStartupProgress(88, if (items.isEmpty()) t("catalog_empty") else t("startup_content_ready"))
                completeStartupWhenReady()
            } },
            onFailure = { message -> runOnUiThread {
                startupCatalogFinished = true
                startupCatalogFailure = t("catalog_load_failed", message)
                completeStartupWhenReady()
            } },
        )
    }

    private fun completeStartupWhenReady() {
        if (startupContentInitializationStarted || !startupAdFinished || !startupCatalogFinished || isFinishing || isDestroyed) return
        startupContentInitializationStarted = true
        val failure = startupCatalogFailure
        if (failure != null) {
            renderFailure("$failure\n${t("current_server", localContentBaseUrl())}")
            return
        }
        updateStartupProgress(100, t("startup_ready"))
        if (restorePlayerAfterCatalog) {
            val drama = catalogItems.firstOrNull { it.id == restoredDramaId } ?: catalogItems.firstOrNull()
            if (drama != null) {
                restorePlayerAfterCatalog = false
                showPlayer(drama, restoredEpisodeIndex, restoredPositionSeconds)
                return
            }
        }
        renderActiveTab()
        showStartupCheckinWhenReady()
    }

    private fun loadLocalCatalog() {
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

    private enum class MainTab(val languageKey: String, val iconRes: Int) {
        HOME("home", R.drawable.ic_nav_home),
        DISCOVER("discover", R.drawable.ic_nav_discover),
        TASKS("rewards", R.drawable.ic_nav_rewards),
        PROFILE("profile", R.drawable.ic_nav_profile),
    }

    private fun renderActiveTab() {
        if (catalogItems.isEmpty()) {
            renderLoading(t("home_greeting"))
            return
        }
        withdrawalPageShown = false
        playerShown = false
        window.statusBarColor = if (activeTab == MainTab.TASKS) 0xFFFFCF9E.toInt() else COLOR_BACKGROUND
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true
        gemBalanceAnchor = null
        gemBalanceLabel = null
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
        // 奖励中心的资产在其首屏 hero 内展示；其他页面保留固定资产栏，滚动不影响余额可见性。
        if (activeTab != MainTab.TASKS) {
            root.addView(createPersistentAssetHeader(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP))
        }
        root.addView(createBottomNavigation(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM))
        root.addView(createFloatingRewardWheel(), FrameLayout.LayoutParams(dp(92), dp(112), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(10)
            bottomMargin = dp(80)
        })
    }

    private fun showWithdrawalPage(wallet: RewardsApiClient.Wallet) {
        withdrawalPageShown = true
        playerShown = false
        window.statusBarColor = COLOR_BACKGROUND
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true
        gemBalanceAnchor = null
        gemBalanceLabel = null
        cashBalanceLabel = null
        exitImmersivePlayerMode()
        val page = ScrollView(this).apply {
            background = pageCanvasDrawable()
            isFillViewport = true
            addView(createWithdrawalPage(wallet))
        }
        root.removeAllViews()
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createPersistentAssetHeader(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP))
    }

    private fun createWithdrawalPage(wallet: RewardsApiClient.Wallet): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(132), dp(18), dp(34))
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@PartnerDramaActivity).apply {
                setImageResource(R.drawable.ic_arrow_back); scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(0, 0, dp(14), 0); isClickable = true; contentDescription = t("back")
                setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(this@PartnerDramaActivity).apply {
                text = t("wallet"); textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@PartnerDramaActivity).apply {
                text = t("rules"); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT)
                setPadding(dp(10), dp(8), 0, dp(8)); isClickable = true; setOnClickListener { showWalletRulesDialog(wallet) }
            })
        }, matchParams(bottom = 16))
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(20))
            background = gradientDrawable(intArrayOf(COLOR_INK, COLOR_PLUM, 0xFF24304A.toInt()), 24)
            val balanceRow = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            balanceRow.addView(LinearLayout(this@PartnerDramaActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@PartnerDramaActivity).apply { text = t("wallet_balance"); textSize = 14f; setTextColor(0xCCFFFFFF.toInt()) })
                addView(TextView(this@PartnerDramaActivity).apply { text = formatWalletTotal(wallet, includeUsdReference = true); textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(5), 0, 0); maxLines = 2 })
                addView(TextView(this@PartnerDramaActivity).apply { text = t("wallet_total_hint"); textSize = 11f; setTextColor(0xBFFFFFFF.toInt()); setPadding(0, dp(6), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            balanceRow.addView(ImageView(this@PartnerDramaActivity).apply {
                setImageResource(R.drawable.ic_wallet); scaleType = ImageView.ScaleType.CENTER_INSIDE; contentDescription = t("wallet")
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(balanceRow)
        }, matchParams(bottom = 14))
        addView(createCashoutSummary(wallet), matchParams(bottom = 12))
        addView(TextView(this@PartnerDramaActivity).apply {
            text = t("wallet_ad_accelerate"); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(COLOR_SUNSET, COLOR_CORAL), 18); setPadding(dp(14), dp(13), dp(14), dp(13)); isClickable = true
            setOnClickListener { showWalletReleaseBoostDialog() }
        }, matchParams(bottom = 14))
        addView(createCashoutExplainer(wallet), matchParams(bottom = 20))
        addView(TextView(this@PartnerDramaActivity).apply {
            text = t("request_cashout"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(COLOR_CORAL, COLOR_SUNSET), 24); setPadding(0, dp(16), 0, dp(16)); isClickable = true
            setOnClickListener { showCashoutEligibilityNotice() }
        })
    }

    private fun formatCurrencyAmount(wallet: RewardsApiClient.Wallet, amount: String, usdReference: String? = null, includeUsdReference: Boolean = false): String {
        val fractionDigits = wallet.currency.fractionDigits.coerceIn(0, 6)
        val locale = when (wallet.currency.code) {
            "BRL" -> Locale("pt", "BR")
            "IDR" -> Locale("id", "ID")
            else -> Locale.US
        }
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }
        val numeric = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val local = "${wallet.currency.symbol}${formatter.format(numeric)}"
        val usd = usdReference?.toBigDecimalOrNull()
        return if (includeUsdReference && wallet.currency.code != "USD" && usd != null) {
            "$local  ≈ US$${String.format(Locale.US, "%.2f", usd)}"
        } else local
    }

    private fun formatWalletTotal(wallet: RewardsApiClient.Wallet, includeUsdReference: Boolean = false): String {
        val total = (wallet.cashWallet.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) +
            (wallet.availableBalance.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        val usdTotal = (wallet.cashWalletUsdReference.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) +
            (wallet.availableBalanceUsdReference.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        return formatCurrencyAmount(wallet, total.toPlainString(), usdTotal.toPlainString(), includeUsdReference)
    }

    private fun showCashoutEligibilityNotice() {
        val dynamicReason = rewardsSnapshot?.withdrawal?.reason?.takeIf { it.isNotBlank() }
            ?: t("cashout_minimum_notice")
        AlertDialog.Builder(this).setTitle(t("request_cashout"))
            .setMessage(dynamicReason)
            .setPositiveButton(t("done"), null).show()
    }

    private fun createHomePage(): View = scrollPage { content ->
        val filtered = filteredCatalogItems()
        val recommended = recommendedCatalogItems()
        val primaryItems = if (selectedCatalogGenre == null) recommended else filtered
        content.addView(createPageTitle(t("home"), t("home_subtitle")), matchParams(bottom = 16))
        content.addView(createSearchPrompt(), matchParams(bottom = 14))
        content.addView(createCategoryTabs(), matchParams(bottom = 18))
        primaryItems.firstOrNull()?.let { content.addView(createHeroBanner(it), matchParams(bottom = 22)) }
        content.addView(createSectionHeader(
            if (selectedCatalogGenre == null) t("recommended_for_you") else "$selectedCatalogGenre ${t("discover")}",
            if (hasCatalogPreferenceSignals()) t("continue_watching") else t("home_greeting"),
        ), matchParams(bottom = 12))
        if (primaryItems.isNotEmpty()) content.addView(createHorizontalDramaShelf(primaryItems.take(SHELF_ITEM_LIMIT)), matchParams(bottom = 22))
        else content.addView(createInlineNotice(t("home_greeting")), matchParams(bottom = 22))
        content.addView(createSectionHeader(t("all_dramas"), t("discover_subtitle")), matchParams(bottom = 12))
        addDramaGrid(content, filtered)
    }

    private fun createDiscoverPage(): View = scrollPage { content ->
        val recommended = recommendedCatalogItems()
        content.addView(createPageTitle(t("discover"), t("discover_subtitle")), matchParams(bottom = 16))
        content.addView(createCategoryTabs(), matchParams(bottom = 16))
        primaryFeaturedDrama(recommended)?.let { content.addView(createHeroBanner(it), matchParams(bottom = 22)) }
        content.addView(createSectionHeader(t("popular_this_week"), t("recommended_for_you")), matchParams(bottom = 12))
        if (recommended.isNotEmpty()) content.addView(createHorizontalDramaShelf(recommended.take(SHELF_ITEM_LIMIT)), matchParams(bottom = 22))
        catalogGenreGroups().forEach { (genre, dramas) ->
            content.addView(createSectionHeader("$genre ${t("discover")}", t("episodes_count", dramas.size)), matchParams(bottom = 12))
            content.addView(createHorizontalDramaShelf(dramas.take(SHELF_ITEM_LIMIT)), matchParams(bottom = 20))
        }
    }

    private fun primaryFeaturedDrama(recommended: List<LocalDrama>): LocalDrama? = recommended.firstOrNull() ?: catalogItems.firstOrNull()

    private fun createTasksPage(): View = scrollPage { content ->
        content.setPadding(0, 0, 0, dp(86))
        val snapshot = rewardsSnapshot
        content.addView(createRewardsHeroHeader(snapshot), matchParams())
        content.addView(createCrownRoadPanel(snapshot), matchParams(top = 12, bottom = 14))
        content.addView(createDailyBenefitsPanel(snapshot), matchParams(bottom = 14))
        if (snapshot == null) content.addView(createInlineNotice(t("ledger_note")))
        else content.addView(createRewardChestPanel(snapshot), matchParams(bottom = 16))
    }

    private fun createRewardsHeroHeader(snapshot: RewardsApiClient.Snapshot?): View = FrameLayout(this).apply {
        minimumHeight = dp(250)
        clipToOutline = true
        background = gradientDrawable(intArrayOf(0xFFFFCF9E.toInt(), 0xFFFFEAC8.toInt(), COLOR_WARM_PEACH), 0)
        addView(ImageView(this@PartnerDramaActivity).apply {
            setImageResource(R.drawable.bg_rewards_hero)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0.98f
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            addView(TextView(this@PartnerDramaActivity).apply {
                text = t("reward_center"); textSize = 29f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY)
            })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = t("rewards_subtitle"); textSize = 13f; setTextColor(0xB819202B.toInt()); setPadding(0, dp(3), 0, dp(11))
            })
            val balances = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val wallet = snapshot?.wallet
            balances.addView(createRewardBalance(t("gems"), wallet?.coins?.let(::formatGems) ?: "—", compact = false, isGem = true) { showGemIncomeDetails() }, LinearLayout.LayoutParams(0, dp(88), 1f))
            balances.addView(createRewardBalance(t("wallet_balance"), wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—", compact = true, isGem = false) { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) }, LinearLayout.LayoutParams(0, dp(88), 1f).apply { marginStart = dp(10) })
            addView(balances)
            addView(TextView(this@PartnerDramaActivity).apply {
                text = t("reward_balance_tap_hint"); textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xA619202B.toInt()); setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun createRewardBalance(label: String, value: String, compact: Boolean, isGem: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(13), dp(10), dp(13))
        background = roundedDrawable(0xF7FFFFFF.toInt(), 20)
        elevation = 0f
        isClickable = true
        contentDescription = "$label $value"
        setOnClickListener { onClick() }
        val icon = ImageView(this@PartnerDramaActivity).apply {
            setImageResource(if (isGem) R.drawable.reward_gem_crystal else R.drawable.ic_wallet)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        if (isGem) gemBalanceAnchor = icon
        addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0)
            val valueView = TextView(this@PartnerDramaActivity).apply {
                text = value; textSize = if (compact) 18f else 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 1
            }
            if (isGem) gemBalanceLabel = valueView else cashBalanceLabel = valueView
            addView(valueView)
            addView(TextView(this@PartnerDramaActivity).apply { text = label; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(COLOR_TEXT_MUTED) }, LinearLayout.LayoutParams(dp(18), dp(18)))
    }

    private fun createCrownRoadPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(16))
        background = roundedDrawable(Color.WHITE, 24)
        addView(createRewardPanelTitle(t("crown_road"), t("crown_road_subtitle"), t("crown_accelerate"), R.drawable.ic_crown) {
            snapshot?.crownProgress?.firstOrNull { !it.earned }?.let(::showCrownRewardedUpgradeDialog)
        })
        val crowns = snapshot?.crownProgress?.sortedBy { it.levelRank }.orEmpty()
        if (crowns.isEmpty()) {
            addView(createInlineNotice(t("ledger_note")), matchParams(top = 12))
        } else {
            crowns.forEachIndexed { index, level ->
                addView(createCrownLevelRow(level, crowns), matchParams(top = if (index == 0) 12 else 8))
            }
        }
    }

    private fun createCrownLevelRow(level: RewardsApiClient.CrownProgress, all: List<RewardsApiClient.CrownProgress>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = roundedDrawable(if (level.earned) 0xFFFFF5E8.toInt() else 0xFFF7F7FA.toInt(), 17)
        val titleRow = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_crown); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = ColorStateList.valueOf(if (level.earned) COLOR_SUNSET else COLOR_TEXT_MUTED) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        titleRow.addView(TextView(this@PartnerDramaActivity).apply { text = t(crownTitleKey(level.code)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(dp(7), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this@PartnerDramaActivity).apply { text = t("crown_multiplier", String.format(Locale.US, "%.2f", level.episodeRewardMultiplier)); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (level.earned) COLOR_CORAL else COLOR_TEXT_MUTED); maxLines = 1 })
        addView(titleRow)
        addView(TextView(this@PartnerDramaActivity).apply {
            text = t(crownBenefitKey(level.code), formatGems(level.gems)); textSize = 11f; setTextColor(COLOR_TEXT_MUTED); maxLines = 2; setPadding(dp(35), dp(3), 0, 0)
        })
        val metrics = listOf(level.rounds to t("crown_metric_watch"), level.signins to t("crown_metric_checkin"), level.rewardedAds to t("crown_metric_ads"))
            .filter { it.first.target > 0 }
        if (metrics.isNotEmpty()) {
            addView(TextView(this@PartnerDramaActivity).apply {
                text = metrics.joinToString("  ·  ") { (metric, label) -> "$label ${metric.current}/${metric.target}" }
                textSize = 10f; setTextColor(COLOR_TEXT_MUTED); maxLines = 1; setPadding(dp(35), dp(5), 0, 0)
            })
        }
        if (!level.earned) {
            addView(ProgressBar(this@PartnerDramaActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = (level.progress * 100).toInt().coerceIn(0, 100); progressTintList = ColorStateList.valueOf(COLOR_CORAL); backgroundTintList = ColorStateList.valueOf(0x1F202B33)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8); leftMargin = dp(35) })
        }
    }

    private fun crownTitleKey(code: String): String = when (code) {
        "BRONZE" -> "crown_bronze"; "SILVER" -> "crown_silver"; "GOLD" -> "crown_gold"; "DIAMOND" -> "crown_diamond"; else -> "crown_crown"
    }

    private fun crownBenefitKey(code: String): String = when (code) {
        "BRONZE" -> "crown_bronze_benefit"; "SILVER" -> "crown_silver_benefit"; "GOLD" -> "crown_gold_benefit"; "DIAMOND" -> "crown_diamond_benefit"; else -> "crown_crown_benefit"
    }

    private fun createRewardPanelTitle(title: String, subtitle: String, action: String, iconRes: Int, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(iconRes); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(6), 0)
            addView(TextView(this@PartnerDramaActivity).apply { text = title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@PartnerDramaActivity).apply { text = subtitle; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PartnerDramaActivity).apply { text = "$action ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(COLOR_CORAL, 20); setPadding(dp(13), dp(9), dp(13), dp(9)); setOnClickListener { onClick() }; isClickable = true })
    }

    private fun createPendingRewardAction(pending: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = roundedDrawable(0xFFFFF4EF.toInt(), 18)
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
            addView(TextView(this@PartnerDramaActivity).apply { text = if (pending > 0) "+${formatGems(pending.toLong())} ${t("gems")}" else t("pending_rewards"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@PartnerDramaActivity).apply { text = t("claim_after_ad"); textSize = 11f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PartnerDramaActivity).apply { text = t("claim_all"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); isClickable = pending > 0; setOnClickListener { showEpisodeRewardClaimDialog() } })
    }

    private fun createCoinMilestone(value: String, label: String, reward: Int = 0): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(7), dp(7), dp(7)); background = gradientDrawable(intArrayOf(0xFFFFD7A3.toInt(), 0xFFFFA858.toInt()), 16)
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@PartnerDramaActivity).apply { text = value; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY) })
        addView(TextView(this@PartnerDramaActivity).apply { text = if (reward > 0) "$label · +$reward" else label; textSize = 10f; maxLines = 2; gravity = Gravity.CENTER; setTextColor(0xCC1C1C1C.toInt()); setPadding(0, dp(2), 0, 0) })
    }

    private fun createChallengeItem(title: String, coins: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(9), dp(6), dp(7)); background = roundedDrawable(0xFFFFF6ED.toInt(), 16)
        addView(TextView(this@PartnerDramaActivity).apply { text = title; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2 })
        val row = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(TextView(this@PartnerDramaActivity).apply { text = coins; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL); setPadding(dp(3), 0, 0, 0) })
        addView(row)
    }

    private fun createDailyBenefitsPanel(snapshot: RewardsApiClient.Snapshot?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(15)); background = roundedDrawable(Color.WHITE, 24)
        val heading = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_calendar_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(36), dp(36)))
        heading.addView(LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(7), 0, 0, 0)
            addView(TextView(this@PartnerDramaActivity).apply { text = t("daily_benefits"); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@PartnerDramaActivity).apply { text = t("daily_benefits_subtitle"); textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(3), 0, 0) })
        })
        addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        val checkinText = snapshot?.let { t("today_day_reward", it.checkinTodayIndex, it.checkinNextRewardCoins) } ?: t("ledger_note")
        addView(createBenefitRow(t("checkin"), checkinText, if (snapshot?.checkinSignedToday == true) t("checked_in") else t("watch_ad_checkin"), R.drawable.ic_calendar_reward) { if (snapshot?.checkinSignedToday != true) completeDailyCheckin() })
        addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun createBenefitRow(title: String, subtitle: String, action: String, iconRes: Int, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), 0, dp(6))
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(iconRes); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(50), dp(50)))
        addView(LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, dp(5), 0); addView(TextView(this@PartnerDramaActivity).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) }); addView(TextView(this@PartnerDramaActivity).apply { text = subtitle; textSize = 11f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(3), 0, 0) }) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PartnerDramaActivity).apply { text = action; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(COLOR_CORAL, 18); setPadding(dp(11), dp(9), dp(11), dp(9)); setOnClickListener { onClick() }; isClickable = true })
    }

    private fun createRewardChestPanel(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = roundedDrawable(Color.WHITE, 22)
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_random_chest_reward); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(76), dp(76)))
        addView(LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, 0, 0)
            addView(TextView(this@PartnerDramaActivity).apply { text = t("chest"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@PartnerDramaActivity).apply { text = if (snapshot.chest.availableCount > 0) "${snapshot.chest.availableCount} ${t("chest")}" else t("no_chest"); textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, dp(7)) })
            addView(TextView(this@PartnerDramaActivity).apply { text = if (snapshot.chest.availableCount > 0) t("open_chest") else t("go_watch"); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(COLOR_CORAL, 18); setPadding(dp(13), dp(8), dp(13), dp(8)); setOnClickListener { if (snapshot.chest.availableCount > 0) requestRandomChestOpen() else { activeTab = MainTab.HOME; renderActiveTab() } }; isClickable = true })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun createProfilePage(): View = scrollPage { content ->
        content.addView(createProfileHero(), matchParams(bottom = 16))
        content.addView(createWalletCard(), matchParams(bottom = 18))
        content.addView(createSectionHeader(t("continue_watching"), t("home_subtitle")), matchParams(bottom = 12))
        catalogItems.take(4).forEach { content.addView(createHistoryRow(it), matchParams(bottom = 10)) }
        content.addView(createSectionHeader(t("my_services"), t("profile_subtitle")), matchParams(top = 10, bottom = 10))
        content.addView(createSettingsRow(t("favorites"), t("continue_watching"), "›") { toast(t("favorites")) }, matchParams(bottom = 8))
        content.addView(createSettingsRow(t("reward_ledger"), t("ledger_note"), "›") { showRewardLedgerHint() }, matchParams(bottom = 8))
        content.addView(createSettingsRow(t("ad_privacy"), t("rules"), "›") { showAdPrivacySettings() }, matchParams(bottom = 8))
        content.addView(createSettingsRow(t("language"), AppLanguage.label(this), "›") { showLanguageSettings() }, matchParams(bottom = 16))
        // 联调阶段保留服务器地址；上线前按产品要求移除此开发分组。
        content.addView(createSectionHeader(t("developer_services"), ""), matchParams(bottom = 10))
        content.addView(createSettingsRow(t("content_service"), localContentBaseUrl(), "›") { showLocalServerSettings() }, matchParams(bottom = 8))
        content.addView(createSettingsRow(t("rewards_service"), rewardsBaseUrl(), "›") { showRewardsServerSettings() }, matchParams(bottom = 18))
    }

    private fun createProfileHero(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(4))
        addView(TextView(this@PartnerDramaActivity).apply {
            text = "J"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(COLOR_AQUA, COLOR_CORAL), 30)
        }, LinearLayout.LayoutParams(dp(62), dp(62)))
        addView(LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, 0, 0)
            addView(TextView(this@PartnerDramaActivity).apply { text = t("profile"); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@PartnerDramaActivity).apply { text = t("profile_subtitle"); textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun scrollPage(build: (LinearLayout) -> Unit): View {
        // 常规页面为固定资产栏预留 126dp 高度；奖励页使用自己的首屏 hero，因此从顶部开始。
        val topInset = if (activeTab == MainTab.TASKS) 0 else 128
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(topInset), dp(16), dp(94))
        }
        build(content)
        return ScrollView(this).apply {
            isFillViewport = true
            background = pageCanvasDrawable()
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun matchParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun createPageTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@PartnerDramaActivity).apply {
            text = title
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = subtitle
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun createBalanceMissionCard(): View = createInlineNotice(t("ledger_note"))

    private fun createCategoryTabs(): View = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val allLabel = t("all_dramas")
        (listOf(allLabel) + catalogGenreGroups().map { it.first }).forEachIndexed { index, label ->
            val selected = if (label == allLabel) selectedCatalogGenre == null else label == selectedCatalogGenre
            row.addView(TextView(this@PartnerDramaActivity).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else COLOR_TEXT_MUTED)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundedDrawable(if (selected) COLOR_CORAL else COLOR_SURFACE, 16)
                isClickable = true
                setOnClickListener { selectedCatalogGenre = label.takeUnless { it == allLabel }; renderActiveTab() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = dp(8) })
        }
        addView(row)
    }

    /** 分类、推荐与排序均读取统一内容模型；未来 PSSDK 接通后仅替换 catalogItems 的数据来源。 */
    private fun catalogGenreGroups(): List<Pair<String, List<LocalDrama>>> = catalogItems
        .flatMap { drama -> drama.genres.mapNotNull { genre -> genre.trim().takeIf(String::isNotBlank)?.let { it to drama } } }
        .groupBy({ it.first }, { it.second })
        .map { (genre, dramas) -> genre to dramas.distinctBy { it.id } }
        .sortedByDescending { it.second.size }
        .take(MAX_CATALOG_GENRES)

    private fun filteredCatalogItems(): List<LocalDrama> {
        val genre = selectedCatalogGenre ?: return catalogItems
        return catalogItems.filter { drama -> drama.genres.any { it.equals(genre, ignoreCase = true) } }
    }

    private fun hasCatalogPreferenceSignals(): Boolean = catalogItems.any { drama ->
        favorites.getBoolean("favorite_${drama.id}", false) || playbackHistory.contains(resumePreferenceKey(drama.id, "episode"))
    }

    private fun recommendedCatalogItems(): List<LocalDrama> {
        val preferredGenres = catalogItems
            .filter { drama -> favorites.getBoolean("favorite_${drama.id}", false) || playbackHistory.contains(resumePreferenceKey(drama.id, "episode")) }
            .flatMap { it.genres }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()
        return catalogItems.sortedBy { drama ->
            when {
                favorites.getBoolean("favorite_${drama.id}", false) -> 0
                playbackHistory.contains(resumePreferenceKey(drama.id, "episode")) -> 1
                drama.genres.any { it.trim().lowercase(Locale.ROOT) in preferredGenres } -> 2
                else -> 3
            }
        }
    }

    private fun createHeroBanner(drama: LocalDrama): View = FrameLayout(this).apply {
        minimumHeight = dp(214)
        clipToOutline = true
        background = roundedDrawable(COLOR_INK, 24)
        isClickable = true
        setOnClickListener { showPlayer(drama, 0) }
        addView(ImageView(this@PartnerDramaActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(drama.coverImage) { crossfade(true); placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(View(this@PartnerDramaActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xD9161F2A.toInt(), 0x54161F2A.toInt(), 0xB3161F2A.toInt()))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val copy = LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(createPill(t("featured_this_week"), COLOR_CORAL))
        copy.addView(TextView(this@PartnerDramaActivity).apply { text = drama.title.ifBlank { t("watch_now") }; textSize = 25f; maxLines = 2; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(14), 0, dp(4)) })
        copy.addView(TextView(this@PartnerDramaActivity).apply { text = t("episodes_count", drama.total.coerceAtLeast(1)); textSize = 13f; setTextColor(0xE6FFFFFF.toInt()) })
        addView(copy, FrameLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(18); bottomMargin = dp(18) })
        val play = LinearLayout(this@PartnerDramaActivity).apply {
            gravity = Gravity.CENTER_VERTICAL; background = roundedDrawable(0xEBFFFFFF.toInt(), 24); setPadding(dp(4), dp(4), dp(12), dp(4))
            addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_play_coral); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(TextView(this@PartnerDramaActivity).apply { text = t("episodes_count", drama.total.coerceAtLeast(1)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(dp(5), 0, 0, 0) })
        }
        addView(play, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(14); bottomMargin = dp(14) })
    }

    private fun createSearchPrompt(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(12), dp(15), dp(12)); background = roundedDrawable(0xFAFFFFFF.toInt(), 19); elevation = dp(2).toFloat(); isClickable = true
        setOnClickListener { showSearchPage() }
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(COLOR_TEXT_MUTED); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(23), dp(23)))
        addView(TextView(this@PartnerDramaActivity).apply { text = t("search_hint"); textSize = 14f; setTextColor(COLOR_TEXT_MUTED); setPadding(dp(10), 0, 0, 0) })
    }

    private fun showSearchPage() {
        playerShown = false
        withdrawalPageShown = false
        gemBalanceAnchor = null; gemBalanceLabel = null; cashBalanceLabel = null
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(128), dp(16), dp(36)) }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_arrow_back); imageTintList = ColorStateList.valueOf(COLOR_TEXT_PRIMARY); scaleType = ImageView.ScaleType.CENTER_INSIDE; contentDescription = t("back"); isClickable = true; setOnClickListener { renderActiveTab() } }, LinearLayout.LayoutParams(dp(40), dp(40)))
        titleRow.addView(TextView(this@PartnerDramaActivity).apply { text = t("search"); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(dp(5), 0, 0, 0) })
        content.addView(titleRow, matchParams(bottom = 12))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply {
            hint = t("search_hint"); textSize = 15f; setSingleLine(true); setTextColor(COLOR_TEXT_PRIMARY); setHintTextColor(COLOR_TEXT_MUTED)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); compoundDrawablePadding = dp(10)
            background = roundedDrawable(0xF7FFFFFF.toInt(), 19); setPadding(dp(15), dp(7), dp(15), dp(7))
        }
        fun renderResults(query: String) {
            results.removeAllViews()
            val normalized = query.trim()
            val filtered = if (normalized.isBlank()) catalogItems else catalogItems.filter { drama ->
                listOf(drama.title, drama.desc).plus(drama.genres).any { it.contains(normalized, ignoreCase = true) }
            }
            if (filtered.isEmpty()) results.addView(createInlineNotice(t("search_empty")), matchParams(top = 16))
            else filtered.forEach { drama -> results.addView(createDiscoverDramaCard(drama), matchParams(top = 10)) }
            if (BuildConfig.SHORT_DRAMA_SDK_ENABLED && normalized.isNotBlank()) {
                val requestedQuery = normalized
                ShortDramaSdkManager.get(this).search(normalized, onSuccess = { cards -> runOnUiThread {
                    if (input.text.toString().trim() != requestedQuery) return@runOnUiThread
                    if (cards.isNotEmpty()) {
                        results.addView(createSectionHeader(t("sdk_search_results"), t("sdk_search_loaded", cards.size)), matchParams(top = 18, bottom = 4))
                        cards.forEach { card -> results.addView(createSdkSearchDramaCard(card), matchParams(top = 10)) }
                    }
                } }, onFailure = { message -> logStatus("PSSDK search unavailable: $message") })
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderResults(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        content.addView(results, matchParams(top = 6))
        renderResults("")
        root.removeAllViews()
        root.addView(ScrollView(this).apply { background = pageCanvasDrawable(); addView(content) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createPersistentAssetHeader(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP))
    }

    private fun createSdkSearchDramaCard(card: ShortDramaSdkManager.DramaCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(10), dp(12), dp(10)); background = roundedDrawable(COLOR_CARD, 18); isClickable = true
        setOnClickListener { showSdkDramaPlayer(card) }
        addView(ImageView(this@PartnerDramaActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = posterFallbackDrawable(); load(card.coverUrl) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) } }, LinearLayout.LayoutParams(dp(92), dp(122)))
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), 0, dp(4))
            addView(TextView(this@PartnerDramaActivity).apply { text = card.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2 })
            addView(TextView(this@PartnerDramaActivity).apply { text = t("episodes_count", card.totalEpisodes.coerceAtLeast(1)); textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), 0, 0) })
            addView(TextView(this@PartnerDramaActivity).apply { text = t("watch_now"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT); setPadding(0, dp(18), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun showSdkDramaPlayer(card: ShortDramaSdkManager.DramaCard) {
        val fragment = ShortDramaSdkManager.get(this).createDetailFragment(card) { renderActiveTab() }
        playerShown = false; withdrawalPageShown = false; gemBalanceAnchor = null; gemBalanceLabel = null; cashBalanceLabel = null
        val host = FrameLayout(this).apply { id = View.generateViewId(); setBackgroundColor(Color.BLACK) }
        root.removeAllViews()
        root.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        supportFragmentManager.beginTransaction().replace(host.id, fragment, "pssdk_detail").commitNowAllowingStateLoss()
        // SDK owns video controls; JoyeDrama keeps actionable wallet/gem balances on the same safe top row.
        root.addView(createPlayerTopBar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.TOP))
    }

    private fun createHorizontalDramaShelf(items: List<LocalDrama>): View = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { index, drama ->
            row.addView(createCompactDramaCard(drama), LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = dp(10) })
        }
        addView(row)
    }

    private fun addDramaGrid(parent: LinearLayout, dramas: List<LocalDrama>) {
        dramas.chunked(2).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { index, drama ->
                row.addView(createDramaCard(drama), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) marginEnd = dp(8) else marginStart = dp(8)
                })
            }
            if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            parent.addView(row, matchParams(bottom = 16))
        }
    }

    private fun createCompactDramaCard(drama: LocalDrama): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setOnClickListener { showPlayer(drama, 0) }
        addView(ImageView(this@PartnerDramaActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = posterFallbackDrawable()
            load(drama.coverImage) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176)))
        addView(TextView(this@PartnerDramaActivity).apply {
            text = drama.title.ifBlank { t("drama_fallback") }
            maxLines = 2
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun createDiscoverDramaCard(drama: LocalDrama): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(10), dp(10), dp(12), dp(10))
        background = roundedDrawable(COLOR_CARD, 18)
        isClickable = true
        setOnClickListener { showPlayer(drama, 0) }
        addView(ImageView(this@PartnerDramaActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = posterFallbackDrawable()
            load(drama.coverImage) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
        }, LinearLayout.LayoutParams(dp(92), dp(122)))
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), 0, dp(4))
            addView(TextView(this@PartnerDramaActivity).apply { text = drama.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2 })
            addView(TextView(this@PartnerDramaActivity).apply { text = "${t("episodes_count", drama.total.coerceAtLeast(1))} · ${t("portrait_drama")}"; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), 0, 0) })
            addView(TextView(this@PartnerDramaActivity).apply { text = "${t("continue_watching")} ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT); setPadding(0, dp(18), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun createWalletCard(): View = createRewardsWalletSummary()

    private fun createHistoryRow(drama: LocalDrama): View = LinearLayout(this).apply {
        val resumeEpisode = savedResumeEpisodeIndex(drama)
        val resumeSecond = savedResumePositionSeconds(drama)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(12), dp(10))
        background = roundedDrawable(COLOR_CARD, 16)
        isClickable = true
        setOnClickListener { showPlayer(drama, resumeEpisode, resumeSecond) }
        addView(ImageView(this@PartnerDramaActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = posterFallbackDrawable(); load(drama.coverImage) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) } }, LinearLayout.LayoutParams(dp(48), dp(64)))
        addView(TextView(this@PartnerDramaActivity).apply { text = "${drama.title}\n${t("watch_progress", resumeEpisode + 1, formatPlaybackTime(resumeSecond))}"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2; setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PartnerDramaActivity).apply { text = "${t("continue_label")} ›"; textSize = 13f; setTextColor(COLOR_ACCENT) })
    }

    private fun createSettingsRow(title: String, subtitle: String, trailing: String, action: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedDrawable(COLOR_CARD, 16)
        isClickable = true
        setOnClickListener { action() }
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PartnerDramaActivity).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@PartnerDramaActivity).apply { text = subtitle; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing == "›") {
            addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(COLOR_TEXT_MUTED); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(22), dp(22)))
        } else {
            addView(TextView(this@PartnerDramaActivity).apply { text = trailing; textSize = 14f; setTextColor(COLOR_TEXT_MUTED) })
        }
    }

    private fun createInlineNotice(message: String): View = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(COLOR_TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(28), dp(18), dp(28))
        background = roundedDrawable(COLOR_CARD, 16)
    }

    /** 非奖励页固定在顶部的资产栏；点击宝石进入奖励页，点击钱包进入提现页。 */
    /** 固定资产栏使用系统状态栏/挖孔 inset；真实设备上不会再被顶部传感器或状态栏遮挡。 */
    private fun createPersistentAssetHeader(): View {
        val shell = FrameLayout(this).apply { background = pageCanvasDrawable() }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            val wallet = rewardsSnapshot?.wallet
            addView(createAssetPill(
                icon = R.drawable.reward_gem_crystal,
                label = t("gems"),
                value = wallet?.coins?.let(::formatGems) ?: "—",
                isGem = true,
            ) { showGemIncomeDetails() }, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(createAssetPill(
                icon = R.drawable.ic_wallet,
                label = t("wallet_balance"),
                value = wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—",
                isGem = false,
            ) { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
        }
        shell.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP).apply { topMargin = dp(30) })
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val params = row.layoutParams as FrameLayout.LayoutParams
            val margin = maxOf(safeTop, dp(18)) + dp(14)
            if (params.topMargin != margin) { params.topMargin = margin; row.layoutParams = params }
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        return shell
    }

    private fun createAssetPill(icon: Int, label: String, value: String, isGem: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), dp(6), dp(10), dp(6))
        background = roundedDrawable(0xEFFFFFFF.toInt(), 18)
        elevation = 0f
        isClickable = true
        contentDescription = "$label $value"
        setOnClickListener { onClick() }
        val image = ImageView(this@PartnerDramaActivity).apply { setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        if (isGem) gemBalanceAnchor = image
        addView(image, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), 0, 0, 0)
            val valueView = TextView(this@PartnerDramaActivity).apply { text = value; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 1 }
            if (isGem) gemBalanceLabel = valueView else cashBalanceLabel = valueView
            addView(valueView)
            addView(TextView(this@PartnerDramaActivity).apply { text = label; textSize = 9f; setTextColor(COLOR_TEXT_MUTED); maxLines = 1 })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun createCashoutSummary(wallet: RewardsApiClient.Wallet): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(14)); background = roundedDrawable(COLOR_CARD, 22)
        val items = listOf(
            Triple(R.drawable.ic_wallet, t("available_balance"), formatCurrencyAmount(wallet, wallet.availableBalance, wallet.availableBalanceUsdReference, includeUsdReference = true)),
            Triple(R.drawable.ic_wallet, t("tomorrow_transfer"), formatCurrencyAmount(wallet, wallet.release.expectedTomorrowTransfer, wallet.release.expectedTomorrowTransferUsdReference, includeUsdReference = true)),
            Triple(R.drawable.reward_gem_crystal, t("ad_boost"), "+${formatCurrencyAmount(wallet, wallet.release.expectedBoostTransfer, wallet.release.expectedBoostTransferUsdReference, includeUsdReference = true)}"),
        )
        items.forEachIndexed { index, item ->
            addView(LinearLayout(this@PartnerDramaActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(item.first); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(TextView(this@PartnerDramaActivity).apply { text = item.second; textSize = 10f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); maxLines = 2; setPadding(0, dp(4), 0, dp(2)) })
                addView(TextView(this@PartnerDramaActivity).apply { text = item.third; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index < items.lastIndex) addView(View(this@PartnerDramaActivity).apply { setBackgroundColor(0x151C1C1C) }, LinearLayout.LayoutParams(dp(1), dp(55)))
        }
    }

    private fun createCashoutExplainer(wallet: RewardsApiClient.Wallet): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(14)); background = roundedDrawable(COLOR_CARD, 22)
        addView(TextView(this@PartnerDramaActivity).apply { text = t("cashout_explainer"); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(0, 0, 0, dp(8)) })
        val requestDetail = rewardsSnapshot?.withdrawal?.let {
            t("cashout_step_request_dynamic", formatCurrencyAmount(wallet, it.threshold, it.thresholdUsdReference, includeUsdReference = true))
        } ?: t("cashout_step_request")
        val steps = listOf(
            t("earn_gems") to t("cashout_step_earn"),
            t("convert_wallet") to t("cashout_step_convert"),
            t("request_cashout") to requestDetail,
        )
        steps.forEachIndexed { index, step ->
            addView(LinearLayout(this@PartnerDramaActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                addView(TextView(this@PartnerDramaActivity).apply { text = "${index + 1}"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(if (index == 0) COLOR_CORAL else COLOR_AQUA, 14) }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0)
                    addView(TextView(this@PartnerDramaActivity).apply { text = step.first; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
                    addView(TextView(this@PartnerDramaActivity).apply { text = step.second; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
                })
            })
        }
    }

    /** 服务端余额确认后，宝石由屏幕中心飞入当前余额锚点，最终才刷新锚点文本。 */
    private fun animateClaimedGems(previous: Long, current: Long) {
        val target = gemBalanceAnchor ?: return
        if (!target.isAttachedToWindow || gemFlightInProgress) return
        gemFlightInProgress = true
        gemBalanceLabel?.text = formatGems(previous)
        val rootLocation = IntArray(2)
        val targetLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        target.getLocationOnScreen(targetLocation)
        val fly = ImageView(this).apply {
            setImageResource(R.drawable.reward_gem_crystal)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0f
        }
        val size = dp(42)
        val startX = root.width / 2f - size / 2f
        val startY = root.height * 0.53f - size / 2f
        root.addView(fly, FrameLayout.LayoutParams(size, size).apply { leftMargin = startX.toInt(); topMargin = startY.toInt() })
        val endX = targetLocation[0] - rootLocation[0] + target.width / 2f - startX - size / 2f
        val endY = targetLocation[1] - rootLocation[1] + target.height / 2f - startY - size / 2f
        val animation = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(fly, View.ALPHA, 0f, 1f, 1f, 0f),
                ObjectAnimator.ofFloat(fly, View.TRANSLATION_X, 0f, endX),
                ObjectAnimator.ofFloat(fly, View.TRANSLATION_Y, 0f, endY),
                ObjectAnimator.ofFloat(fly, View.SCALE_X, 1.35f, 0.54f),
                ObjectAnimator.ofFloat(fly, View.SCALE_Y, 1.35f, 0.54f),
            )
            duration = 720L
            interpolator = AccelerateDecelerateInterpolator()
        }
        animation.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                (fly.parent as? ViewGroup)?.removeView(fly)
                gemBalanceLabel?.text = formatGems(current)
                target.animate().scaleX(1.14f).scaleY(1.14f).setDuration(120L).withEndAction {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                }.start()
                gemFlightInProgress = false
            }
        })
        animation.start()
    }

    private fun createPlayerAssetStrip(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(5), dp(9), dp(5)); background = roundedDrawable(0xD9141720.toInt(), 18)
        val wallet = rewardsSnapshot?.wallet
        val gem = ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE; isClickable = true; contentDescription = t("gems"); setOnClickListener { showGemIncomeDetails() } }
        gemBalanceAnchor = gem
        addView(gem, LinearLayout.LayoutParams(dp(30), dp(30)))
        val gems = TextView(this@PartnerDramaActivity).apply { text = wallet?.coins?.let(::formatGems) ?: "—"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(4), 0, dp(8), 0) }
        gemBalanceLabel = gems
        addView(gems)
        addView(View(this@PartnerDramaActivity).apply { setBackgroundColor(0x55FFFFFF); }, LinearLayout.LayoutParams(dp(1), dp(20)))
        addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.ic_wallet); scaleType = ImageView.ScaleType.CENTER_INSIDE; isClickable = true; contentDescription = t("wallet"); setOnClickListener { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) } }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(6) })
        val cash = TextView(this@PartnerDramaActivity).apply { text = wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(3), 0, 0, 0); isClickable = true; setOnClickListener { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) } }
        cashBalanceLabel = cash
        addView(cash)
    }

    private fun createTopSafePlayerAssetStrip(): View {
        val shell = FrameLayout(this)
        val strip = createPlayerAssetStrip()
        shell.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.TOP or Gravity.END).apply { topMargin = dp(26); marginEnd = dp(18) })
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val params = strip.layoutParams as FrameLayout.LayoutParams
            val margin = maxOf(safeTop, dp(18)) + dp(10)
            if (params.topMargin != margin) { params.topMargin = margin; strip.layoutParams = params }
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        return shell
    }

    private fun formatGems(value: Long): String = String.format(Locale.US, "%,d", value)

    private fun createBottomNavigation(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(9), dp(14), dp(10)); background = topRoundedDrawable(0xFCFFFFFF.toInt(), 26); elevation = dp(10).toFloat()
        MainTab.entries.forEach { tab ->
            val active = activeTab == tab
            addView(LinearLayout(this@PartnerDramaActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
                setOnClickListener { if (activeTab != tab) { activeTab = tab; renderActiveTab() } }
                val icon = ImageView(this@PartnerDramaActivity).apply {
                    setImageResource(tab.iconRes)
                    imageTintList = ColorStateList.valueOf(if (active) COLOR_CORAL else COLOR_TEXT_MUTED)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(this@PartnerDramaActivity).apply { text = t(tab.languageKey); textSize = 11f; gravity = Gravity.CENTER; typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; setTextColor(if (active) COLOR_CORAL else COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun createSectionHeader(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@PartnerDramaActivity).apply { text = title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        if (subtitle.isNotBlank()) addView(TextView(this@PartnerDramaActivity).apply { text = subtitle; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0) })
    }

    private fun createFloatingRewardWheel(compact: Boolean = false): View {
        val gemSize = if (compact) dp(62) else dp(68)
        val gem = ImageView(this).apply {
            setImageResource(R.drawable.reward_gem_crystal)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = t("claim_all")
            isClickable = true
            setOnClickListener {
                if (rewardsSnapshot?.ring?.claimable == true) showEpisodeRewardClaimDialog() else {
                    exoPlayer?.pause(); playerIsPaused = true; activeTab = MainTab.TASKS; showCatalog()
                }
            }
        }
        ObjectAnimator.ofFloat(gem, View.TRANSLATION_Y, 0f, -dp(4).toFloat(), 0f).apply {
            duration = 1_700L; repeatCount = ObjectAnimator.INFINITE; interpolator = AccelerateDecelerateInterpolator(); start()
        }
        val label = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = if (compact) 10f else 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); setPadding(dp(10), dp(5), dp(10), dp(5)); background = gradientDrawable(intArrayOf(0xEFFF9061.toInt(), 0xE8FF5B62.toInt()), 14)
            isClickable = true; setOnClickListener { gem.performClick() }
        }
        floatingWheelLabels.add(label); updateFloatingRewardWheel(label)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false
            addView(gem, LinearLayout.LayoutParams(gemSize, gemSize))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { topMargin = -dp(4) })
            if (playerShown) {
                val boostButton = TextView(this@PartnerDramaActivity).apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)) }
                floatingBoostButtons.add(boostButton); updateFloatingRewardBoostButton(boostButton)
                addView(boostButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { topMargin = dp(7) })
            }
        }
    }

    private fun refreshFloatingRewardWheels() {
        floatingWheelLabels.removeAll { !it.isAttachedToWindow }
        floatingWheelLabels.forEach { updateFloatingRewardWheel(it) }
        floatingBoostButtons.removeAll { !it.isAttachedToWindow }
        floatingBoostButtons.forEach { updateFloatingRewardBoostButton(it) }
    }

    private fun updateFloatingRewardBoostButton(button: TextView) {
        val episodeId = selectedEpisode()?.id
        val boosted = episodeId != null && isEpisodeRewardBoosted(episodeId)
        button.text = if (boosted) t("reward_doubled") else t("double_episode_reward")
        button.setTextColor(if (boosted) 0xFFFFE7A0.toInt() else Color.WHITE)
        button.background = gradientDrawable(
                if (boosted) intArrayOf(0xFFFFD180.toInt(), 0xFFFFB74D.toInt()) else intArrayOf(0xFFFF9A62.toInt(), 0xFFFF5C63.toInt()),
            14,
        )
        button.isClickable = episodeId != null
        button.alpha = if (boosted) 0.78f else 1f
        button.setOnClickListener {
            when {
                episodeId == null -> toast(t("episode_not_loaded"))
                boosted -> toast(t("reward_doubled_notice"))
                else -> requestEpisodeRewardBoost(episodeId)
            }
        }
    }

    private fun updateFloatingRewardWheel(label: TextView) {
        val ring = rewardsSnapshot?.ring
        label.text = when {
            ring == null -> "…"
            ring.claimable -> "+${ring.rewardCoins}"
            else -> t("watch_to_earn")
        }
    }

    private fun createRingCard(): View {
        val ring = rewardsSnapshot?.ring
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedDrawable(COLOR_CARD, 18)
            val circle = TextView(this@PartnerDramaActivity).apply {
                text = if (ring?.claimable == true) "+${ring.rewardCoins}" else t("watch_to_earn")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT_PRIMARY)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(5), if (ring?.claimable == true) COLOR_ACCENT else COLOR_PURPLE); setColor(COLOR_SURFACE) }
            }
            addView(circle, LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(LinearLayout(this@PartnerDramaActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@PartnerDramaActivity).apply { text = t("watch_tasks"); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
                addView(TextView(this@PartnerDramaActivity).apply {
                    text = ring?.let { if (it.claimable) t("ring_claimable", it.rewardCoins) else t("ring_collecting") } ?: t("wallet_syncing")
                    textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@PartnerDramaActivity).apply {
                text = if (ring?.claimable == true) t("claim_all") else t("keep_watching")
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
                background = roundedDrawable(if (ring?.claimable == true) COLOR_ACCENT else COLOR_SURFACE, 14)
                setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true
                setOnClickListener { if (ring?.claimable == true) showEpisodeRewardClaimDialog() else { activeTab = MainTab.DISCOVER; renderActiveTab() } }
            })
        }
    }

    private fun currentEpisodePendingReward(): RewardsApiClient.EpisodeReward? {
        val pending = rewardsSnapshot?.pendingEpisodeRewards.orEmpty()
        if (playerShown) {
            val currentId = selectedEpisode()?.id
            return pending.firstOrNull { it.episodeId == currentId }
        }
        return pending.lastOrNull()
    }

    private fun isEpisodeRewardBoosted(episodeId: String): Boolean =
        episodeId in rewardsSnapshot?.preboostedEpisodeIds.orEmpty() ||
            rewardsSnapshot?.pendingEpisodeRewards?.firstOrNull { it.episodeId == episodeId }?.boosted == true

    private fun showEpisodeRewardClaimDialog() {
        val snapshot = rewardsSnapshot ?: return
        val ring = snapshot.ring
        if (!ring.claimable || ring.rewardCoins <= 0) { toast(t("no_pending_gems")); return }
        AlertDialog.Builder(this)
            .setTitle(t("claim_episode_rewards"))
            .setMessage(t("claim_episode_rewards_message", ring.rewardCoins))
            .setNegativeButton(t("later"), null)
            .setPositiveButton(t("watch_ad_claim")) { _, _ -> requestRewardedClaimAllEpisodeRewards() }
            .show()
    }

    private fun requestEpisodeRewardBoost(episodeId: String) {
        AlertDialog.Builder(this)
            .setTitle(t("double_episode_reward"))
            .setMessage(t("double_episode_reward_message"))
            .setNegativeButton(t("not_now"), null)
            .setPositiveButton(t("watch_ad_double")) { _, _ ->
                requestRewardedAction("rv_episode_reward_boost", targetId = episodeId, onSettled = { action ->
                    applyRewardsSnapshot(action.snapshot)
                    resumeAfterFullscreenAdIfNeeded()
                    toast(t("reward_doubled_notice"))
                })
            }
            .show()
    }

    private fun requestRewardedClaimAllEpisodeRewards() {
        requestRewardedAction("rv_episode_rewards_claim", onSettled = { authorization ->
            val adEventId = authorization.adEventId
            if (adEventId.isNullOrBlank()) {
                resumeAfterFullscreenAdIfNeeded()
                toast(t("claim_authorization_missing"))
                return@requestRewardedAction
            }
            rewardsApi.claimAllEpisodeRewards(anonymousInstallationId(), adEventId, { action -> runOnUiThread {
                applyRewardsSnapshot(action.snapshot)
                resumeAfterFullscreenAdIfNeeded()
                toast(if (action.idempotent) t("no_pending_gems") else t("gems_claimed", action.gemsAdded))
            } }, { message -> runOnUiThread {
                resumeAfterFullscreenAdIfNeeded()
                toast(t("claim_failed", message))
            } })
        })
    }

    private fun createCheckinCard(): View {
        val snapshot = rewardsSnapshot
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedDrawable(0xFFFFFFFF.toInt(), 18)
            addView(TextView(this@PartnerDramaActivity).apply { text = t("checkin_rewards"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = snapshot?.let {
                    if (it.checkinSignedToday) t("checkin_done_message", it.checkinStreak)
                    else t("today_day_reward", it.checkinTodayIndex, it.checkinNextRewardCoins)
                } ?: t("checkin_syncing")
                textSize = 13f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(6), 0, dp(13))
            })
            addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
            val row = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this@PartnerDramaActivity).apply {
                text = if (snapshot?.checkinSignedToday == true) t("checked_in") else t("watch_ad_checkin")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT_PRIMARY)
                background = roundedDrawable(if (snapshot?.checkinSignedToday == true) COLOR_SURFACE else COLOR_ACCENT, 14)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { if (snapshot?.checkinSignedToday != true) completeDailyCheckin() }
                isClickable = snapshot != null && snapshot.checkinSignedToday != true
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(row)
        }
    }

    private fun createWelcomeRewardCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = gradientDrawable(intArrayOf(0xFFFFA5B8.toInt(), 0xFFFFD8A7.toInt()), 20)
        addView(TextView(this@PartnerDramaActivity).apply { text = t("newcomer_reward"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = t("welcome_reward_message", snapshot.welcome.gems)
            textSize = 13f; setTextColor(0xECFFFFFF.toInt()); setPadding(0, dp(6), 0, dp(13))
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = t("watch_ad_claim")
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(0xFF7A2440.toInt())
            background = roundedDrawable(0xFFFFE7A0.toInt(), 14); setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
            setOnClickListener { claimWelcomeBonus() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    }

    private fun claimWelcomeBonus() {
        requestRewardedAction("rv_welcome_bonus", onSettled = { action ->
            applyRewardsSnapshot(action.snapshot)
            resumeAfterFullscreenAdIfNeeded()
            toast(if (action.idempotent) t("welcome_claimed") else t("welcome_received", action.gemsAdded))
        })
    }

    private fun createRewardsWalletSummary(): View {
        val wallet = rewardsSnapshot?.wallet
        if (wallet == null) return createInlineNotice("正在同步奖励账本，连接成功后将展示宝石和钱包余额。")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(16))
            background = gradientDrawable(intArrayOf(0xFFFFF0E5.toInt(), 0xFFFFFFFF.toInt()), 22)
            addView(TextView(this@PartnerDramaActivity).apply { text = t("wallet"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            val balances = LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(t("gems") to wallet.coins.toString(), t("cash_balance") to formatCurrencyAmount(wallet, wallet.cashWallet, wallet.cashWalletUsdReference, includeUsdReference = true)).forEachIndexed { index, item ->
                balances.addView(LinearLayout(this@PartnerDramaActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(10), dp(12)); background = roundedDrawable(0xCCFFFFFF.toInt(), 14)
                    addView(TextView(this@PartnerDramaActivity).apply { text = item.first; textSize = 12f; setTextColor(COLOR_TEXT_MUTED) })
                    addView(TextView(this@PartnerDramaActivity).apply { text = item.second; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(0, dp(4), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(9) })
            }
            addView(balances)
            val actions = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
            actions.addView(TextView(this@PartnerDramaActivity).apply {
                text = t("exchange_gems"); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = roundedDrawable(COLOR_ACCENT, 14); setOnClickListener { showGemsExchangeDialog(wallet) }; isClickable = true
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            actions.addView(TextView(this@PartnerDramaActivity).apply {
                text = t("withdraw"); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
                background = roundedDrawable(0xCCFFFFFF.toInt(), 14); setOnClickListener { showWithdrawalPage(wallet) }; isClickable = true
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(9) })
            addView(actions)
        }
    }

    private fun showWithdrawalDetailsDialog(wallet: RewardsApiClient.Wallet) {
        val release = wallet.release
        val details = "可提现余额：${formatCurrencyAmount(wallet, wallet.availableBalance, wallet.availableBalanceUsdReference, includeUsdReference = true)}\n\n${rewardsSnapshot?.withdrawal?.reason.orEmpty()}\n\n预计明日可转入：${formatCurrencyAmount(wallet, release.expectedTomorrowTransfer, release.expectedTomorrowTransferUsdReference, includeUsdReference = true)}\n本次广告加速预计额外转入：${formatCurrencyAmount(wallet, release.expectedBoostTransfer, release.expectedBoostTransferUsdReference, includeUsdReference = true)}"
        AlertDialog.Builder(this).setTitle(t("withdrawal_details")).setMessage(details)
            .setNegativeButton(t("close"), null)
            .setPositiveButton("广告加速") { _, _ -> showWalletReleaseBoostDialog() }.show()
    }

    private fun showWalletReleaseBoostDialog() {
        val release = rewardsSnapshot?.wallet?.release ?: return
        AlertDialog.Builder(this)
            .setTitle(t("ad_boost"))
            .setMessage(t("ad_boost_message", formatCurrencyAmount(rewardsSnapshot?.wallet ?: return, release.expectedBoostTransfer, release.expectedBoostTransferUsdReference, includeUsdReference = true)))
            .setNegativeButton(t("not_now"), null)
            .setPositiveButton(t("watch_ad_claim")) { _, _ -> requestWalletReleaseBoost() }
            .show()
    }

    private fun requestWalletReleaseBoost() {
        requestRewardedAction("rv_release_boost", onSettled = { action ->
            applyRewardsSnapshot(action.snapshot)
            resumeAfterFullscreenAdIfNeeded()
            val wallet = action.snapshot.wallet
            val transferred = action.releasedMicros / 1_000_000.0
            val updatedRelease = wallet.release
            val transferredText = formatCurrencyAmount(wallet, String.format(Locale.US, "%.6f", transferred), null, includeUsdReference = false)
            val tomorrowText = formatCurrencyAmount(wallet, updatedRelease.expectedTomorrowTransfer, updatedRelease.expectedTomorrowTransferUsdReference, includeUsdReference = true)
            val nextMessage = if (transferred > 0.0) {
                "已加快转入进度，本次额外转入 $transferredText 到可提现余额。预计明日可转入 $tomorrowText。"
            } else {
                "已完成本次广告加速。预计明日可转入 $tomorrowText。"
            }
            AlertDialog.Builder(this)
                .setTitle("转入进度已更新")
                .setMessage(nextMessage)
                .setNegativeButton("稍后再说", null)
                .setPositiveButton("继续加速") { _, _ -> showWalletReleaseBoostDialog() }
                .show()
        })
    }

    private fun showGemsExchangeDialog(wallet: RewardsApiClient.Wallet) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = t("minimum_gems", wallet.minimumExchangeGems)
            setTextColor(COLOR_TEXT_PRIMARY); setHintTextColor(COLOR_TEXT_MUTED)
        }
        AlertDialog.Builder(this).setTitle(t("exchange_gems")).setMessage(t("exchange_gems_message"))
            .setView(input).setNegativeButton(t("cancel"), null).setPositiveButton(t("done")) { _, _ ->
                val gems = input.text.toString().trim().toLongOrNull()
                if (gems == null) { toast("请输入有效的宝石数量"); return@setPositiveButton }
                rewardsApi.exchangeGems(anonymousInstallationId(), gems, { snapshot -> runOnUiThread { applyRewardsSnapshot(snapshot); toast("兑换已记入钱包余额") } }, { message -> runOnUiThread { toast(message) } })
            }.show()
    }

    private fun showWalletRulesDialog(wallet: RewardsApiClient.Wallet) {
        AlertDialog.Builder(this).setTitle(t("cashout_rules")).setMessage(
            "宝石可兑换为钱包余额，钱包余额会按当前规则陆续转入可提现余额。\n\n" +
                "提现资格：${rewardsSnapshot?.walletEligibilityCopy.orEmpty()}\n\n" +
                "金额、领取和资格均以服务端账本为准。"
        ).setPositiveButton(t("done"), null).show()
    }

    private fun createChestCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = roundedDrawable(COLOR_CARD, 18)
        addView(TextView(this@PartnerDramaActivity).apply { text = "随机宝箱"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = when {
                snapshot.chest.availableCount > 0 -> "当前有 ${snapshot.chest.availableCount} 个可开启宝箱（每日 ${snapshot.chest.dailyAvailableCount} 个、追剧 ${snapshot.chest.watchAvailableCount} 个）。每次看完激励广告随机获得 ${snapshot.chest.minCoins}–${snapshot.chest.maxCoins} 宝石。"
                snapshot.chest.episodesToNextChest > 0 -> "暂时没有可开启宝箱。每累计 ${snapshot.chest.episodesPerChest} 集有效观看可获得 1 个；再看 ${snapshot.chest.episodesToNextChest} 集即可获得下一只。"
                else -> "暂时没有可开启宝箱；每日会自动获得宝箱，每累计 ${snapshot.chest.episodesPerChest} 集有效观看也会获得 1 个。"
            }
            textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(6), 0, dp(12))
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = if (snapshot.chest.availableCount > 0) "观看广告开启 1 个" else "继续追剧获得宝箱"
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
            background = roundedDrawable(if (snapshot.chest.availableCount > 0) COLOR_ACCENT else COLOR_SURFACE, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
            setOnClickListener {
                if (snapshot.chest.availableCount > 0) requestRandomChestOpen() else {
                    activeTab = MainTab.HOME
                    renderActiveTab()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    }

    private fun requestRandomChestOpen(onFinished: () -> Unit = {}) {
        requestRewardedAction(
            placementId = "rv_random_chest_open",
            onSettled = { action ->
                applyRewardsSnapshot(action.snapshot)
                resumeAfterFullscreenAdIfNeeded()
                toast("随机宝箱开启：+${action.gemsAdded} 宝石")
                onFinished()
            },
            onSettlementUnavailable = { message ->
                resumeAfterFullscreenAdIfNeeded()
                val noChest = message.contains("没有可开启") || message.contains("没有可用")
                toast(if (noChest) "广告已完整观看，但当前没有可开启的宝箱" else "宝箱结算失败：$message")
                onFinished()
            },
        )
    }

    private fun createAchievementSummary(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedDrawable(COLOR_CARD, 18)
        val crowns = snapshot.crownProgress.sortedBy { it.levelRank }
        val current = crowns.filter { it.earned }.maxByOrNull { it.levelRank }
        val next = crowns.firstOrNull { !it.earned }
        if (next == null) {
            addView(TextView(this@PartnerDramaActivity).apply { text = "皇冠成长 · 已达成"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFDB6A.toInt()) })
            addView(TextView(this@PartnerDramaActivity).apply { text = "皇冠头像已解锁，追剧宝石奖励已提升至最高倍率。"; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(7), 0, 0) })
        } else {
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "皇冠成长 · ${current?.title ?: "新手"}"
                textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY)
            })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "当前 ${String.format(Locale.US, "%.2f", current?.episodeRewardMultiplier ?: 1f)} 倍 · 下一档 ${next.title} 为 ${String.format(Locale.US, "%.2f", next.episodeRewardMultiplier)} 倍，皇冠最高可达 2.50 倍"
                textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(6), 0, dp(10))
            })
            addView(ProgressBar(this@PartnerDramaActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = (next.progress * 100).toInt().coerceIn(0, 100)
                progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
                backgroundTintList = ColorStateList.valueOf(0x33333333)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "继续追剧和签到积累成长；越高档位，后续每集宝石倍率提升越明显"
                textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(10), 0, dp(9))
            })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "看 ${next.adsPerEpisode} 段广告加快升级"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY)
                background = gradientDrawable(intArrayOf(0xFFFF5D69.toInt(), 0xFFC42048.toInt()), 13); setPadding(dp(12), dp(9), dp(12), dp(9)); isClickable = true
                setOnClickListener { showCrownRewardedUpgradeDialog(next) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        }
    }

    private fun showCrownRewardedUpgradeDialog(crown: RewardsApiClient.CrownProgress) {
        AlertDialog.Builder(this)
            .setTitle("加快皇冠成长")
            .setMessage("完整观看 ${crown.adsPerEpisode} 段广告后，将为你的皇冠成长加速。完成后可查看最新等级和宝石倍率。")
            .setNegativeButton(t("not_now"), null)
            .setPositiveButton("开始看广告") { _, _ -> requestCrownRewardedUpgrade(crown.adsPerEpisode, crown.title) }
            .show()
    }

    private fun requestCrownRewardedUpgrade(remaining: Int, title: String) {
        if (remaining <= 0) { refreshRewardsSnapshot(); return }
        requestRewardedAction("rv_crown_upgrade", onSettled = { action ->
            applyRewardsSnapshot(action.snapshot)
            resumeAfterFullscreenAdIfNeeded()
            if (remaining > 1) {
                AlertDialog.Builder(this)
                    .setTitle("成长加速中")
                    .setMessage("已完成 1/${remaining} 段广告，再看 ${remaining - 1} 段即可完成本次加速。")
                    .setNegativeButton("稍后继续", null)
                    .setPositiveButton("继续观看") { _, _ -> requestCrownRewardedUpgrade(remaining - 1, title) }
                    .show()
            } else {
                toast("已完成皇冠广告升级，${title}进度已刷新")
            }
        })
    }

    /** 当前仅为前端展示演示；活动配置、进度验证与点数账本将在后续运营后台阶段接入。 */
    private fun createActivityRewardDemo(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = gradientDrawable(intArrayOf(0xFFFFF1E6.toInt(), 0xFFFFFFFF.toInt()), 18)
        addView(TextView(this@PartnerDramaActivity).apply {
            text = "新剧追更挑战"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = "活动期间完成指定短剧 5 集有效观看，可获得 120 活动点数"
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(6), 0, dp(12))
        })
        val row = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this@PartnerDramaActivity).apply {
            text = "进度 0/5 · 即将开放"
            textSize = 13f
            setTextColor(COLOR_TEXT_PRIMARY)
            background = roundedDrawable(COLOR_SURFACE, 12)
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(createPill("+120 点数", COLOR_PURPLE))
        addView(row)
    }

    private fun refreshRewardsSnapshot() {
        if (!rewardsApi.isConfigured || rewardsLoading) return
        rewardsLoading = true
        val endpoint = rewardsBaseUrl()
        logStatus("正在同步奖励服务：$endpoint")
        rewardsApi.bootstrap(anonymousInstallationId(), rewardsMarketCode(), { snapshot ->
            runOnUiThread {
                rewardsLoading = false
                applyRewardsSnapshot(snapshot)
            }
        }, { message ->
            runOnUiThread {
                rewardsLoading = false
                logStatus("奖励服务同步失败（$endpoint）：$message")
                if (catalogItems.isNotEmpty()) renderActiveTab()
                showStartupCheckinWhenReady()
            }
        })
    }

    private fun applyRewardsSnapshot(snapshot: RewardsApiClient.Snapshot) {
        val previousGems = rewardsSnapshot?.wallet?.coins ?: lastConfirmedGemBalance
        rewardsSnapshot = snapshot
        unlockedEpisodeKeys.clear()
        unlockedEpisodeKeys.addAll(snapshot.unlockedEpisodeTargets)
        lastConfirmedGemBalance = snapshot.wallet.coins
        refreshFloatingRewardWheels()
        if (!playerShown) {
            if (withdrawalPageShown) showWithdrawalPage(snapshot.wallet) else if (catalogItems.isNotEmpty()) renderActiveTab()
        }
        updateEpisodeStatus()
        if (startupContentInitializationStarted) showStartupCheckinWhenReady()
        // 只有服务端确认余额增加时播放动效；首次同步和余额不变不会触发。
        if (previousGems != null && snapshot.wallet.coins > previousGems) {
            root.post { animateClaimedGems(previousGems, snapshot.wallet.coins) }
        }
    }

    private fun showStartupCheckinWhenReady() {
        if (!startupCheckinPromptRequested || startupCheckinPromptShown || isFinishing || isDestroyed) return
        val snapshot = rewardsSnapshot
        if (snapshot == null && rewardsLoading) return
        if (snapshot?.welcome?.available == true && !startupWelcomePromptShown) {
            startupWelcomePromptShown = true
            startupCheckinPromptRequested = false
            showWelcomeRewardDialog(snapshot) {
                startupCheckinPromptShown = true
                showCheckinRewardDialog(rewardsSnapshot ?: snapshot)
            }
            return
        }
        startupCheckinPromptRequested = false
        startupCheckinPromptShown = true
        showCheckinRewardDialog(snapshot)
    }

    private fun showWelcomeRewardDialog(snapshot: RewardsApiClient.Snapshot, onFinished: () -> Unit) {
        val dialog = AlertDialog.Builder(this).create()
        val page = FrameLayout(this).apply { background = gradientDrawable(intArrayOf(0xFFFFE2D1.toInt(), 0xFFF5EDFF.toInt(), 0xFFDDF7F4.toInt()), 0) }
        addWelcomeConfetti(page)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(30), dp(42), dp(30), dp(28)) }
        val gem = ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        content.addView(gem, LinearLayout.LayoutParams(dp(154), dp(154)))
        content.addView(TextView(this@PartnerDramaActivity).apply { text = t("welcome_title"); textSize = 30f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY); setPadding(0, dp(6), 0, 0) })
        content.addView(TextView(this@PartnerDramaActivity).apply { text = t("welcome_subtitle"); textSize = 15f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), dp(0), dp(20)) })
        content.addView(TextView(this@PartnerDramaActivity).apply { text = "+${formatGems(snapshot.welcome.gems)} ${t("gems")}"; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_CORAL); background = roundedDrawable(0xB8FFFFFF.toInt(), 20); setPadding(dp(22), dp(14), dp(22), dp(14)) })
        content.addView(TextView(this@PartnerDramaActivity).apply { text = t("welcome_reward_message", snapshot.welcome.gems); textSize = 13f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(16), 0, dp(16)) })
        content.addView(TextView(this@PartnerDramaActivity).apply {
            text = t("watch_ad_claim"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(COLOR_CORAL, COLOR_SUNSET), 18); setPadding(dp(18), dp(15), dp(18), dp(15)); isClickable = true
            setOnClickListener { dialog.dismiss(); claimWelcomeBonus(); onFinished() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        content.addView(TextView(this@PartnerDramaActivity).apply { text = t("later"); textSize = 14f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(18), 0, 0); isClickable = true; setOnClickListener { dialog.dismiss(); onFinished() } })
        page.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setView(page); dialog.setCanceledOnTouchOutside(false); dialog.setOnCancelListener { onFinished() }; dialog.show()
        dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        gem.animate().scaleX(1.14f).scaleY(1.14f).setDuration(320L).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction { gem.animate().scaleX(1f).scaleY(1f).setDuration(420L).start() }.start()
    }

    private fun addWelcomeConfetti(host: FrameLayout) {
        val colors = intArrayOf(COLOR_CORAL, COLOR_SUNSET, COLOR_AQUA, COLOR_PURPLE, 0xFFFFDB6A.toInt())
        repeat(16) { index ->
            val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors[index % colors.size]) }; alpha = 0f }
            host.addView(dot, FrameLayout.LayoutParams(dp(7 + index % 4 * 2), dp(7 + index % 4 * 2)).apply { leftMargin = dp(18 + (index * 41) % 300); topMargin = dp(18 + (index * 29) % 210) })
            dot.postDelayed({
                AnimatorSet().apply { playTogether(ObjectAnimator.ofFloat(dot, View.ALPHA, 0f, .95f, 0f), ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(40 + index * 4).toFloat()), ObjectAnimator.ofFloat(dot, View.ROTATION, 0f, 160f)); duration = 1050L + index * 35L; start() }
            }, index * 70L)
        }
    }

    /** 明亮奖励弹窗：七日格子和金额均以服务端快照为准；服务离线时仅展示同步状态，不发放奖励。 */
    private fun showCheckinRewardDialog(snapshot: RewardsApiClient.Snapshot?) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            background = gradientDrawable(intArrayOf(0xFFFFF0E6.toInt(), COLOR_CARD), 26)
        }
        content.addView(TextView(this).apply {
            text = t("checkin_rewards")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("checkin_done_message", snapshot.checkinStreak)
                else -> t("today_day_reward", snapshot.checkinTodayIndex, snapshot.checkinNextRewardCoins)
            }
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(7), 0, dp(16))
        })
        content.addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("checkin_open_chest_hint")
                else -> t("checkin_ad_reward", snapshot.checkinNextRewardCoins)
            }
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (snapshot == null) COLOR_TEXT_MUTED else 0xFFFFDB6A.toInt())
            setPadding(0, dp(15), 0, dp(12))
        })
        val primary = TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("open_chest")
                else -> t("watch_ad_checkin")
            }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
            background = gradientDrawable(intArrayOf(COLOR_ACCENT, COLOR_PURPLE), 18)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener {
                dialog.dismiss()
                when {
                    snapshot == null -> refreshRewardsSnapshot()
                    !snapshot.checkinSignedToday -> completeDailyCheckin()
                    else -> requestRandomChestOpen()
                }
            }
            isClickable = true
        }
        content.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(TextView(this).apply {
            text = t("later")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(12), dp(14), dp(12), dp(4))
            setOnClickListener { dialog.dismiss() }
            isClickable = true
        })
        dialog.setView(content)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 奖励数据只提供“连续天数”和“今日是否已签”；具体宝石列表来自服务端动态规则。超过七天时保持最高奖励档。
     */
    private fun createCheckinTierStrip(snapshot: RewardsApiClient.Snapshot?): View {
        val tiers = snapshot?.checkinRewardTiers?.take(7).orEmpty().ifEmpty { listOf(0, 0, 0, 0, 0, 0, 0) }
        // checkinStreak 是已经完成的天数：首日未签到时为 0，必须突出“今日第 1 天”而非打勾。
        val completedDays = snapshot?.checkinStreak ?: 0
        val signedToday = snapshot?.checkinSignedToday == true
        val maxTierIndex = tiers.lastIndex
        val todayIndex = ((snapshot?.checkinTodayIndex ?: 1).coerceAtLeast(1) - 1).coerceAtMost(maxTierIndex)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tiers.forEachIndexed { index, coins ->
                val isToday = snapshot != null && index == todayIndex
                val isDone = snapshot != null && index < completedDays.coerceAtMost(tiers.size)
                addView(LinearLayout(this@PartnerDramaActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(3), dp(8), dp(3), dp(8))
                    background = roundedDrawable(
                        when {
                            isToday -> COLOR_ACCENT
                            isDone -> COLOR_PURPLE
                            else -> COLOR_SURFACE
                        },
                        12,
                    )
                    addView(TextView(this@PartnerDramaActivity).apply {
                        text = if (isToday && !signedToday) t("today") else t("day_number", index + 1)
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) Color.WHITE else COLOR_TEXT_MUTED)
                    })
                    addView(TextView(this@PartnerDramaActivity).apply {
                        text = if (isDone) "✓" else if (isToday) t("claim_short") else "○"
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) 0xFFFFDB6A.toInt() else COLOR_TEXT_MUTED)
                        setPadding(0, dp(3), 0, dp(2))
                    })
                    addView(TextView(this@PartnerDramaActivity).apply {
                        text = if (snapshot == null) t("syncing") else "+$coins"
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) Color.WHITE else COLOR_TEXT_MUTED)
                    })
                }, LinearLayout.LayoutParams(0, dp(84), 1f).apply {
                    if (index > 0) marginStart = dp(4)
                })
            }
        }
    }

    private fun completeDailyCheckin() {
        requestRewardedAction("rv_checkin_complete", onSettled = { action ->
            applyRewardsSnapshot(action.snapshot)
            resumeAfterFullscreenAdIfNeeded()
            if (action.idempotent) {
                toast("今天已经签到")
            } else {
                toast("签到成功，获得 ${action.gemsAdded} 宝石")
                showCheckinRewardDialog(action.snapshot)
            }
        })
    }

    /** 全屏广告展示时暂停原视频；TopOn 回调已保证在广告关闭后才会进入这里的结果分支。 */
    private fun requestRewardedAction(
        placementId: String,
        targetId: String? = null,
        episodeIndex: Int? = null,
        onSettled: ((RewardsApiClient.RewardAction) -> Unit)? = null,
        onSettlementUnavailable: ((String) -> Unit)? = null,
    ) {
        if (fullscreenAdInProgress) { toast("广告正在展示，请稍候"); return }
        if (consentStore.read() != AdConsent.GRANTED) { toast("请先在广告与隐私中启用广告"); return }
        if (!rewardsApi.isConfigured) { toast("独立积分服务尚未配置，广告奖励无法结算"); return }
        fullscreenAdInProgress = true
        pauseForFullscreenAd()
        val wasPlayingBeforeRewarded = resumeAfterFullscreenAd
        topOnAds.showRewarded(this) { result ->
            when (result) {
                TopOnAdsManager.RewardedResult.RewardEarned -> {
                    if (wasPlayingBeforeRewarded) { resumeAfterFullscreenAd = true; resumeAfterFullscreenAdIfNeeded() }
                    rewardsApi.completeRewarded(
                        userId = anonymousInstallationId(), placementId = placementId, targetId = targetId,
                        episodeIndex = episodeIndex, proof = testRewardProof(),
                        onSuccess = { action -> runOnUiThread {
                            fullscreenAdInProgress = false; applyRewardsSnapshot(action.snapshot)
                            if (onSettled != null) onSettled.invoke(action) else { resumeAfterFullscreenAdIfNeeded(); toast("已结算 ${action.gemsAdded} 宝石") }
                        } },
                        onFailure = { message -> runOnUiThread {
                            fullscreenAdInProgress = false
                            if (onSettlementUnavailable != null) onSettlementUnavailable.invoke(message) else {
                                episodeTransitionInProgress = false; resumeAfterFullscreenAdIfNeeded()
                                toast("广告已完整观看，奖励状态暂未同步；请稍后在奖励中心查看。"); logStatus("广告奖励同步失败：$message")
                            }
                        } },
                    )
                }
                TopOnAdsManager.RewardedResult.Cancelled -> showRewardedFallbackInterstitial(
                    wasPlayingBeforeRewarded, "广告未完整观看，未获得奖励或解锁权益"
                )
                is TopOnAdsManager.RewardedResult.Unavailable -> showRewardedUnavailableNotice(
                    wasPlayingBeforeRewarded, t("ad_loading_failed") + " ${result.reason}"
                )
                is TopOnAdsManager.RewardedResult.Failed -> showRewardedUnavailableNotice(
                    wasPlayingBeforeRewarded, t("ad_loading_failed") + " (${result.code})"
                )
            }
        }
    }

    /** 无激励广告资源时直接在内容层展示说明，不再触发会遮挡说明的插屏回退。 */
    private fun showRewardedUnavailableNotice(shouldResume: Boolean, message: String) {
        fullscreenAdInProgress = false
        episodeTransitionInProgress = false
        resumeAfterFullscreenAd = shouldResume
        resumeAfterFullscreenAdIfNeeded()
        AlertDialog.Builder(this)
            .setTitle("Reward video unavailable")
            .setMessage("$message\n\nPlease try again later. This does not affect other tasks or coin earnings.")
            .setPositiveButton("Got it", null)
            .show()
    }

    /** 激励中途退出或加载失败时，只在后台开关允许时回退一次插屏；不会授予原激励权益。 */
    private fun showRewardedFallbackInterstitial(shouldResume: Boolean, message: String) {
        fullscreenAdInProgress = false
        episodeTransitionInProgress = false
        if (rewardsSnapshot?.interstitial?.rewardedFallbackEnabled != true) {
            resumeAfterFullscreenAd = shouldResume; resumeAfterFullscreenAdIfNeeded(); toast(message); return
        }
        showPlaybackActionInterstitial(
            placementId = "int_rewarded_fallback", episodeId = selectedEpisode()?.id,
            resumePlaybackAfter = shouldResume,
            onShown = { toast(message) },
            onUnavailable = { reason -> logStatus("激励回退插屏未展示：$reason"); toast(message) },
        )
    }

    private fun pauseForFullscreenAd() {
        resumeAfterFullscreenAd = exoPlayer?.isPlaying == true
        // 第三方广告关闭按钮由广告网络渲染，不能由宿主移动或覆盖。展示前退出沉浸模式，
        // 并让广告 SDK 以有系统栏的安全窗口展示，避免弧形屏顶部边缘难以点击关闭按钮。
        restoreImmersiveAfterFullscreenAd = playerShown && playerInImmersiveMode
        if (playerInImmersiveMode) exitImmersivePlayerMode()
        if (::navigationBackCallback.isInitialized) navigationBackCallback.isEnabled = false
        exoPlayer?.pause()
        playerIsPaused = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun resumeAfterFullscreenAdIfNeeded() {
        if (::navigationBackCallback.isInitialized) navigationBackCallback.isEnabled = true
        if (resumeAfterFullscreenAd && !isFinishing && !isDestroyed) {
            ignoreVideoTapsUntilMillis = System.currentTimeMillis() + 750L
            exoPlayer?.play()
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (restoreImmersiveAfterFullscreenAd && !isFinishing && !isDestroyed && playerShown) {
            enterImmersivePlayerMode(announce = false)
        }
        restoreImmersiveAfterFullscreenAd = false
        resumeAfterFullscreenAd = false
    }

    /**
     * 所有播放行为插屏统一入口。剧中、拖动、倍速及激励回退按每集上限互斥；
     * 横向切剧使用独立频控，不占用当前分集的插屏额度。
     */
    private fun showPlaybackActionInterstitial(
        placementId: String,
        episodeId: String? = selectedEpisode()?.id,
        countTowardEpisodeLimit: Boolean = true,
        resumePlaybackAfter: Boolean? = null,
        onShown: () -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        val policy = rewardsSnapshot?.interstitial
        val episodeKey = episodeId?.let { "${selectedDrama?.id}:$it" }
        if (countTowardEpisodeLimit && policy != null && policy.episodeInterstitialLimit > 0 && episodeKey != null && (interstitialCountByEpisodeKey[episodeKey] ?: 0) >= policy.episodeInterstitialLimit) {
            onUnavailable("本集插屏次数已达上限"); return
        }
        if (fullscreenAdInProgress) { onUnavailable("广告正在展示"); return }
        if (consentStore.read() != AdConsent.GRANTED) { onUnavailable("请先在广告与隐私中启用广告"); return }
        val shouldResume = resumePlaybackAfter ?: (exoPlayer?.isPlaying == true)
        fullscreenAdInProgress = true
        pauseForFullscreenAd()
        resumeAfterFullscreenAd = shouldResume
        topOnAds.showInterstitialForPlaybackAction(this) { result -> runOnUiThread {
            fullscreenAdInProgress = false
            when (result) {
                TopOnAdsManager.FullscreenResult.Shown -> {
                    resumeAfterFullscreenAdIfNeeded()
                    val settled = {
                        if (countTowardEpisodeLimit && episodeKey != null) interstitialCountByEpisodeKey[episodeKey] = (interstitialCountByEpisodeKey[episodeKey] ?: 0) + 1
                        onShown()
                    }
                    if (rewardsApi.isConfigured) {
                        rewardsApi.recordInterstitial(anonymousInstallationId(), placementId, episodeId,
                            onSuccess = { snapshot -> runOnUiThread { applyRewardsSnapshot(snapshot); settled() } },
                            onFailure = { message -> runOnUiThread { logStatus("插屏展示记录未同步：$message"); settled() } },
                        )
                    } else settled()
                }
                is TopOnAdsManager.FullscreenResult.Skipped -> { resumeAfterFullscreenAdIfNeeded(); onUnavailable(result.reason) }
                is TopOnAdsManager.FullscreenResult.Failed -> { resumeAfterFullscreenAdIfNeeded(); onUnavailable("加载失败（${result.code}）") }
            }
        } }
    }

    private fun showGemIncomeDetails() {
        val snapshot = rewardsSnapshot
        if (snapshot == null) { toast(t("wallet_syncing")); return }
        val wallet = snapshot.wallet
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(12)); background = roundedDrawable(COLOR_CARD, 24) }
        detail.addView(TextView(this@PartnerDramaActivity).apply { text = t("gem_income_details"); textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        detail.addView(TextView(this@PartnerDramaActivity).apply { text = t("gem_income_summary", formatGems(wallet.coins), formatGems(wallet.totalGemsEarned)); textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(5), 0, dp(12)) })
        val entries = snapshot.ledger.filter { it.gemsDelta > 0 }
        if (entries.isEmpty()) detail.addView(TextView(this@PartnerDramaActivity).apply { text = t("ledger_empty"); textSize = 14f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), dp(0), dp(14)) })
        else entries.forEach { entry ->
            detail.addView(LinearLayout(this@PartnerDramaActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
                addView(ImageView(this@PartnerDramaActivity).apply { setImageResource(R.drawable.reward_gem_crystal); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(this@PartnerDramaActivity).apply { text = ledgerTitle(entry.type); textSize = 14f; setTextColor(COLOR_TEXT_PRIMARY); setPadding(dp(8), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@PartnerDramaActivity).apply { text = "+${formatGems(entry.gemsDelta)}"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_CORAL) })
            })
        }
        AlertDialog.Builder(this).setView(detail).setPositiveButton(t("done"), null).show()
    }

    private fun ledgerTitle(type: String): String = when (type) {
        "WELCOME_BONUS" -> t("ledger_welcome"); "SIGN_IN" -> t("ledger_checkin"); "EPISODE_REWARD_CLAIM" -> t("ledger_episode"); "ACHIEVEMENT" -> t("ledger_crown"); else -> t("ledger_other")
    }

    private fun showRewardLedgerHint() {
        val snapshot = rewardsSnapshot
        val message = snapshot?.let { "当前规则版本：${it.ruleVersion}。最近权益由服务端账本保存，可通过管理接口查看。" } ?: "奖励服务尚未连接。"
        AlertDialog.Builder(this).setTitle("奖励流水").setMessage(message).setPositiveButton(t("done"), null).show()
    }

    private fun createDramaCard(drama: LocalDrama): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(COLOR_CARD, 16)
        clipToOutline = true
        setPadding(dp(10), dp(10), dp(10), dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { showPlayer(drama, 0) }

        addView(ImageView(this@PartnerDramaActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = posterFallbackDrawable()
            contentDescription = drama.title
            load(drama.coverImage) {
                crossfade(true)
                placeholder(posterFallbackDrawable())
                error(posterFallbackDrawable())
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(188)))
        addView(TextView(this@PartnerDramaActivity).apply {
            text = drama.title.ifBlank { "精彩短剧" }
            textSize = 15f
            maxLines = 2
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, dp(10), 0, dp(5))
        })
        val info = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        // PSSDK 的 language 为内容元数据（例如 zh_hant），并非可直接展示的本地化文案。
        // 在建立完整的语言映射与剧集配音/字幕能力前，不向用户展示该原始代码。
        info.addView(createPill(t("episodes_count", drama.total.coerceAtLeast(1)), COLOR_ACCENT))
        addView(info)
    }

    private fun createPill(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = roundedDrawable(color, 12)
    }

    private fun showPlayer(drama: LocalDrama, startIndex: Int, resumePositionSeconds: Int = 0) {
        if (drama.episodes.isEmpty()) {
            renderLoading(t("loading_episode"))
            localDramaApi.fetchDrama(
                baseUrl = localContentBaseUrl(),
                dramaId = drama.id,
                region = CONTENT_REGION,
                userId = anonymousInstallationId(),
                onSuccess = { detail -> runOnUiThread { showPlayer(detail, startIndex, resumePositionSeconds) } },
                onFailure = { message -> runOnUiThread { renderFailure(t("episode_load_failed", message)) } },
            )
            return
        }

        releasePlayer()
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
        playerShown = true
        root.removeAllViews()

        val player = ExoPlayer.Builder(this).build().also { exoPlayer = it }
        playerView = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            this.player = player
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerIsPaused = !isPlaying && player.playbackState == Player.STATE_READY
                updatePauseOverlayVisibility(playerIsPaused && !fullscreenAdInProgress && !pauseNativeAdInProgress)
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
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
                    404 -> "视频文件未找到。请核对 Windows media 目录、片单路径与文件名。"
                    403 -> "播放链接已失效或服务已重启。请重新进入该集。"
                    402 -> "该分集需要先完成解锁。"
                    500, 503 -> "Windows 视频服务暂不可用。请检查服务终端日志与本地媒体配置。"
                    else -> "视频播放失败：$diagnostic"
                }
                toast("$userMessage 可在“更多”中选择重新加载本集。")
            }
        })
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createPlayerChrome(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createFloatingRewardWheel(compact = true), FrameLayout.LayoutParams(dp(96), dp(128), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(10)
            topMargin = dp(42)
        })
        selectEpisode(currentEpisodeIndex)
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.post(progressTicker)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun createPlayerChrome(): View {
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
        chrome.addView(createPlayerTopBar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.TOP))

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

        playerControlsRevealButton = transparentPlayerTextButton(t("show_controls"), t("show_controls")) { setPlayerControlsVisible(true) }.also { it.visibility = View.GONE }
        chrome.addView(playerControlsRevealButton, FrameLayout.LayoutParams(dp(74), dp(38), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(14)
            marginEnd = dp(14)
        })
        updateEpisodeStatus()
        updatePlayerProgress(0, 0)
        return chrome
    }

    private fun createPlayerTopBar(): View {
        val shell = FrameLayout(this)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
            addView(ImageView(this@PartnerDramaActivity).apply {
                setImageResource(R.drawable.ic_arrow_back); imageTintList = ColorStateList.valueOf(Color.WHITE); scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = roundedDrawable(0x5A0D0B12, 15); contentDescription = t("back"); isClickable = true; setOnClickListener { showCatalog() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(View(this@PartnerDramaActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(createPlayerAssetStrip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }
        shell.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.TOP).apply { topMargin = dp(14) })
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val params = row.layoutParams as FrameLayout.LayoutParams
            params.topMargin = maxOf(safeTop, dp(12)) + dp(8)
            row.layoutParams = params
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        return shell
    }

    private fun createPlayerGestureLayer(): View {
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

    private fun triggerSwipeNextEpisode() {
        val now = System.currentTimeMillis(); if (now - lastSwipeNextAtMillis < 700L) return
        lastSwipeNextAtMillis = now; startNextEpisodeAutomatically(currentEpisodeIndex, userInitiated = true)
    }

    private fun triggerSwipePreviousEpisode() {
        val now = System.currentTimeMillis(); if (now - lastSwipeNextAtMillis < 700L) return
        lastSwipeNextAtMillis = now
        if (currentEpisodeIndex <= 0) switchDramaBySwipe(forward = false) else selectEpisode(currentEpisodeIndex - 1)
    }

    /** 左右切剧：每累计指定手势次数展示一次独立插屏，广告失败不阻断切剧。 */
    private fun switchDramaBySwipe(forward: Boolean = true) {
        if (catalogItems.size < 2 || fullscreenAdInProgress) return
        val current = selectedDrama ?: return
        val currentIndex = catalogItems.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: return
        val nextDrama = catalogItems[(currentIndex + if (forward) 1 else catalogItems.lastIndex) % catalogItems.size]
        dramaSwitchSwipeCount += 1
        val policy = rewardsSnapshot?.interstitial
        val shouldShow = policy?.dramaSwitchEnabled == true && dramaSwitchSwipeCount % policy.dramaSwitchEveryCount == 0
        val switch = { showPlayer(nextDrama, 0) }
        if (shouldShow) {
            showPlaybackActionInterstitial("int_drama_switch", episodeId = selectedEpisode()?.id, countTowardEpisodeLimit = false, onShown = switch, onUnavailable = { reason -> logStatus("切剧插屏未展示：$reason"); switch() })
        } else switch()
    }

    private fun requestMidEpisodeInterstitialIfEligible(positionSeconds: Int) {
        val snapshot = rewardsSnapshot ?: return
        val policy = snapshot.interstitial
        val episodeKey = episodeKey(currentEpisodeIndex)
        if (!playerShown || playerIsPaused || fullscreenAdInProgress || !policy.midEpisodeEnabled) return
        if (positionSeconds < policy.midEpisodeTriggerSeconds || !midEpisodeInterstitialRequestedEpisodeKeys.add(episodeKey)) return
        showPlaybackActionInterstitial("int_episode_midroll", episodeId = selectedEpisode()?.id, onShown = {}, onUnavailable = { message ->
            logStatus("剧中插屏未展示：$message")
        })
    }

    private fun completeSeekAndResume(targetSeconds: Int) {
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

    private fun createPlayerProgressControls(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val timeRow = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        playerProgressText = TextView(this@PartnerDramaActivity).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            text = "00:00 / --:--"
        }
        timeRow.addView(playerProgressText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(TextView(this@PartnerDramaActivity).apply {
            text = t("seek_to")
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
        })
        addView(timeRow)
        playerProgressSeekBar = SeekBar(this@PartnerDramaActivity).apply {
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
                    val policy = rewardsSnapshot?.interstitial
                    val pendingGems = rewardsSnapshot?.ring?.rewardCoins ?: 0
                    val rewardThresholdReached = policy != null && pendingGems >= policy.actionRewardThresholdGems
                    if (policy?.seekEnabled == true && rewardThresholdReached && !seekInterstitialInProgress) {
                        seekInterstitialInProgress = true
                        showPlaybackActionInterstitial("int_seek", onShown = {
                            seekInterstitialInProgress = false
                            completeSeekAndResume(targetSeconds)
                        }, onUnavailable = { message ->
                            seekInterstitialInProgress = false
                            logStatus("拖拽插屏未展示：$message")
                            completeSeekAndResume(targetSeconds)
                        })
                    } else {
                        completeSeekAndResume(targetSeconds)
                    }
                }
            })
        }
        addView(playerProgressSeekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
    }

    private fun createPlayerActionRow(): View = LinearLayout(this).apply {
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

    private fun togglePlayerDescription() {
        playerDescriptionExpanded = !playerDescriptionExpanded
        playerDescriptionContainer?.visibility = if (playerDescriptionExpanded) View.VISIBLE else View.GONE
    }

    private fun transparentPlayerTextButton(label: String, description: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedDrawable(0x26000000, 14)
        contentDescription = description
        setOnClickListener { onClick() }
        isClickable = true
    }

    private fun setPlayerControlsVisible(visible: Boolean) {
        playerControlsVisible = visible
        playerControlsPanel?.visibility = if (visible) View.VISIBLE else View.GONE
        playerControlsRevealButton?.visibility = if (visible) View.GONE else View.VISIBLE
    }

    /** 视频区域单击是唯一的播放/暂停操作；Media3 会同步停止画面、音频和播放时钟。 */
    private fun updatePauseOverlayVisibility(visible: Boolean) {
        playerPauseOverlay?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun resumePlaybackFromPauseOverlay() {
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

    private fun togglePlaybackFromVideoTap() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playerIsPaused = true
            updatePauseOverlayVisibility(true)
            requestPauseInterstitialIfEligible()
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

    private fun transparentPlayerIcon(label: String, description: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (label.length > 1) 12f else 21f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedDrawable(0x26000000, 14)
        contentDescription = description
        setOnClickListener { onClick() }
        isClickable = true
    }

    private fun showPlaybackSpeedPicker() {
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        val selected = speeds.indexOfFirst { it == playerSpeed }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(t("playback_speed"))
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val applySpeed = {
                    playerSpeed = speeds[which]
                    exoPlayer?.setPlaybackSpeed(playerSpeed)
                    speedButton?.text = labels[which]
                    resumeAfterFullscreenAdIfNeeded()
                }
                dialog.dismiss()
                val policy = rewardsSnapshot?.interstitial
                val pendingGems = rewardsSnapshot?.ring?.rewardCoins ?: 0
                val rewardThresholdReached = policy != null && pendingGems >= policy.actionRewardThresholdGems
                if (policy?.speedEnabled == true && rewardThresholdReached) {
                    showPlaybackActionInterstitial("int_speed", onShown = applySpeed, onUnavailable = { message ->
                        logStatus("倍速插屏未展示：$message")
                        applySpeed()
                    })
                } else {
                    applySpeed()
                }
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun showPlaybackMoreControls() {
        val muteLabel = if (playerMuted) t("restore_sound") else t("mute")
        AlertDialog.Builder(this)
            .setTitle(t("playback_settings"))
            .setItems(arrayOf(muteLabel, t("increase_brightness"), t("restore_brightness"), t("immersive_mode"), t("reload_episode"))) { _, which ->
                when (which) {
                    0 -> {
                        playerMuted = !playerMuted
                        exoPlayer?.volume = if (playerMuted) 0f else 1f
                        toast(if (playerMuted) t("muted") else t("sound_restored"))
                    }
                    1 -> {
                        window.attributes = window.attributes.apply { screenBrightness = 1f }
                        toast(t("brightness_increased"))
                    }
                    2 -> {
                        window.attributes = window.attributes.apply { screenBrightness = -1f }
                        toast(t("brightness_restored"))
                    }
                    3 -> enterImmersivePlayerMode()
                    4 -> selectEpisode(currentEpisodeIndex)
                }
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun enterImmersivePlayerMode(announce: Boolean = true) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        playerInImmersiveMode = true
        setPlayerControlsVisible(false)
        if (announce) toast(t("immersive_hint"))
    }

    private fun exitImmersivePlayerMode() {
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
        playerInImmersiveMode = false
    }

    private fun updatePlayerProgress(progressSeconds: Int, durationSeconds: Int) {
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

    private fun formatPlaybackTime(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d".format(Locale.US, safe / 60, safe % 60)
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedDrawable(COLOR_SURFACE, 14)
        setOnClickListener { onClick() }
        isClickable = true
    }

    private fun toggleEpisodePicker() {
        episodePickerDialog?.let { active ->
            if (active.isShowing) {
                active.dismiss()
                return
            }
        }
        showEpisodePicker()
    }

    private fun showEpisodePicker() {
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
        sheet.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun selectEpisode(index: Int) {
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
        midEpisodeInterstitialRequestedEpisodeKeys.remove(episodeKey(index))
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

    private fun selectedEpisode(): LocalEpisode? = selectedDrama?.episodes?.getOrNull(currentEpisodeIndex)

    private fun playNextEpisode() {
        startNextEpisodeAutomatically(currentEpisodeIndex, userInitiated = true)
    }

    private fun startNextEpisodeAutomatically(completedIndex: Int, userInitiated: Boolean = false) {
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

    /** 免费集数用完后由用户明确确认；确认后才展示插屏并请求内容服务解锁。 */
    private fun unlockEpisodeThenAutoPlay(index: Int) {
        if (episodeTransitionInProgress) return
        val episode = selectedDrama?.episodes?.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(t("free_episodes_used"))
            .setMessage(t("unlock_episode_message", index + 1))
            .setNegativeButton(t("later")) { _, _ ->
                episodeTransitionInProgress = false
                exoPlayer?.pause()
                playerIsPaused = true
                updatePauseOverlayVisibility(true)
            }
            .setPositiveButton(t("unlock_with_ad")) { _, _ -> startEpisodeUnlockAfterConfirmation(index) }
            .show()
    }

    /** 用户确认后完整观看激励广告；服务端结算成功后才向内容服务申请本集解锁并自动播放。 */
    private fun startEpisodeUnlockAfterConfirmation(index: Int) {
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
                settleEpisodeUnlockAfterAd(index, unlockMethod = "AD_REWARDED") {
                    selectEpisode(index)
                }
            },
            onSettlementUnavailable = { message ->
                episodeTransitionInProgress = false
                resumeAfterFullscreenAdIfNeeded()
                toast("广告已完整观看，但解锁结算暂未同步：$message")
            },
        )
    }

    private fun handleEpisodeCompleted(index: Int) {
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

    private fun settleEpisodeUnlockAfterAd(index: Int, unlockMethod: String, onUnlocked: () -> Unit = {}) {
        val episode = selectedDrama?.episodes?.getOrNull(index) ?: return
        localDramaApi.unlockEpisode(
            baseUrl = localContentBaseUrl(),
            episodeId = episode.id,
            region = CONTENT_REGION,
            userId = anonymousInstallationId(),
            unlockMethod = unlockMethod,
            onSuccess = { runOnUiThread {
                unlockEpisodeForCurrentSession(index, t("episode_unlocked", index + 1))
                onUnlocked()
            } },
            onFailure = { message -> runOnUiThread {
                episodeTransitionInProgress = false
                if (unlockMethod == "AD_INTERSTITIAL" || unlockMethod == "AD_REWARDED") interstitialRequestedEpisodeKeys.remove(episodeKey(index))
                logStatus("自有剧解锁失败：$message")
                resumeAfterFullscreenAdIfNeeded()
                toast("解锁服务暂不可用：$message")
            } },
        )
    }

    private fun unlockEpisodeForCurrentSession(index: Int, message: String) {
        sessionUnlockedEpisodeKeys.add(episodeKey(index))
        pendingUnlockIndex = -1
        updateEpisodeStatus()
        toast(message)
    }

    private fun recordCompletedEpisodeForRewards(index: Int, onContinuePlayback: () -> Unit) {
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
                when {
                    reward.randomChestsCreated > 0 -> showRandomChestEarnedDialog(
                        reward.randomChestsCreated,
                        reward.snapshot.chest.availableCount,
                        onContinuePlayback,
                    )
                    reward.episodeRewardCoins > 0 -> {
                        toast(t("episode_reward_pending", reward.episodeRewardCoins))
                        onContinuePlayback()
                    }
                    else -> onContinuePlayback()
                }
            } },
            onFailure = { message -> runOnUiThread {
                logStatus("观看奖励同步失败：$message")
                onContinuePlayback()
            } },
        )
    }

    private fun showRandomChestEarnedDialog(createdCount: Int, availableCount: Int, onContinuePlayback: () -> Unit) {
        val dialog = AlertDialog.Builder(this).create()
        dialog.setCancelable(false)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = gradientDrawable(intArrayOf(0xFFFFF0E6.toInt(), 0xFFFFFFFF.toInt()), 28)
        }
        val chestImage = ImageView(this).apply {
            setImageResource(com.dramaflow.app.R.drawable.ic_random_chest_reward)
            contentDescription = t("chest")
            scaleX = 0.55f
            scaleY = 0.55f
            alpha = 0f
        }
        content.addView(chestImage, LinearLayout.LayoutParams(dp(126), dp(126)))
        content.addView(TextView(this).apply {
            text = t("chest_earned", createdCount)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        content.addView(TextView(this).apply {
            text = t("chest_earned_message")
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
        chestImage.animate().alpha(1f).scaleX(1.10f).scaleY(1.10f).setDuration(260L).withEndAction {
            chestImage.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        }.start()
    }

    /**
     * 暂停时仅在锁集阶段、达到最低有效播放时长且服务端允许时请求 Native 暂停广告。
     * 该卡片由应用提供“返回播放”按钮，因此用户可随时关闭，并从暂停前位置继续。
     */
    private fun requestPauseInterstitialIfEligible() {
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

    private fun needsUnlock(index: Int): Boolean {
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

    private fun episodeKey(index: Int): String = "${selectedDrama?.id ?: 0}:${index + 1}"

    private fun updateEpisodeStatus() {
        val drama = selectedDrama ?: return
        val lockText = if (needsUnlock(currentEpisodeIndex)) " · ${t("unlock_with_ad")}" else " · ${t("watchable")}"
        episodeStatusView?.text = t("episode_progress", currentEpisodeIndex + 1, drama.total.coerceAtLeast(1), lockText)
    }

    private fun showLocalServerSettings() {
        val input = EditText(this).apply {
            setText(localContentBaseUrl())
            hint = "http://192.168.1.100:8000"
            isSingleLine = true
            setSelectAllOnFocus(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("局域网内容服务")
            .setMessage("请输入运行自有短剧 FastAPI 服务的电脑局域网地址。手机与电脑需连接同一局域网。")
            .setView(input)
            .setPositiveButton("保存并重连") { _, _ ->
                val value = input.text.toString().trim().trimEnd('/')
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    toast("地址必须以 http:// 或 https:// 开头")
                } else {
                    serverPreferences.edit().putString(SERVER_BASE_URL_KEY, value).apply()
                    catalogItems = emptyList()
                    loadLocalCatalog()
                }
            }
            .setNeutralButton("恢复默认") { _, _ ->
                serverPreferences.edit().remove(SERVER_BASE_URL_KEY).apply()
                catalogItems = emptyList()
                loadLocalCatalog()
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun showRewardsServerSettings() {
        val input = EditText(this).apply {
            setText(rewardsBaseUrl())
            hint = "http://192.168.1.100:8100"
            isSingleLine = true
            setSelectAllOnFocus(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("独立积分与奖励服务")
            .setMessage("请输入运行 PostgreSQL 奖励服务的电脑局域网地址。它与短剧内容服务使用同一主机、不同端口，默认端口为 8100。保存后会立即请求 /v2/bootstrap 验证连接。")
            .setView(input)
            .setPositiveButton("保存并同步") { _, _ ->
                val value = input.text.toString().trim().trimEnd('/')
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    toast("地址必须以 http:// 或 https:// 开头")
                } else {
                    serverPreferences.edit().putString(REWARDS_SERVER_BASE_URL_KEY, value).apply()
                    refreshRewardsSnapshot()
                    toast("积分服务地址已保存")
                }
            }
            .setNeutralButton("恢复默认") { _, _ ->
                serverPreferences.edit().remove(REWARDS_SERVER_BASE_URL_KEY).apply()
                refreshRewardsSnapshot()
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun showAdPrivacySettings() {
        val granted = consentStore.read() == AdConsent.GRANTED
        AlertDialog.Builder(this)
            .setTitle("广告设置")
            .setMessage(if (granted) "你已同意广告。开屏和插屏可能在合适时机展示；锁定剧集可通过激励广告解锁。" else "你当前未同意广告。免费内容可正常观看，但广告解锁不可用。")
            .setPositiveButton(if (granted) "关闭广告" else "启用广告") { _, _ ->
                consentStore.save(if (granted) AdConsent.DECLINED else AdConsent.GRANTED)
                appOpenAttempted = false
                if (!granted) maybeShowAppOpenAd()
                toast(if (granted) "已关闭广告" else "已启用广告")
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun toggleFavorite() {
        val dramaId = selectedDrama?.id ?: return
        val key = "favorite_$dramaId"
        val nowFavorite = !favorites.getBoolean(key, false)
        favorites.edit().putBoolean(key, nowFavorite).apply()
        favoriteButton?.text = if (nowFavorite) "♥" else "♡"
        toast(if (nowFavorite) "已加入追剧" else "已取消追剧")
    }

    private fun isFavorite(): Boolean = selectedDrama?.id?.let { favorites.getBoolean("favorite_$it", false) } ?: false

    private fun showCatalog() {
        exitImmersivePlayerMode()
        playerShown = false
        activeTab = MainTab.HOME
        releasePlayer()
        if (catalogItems.isNotEmpty()) renderActiveTab() else loadLocalCatalog()
    }

    private fun renderStartupLoading() {
        playerShown = false
        root.removeAllViews()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(34), dp(28), dp(34), dp(28)) }
        column.addView(TextView(this).apply { text = "J"; textSize = 38f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = gradientDrawable(intArrayOf(COLOR_SUNSET, COLOR_CORAL), 28) }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 28f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY); setPadding(0, dp(18), 0, dp(4)) })
        column.addView(TextView(this).apply { text = t("startup_tagline"); textSize = 14f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED) })
        startupProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 8; progressTintList = ColorStateList.valueOf(COLOR_CORAL); backgroundTintList = ColorStateList.valueOf(0x1E202B33) }
        column.addView(startupProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(34) })
        startupProgressLabel = TextView(this).apply { text = t("startup_preparing"); textSize = 12f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(10), 0, 0) }
        column.addView(startupProgressLabel)
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun updateStartupProgress(progress: Int, label: String) {
        startupProgressBar?.progress = progress.coerceIn(0, 100)
        startupProgressLabel?.text = label
    }

    private fun renderLoading(message: String) {
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

    private fun renderFailure(message: String) {
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
    private fun testRewardProof(): String = if (BuildConfig.DEBUG) "TEST_TOPON_REWARD" else ""

    private fun rewardsBaseUrl(): String {
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

    private fun localContentBaseUrl(): String = serverPreferences
        .getString(SERVER_BASE_URL_KEY, null)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: BuildConfig.LOCAL_CONTENT_API_BASE_URL.trim().trimEnd('/')

    private fun resumePreferenceKey(dramaId: String, suffix: String): String = "resume_${suffix}_${dramaId}"

    private fun savedResumeEpisodeIndex(drama: LocalDrama): Int = playbackHistory
        .getInt(resumePreferenceKey(drama.id, "episode"), 0)
        .coerceIn(0, drama.episodes.lastIndex.coerceAtLeast(0))

    private fun savedResumePositionSeconds(drama: LocalDrama): Int = playbackHistory
        .getInt(resumePreferenceKey(drama.id, "position"), 0)
        .coerceAtLeast(0)

    private fun persistPlaybackProgressIfNeeded(positionSeconds: Int) {
        if (positionSeconds == lastPersistedPlaybackSecond || positionSeconds % 3 != 0) return
        persistPlaybackProgress(positionSeconds)
    }

    private fun persistPlaybackProgress(positionSeconds: Int = playerProgressSeconds) {
        val drama = selectedDrama ?: return
        if (!playerShown || currentEpisodeIndex !in drama.episodes.indices) return
        val safePosition = positionSeconds.coerceAtLeast(0)
        playbackHistory.edit()
            .putInt(resumePreferenceKey(drama.id, "episode"), currentEpisodeIndex)
            .putInt(resumePreferenceKey(drama.id, "position"), safePosition)
            .apply()
        lastPersistedPlaybackSecond = safePosition
    }

    private fun persistNextEpisodeResume(completedIndex: Int) {
        val drama = selectedDrama ?: return
        val nextIndex = (completedIndex + 1).coerceAtMost(drama.episodes.lastIndex.coerceAtLeast(0))
        playbackHistory.edit()
            .putInt(resumePreferenceKey(drama.id, "episode"), nextIndex)
            .putInt(resumePreferenceKey(drama.id, "position"), 0)
            .apply()
    }

    private fun releasePlayer() {
        persistPlaybackProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressHandler.removeCallbacks(progressTicker)
        playerView?.player = null
        playerView = null
        playerPauseOverlay = null
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun anonymousInstallationId(): String {
        return getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(INSTALLATION_KEY, null)
            ?: UUID.randomUUID().toString().also {
                getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(INSTALLATION_KEY, it).apply()
            }
    }

    private fun pageCanvasDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(COLOR_BACKGROUND, COLOR_MIST_BLUE, COLOR_BACKGROUND),
    )

    private fun gradientDrawable(colors: IntArray, radiusDp: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        colors,
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    private fun roundedDrawable(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun topRoundedDrawable(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
            dp(radiusDp).toFloat(), dp(radiusDp).toFloat(),
            dp(radiusDp).toFloat(), dp(radiusDp).toFloat(),
            0f, 0f, 0f, 0f
        )
        setColor(color)
    }

    private fun posterFallbackDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(COLOR_ACCENT, COLOR_PURPLE, COLOR_BACKGROUND)
    ).apply { cornerRadius = dp(10).toFloat() }

    private fun showLanguageSettings() {
        val options = AppLanguage.options
        val selected = options.indexOfFirst { it.tag == AppLanguage.tag(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(t("language")).setSingleChoiceItems(options.map { it.label }.toTypedArray(), selected) { dialog, which ->
            AppLanguage.save(this, options[which].tag)
            ShortDramaSdkManager.get(this).setContentLanguageForUi(options[which].tag)
            dialog.dismiss()
            if (withdrawalPageShown) rewardsSnapshot?.wallet?.let(::showWithdrawalPage) else renderActiveTab()
        }.show()
    }

    private fun rewardsMarketCode(): String = Locale.getDefault().country.trim().uppercase(Locale.ROOT).ifBlank { CONTENT_REGION }

    private fun t(key: String, vararg values: Any): String = AppLanguage.text(this, key, *values)
    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun logStatus(message: String) = android.util.Log.i(TAG, message)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_CATALOG_GENRES = 6
        private const val SHELF_ITEM_LIMIT = 6
        private const val FREE_EPISODES = 3
        private const val CONTENT_REGION = "SG"
        private const val PREFERENCES = "joyedrama.short_drama"
        private const val PLAYBACK_HISTORY_PREFERENCES = "joyedrama.playback_history"
        private const val SERVER_PREFERENCES = "dramaflow.local_content"
        private const val SERVER_BASE_URL_KEY = "base_url"
        private const val REWARDS_SERVER_BASE_URL_KEY = "rewards_base_url"
        private const val INSTALLATION_KEY = "anonymous_installation_id"
        private const val STATE_PLAYER_VISIBLE = "state_player_visible"
        private const val STATE_DRAMA_ID = "state_drama_id"
        private const val STATE_EPISODE_INDEX = "state_episode_index"
        private const val STATE_POSITION_SECONDS = "state_position_seconds"
        private const val TAG = "JoyeDramaPlayer"

        private const val COLOR_BACKGROUND = 0xFFFCFBF8.toInt()
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()
        private const val COLOR_SURFACE = 0xFFF4F3EF.toInt()
        private const val COLOR_ACCENT = 0xFFFF6D4A.toInt()
        private const val COLOR_PURPLE = 0xFFF0B061.toInt()
        private const val COLOR_INK = 0xFF19202B.toInt()
        private const val COLOR_PLUM = 0xFF493B61.toInt()
        private const val COLOR_CORAL = 0xFFFF6D4A.toInt()
        private const val COLOR_SUNSET = 0xFFFFA45B.toInt()
        private const val COLOR_AQUA = 0xFF65CFCB.toInt()
        private const val COLOR_MIST_BLUE = 0xFFEAF7FA.toInt()
        private const val COLOR_WARM_PEACH = 0xFFFFEEE5.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF19202B.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF7E8490.toInt()

        fun intent(context: Context): Intent = Intent(context, PartnerDramaActivity::class.java)
    }
}
