package com.dramaflow.app.partner

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.dramaflow.app.ads.PangleAdsManager
import com.dramaflow.app.ads.PangleConsent
import com.dramaflow.app.ads.PangleConsentStore
import com.dramaflow.app.ads.PangleSettings
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalDramaApiClient
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.util.Locale
import java.util.UUID

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
    private var activeTab: MainTab = MainTab.HOME
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
    private var startupCheckinPromptRequested = false
    private var startupCheckinPromptShown = false
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
    private var pauseNativeAdInProgress = false
    private var episodePickerDialog: AlertDialog? = null
    private val unlockedEpisodeKeys = mutableSetOf<String>()
    // 奖励服务不可达时仅保留本次进程内播放权益；不会伪造为已持久化的服务器权益。
    private val sessionUnlockedEpisodeKeys = mutableSetOf<String>()
    private val interstitialRequestedEpisodeKeys = mutableSetOf<String>()
    private val midEpisodeInterstitialRequestedEpisodeKeys = mutableSetOf<String>()
    private var seekInterstitialInProgress = false
    private val completedEpisodeKeys = mutableSetOf<String>()
    private val rewardWatchRecordedEpisodeKeys = mutableSetOf<String>()
    private val floatingWheelLabels = mutableSetOf<TextView>()
    private val favorites by lazy { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val playbackHistory by lazy { getSharedPreferences(PLAYBACK_HISTORY_PREFERENCES, Context.MODE_PRIVATE) }

    private val consentStore by lazy { PangleConsentStore(applicationContext) }
    private val pangleAds by lazy { PangleAdsManager(applicationContext, PangleSettings.fromBuildConfig()) }
    private val rewardsApi by lazy { RewardsApiClient(baseUrlProvider = { rewardsBaseUrl() }) }
    private val localDramaApi by lazy { LocalDramaApiClient() }
    private val serverPreferences by lazy { getSharedPreferences(SERVER_PREFERENCES, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerShown) showCatalog() else finish()
            }
        })
        beginStartupExperience()
    }

    override fun onStop() {
        // 退后台时必须停止画面、音频与播放时钟，返回应用后不自动续播。
        persistPlaybackProgress()
        exoPlayer?.pause()
        playerIsPaused = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
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
        if (consentStore.read() == PangleConsent.UNKNOWN) {
            consentStore.save(PangleConsent.GRANTED)
        }
        startupCheckinPromptRequested = true
        startupCheckinPromptShown = false
        renderStartupLoading()
        maybeShowAppOpenAd()
    }

    private fun maybeShowAppOpenAd() {
        if (appOpenAttempted) return
        appOpenAttempted = true
        if (consentStore.read() != PangleConsent.GRANTED) {
            finishStartupAfterAppOpen("用户已关闭广告")
            return
        }
        // 立即请求，不再等待固定延迟；每次 Activity 冷启动都会进入该请求链路。
        pangleAds.showAppOpenIfEligible(this) { result ->
            when (result) {
                PangleAdsManager.FullscreenResult.Shown -> logStatus("开屏广告已关闭")
                is PangleAdsManager.FullscreenResult.Failed -> logStatus("开屏广告加载失败：${result.code} ${result.message}")
                is PangleAdsManager.FullscreenResult.Skipped -> logStatus("开屏广告未展示：${result.reason}")
            }
            runOnUiThread { finishStartupAfterAppOpen(result.javaClass.simpleName) }
        }
    }

    private fun finishStartupAfterAppOpen(reason: String) {
        if (startupContentInitializationStarted || isFinishing || isDestroyed) return
        startupContentInitializationStarted = true
        logStatus("开屏流程结束：$reason")
        refreshRewardsSnapshot()
        showStartupCheckinWhenReady()
        loadLocalCatalog()
    }

    private fun loadLocalCatalog() {
        renderLoading("正在连接局域网自有短剧服务…")
        localDramaApi.fetchFeed(
            baseUrl = localContentBaseUrl(),
            region = CONTENT_REGION,
            onSuccess = { items -> runOnUiThread {
                if (items.isEmpty()) {
                    renderFailure("局域网服务已连接，但当前地区没有可播放的自有短剧。")
                } else {
                    catalogItems = items
                    renderActiveTab()
                }
            } },
            onFailure = { message -> runOnUiThread {
                renderFailure("局域网短剧目录加载失败：$message\n当前地址：${localContentBaseUrl()}")
            } },
        )
    }

    private enum class MainTab(val title: String, val icon: String) {
        HOME("首页", "⌂"), DISCOVER("发现", "◉"), TASKS("奖励", "🎁"), PROFILE("我的", "☺")
    }

    private fun renderActiveTab() {
        if (catalogItems.isEmpty()) {
            renderLoading("正在准备短剧内容…")
            return
        }
        playerShown = false
        val page = when (activeTab) {
            MainTab.HOME -> createHomePage()
            MainTab.DISCOVER -> createDiscoverPage()
            MainTab.TASKS -> createTasksPage()
            MainTab.PROFILE -> createProfilePage()
        }
        catalogPage = page
        root.removeAllViews()
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createBottomNavigation(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM))
        root.addView(createFloatingRewardWheel(), FrameLayout.LayoutParams(dp(92), dp(112), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(10)
            bottomMargin = dp(80)
        })
    }

    private fun createHomePage(): View = scrollPage { content ->
        content.addView(createBalanceMissionCard(), matchParams(bottom = 12))
        rewardsSnapshot?.takeIf { it.welcome.available }?.let { content.addView(createWelcomeRewardCard(it), matchParams(bottom = 16)) }
        content.addView(createCategoryTabs(), matchParams(bottom = 16))
        catalogItems.firstOrNull()?.let { content.addView(createHeroBanner(it), matchParams(bottom = 22)) }
        content.addView(createSectionHeader("精选好剧", "高能剧情，随时开追"), matchParams(bottom = 12))
        content.addView(createHorizontalDramaShelf(catalogItems.take(6)), matchParams(bottom = 22))
        content.addView(createSectionHeader("热门短剧", "正在热播的故事"), matchParams(bottom = 12))
        addDramaGrid(content, catalogItems)
    }

    private fun createDiscoverPage(): View = scrollPage { content ->
        content.addView(createSectionHeader("发现好剧", "沉浸追剧，解锁更多精彩"), matchParams(bottom = 14))
        if (rewardsSnapshot?.ring?.enabled == true) content.addView(createRingCard(), matchParams(bottom = 18))
        catalogItems.forEach { drama ->
            content.addView(createDiscoverDramaCard(drama), matchParams(bottom = 14))
        }
    }

    private fun createTasksPage(): View = scrollPage { content ->
        content.addView(createPageTitle("奖励中心", "有效追剧先积累宝石，点击领取后才记入你的余额"))
        rewardsSnapshot?.takeIf { it.welcome.available }?.let { content.addView(createWelcomeRewardCard(it), matchParams(top = 16, bottom = 12)) }
        content.addView(createRewardsWalletSummary(), matchParams(top = 4, bottom = 16))
        content.addView(createCheckinCard(), matchParams(bottom = 16))
        val snapshot = rewardsSnapshot
        if (snapshot == null) {
            content.addView(createInlineNotice(if (rewardsLoading) "正在同步独立积分服务…" else "积分服务尚未连接。可在“我的”中填写独立奖励服务地址。"))
        } else {
            content.addView(createChestCard(snapshot), matchParams(bottom = 16))
            val chestHint = if (snapshot.chest.availableCount > 0) {
                "当前有 ${snapshot.chest.availableCount} 个随机宝箱可连续开启；每只完整观看一条激励广告后随机获得 ${snapshot.chest.minCoins}–${snapshot.chest.maxCoins} 宝石。"
            } else if (snapshot.episodesToNextRandomChest > 0) {
                "每累计 ${snapshot.episodesPerRandomChest} 集有效观看可获得 1 个随机宝箱；再看 ${snapshot.episodesToNextRandomChest} 集即可获得下一只。"
            } else {
                "每累计 ${snapshot.episodesPerRandomChest} 集有效观看可获得 1 个随机宝箱。"
            }
            content.addView(createInlineNotice(chestHint), matchParams(bottom = 16))
            content.addView(createSectionHeader("皇冠之路", "升级头像等级，同时提升后续每集宝石奖励倍率"), matchParams(top = 6, bottom = 12))
            content.addView(createAchievementSummary(snapshot), matchParams(bottom = 16))
        }
    }

    private fun createProfilePage(): View = scrollPage { content ->
        content.addView(createPageTitle("我的", "追剧进度与奖励明细"))
        content.addView(createWalletCard(), matchParams(top = 16, bottom = 16))
        content.addView(createSectionHeader("继续观看", "从上次停留的剧情继续"), matchParams(bottom = 12))
        catalogItems.take(4).forEach { content.addView(createHistoryRow(it), matchParams(bottom = 10)) }
        content.addView(createSectionHeader("我的服务", "收藏、历史与隐私设置"), matchParams(top = 10, bottom = 10))
        content.addView(createSettingsRow("局域网内容服务", localContentBaseUrl(), "›") { showLocalServerSettings() }, matchParams(bottom = 8))
        content.addView(createSettingsRow("积分与奖励服务", rewardsBaseUrl(), "›") { showRewardsServerSettings() }, matchParams(bottom = 8))
        content.addView(createSettingsRow("广告与隐私", "管理广告偏好", "›") { showAdPrivacySettings() }, matchParams(bottom = 8))
        content.addView(createSettingsRow("奖励流水", "查看服务端已结算奖励", "›") { showRewardLedgerHint() }, matchParams(bottom = 18))
    }

    private fun scrollPage(build: (LinearLayout) -> Unit): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(92))
        }
        build(content)
        return ScrollView(this).apply {
            isFillViewport = true
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
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = subtitle
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun createBalanceMissionCard(): View {
        val snapshot = rewardsSnapshot
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = gradientDrawable(intArrayOf(COLOR_PURPLE, COLOR_ACCENT), 22)
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "追剧奖励"
                textSize = 14f
                setTextColor(0xDFFFFFFF.toInt())
            })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = snapshot?.wallet?.let { "${it.coins} 宝石" } ?: "奖励服务同步中"
                textSize = 29f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, dp(10))
            })
            val row = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this@PartnerDramaActivity).apply {
                text = snapshot?.let { if (it.ring.claimable) "${it.ring.rewardCoins} 宝石待领取，点击悬浮球领取" else "完成有效观看即可获得待领取宝石" } ?: "完成有效观看即可攒宝石"
                textSize = 13f
                setTextColor(0xEFFFFFFF.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this@PartnerDramaActivity).apply {
                text = "去完成 ›"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_ACCENT)
                background = roundedDrawable(Color.WHITE, 14)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { activeTab = MainTab.TASKS; renderActiveTab() }
                isClickable = true
            })
            addView(row)
        }
    }

    private fun createCategoryTabs(): View = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("精选", "热门", "最新", "男频", "女频").forEachIndexed { index, label ->
            row.addView(TextView(this@PartnerDramaActivity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) Color.WHITE else COLOR_TEXT_MUTED)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundedDrawable(if (index == 0) COLOR_ACCENT else COLOR_SURFACE, 16)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = dp(8) })
        }
        addView(row)
    }

    private fun createHeroBanner(drama: LocalDrama): View = FrameLayout(this).apply {
        background = gradientDrawable(intArrayOf(COLOR_PURPLE, 0xFF20182F.toInt()), 22)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        minimumHeight = dp(172)
        isClickable = true
        setOnClickListener { showPlayer(drama, 0) }
        val copy = LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this@PartnerDramaActivity).apply {
            text = "本周精选"
            textSize = 13f
            setTextColor(0xDFFFFFFF.toInt())
        })
        copy.addView(TextView(this@PartnerDramaActivity).apply {
            text = drama.title.ifBlank { "精彩短剧" }
            textSize = 23f
            maxLines = 2
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
        })
        copy.addView(TextView(this@PartnerDramaActivity).apply {
            text = "${drama.total.coerceAtLeast(1)} 集 · 立即开追"
            textSize = 13f
            setTextColor(0xEFFFFFFF.toInt())
            background = roundedDrawable(0x44FFFFFF, 14)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        addView(copy, FrameLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
        addView(ImageView(this@PartnerDramaActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.72f
            load(drama.coverImage) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
        }, FrameLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
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
            text = drama.title.ifBlank { "精彩短剧" }
            maxLines = 2
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
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
            addView(TextView(this@PartnerDramaActivity).apply { text = drama.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); maxLines = 2 })
            addView(TextView(this@PartnerDramaActivity).apply { text = "${drama.total.coerceAtLeast(1)} 集 · 竖屏短剧"; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), 0, 0) })
            addView(TextView(this@PartnerDramaActivity).apply { text = "继续观看 ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT); setPadding(0, dp(18), 0, 0) })
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
        addView(TextView(this@PartnerDramaActivity).apply { text = "${drama.title}\n第 ${resumeEpisode + 1} 集 · ${formatPlaybackTime(resumeSecond)}"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); maxLines = 2; setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PartnerDramaActivity).apply { text = "继续 ›"; textSize = 13f; setTextColor(COLOR_ACCENT) })
    }

    private fun createSettingsRow(title: String, subtitle: String, trailing: String, action: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedDrawable(COLOR_CARD, 16)
        isClickable = true
        setOnClickListener { action() }
        addView(LinearLayout(this@PartnerDramaActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PartnerDramaActivity).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            addView(TextView(this@PartnerDramaActivity).apply { text = subtitle; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PartnerDramaActivity).apply { text = trailing; textSize = 22f; setTextColor(COLOR_TEXT_MUTED) })
    }

    private fun createInlineNotice(message: String): View = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(COLOR_TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(28), dp(18), dp(28))
        background = roundedDrawable(COLOR_CARD, 16)
    }

    private fun createBottomNavigation(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(10))
        background = topRoundedDrawable(0xFA191522.toInt(), 24)
        MainTab.entries.forEach { tab ->
            addView(LinearLayout(this@PartnerDramaActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { if (activeTab != tab) { activeTab = tab; renderActiveTab() } }
                addView(TextView(this@PartnerDramaActivity).apply { text = tab.icon; textSize = 18f; gravity = Gravity.CENTER; setTextColor(if (activeTab == tab) COLOR_ACCENT else COLOR_TEXT_MUTED) })
                addView(TextView(this@PartnerDramaActivity).apply { text = tab.title; textSize = 11f; gravity = Gravity.CENTER; setTextColor(if (activeTab == tab) Color.WHITE else COLOR_TEXT_MUTED) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun createSectionHeader(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@PartnerDramaActivity).apply {
            text = title
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Color.rgb(185, 173, 197))
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun createFloatingRewardWheel(compact: Boolean = false): View {
        val size = if (compact) dp(72) else dp(78)
        val wheel = FrameLayout(this).apply {
            elevation = dp(10).toFloat()
            isClickable = true
            contentDescription = "累计待领取宝石，点击完整观看激励广告后全部领取"
            setOnClickListener {
                if (rewardsSnapshot?.ring?.claimable == true) showEpisodeRewardClaimDialog() else {
                    exoPlayer?.pause()
                    playerIsPaused = true
                    activeTab = MainTab.TASKS
                    showCatalog()
                }
            }
        }
        val halo = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFF2D55.toInt(), 0xFFFFA928.toInt(), 0xFFFFDB6A.toInt()),
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), Color.WHITE)
            }
        }
        wheel.addView(halo, FrameLayout.LayoutParams(size, size))
        ObjectAnimator.ofFloat(halo, View.ROTATION, 0f, 360f).apply {
            duration = 7_000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        val label = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = if (compact) 10f else 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFE8294C.toInt(), 0xFF8F1738.toInt()),
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xFFFFDB6A.toInt())
            }
        }
        floatingWheelLabels.add(label)
        updateFloatingRewardWheel(label)
        wheel.addView(label, FrameLayout.LayoutParams(size - dp(10), size - dp(10), Gravity.CENTER))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(wheel, LinearLayout.LayoutParams(size, size))
            if (playerShown) {
                addView(TextView(this@PartnerDramaActivity).apply {
                    val reward = currentEpisodePendingReward()
                    text = when {
                        reward == null -> "本集翻倍"
                        reward.boosted -> "已翻倍"
                        else -> "本集翻倍"
                    }
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (reward != null && !reward.boosted) Color.WHITE else COLOR_TEXT_MUTED)
                    background = gradientDrawable(intArrayOf(0xFFFF4B58.toInt(), 0xFFC91F39.toInt()), 14)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    isClickable = reward != null && !reward.boosted
                    alpha = if (reward != null && !reward.boosted) 1f else 0.55f
                    setOnClickListener { if (reward != null && !reward.boosted) requestEpisodeRewardBoost(reward) else toast("本集暂无可翻倍的待领取宝石") }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { topMargin = dp(5) })
            }
        }
    }

    private fun refreshFloatingRewardWheels() {
        floatingWheelLabels.removeAll { !it.isAttachedToWindow }
        floatingWheelLabels.forEach { updateFloatingRewardWheel(it) }
    }

    private fun updateFloatingRewardWheel(label: TextView) {
        val ring = rewardsSnapshot?.ring
        label.text = when {
            ring == null -> "攒币\n同步中"
            ring.claimable -> "+${ring.rewardCoins}\n全部领取"
            else -> "看剧\n攒宝石"
        }
    }

    private fun createRingCard(): View {
        val ring = rewardsSnapshot?.ring
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedDrawable(COLOR_CARD, 18)
            val circle = TextView(this@PartnerDramaActivity).apply {
                text = if (ring?.claimable == true) "+${ring.rewardCoins}" else "攒宝石"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(5), if (ring?.claimable == true) COLOR_ACCENT else COLOR_PURPLE); setColor(COLOR_SURFACE) }
            }
            addView(circle, LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(LinearLayout(this@PartnerDramaActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@PartnerDramaActivity).apply { text = "追剧宝石"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
                addView(TextView(this@PartnerDramaActivity).apply {
                    text = ring?.let { if (it.claimable) "已累计 ${it.rewardCoins} 宝石，完整观看一次激励广告后全部领取" else "每完成 1 集有效观看即可积累宝石" } ?: "连接奖励服务后显示追剧奖励"
                    textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@PartnerDramaActivity).apply {
                text = if (ring?.claimable == true) "全部领取" else "继续追剧"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
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

    private fun showEpisodeRewardClaimDialog() {
        val snapshot = rewardsSnapshot ?: return
        val ring = snapshot.ring
        if (!ring.claimable || ring.rewardCoins <= 0) { toast("当前没有待领取的追剧宝石"); return }
        AlertDialog.Builder(this)
            .setTitle("领取全部追剧宝石")
            .setMessage("你已累计 ${ring.rewardCoins} 宝石。完整观看一条激励广告后将一次性领取全部待领取宝石；已提前开启翻倍的分集会按双倍结算。")
            .setNegativeButton("稍后领取", null)
            .setPositiveButton("看激励广告并领取") { _, _ -> requestRewardedClaimAllEpisodeRewards() }
            .show()
    }

    private fun requestEpisodeRewardBoost(reward: RewardsApiClient.EpisodeReward) {
        AlertDialog.Builder(this)
            .setTitle("本集宝石翻倍")
            .setMessage("完整观看一条激励广告后，本集 ${reward.rewardCoins} 宝石将在稍后全部领取时按双倍结算。每集仅可翻倍一次。")
            .setNegativeButton("暂不翻倍", null)
            .setPositiveButton("看广告翻倍") { _, _ ->
                requestRewardedAction("rv_episode_reward_boost", targetId = reward.episodeId, onSettled = { action ->
                    applyRewardsSnapshot(action.snapshot)
                    resumeAfterFullscreenAdIfNeeded()
                    toast("本集已开启翻倍，领取时将按双倍宝石结算")
                })
            }
            .show()
    }

    private fun requestRewardedClaimAllEpisodeRewards() {
        requestRewardedAction("rv_episode_rewards_claim", onSettled = { authorization ->
            val adEventId = authorization.adEventId
            if (adEventId.isNullOrBlank()) {
                resumeAfterFullscreenAdIfNeeded()
                toast("领取授权未生成，请稍后重试")
                return@requestRewardedAction
            }
            rewardsApi.claimAllEpisodeRewards(anonymousInstallationId(), adEventId, { action -> runOnUiThread {
                applyRewardsSnapshot(action.snapshot)
                resumeAfterFullscreenAdIfNeeded()
                toast(if (action.idempotent) "当前没有待领取宝石" else "已领取全部 ${action.gemsAdded} 宝石")
            } }, { message -> runOnUiThread {
                resumeAfterFullscreenAdIfNeeded()
                toast("领取失败：$message")
            } })
        })
    }

    private fun createCheckinCard(): View {
        val snapshot = rewardsSnapshot
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedDrawable(0xFF242036.toInt(), 18)
            addView(TextView(this@PartnerDramaActivity).apply { text = "连续签到奖励"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = snapshot?.let {
                    if (it.checkinSignedToday) "今天已签到 · 已连续签到 ${it.checkinStreak} 天"
                    else "今天是第 ${it.checkinTodayIndex} 天 · 可领 ${it.checkinNextRewardCoins} 宝石"
                } ?: "独立积分服务同步后将显示签到进度"
                textSize = 13f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(6), 0, dp(13))
            })
            addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
            val row = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this@PartnerDramaActivity).apply {
                text = if (snapshot?.checkinSignedToday == true) "今日已签到" else "看广告签到"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedDrawable(if (snapshot?.checkinSignedToday == true) COLOR_SURFACE else COLOR_ACCENT, 14)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { if (snapshot?.checkinSignedToday != true) completeDailyCheckin() }
                isClickable = snapshot != null && snapshot.checkinSignedToday != true
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            if (snapshot?.checkinSignedToday == true) {
                row.addView(TextView(this@PartnerDramaActivity).apply {
                    text = "看广告开宝箱"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(COLOR_ACCENT)
                    background = roundedDrawable(0x22FF5D69, 14)
                    setOnClickListener { requestRandomChestOpen() }
                    isClickable = true
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(10) })
            }
            addView(row)
        }
    }

    private fun createWelcomeRewardCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = gradientDrawable(intArrayOf(0xFFB05A23.toInt(), 0xFF5C2A6E.toInt()), 20)
        addView(TextView(this@PartnerDramaActivity).apply { text = "新人欢迎奖励"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = "首次加入可领取 ${snapshot.welcome.gems} 宝石。完整观看一条激励广告后即可到账。"
            textSize = 13f; setTextColor(0xECFFFFFF.toInt()); setPadding(0, dp(6), 0, dp(13))
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = "看广告领取欢迎奖励"
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(0xFF542012.toInt())
            background = roundedDrawable(0xFFFFE7A0.toInt(), 14); setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
            setOnClickListener { claimWelcomeBonus() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    }

    private fun claimWelcomeBonus() {
        requestRewardedAction("rv_welcome_bonus", onSettled = { action ->
            applyRewardsSnapshot(action.snapshot)
            resumeAfterFullscreenAdIfNeeded()
            toast(if (action.idempotent) "欢迎奖励已领取" else "欢迎奖励到账：${action.gemsAdded} 宝石")
        })
    }

    private fun createRewardsWalletSummary(): View {
        val wallet = rewardsSnapshot?.wallet
        if (wallet == null) return createInlineNotice("正在同步独立积分账本，连接成功后将展示宝石、兑换与现金释放进度。")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = gradientDrawable(intArrayOf(0xFF40317A.toInt(), 0xFF1F1936.toInt()), 22)
            addView(TextView(this@PartnerDramaActivity).apply { text = "我的钱包"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })

            val balances = LinearLayout(this@PartnerDramaActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                "宝石" to wallet.coins.toString(),
                "钱包余额" to "\$${wallet.lockedBalance}",
                "可提现余额" to "\$${wallet.availableBalance}",
            ).forEachIndexed { index, item ->
                balances.addView(LinearLayout(this@PartnerDramaActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(10), dp(8), dp(10))
                    background = roundedDrawable(0x2AFFFFFF, 14)
                    addView(TextView(this@PartnerDramaActivity).apply { text = item.first; textSize = 11f; setTextColor(0xDFFFFFFF.toInt()) })
                    addView(TextView(this@PartnerDramaActivity).apply { text = item.second; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFDB6A.toInt()); setPadding(0, dp(4), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(7) })
            }
            addView(balances)
            val release = wallet.release
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "预计明日可转入可提现余额 \$${release.expectedTomorrowTransfer}"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(16), 0, dp(5))
            })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = if (release.boostAvailable) "看广告预计额外转入 \$${release.expectedBoostTransfer} 到可提现余额（当日基础转入的 ${(release.boostPercent * 100).toInt()}%）" else "今日暂无可广告加速的转入额度"
                textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(0), 0, dp(12))
            })
            val actions = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            actions.addView(TextView(this@PartnerDramaActivity).apply {
                text = "兑换现金奖励"
                textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = roundedDrawable(COLOR_ACCENT, 14); setOnClickListener { showGemsExchangeDialog(wallet) }; isClickable = true
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            actions.addView(TextView(this@PartnerDramaActivity).apply {
                text = "广告加速"
                textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFFFFDB6A.toInt())
                background = roundedDrawable(0x30FFFFFF, 14)
                setOnClickListener { showWalletReleaseBoostDialog() }
                isClickable = release.boostAvailable
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(9) })
            addView(actions)
            addView(TextView(this@PartnerDramaActivity).apply {
                text = rewardsSnapshot?.walletEligibilityCopy.orEmpty()
                textSize = 12f; setTextColor(COLOR_TEXT_MUTED); gravity = Gravity.CENTER; setPadding(0, dp(13), 0, 0)
                setOnClickListener { showWalletRulesDialog(wallet) }; isClickable = true
            })
        }
    }

    private fun showWalletReleaseBoostDialog() {
        val release = rewardsSnapshot?.wallet?.release ?: return
        if (!release.boostAvailable) { toast("今日暂无可广告加速的转入额度"); return }
        AlertDialog.Builder(this)
            .setTitle("广告加速转入")
            .setMessage("完整观看一条激励广告后，预计额外转入 \$${release.expectedBoostTransfer} 到可提现余额（当日基础转入的 ${(release.boostPercent * 100).toInt()}%）。")
            .setNegativeButton("暂不加速", null)
            .setPositiveButton("看广告加速") { _, _ -> requestWalletReleaseBoost() }
            .show()
    }

    private fun requestWalletReleaseBoost() {
        requestRewardedAction("rv_release_boost", onSettled = { action ->
            applyRewardsSnapshot(action.snapshot)
            resumeAfterFullscreenAdIfNeeded()
            val transferred = action.releasedMicros / 1_000_000.0
            val updatedRelease = action.snapshot.wallet.release
            val nextMessage = if (updatedRelease.boostAvailable) {
                "本次已额外转入 \$${String.format(Locale.US, "%.2f", transferred)} 到可提现余额。继续加速预计还可额外转入 \$${updatedRelease.expectedBoostTransfer}。"
            } else {
                "本次已额外转入 \$${String.format(Locale.US, "%.2f", transferred)} 到可提现余额。今日广告加速额度已用完。"
            }
            AlertDialog.Builder(this)
                .setTitle("广告加速完成")
                .setMessage(nextMessage)
                .setNegativeButton("稍后再说", null)
                .setPositiveButton(if (updatedRelease.boostAvailable) "继续加速" else "知道了") { _, _ ->
                    if (updatedRelease.boostAvailable) showWalletReleaseBoostDialog()
                }
                .show()
        })
    }

    private fun showGemsExchangeDialog(wallet: RewardsApiClient.Wallet) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "至少 ${wallet.minimumExchangeGems} 宝石"
            setTextColor(Color.WHITE); setHintTextColor(COLOR_TEXT_MUTED)
        }
        AlertDialog.Builder(this).setTitle("兑换现金奖励").setMessage("兑换后记入钱包余额，系统会按当前规则陆续转入可提现余额。")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("确认兑换") { _, _ ->
                val gems = input.text.toString().trim().toLongOrNull()
                if (gems == null) { toast("请输入有效的宝石数量"); return@setPositiveButton }
                rewardsApi.exchangeGems(anonymousInstallationId(), gems, { snapshot -> runOnUiThread { applyRewardsSnapshot(snapshot); toast("兑换已记入钱包余额") } }, { message -> runOnUiThread { toast(message) } })
            }.show()
    }

    private fun showWalletRulesDialog(wallet: RewardsApiClient.Wallet) {
        AlertDialog.Builder(this).setTitle("钱包与奖励规则").setMessage(
            "宝石可兑换为钱包余额，钱包余额会按当前规则陆续转入可提现余额。\n\n" +
                "提现资格：${rewardsSnapshot?.walletEligibilityCopy.orEmpty()}\n\n" +
                "金额、领取和资格均以服务端账本为准。"
        ).setPositiveButton("知道了", null).show()
    }

    private fun createChestCard(snapshot: RewardsApiClient.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = roundedDrawable(COLOR_CARD, 18)
        addView(TextView(this@PartnerDramaActivity).apply { text = "随机宝箱"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
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
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
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
            addView(TextView(this@PartnerDramaActivity).apply { text = "皇冠之路 · 皇冠已达成"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFDB6A.toInt()) })
            addView(TextView(this@PartnerDramaActivity).apply { text = "青铜、白银、黄金、钻石、皇冠五级目标均已完成，皇冠奖励已由服务端记入宝石账本。"; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(7), 0, 0) })
        } else {
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "当前等级：${current?.title ?: "新手"} · 每集宝石 ${String.format(Locale.US, "%.2f", current?.episodeRewardMultiplier ?: 1f)}x"
                textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "下一等级：${next.title} · 达成后每集宝石 ${String.format(Locale.US, "%.2f", next.episodeRewardMultiplier)}x · 另得 ${next.gems} 宝石"
                textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(6), 0, dp(10))
            })
            addView(ProgressBar(this@PartnerDramaActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = (next.progress * 100).toInt().coerceIn(0, 100)
                progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
                backgroundTintList = ColorStateList.valueOf(0x33333333)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "距离下一等级：有效追剧还差 ${(next.rounds.target - next.rounds.current).coerceAtLeast(0)} 集（其中广告已抵 ${next.adEquivalentRounds} 集）· 签到还差 ${(next.signins.target - next.signins.current).coerceAtLeast(0)} 天 · 激励广告还差 ${(next.rewardedAds.target - next.rewardedAds.current).coerceAtLeast(0)} 次"
                textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(10), 0, dp(9))
            })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "看 ${next.adsPerEpisode} 条激励广告升级（抵 1 集追剧）"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = gradientDrawable(intArrayOf(0xFFFF5D69.toInt(), 0xFFC42048.toInt()), 13); setPadding(dp(12), dp(9), dp(12), dp(9)); isClickable = true
                setOnClickListener { showCrownRewardedUpgradeDialog(next) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(8) })
            addView(TextView(this@PartnerDramaActivity).apply {
                text = "查看 ${next.title} 达成条件"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(0xFFFFDB6A.toInt())
                background = roundedDrawable(COLOR_SURFACE, 13); setPadding(dp(12), dp(9), dp(12), dp(9)); isClickable = true
                setOnClickListener { showCrownRulesDialog(next) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        }
    }

    private fun showCrownRulesDialog(crown: RewardsApiClient.CrownProgress) {
        AlertDialog.Builder(this).setTitle("${crown.title}等级条件").setMessage(
            "完成以下累计条件后，服务端会自动结算对应宝石：\n\n" +
                "• 有效追剧：${crown.rounds.current}/${crown.rounds.target} 集（实际看剧 ${crown.watchedRounds.current} 集，广告等效 ${crown.adEquivalentRounds} 集）\n" +
                "• 广告升级：每完整观看 ${crown.adsPerEpisode} 条激励广告可抵 1 集有效追剧\n" +
                "• 签到天数：${crown.signins.current}/${crown.signins.target} 天\n" +
                "• 激励广告：${crown.rewardedAds.current}/${crown.rewardedAds.target} 次\n\n" +
                "达成奖励：${crown.gems} 宝石；后续每集待领取宝石提升至 ${String.format(Locale.US, "%.2f", crown.episodeRewardMultiplier)}x${if (crown.entitlement.isBlank()) "" else "，并获得 ${crown.entitlement}"}。\n\n" +
                "规则与当前进度均由服务端公开计算，不设置未披露的隐藏条件。"
        ).setPositiveButton("知道了", null).show()
    }

    private fun showCrownRewardedUpgradeDialog(crown: RewardsApiClient.CrownProgress) {
        AlertDialog.Builder(this)
            .setTitle("广告升级皇冠之路")
            .setMessage("完整观看 ${crown.adsPerEpisode} 条激励广告，可按 1 集有效追剧计入 ${crown.title} 成长进度。广告完成后由服务端重新计算等级和单集宝石倍率。")
            .setNegativeButton("暂不升级", null)
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
                    .setTitle("皇冠升级进度")
                    .setMessage("已完成 1/${remaining} 条广告，再看 ${remaining - 1} 条即可获得 1 集等效追剧进度。")
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
        background = gradientDrawable(intArrayOf(0xFF283142.toInt(), 0xFF242036.toInt()), 18)
        addView(TextView(this@PartnerDramaActivity).apply {
            text = "新剧追更挑战"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@PartnerDramaActivity).apply {
            text = "活动演示：活动期间完成指定短剧 5 集有效观看，可获得 120 活动点数"
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(6), 0, dp(12))
        })
        val row = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this@PartnerDramaActivity).apply {
            text = "进度 0/5 · 即将开放"
            textSize = 13f
            setTextColor(0xEFFFFFFF.toInt())
            background = roundedDrawable(COLOR_SURFACE, 12)
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(createPill("+120 点数", COLOR_PURPLE))
        addView(row)
    }

    private fun refreshRewardsSnapshot() {
        if (!rewardsApi.isConfigured || rewardsLoading) return
        rewardsLoading = true
        rewardsApi.bootstrap(anonymousInstallationId(), REWARDS_REGION, { snapshot ->
            runOnUiThread {
                rewardsLoading = false
                applyRewardsSnapshot(snapshot)
            }
        }, { message ->
            runOnUiThread {
                rewardsLoading = false
                logStatus("奖励服务同步失败：$message")
                if (catalogItems.isNotEmpty()) renderActiveTab()
                showStartupCheckinWhenReady()
            }
        })
    }

    private fun applyRewardsSnapshot(snapshot: RewardsApiClient.Snapshot) {
        rewardsSnapshot = snapshot
        refreshFloatingRewardWheels()
        unlockedEpisodeKeys.clear()
        unlockedEpisodeKeys.addAll(snapshot.unlockedEpisodeTargets)
        if (catalogItems.isNotEmpty() && !playerShown) renderActiveTab()
        updateEpisodeStatus()
        showStartupCheckinWhenReady()
    }

    private fun showStartupCheckinWhenReady() {
        if (!startupCheckinPromptRequested || startupCheckinPromptShown || isFinishing || isDestroyed) return
        val snapshot = rewardsSnapshot
        if (snapshot == null && rewardsLoading) return
        startupCheckinPromptRequested = false
        startupCheckinPromptShown = true
        showCheckinRewardDialog(snapshot)
    }

    /** 深色奖励弹窗：七日格子和金额均以服务端快照为准；服务离线时仅展示同步状态，不发放奖励。 */
    private fun showCheckinRewardDialog(snapshot: RewardsApiClient.Snapshot?) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            background = gradientDrawable(intArrayOf(0xFF2A2141.toInt(), COLOR_CARD), 26)
        }
        content.addView(TextView(this).apply {
            text = "连续签到奖励"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> "奖励服务正在同步，连接成功后即可领取"
                snapshot.checkinSignedToday -> "今天已签到 · 已连续签到 ${snapshot.checkinStreak} 天；看广告开启随机宝箱获取更多宝石"
                else -> "今天是第 ${snapshot.checkinTodayIndex} 天 · 看广告签到可得 ${snapshot.checkinNextRewardCoins} 宝石"
            }
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(7), 0, dp(16))
        })
        content.addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> "奖励将在服务连接后按动态规则结算"
                snapshot.checkinSignedToday -> "观看一条激励广告即可尝试开启 1 个随机宝箱"
                else -> "完整观看激励广告后签到：${snapshot.checkinNextRewardCoins} 宝石"
            }
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (snapshot == null) COLOR_TEXT_MUTED else 0xFFFFDB6A.toInt())
            setPadding(0, dp(15), 0, dp(12))
        })
        val primary = TextView(this).apply {
            text = when {
                snapshot == null -> "正在同步签到状态"
                snapshot.checkinSignedToday -> "看广告开宝箱"
                else -> "看广告签到"
            }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
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
            text = "稍后再说"
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
                        text = if (isToday && !signedToday) "今日" else "第${index + 1}天"
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) Color.WHITE else COLOR_TEXT_MUTED)
                    })
                    addView(TextView(this@PartnerDramaActivity).apply {
                        text = if (isDone) "✓" else if (isToday) "领" else "○"
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) 0xFFFFDB6A.toInt() else COLOR_TEXT_MUTED)
                        setPadding(0, dp(3), 0, dp(2))
                    })
                    addView(TextView(this@PartnerDramaActivity).apply {
                        text = if (snapshot == null) "同步中" else "+$coins"
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

    /** 全屏广告展示时暂停原视频；Pangle 回调已保证在广告关闭后才会进入这里的结果分支。 */
    private fun requestRewardedAction(
        placementId: String,
        targetId: String? = null,
        episodeIndex: Int? = null,
        onSettled: ((RewardsApiClient.RewardAction) -> Unit)? = null,
        onSettlementUnavailable: ((String) -> Unit)? = null,
    ) {
        if (fullscreenAdInProgress) { toast("广告正在展示，请稍候"); return }
        if (consentStore.read() != PangleConsent.GRANTED) { toast("请先在广告设置中启用广告"); return }
        if (!rewardsApi.isConfigured) { toast("独立积分服务尚未配置，广告奖励无法结算"); return }
        fullscreenAdInProgress = true
        pauseForFullscreenAd()
        pangleAds.showRewarded(this) { result ->
            when (result) {
                PangleAdsManager.RewardedResult.RewardEarned -> {
                    rewardsApi.completeRewarded(
                        userId = anonymousInstallationId(),
                        placementId = placementId,
                        targetId = targetId,
                        episodeIndex = episodeIndex,
                        proof = testRewardProof(),
                        onSuccess = { action -> runOnUiThread {
                            fullscreenAdInProgress = false
                            applyRewardsSnapshot(action.snapshot)
                            if (onSettled != null) {
                                onSettled.invoke(action)
                            } else {
                                resumeAfterFullscreenAdIfNeeded()
                                toast("已结算 ${action.gemsAdded} 宝石")
                            }
                        } },
                        onFailure = { message -> runOnUiThread {
                            fullscreenAdInProgress = false
                            if (onSettlementUnavailable != null) {
                                onSettlementUnavailable.invoke(message)
                            } else {
                                episodeTransitionInProgress = false
                                resumeAfterFullscreenAdIfNeeded()
                                toast("广告已完整观看，奖励状态暂未同步；请稍后在奖励中心查看。")
                                logStatus("广告奖励同步失败：$message")
                            }
                        } },
                    )
                }
                PangleAdsManager.RewardedResult.Cancelled -> {
                    fullscreenAdInProgress = false
                    episodeTransitionInProgress = false
                    resumeAfterFullscreenAdIfNeeded()
                    toast("广告未完整观看，未获得奖励或解锁权益")
                }
                is PangleAdsManager.RewardedResult.Unavailable -> {
                    fullscreenAdInProgress = false
                    episodeTransitionInProgress = false
                    resumeAfterFullscreenAdIfNeeded()
                    toast("奖励广告暂不可用：${result.reason}")
                }
                is PangleAdsManager.RewardedResult.Failed -> {
                    fullscreenAdInProgress = false
                    episodeTransitionInProgress = false
                    resumeAfterFullscreenAdIfNeeded()
                    toast("奖励广告加载失败（${result.code}）")
                }
            }
        }
    }

    private fun pauseForFullscreenAd() {
        resumeAfterFullscreenAd = exoPlayer?.isPlaying == true
        exoPlayer?.pause()
        playerIsPaused = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun resumeAfterFullscreenAdIfNeeded() {
        if (resumeAfterFullscreenAd && !isFinishing && !isDestroyed) {
            exoPlayer?.play()
            playerIsPaused = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        resumeAfterFullscreenAd = false
    }

    private fun showPlaybackActionInterstitial(
        placementId: String,
        onShown: () -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        if (fullscreenAdInProgress) { onUnavailable("广告正在展示"); return }
        if (consentStore.read() != PangleConsent.GRANTED) { onUnavailable("请先在广告设置中启用广告"); return }
        fullscreenAdInProgress = true
        pauseForFullscreenAd()
        pangleAds.showInterstitialForPlaybackAction(this) { result -> runOnUiThread {
            fullscreenAdInProgress = false
            when (result) {
                PangleAdsManager.FullscreenResult.Shown -> {
                    val settled = {
                        onShown()
                    }
                    if (rewardsApi.isConfigured) {
                        rewardsApi.recordInterstitial(anonymousInstallationId(), placementId,
                            onSuccess = { snapshot -> runOnUiThread { applyRewardsSnapshot(snapshot); settled() } },
                            onFailure = { message -> runOnUiThread { logStatus("插屏展示记录未同步：$message"); settled() } },
                        )
                    } else settled()
                }
                is PangleAdsManager.FullscreenResult.Skipped -> onUnavailable(result.reason)
                is PangleAdsManager.FullscreenResult.Failed -> onUnavailable("加载失败（${result.code}）")
            }
        } }
    }

    private fun showRewardLedgerHint() {
        val snapshot = rewardsSnapshot
        val message = snapshot?.let { "当前规则版本：${it.ruleVersion}。最近权益由服务端账本保存，可通过管理接口查看。" } ?: "奖励服务尚未连接。"
        AlertDialog.Builder(this).setTitle("奖励流水").setMessage(message).setPositiveButton("知道了", null).show()
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
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(5))
        })
        val info = LinearLayout(this@PartnerDramaActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        // PSSDK 的 language 为内容元数据（例如 zh_hant），并非可直接展示的本地化文案。
        // 在建立完整的语言映射与剧集配音/字幕能力前，不向用户展示该原始代码。
        info.addView(createPill("${drama.total.coerceAtLeast(1)} 集", COLOR_ACCENT))
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
            renderLoading("正在加载剧集详情…")
            localDramaApi.fetchDrama(
                baseUrl = localContentBaseUrl(),
                dramaId = drama.id,
                region = CONTENT_REGION,
                userId = anonymousInstallationId(),
                onSuccess = { detail -> runOnUiThread { showPlayer(detail, startIndex, resumePositionSeconds) } },
                onFailure = { message -> runOnUiThread { renderFailure("剧集详情加载失败：$message") } },
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
        root.addView(createFloatingRewardWheel(compact = true), FrameLayout.LayoutParams(dp(96), dp(132), Gravity.END or Gravity.CENTER_VERTICAL).apply {
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
            text = "▶\n继续播放"
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
            contentDescription = "继续播放"
            isClickable = true
            setOnClickListener { resumePlaybackFromPauseOverlay() }
        }
        chrome.addView(playerPauseOverlay, FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER))
        chrome.addView(transparentPlayerIcon("‹", "返回首页") { showCatalog() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(28)
            marginStart = dp(14)
        })

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
        titleRow.addView(transparentPlayerTextButton("简介", "展开剧集简介") { togglePlayerDescription() }, LinearLayout.LayoutParams(dp(54), dp(32)))
        controls.addView(titleRow)
        playerDescriptionView = TextView(this).apply {
            text = selectedDrama?.desc?.trim().orEmpty().ifBlank { "暂无剧集简介。" }
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

        playerControlsRevealButton = transparentPlayerTextButton("显示控制", "显示播放控制") { setPlayerControlsVisible(true) }.also { it.visibility = View.GONE }
        chrome.addView(playerControlsRevealButton, FrameLayout.LayoutParams(dp(74), dp(38), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(14)
            marginEnd = dp(14)
        })
        updateEpisodeStatus()
        updatePlayerProgress(0, 0)
        return chrome
    }

    private fun createPlayerGestureLayer(): View {
        var downX = 0f
        var downY = 0f
        val swipeThreshold = dp(56).toFloat()
        val tapThreshold = dp(12).toFloat()
        return View(this).apply {
            contentDescription = "轻点播放或暂停，上滑下一集"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaX = event.x - downX
                        val deltaY = event.y - downY
                        when {
                            deltaY <= -swipeThreshold && kotlin.math.abs(deltaX) < swipeThreshold -> {
                                triggerSwipeNextEpisode()
                            }
                            kotlin.math.abs(deltaX) <= tapThreshold && kotlin.math.abs(deltaY) <= tapThreshold -> {
                                togglePlaybackFromVideoTap()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
    }

    private fun triggerSwipeNextEpisode() {
        val now = System.currentTimeMillis()
        if (now - lastSwipeNextAtMillis < 700L) return
        lastSwipeNextAtMillis = now
        startNextEpisodeAutomatically(currentEpisodeIndex, userInitiated = true)
    }

    private fun requestMidEpisodeInterstitialIfEligible(positionSeconds: Int) {
        val snapshot = rewardsSnapshot ?: return
        val policy = snapshot.interstitial
        val episodeKey = episodeKey(currentEpisodeIndex)
        if (!playerShown || playerIsPaused || fullscreenAdInProgress || !policy.midEpisodeEnabled) return
        if (positionSeconds < policy.midEpisodeTriggerSeconds || !midEpisodeInterstitialRequestedEpisodeKeys.add(episodeKey)) return
        showPlaybackActionInterstitial("int_episode_midroll", onShown = {
            resumeAfterFullscreenAdIfNeeded()
        }, onUnavailable = { message ->
            logStatus("剧中插屏未展示：$message")
            resumeAfterFullscreenAdIfNeeded()
        })
    }

    private fun completeSeekAndResume(targetSeconds: Int) {
        val player = exoPlayer
        if (player == null || player.playbackState != Player.STATE_READY) {
            playerIsSeeking = false
            toast("播放器尚未就绪，请重新加载本集后再拖动")
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
            text = "拖动跳转"
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
                    if (policy?.seekEnabled == true && !seekInterstitialInProgress) {
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
            Triple("选集", "选择剧集") { toggleEpisodePicker() },
            Triple("1x", "播放速度") { showPlaybackSpeedPicker() },
            Triple("更多", "更多播放设置") { showPlaybackMoreControls() },
            Triple(if (isFavorite()) "♥" else "♡", "收藏") { toggleFavorite() },
            Triple("隐藏", "隐藏播放控制") { setPlayerControlsVisible(false) },
        )
        actions.forEachIndexed { index, action ->
            val button = if (action.first == "选集" || action.first == "更多" || action.first == "隐藏") {
                transparentPlayerTextButton(action.first, action.second, action.third)
            } else {
                transparentPlayerIcon(action.first, action.second, action.third)
            }
            if (action.first == "1x") speedButton = button
            if (action.second == "收藏") favoriteButton = button
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
            .setTitle("播放速度")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val applySpeed = {
                    playerSpeed = speeds[which]
                    exoPlayer?.setPlaybackSpeed(playerSpeed)
                    speedButton?.text = labels[which]
                    resumeAfterFullscreenAdIfNeeded()
                }
                dialog.dismiss()
                val policy = rewardsSnapshot?.interstitial
                if (policy?.speedEnabled == true) {
                    showPlaybackActionInterstitial("int_speed", onShown = applySpeed, onUnavailable = { message ->
                        logStatus("倍速插屏未展示：$message")
                        applySpeed()
                    })
                } else {
                    applySpeed()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPlaybackMoreControls() {
        val muteLabel = if (playerMuted) "恢复原声" else "静音"
        AlertDialog.Builder(this)
            .setTitle("播放设置")
            .setItems(arrayOf(muteLabel, "调高亮度", "恢复系统亮度", "进入沉浸模式", "重新加载本集")) { _, which ->
                when (which) {
                    0 -> {
                        playerMuted = !playerMuted
                        exoPlayer?.volume = if (playerMuted) 0f else 1f
                        toast(if (playerMuted) "已静音" else "已恢复原声")
                    }
                    1 -> {
                        window.attributes = window.attributes.apply { screenBrightness = 1f }
                        toast("亮度已调高")
                    }
                    2 -> {
                        window.attributes = window.attributes.apply { screenBrightness = -1f }
                        toast("已恢复系统亮度")
                    }
                    3 -> enterImmersivePlayerMode()
                    4 -> selectEpisode(currentEpisodeIndex)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun enterImmersivePlayerMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setPlayerControlsVisible(false)
        toast("已进入沉浸式播放，向下滑动或点击控制按钮可退出")
    }

    private fun exitImmersivePlayerMode() {
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
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
            text = "选集"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "收起 ⌄"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { sheet.dismiss() }
            isClickable = true
        })
        container.addView(titleRow)
        container.addView(TextView(this).apply {
            text = "前 ${rewardsSnapshot?.unlock?.freeEpisodesDefault ?: FREE_EPISODES} 集可免费观看，后续集数按服务端规则解锁"
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
            if (userInitiated) toast("已经是最后一集") else toast("本剧已播放完成")
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
            .setTitle("免费集数已用完")
            .setMessage("第 ${index + 1} 集需要完整观看一条激励广告解锁。广告结束后会自动开始播放本集。")
            .setNegativeButton("稍后再看") { _, _ ->
                episodeTransitionInProgress = false
                exoPlayer?.pause()
                playerIsPaused = true
                updatePauseOverlayVisibility(true)
            }
            .setPositiveButton("看激励广告解锁") { _, _ -> startEpisodeUnlockAfterConfirmation(index) }
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
                unlockEpisodeForCurrentSession(index, "第 ${index + 1} 集已解锁")
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
                        toast("本集奖励 +${reward.episodeRewardCoins} 宝石待领取，点击悬浮球观看激励广告后全部领取")
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
        AlertDialog.Builder(this)
            .setTitle("恭喜获得 ${createdCount} 个随机宝箱")
            .setMessage("当前共有 ${availableCount} 个可开启宝箱。每开启 1 个需要完整观看一条激励广告，是否现在开启？")
            .setNegativeButton("继续观看") { _, _ -> onContinuePlayback() }
            .setPositiveButton("立即开启") { _, _ -> requestRandomChestOpen(onFinished = onContinuePlayback) }
            .show()
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
        if (consentStore.read() != PangleConsent.GRANTED) return

        pauseNativeAdInProgress = true
        pangleAds.showPauseNativeAd(
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
        val lockText = if (needsUnlock(currentEpisodeIndex)) " · ${rewardsSnapshot?.unlock?.title ?: "观看广告解锁"}" else " · 可观看"
        episodeStatusView?.text = "第 ${currentEpisodeIndex + 1}/${drama.total.coerceAtLeast(1)} 集$lockText"
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
            .setNegativeButton("取消", null)
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
            .setMessage("请输入运行 PostgreSQL 奖励服务的电脑局域网地址。它与短剧内容服务使用不同端口，默认示例为 8100。")
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
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAdPrivacySettings() {
        val granted = consentStore.read() == PangleConsent.GRANTED
        AlertDialog.Builder(this)
            .setTitle("广告设置")
            .setMessage(if (granted) "你已同意广告。开屏和插屏可能在合适时机展示；锁定剧集可通过激励广告解锁。" else "你当前未同意广告。免费内容可正常观看，但广告解锁不可用。")
            .setPositiveButton(if (granted) "关闭广告" else "启用广告") { _, _ ->
                consentStore.save(if (granted) PangleConsent.DECLINED else PangleConsent.GRANTED)
                appOpenAttempted = false
                if (!granted) maybeShowAppOpenAd()
                toast(if (granted) "已关闭广告" else "已启用广告")
            }
            .setNegativeButton("取消", null)
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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        column.addView(TextView(this).apply {
            text = "J"
            textSize = 38f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(COLOR_ACCENT, COLOR_PURPLE), 28)
        }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(TextView(this).apply {
            text = "JoyeDrama"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, dp(4))
        })
        column.addView(TextView(this).apply {
            text = "精彩短剧，即刻开追"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
        })
        column.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(COLOR_ACCENT)
            setPadding(0, dp(28), 0, 0)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderLoading(message: String) {
        playerShown = false
        root.removeAllViews()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        column.addView(ProgressBar(this))
        column.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
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
            text = "暂时无法连接内容服务"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        column.addView(TextView(this).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(12), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = "请确认 Windows 服务已启动，并填入电脑的局域网 IPv4 地址。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(Button(this).apply {
            text = "设置服务器地址"
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
            setTextColor(Color.WHITE)
            setOnClickListener { showLocalServerSettings() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(22) })
        column.addView(Button(this).apply {
            text = "重试"
            isAllCaps = false
            setOnClickListener { loadLocalCatalog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        column.addView(TextView(this).apply {
            text = "恢复默认地址"
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
    private fun testRewardProof(): String = if (BuildConfig.DEBUG) "TEST_PANGLE_REWARD" else ""

    private fun rewardsBaseUrl(): String = serverPreferences
        .getString(REWARDS_SERVER_BASE_URL_KEY, null)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: BuildConfig.REWARDS_API_BASE_URL.trim().trimEnd('/')

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

    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun logStatus(message: String) = android.util.Log.i(TAG, message)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val FREE_EPISODES = 3
        private const val CONTENT_REGION = "SG"
        private const val REWARDS_REGION = CONTENT_REGION
        private const val PREFERENCES = "joyedrama.short_drama"
        private const val PLAYBACK_HISTORY_PREFERENCES = "joyedrama.playback_history"
        private const val SERVER_PREFERENCES = "dramaflow.local_content"
        private const val SERVER_BASE_URL_KEY = "base_url"
        private const val REWARDS_SERVER_BASE_URL_KEY = "rewards_base_url"
        private const val INSTALLATION_KEY = "anonymous_installation_id"
        private const val TAG = "JoyeDramaPlayer"

        private const val COLOR_BACKGROUND = 0xFF12101A.toInt()
        private const val COLOR_CARD = 0xFF211C2B.toInt()
        private const val COLOR_SURFACE = 0xFF332A40.toInt()
        private const val COLOR_ACCENT = 0xFFFF5D69.toInt()
        private const val COLOR_PURPLE = 0xFF6847D8.toInt()
        private const val COLOR_TEXT_MUTED = 0xFFBFB2C7.toInt()

        fun intent(context: Context): Intent = Intent(context, PartnerDramaActivity::class.java)
    }
}
