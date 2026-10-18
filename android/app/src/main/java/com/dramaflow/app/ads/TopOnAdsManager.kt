package com.dramaflow.app.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.analytics.EngagementAnalytics
import com.dramaflow.app.analytics.UserBehaviorEventReporter
import com.dramaflow.app.content.PssdkRevenueReporter
import com.secmtp.sdk.core.api.ATAdInfo
import com.secmtp.sdk.core.api.ATAdRevenueListener
import com.secmtp.sdk.core.api.ATNetworkConfirmInfo
import com.secmtp.sdk.core.api.ATNetworkConfig
import com.secmtp.sdk.core.api.ATSDK
import com.secmtp.sdk.core.api.ATSDKInitListener
import com.secmtp.sdk.core.api.ATShowConfig
import com.secmtp.sdk.core.api.AdError
import com.secmtp.sdk.interstitial.api.ATInterstitial
import com.secmtp.sdk.interstitial.api.ATInterstitialExListener
import com.secmtp.sdk.rewardvideo.api.ATRewardVideoAd
import com.secmtp.sdk.rewardvideo.api.ATRewardVideoExListener
import com.secmtp.sdk.splashad.api.ATSplashAd
import com.secmtp.sdk.splashad.api.ATSplashAdExtraInfo
import com.secmtp.sdk.splashad.api.ATSplashExListener

/**
 * TopOn 聚合广告唯一调用入口。
 *
 * v8 奖励服务仍只相信服务端激励事件；本类只在 TopOn 奖励回调后把“完整观看”交给
 * 业务层。广告展示时同时触发 AppsFlyer、Firebase 与炫羽测试服务器上报。
 */
class TopOnAdsManager(
    private val applicationContext: Context,
    private val settings: TopOnSettings,
    private val analytics: EngagementAnalytics = EngagementAnalytics.get(applicationContext),
    private val adMediationReporter: AdMediationEventReporter? = null,
    private val rewardedPolicy: RewardedAdPolicy = RewardedAdPolicy(applicationContext),
    private val interstitialPolicy: FullscreenAdPolicy = FullscreenAdPolicy(
        applicationContext,
        key = "interstitial_last_show",
        minIntervalMillis = if (BuildConfig.DEBUG) 10_000L else 60_000L,
    ),
    private val episodeUnlockPolicy: FullscreenAdPolicy = FullscreenAdPolicy(
        applicationContext,
        key = "episode_unlock_last_show",
        minIntervalMillis = 0L,
    ),
    private val playbackActionPolicy: FullscreenAdPolicy = FullscreenAdPolicy(
        applicationContext,
        key = "playback_action_last_show",
        minIntervalMillis = 0L,
    ),
    private val splashPolicy: FullscreenAdPolicy = FullscreenAdPolicy(
        applicationContext,
        key = "splash_last_show",
        minIntervalMillis = 0L,
    ),
    /** A short shared guard prevents different full-screen entry points from chaining ads. */
    private val globalFullscreenPolicy: FullscreenAdPolicy = FullscreenAdPolicy(
        applicationContext,
        key = "fullscreen_last_show",
        minIntervalMillis = GLOBAL_FULLSCREEN_MIN_INTERVAL_MS,
    ),
) {
    sealed interface Initialization {
        data object Ready : Initialization
        data object NotConfigured : Initialization
        data class Failed(val code: Int, val message: String) : Initialization
    }

    sealed interface RewardedResult {
        data object RewardEarned : RewardedResult
        data object Cancelled : RewardedResult
        data class Unavailable(val reason: String) : RewardedResult
        data class Failed(val code: Int, val message: String) : RewardedResult
    }

    sealed interface FullscreenResult {
        data object Shown : FullscreenResult
        data class Skipped(val reason: String) : FullscreenResult
        data class Failed(val code: Int, val message: String) : FullscreenResult
    }

    private enum class RewardState { NOT_REWARDED, REWARDED, REWARD_FAILED }

    private var rewardedRequestInFlight = false
    private var interstitialRequestInFlight = false
    private var splashRequestInFlight = false
    private var topOnInitialized = false
    private var topOnInitializationInFlight = false
    private val pendingInitializationCallbacks = mutableListOf<(Initialization) -> Unit>()
    private val initializationLock = Any()
    private var initializationGeneration = 0L
    private var initializationTimeout: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Keeps TopOn ads out of edge-to-edge host mode; creative close controls remain ad-network owned. */
    private val safeAdWindows = mutableMapOf<Activity, SafeAdWindowState>()
    /** Activity-level window mode is owned by the presentation layer and must survive a full-screen ad. */
    private val hostEdgeToEdge = mutableMapOf<Activity, Boolean>()
    /** Local, shareable record for reconciling returned ADN with the mediation dashboard. */
    private val adNetworkAudit = AdNetworkAuditLog.get(applicationContext)

    private data class SafeAdWindowState(
        val statusBarColor: Int,
        val navigationBarColor: Int,
        val cutoutMode: Int,
        val edgeToEdge: Boolean,
        val lightStatusBars: Boolean,
        val lightNavigationBars: Boolean,
    )

    /** Records the application surface mode before an ad temporarily switches to a safe system-bar window. */
    fun setHostEdgeToEdge(activity: Activity, enabled: Boolean) {
        hostEdgeToEdge[activity] = enabled
        WindowCompat.setDecorFitsSystemWindows(activity.window, !enabled)
    }

    /**
     * TopOn 初始化会异步拉取聚合配置。必须等 [ATSDKInitListener.onSuccess] 后再请求开屏，
     * 否则冷启动时可能在配置尚未就绪前出现无填充或不展示。
     */
    fun initialize(onResult: (Initialization) -> Unit) {
        runCatching { adNetworkAudit.recordConfiguration(settings) }
        if (!settings.isConfigured) {
            runCatching { auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "NOT_CONFIGURED") }
            onResult(Initialization.NotConfigured)
            return
        }

        var startInitialization = false
        var attempt = 0L
        synchronized(initializationLock) {
            if (topOnInitialized) {
                mainHandler.post { onResult(Initialization.Ready) }
                return
            }
            pendingInitializationCallbacks += onResult
            if (!topOnInitializationInFlight) {
                topOnInitializationInFlight = true
                initializationGeneration += 1L
                attempt = initializationGeneration
                startInitialization = true
            }
        }
        if (!startInitialization) return

        val timeout = Runnable {
            completeInitialization(attempt, Initialization.Failed(-408, "TopOn initialization timed out"))
        }
        synchronized(initializationLock) { initializationTimeout = timeout }
        mainHandler.postDelayed(timeout, 8_000L)
        runCatching {
            auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "STARTED")
            ATSDK.setNetworkLogDebug(settings.enableNetworkDebug)
            ATSDK.init(
                applicationContext,
                settings.appId,
                settings.appKey,
                ATNetworkConfig(),
                object : ATSDKInitListener {
                    override fun onSuccess() = completeInitialization(attempt, Initialization.Ready)

                    override fun onFail(errorMessage: String) =
                        completeInitialization(attempt, Initialization.Failed(-1, errorMessage))
                },
            )
        }.onFailure {
            Log.w(TAG, "TopOn initialization invocation failed", it)
            runCatching { auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "FAILED", errorCode = -1, detail = "initialization_exception") }
            completeInitialization(attempt, Initialization.Failed(-1, it.message.orEmpty()))
        }
    }

    private fun completeInitialization(attempt: Long, result: Initialization) {
        val callbacks = synchronized(initializationLock) {
            // Only the current attempt may release waiters. A late SDK callback must not
            // consume callbacks belonging to a retry or revive an already timed-out request.
            if (!topOnInitializationInFlight || attempt != initializationGeneration) return
            topOnInitialized = result is Initialization.Ready
            topOnInitializationInFlight = false
            initializationTimeout?.let { mainHandler.removeCallbacks(it) }
            initializationTimeout = null
            pendingInitializationCallbacks.toList().also { pendingInitializationCallbacks.clear() }
        }
        runCatching {
            when (result) {
                Initialization.Ready -> {
                    auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "READY")
                    UserBehaviorEventReporter.get(applicationContext).record("SDK", "TOPON_INITIALIZED")
                }
                Initialization.NotConfigured -> Unit
                is Initialization.Failed -> {
                    auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "FAILED", errorCode = result.code, detail = "sdk_initialization_failed")
                    UserBehaviorEventReporter.get(applicationContext).record(
                        "SDK",
                        "TOPON_INITIALIZATION_FAILED",
                        mapOf("error_code" to result.code),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "TopOn initialization audit failed", it) }
        mainHandler.post {
            callbacks.forEach { callback ->
                runCatching { callback(result) }.onFailure { Log.w(TAG, "TopOn initialization consumer failed", it) }
            }
        }
    }

    fun showRewarded(
        activity: Activity,
        logicalPlacement: String = "rewarded",
        onWillShow: () -> Unit = {},
        onAdClicked: () -> Unit = {},
        onRewardVerified: () -> Unit = {},
        onResult: (RewardedResult) -> Unit,
    ) {
        adNetworkAudit.recordConfiguration(settings)
        if (!settings.canShowRewarded) {
            auditEvent("REQUEST", "rewarded", settings.rewardedPlacementId, "rewarded", result = "NOT_CONFIGURED")
            onResult(RewardedResult.Unavailable("尚未配置 TopOn 广告参数"))
            return
        }
        if (rewardedRequestInFlight) {
            auditEvent("REQUEST", "rewarded", settings.rewardedPlacementId, "rewarded", result = "BLOCKED_IN_FLIGHT")
            onResult(RewardedResult.Unavailable("广告请求正在进行中，请勿重复点击"))
            return
        }
        if (!globalFullscreenPolicy.canShow()) {
            auditEvent("REQUEST", "rewarded", settings.rewardedPlacementId, "rewarded", result = "BLOCKED_GLOBAL_FREQUENCY")
            onResult(RewardedResult.Unavailable("请稍候再观看广告"))
            return
        }
        when (val decision = rewardedPolicy.canStart()) {
            is RewardedAdPolicy.Decision.Blocked -> {
                auditEvent("REQUEST", "rewarded", settings.rewardedPlacementId, "rewarded", result = "BLOCKED_POLICY", detail = decision.message)
                onResult(RewardedResult.Unavailable(decision.message))
                return
            }
            RewardedAdPolicy.Decision.Allowed -> Unit
        }

        rewardedRequestInFlight = true
        auditEvent("REQUEST", "rewarded", settings.rewardedPlacementId, "rewarded", result = "STARTED")
        var delivered = false
        fun deliver(result: RewardedResult) {
            if (delivered) return
            delivered = true
            rewardedRequestInFlight = false
            restoreAppWindowAfterAd(activity)
            onResult(result)
        }

        initialize { init ->
            when (init) {
                Initialization.Ready -> loadAndShowRewarded(activity, logicalPlacement, onWillShow, onAdClicked, onRewardVerified, ::deliver)
                Initialization.NotConfigured -> deliver(RewardedResult.Unavailable("尚未配置 TopOn 广告参数"))
                is Initialization.Failed -> deliver(RewardedResult.Failed(init.code, init.message))
            }
        }
    }

    fun showInterstitialAtNaturalBreak(
        activity: Activity,
        onWillShow: () -> Unit = {},
        onResult: (FullscreenResult) -> Unit,
    ) = showInterstitial(activity, interstitialPolicy, "int_natural_break", onWillShow = onWillShow, onResult = onResult)

    /** 锁集解锁采用独立频控；每个锁定分集均可尝试 TopOn 激励广告，不使用插屏。 */
    fun showInterstitialForEpisodeUnlock(
        activity: Activity,
        onWillShow: () -> Unit = {},
        onResult: (FullscreenResult) -> Unit,
    ) = showInterstitial(activity, episodeUnlockPolicy, "int_episode_unlock_legacy", onWillShow = onWillShow, onResult = onResult)

    /** 适用于剧中、拖拽和倍速等明确操作；服务端仍负责 v8 最终频控。 */
    fun showInterstitialForPlaybackAction(
        activity: Activity,
        logicalPlacement: String = "int_playback_action",
        bypassGlobalFrequencyCap: Boolean = false,
        onWillShow: () -> Unit = {},
        onShown: () -> Unit = {},
        onAdClicked: () -> Unit = {},
        onResult: (FullscreenResult) -> Unit,
    ) = showInterstitial(
        activity = activity,
        policy = playbackActionPolicy,
        logicalPlacement = logicalPlacement,
        bypassGlobalFrequencyCap = bypassGlobalFrequencyCap,
        onWillShow = onWillShow,
        onShown = onShown,
        onAdClicked = onAdClicked,
        onResult = onResult,
    )

    fun showAppOpenIfEligible(activity: Activity, onResult: (FullscreenResult) -> Unit) {
        adNetworkAudit.recordConfiguration(settings)
        if (!settings.canShowSplash) {
            auditEvent("REQUEST", "splash", settings.splashPlacementId, "splash", result = "NOT_CONFIGURED")
            onResult(FullscreenResult.Skipped("尚未配置 TopOn 广告参数"))
            return
        }
        if (splashRequestInFlight) {
            auditEvent("REQUEST", "splash", settings.splashPlacementId, "splash", result = "BLOCKED_IN_FLIGHT")
            onResult(FullscreenResult.Skipped("开屏广告请求正在进行中"))
            return
        }
        if (!splashPolicy.canShow()) {
            auditEvent("REQUEST", "splash", settings.splashPlacementId, "splash", result = "BLOCKED_POLICY")
            onResult(FullscreenResult.Skipped("开屏广告频控中"))
            return
        }
        if (!globalFullscreenPolicy.canShow()) {
            auditEvent("REQUEST", "splash", settings.splashPlacementId, "splash", result = "BLOCKED_GLOBAL_FREQUENCY")
            onResult(FullscreenResult.Skipped("全屏广告冷却中"))
            return
        }
        splashRequestInFlight = true
        auditEvent("REQUEST", "splash", settings.splashPlacementId, "splash", result = "STARTED")
        Log.i(TAG, "Splash request started: mode=${if (settings.isDemoMode) "official-demo" else "configured-network"}, placement=${settings.splashPlacementId}")
        var delivered = false
        // TopOn 可能先回调 onAdLoadTimeout，随后仍回调 onAdLoaded(isTimeout=true)。
        // 一旦启动流程已因超时进入内容页，禁止再展示迟到的开屏，避免与新人/签到窗口叠加。
        var splashRequestExpired = false
        fun deliver(result: FullscreenResult, host: FrameLayout? = null) {
            if (delivered) return
            delivered = true
            splashRequestInFlight = false
            host?.let { (it.parent as? ViewGroup)?.removeView(it) }
            restoreAppWindowAfterAd(activity)
            onResult(result)
        }

        initialize { init ->
            when (init) {
                Initialization.Ready -> {
                    auditEvent("SCENARIO_REACHED", "splash", settings.splashPlacementId, "splash", result = "ENTERED", detail = DEFAULT_SCENARIO_DETAIL)
                    var splash: ATSplashAd? = null
                    var host: FrameLayout? = null
                    fun showSplashIfReady(source: String): Boolean {
                        val currentSplash = splash
                        val ready = currentSplash?.isAdReady == true
                        auditEvent(
                            "READY_CHECK",
                            "splash",
                            settings.splashPlacementId,
                            "splash",
                            result = if (ready) "READY" else "NOT_READY",
                            detail = "$DEFAULT_SCENARIO_DETAIL; source=$source",
                        )
                        if (splashRequestExpired || delivered) return true
                        if (!ready || currentSplash == null) return false
                        host = splashHost(activity)
                        if (host == null) {
                            Log.w(TAG, "Splash cannot show: host=false, ad=true, ready=true")
                            deliver(FullscreenResult.Skipped("TopOn 未返回可展示的开屏广告"), host)
                            return true
                        }
                        prepareSafeWindowForAd(activity)
                        currentSplash.show(activity, requireNotNull(host), null, defaultScenarioShowConfig())
                        return true
                    }
                    splash = ATSplashAd(activity, settings.splashPlacementId, object : ATSplashExListener {
                        override fun onAdLoaded(isTimeout: Boolean) {
                            val ready = splash?.isAdReady == true
                            Log.i(TAG, "Splash loaded: isTimeout=$isTimeout, ready=$ready")
                            auditEvent("LOADED", "splash", settings.splashPlacementId, "splash", result = if (ready) "READY" else "NOT_READY", detail = "timeout_callback=$isTimeout")
                            if (splashRequestExpired || delivered) {
                                Log.i(TAG, "Splash loaded after startup timeout; discarded to keep startup dialogs ordered")
                                return
                            }
                            if (!showSplashIfReady("loaded_callback")) {
                                Log.w(TAG, "Splash loaded callback was not ready for display")
                                deliver(FullscreenResult.Skipped("TopOn 未返回可展示的开屏广告"), host)
                            }
                        }

                        override fun onAdLoadTimeout() {
                            splashRequestExpired = true
                            Log.w(TAG, "Splash load timed out after ${SPLASH_TIMEOUT_MS}ms; startup will continue without a late splash")
                            auditEvent("LOAD_FAILED", "splash", settings.splashPlacementId, "splash", result = "TIMEOUT", detail = "timeout_ms=$SPLASH_TIMEOUT_MS")
                            deliver(FullscreenResult.Skipped("TopOn 开屏广告加载超时"))
                        }

                        override fun onNoAdError(adError: AdError) {
                            Log.w(TAG, "Splash no-ad error: ${adError.fullErrorInfo}")
                            auditEvent("LOAD_FAILED", "splash", settings.splashPlacementId, "splash", result = "NO_AD", errorCode = errorCode(adError), detail = "topon_no_ad")
                            deliver(FullscreenResult.Failed(errorCode(adError), adError.fullErrorInfo.orEmpty()))
                        }

                        override fun onAdShow(entity: ATAdInfo) {
                            Log.i(TAG, "Splash shown: ${entity.adNetworkType}/${entity.adsourceId}")
                            auditAdInfo("SHOWN", "splash", settings.splashPlacementId, "splash", entity, "DISPLAYED")
                            splashPolicy.recordShown()
                            globalFullscreenPolicy.recordShown()
                            // Splash stays out of external impression/revenue reporting: no
                            // analytics.reportTopOnImpression and no TopOnRevenueListener are
                            // attached. auditAdInfo still mirrors SHOWN to the internal Rewards
                            // operations ledger for the advertising-operations dashboard.
                        }

                        override fun onAdClick(entity: ATAdInfo) = Unit

                        // v6.6.21 makes this callback mandatory. Splash never drives rewards in
                        // JoyeDrama, so retain only a local audit trail and no external reporting.
                        override fun onSplashAdReward(entity: ATAdInfo) {
                            auditAdInfo("REWARD_CALLBACK", "splash", settings.splashPlacementId, "splash", entity, "IGNORED")
                        }

                        override fun onAdDismiss(entity: ATAdInfo, splashAdExtraInfo: ATSplashAdExtraInfo) {
                            Log.i(TAG, "Splash dismissed")
                            auditAdInfo("CLOSED", "splash", settings.splashPlacementId, "splash", entity, "DISMISSED")
                            deliver(FullscreenResult.Shown, host)
                        }

                        override fun onDeeplinkCallback(adInfo: ATAdInfo, isSuccess: Boolean) = Unit

                        override fun onDownloadConfirm(context: Context, adInfo: ATAdInfo, networkConfirmInfo: ATNetworkConfirmInfo) = Unit
                    }, SPLASH_TIMEOUT_MS)
                    recordSplashScenarioArrival(requireNotNull(splash))
                    // Do not attach TopOnRevenueListener for Splash: it would send paid-impression
                    // revenue to AppsFlyer, Firebase, PSSDK and the operations reconciliation flow.
                    // Required sequence: entryAdScenario(Default) -> isAdReady -> show; load only
                    // when this initial Ready check is false. New SDK instances are commonly empty,
                    // but the check remains explicit for a uniform, auditable integration path.
                    if (!showSplashIfReady("before_load")) {
                        splash!!.loadAd()
                    }
                }
                Initialization.NotConfigured -> deliver(FullscreenResult.Skipped("尚未配置 TopOn 广告参数"))
                is Initialization.Failed -> deliver(FullscreenResult.Failed(init.code, init.message))
            }
        }
    }

    private fun showInterstitial(
        activity: Activity,
        policy: FullscreenAdPolicy,
        logicalPlacement: String,
        bypassGlobalFrequencyCap: Boolean = false,
        onWillShow: () -> Unit,
        onShown: () -> Unit = {},
        onAdClicked: () -> Unit = {},
        onResult: (FullscreenResult) -> Unit,
    ) {
        adNetworkAudit.recordConfiguration(settings)
        if (!settings.canShowInterstitial) {
            auditEvent("REQUEST", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "NOT_CONFIGURED")
            onResult(FullscreenResult.Skipped("尚未配置 TopOn 广告参数"))
            return
        }
        if (interstitialRequestInFlight) {
            auditEvent("REQUEST", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "BLOCKED_IN_FLIGHT")
            onResult(FullscreenResult.Skipped("插屏广告请求正在进行中"))
            return
        }
        if (!policy.canShow()) {
            auditEvent("REQUEST", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "BLOCKED_POLICY")
            onResult(FullscreenResult.Skipped("插屏广告频控中"))
            return
        }
        if (!bypassGlobalFrequencyCap && !globalFullscreenPolicy.canShow()) {
            auditEvent("REQUEST", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "BLOCKED_GLOBAL_FREQUENCY")
            onResult(FullscreenResult.Skipped("全屏广告冷却中"))
            return
        }
        interstitialRequestInFlight = true
        auditEvent("REQUEST", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "STARTED")
        Log.i(TAG, "Interstitial request started: logical=$logicalPlacement, placement=${settings.interstitialPlacementId}")
        var delivered = false
        var requestExpired = false
        var interstitialShown = false
        lateinit var requestTimeout: Runnable
        lateinit var closeTimeout: Runnable
        fun deliver(result: FullscreenResult) {
            if (delivered) return
            delivered = true
            mainHandler.removeCallbacks(requestTimeout)
            mainHandler.removeCallbacks(closeTimeout)
            interstitialRequestInFlight = false
            restoreAppWindowAfterAd(activity)
            onResult(result)
        }
        requestTimeout = Runnable {
            if (delivered || interstitialShown) return@Runnable
            requestExpired = true
            auditEvent(
                "LOAD_FAILED",
                logicalPlacement,
                settings.interstitialPlacementId,
                "interstitial",
                result = "TIMEOUT",
                errorCode = INTERSTITIAL_LOAD_TIMEOUT_CODE,
                detail = "client_timeout_ms=$INTERSTITIAL_LOAD_TIMEOUT_MS",
            )
            Log.w(TAG, "Interstitial load timed out after ${INTERSTITIAL_LOAD_TIMEOUT_MS}ms: $logicalPlacement")
            deliver(FullscreenResult.Failed(INTERSTITIAL_LOAD_TIMEOUT_CODE, "interstitial_load_timeout"))
        }
        closeTimeout = Runnable {
            if (delivered || !interstitialShown) return@Runnable
            auditEvent(
                "CLOSE_TIMEOUT",
                logicalPlacement,
                settings.interstitialPlacementId,
                "interstitial",
                result = "TIMEOUT",
                errorCode = INTERSTITIAL_CLOSE_TIMEOUT_CODE,
                detail = "client_timeout_ms=$INTERSTITIAL_CLOSE_TIMEOUT_MS",
            )
            Log.w(TAG, "Interstitial close callback timed out after ${INTERSTITIAL_CLOSE_TIMEOUT_MS}ms: $logicalPlacement")
            deliver(FullscreenResult.Failed(INTERSTITIAL_CLOSE_TIMEOUT_CODE, "interstitial_close_timeout"))
        }
        mainHandler.postDelayed(requestTimeout, INTERSTITIAL_LOAD_TIMEOUT_MS)

        initialize { init ->
            if (requestExpired || delivered) return@initialize
            when (init) {
                Initialization.Ready -> {
                    auditEvent("SCENARIO_REACHED", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "ENTERED", detail = DEFAULT_SCENARIO_DETAIL)
                    val ad = ATInterstitial(applicationContext, settings.interstitialPlacementId)
                    recordInterstitialScenarioArrival(ad)
                    ad.setAdRevenueListener(TopOnRevenueListener(logicalPlacement, settings.interstitialPlacementId, "interstitial", analytics, adNetworkAudit, adMediationReporter))
                    fun showInterstitialIfReady(source: String): Boolean {
                        if (requestExpired || delivered) return true
                        val ready = ad.isAdReady
                        auditEvent(
                            "READY_CHECK",
                            logicalPlacement,
                            settings.interstitialPlacementId,
                            "interstitial",
                            result = if (ready) "READY" else "NOT_READY",
                            detail = "$DEFAULT_SCENARIO_DETAIL; source=$source",
                        )
                        if (!ready) return false
                        mainHandler.removeCallbacks(requestTimeout)
                        showOnActiveActivity(activity, { deliver(FullscreenResult.Skipped(it)) }, onWillShow) {
                            ad.show(activity, defaultScenarioShowConfig())
                        }
                        return true
                    }
                    ad.setAdListener(object : ATInterstitialExListener {
                        override fun onInterstitialAdLoaded() {
                            if (requestExpired || delivered) {
                                auditEvent("LOADED_LATE", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "IGNORED", detail = "client_timeout_already_delivered")
                                return
                            }
                            mainHandler.removeCallbacks(requestTimeout)
                            Log.i(TAG, "Interstitial loaded: logical=$logicalPlacement, ready=${ad.isAdReady}")
                            auditEvent("LOADED", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = if (ad.isAdReady) "READY" else "NOT_READY")
                            if (!showInterstitialIfReady("loaded_callback")) {
                                deliver(FullscreenResult.Failed(INTERSTITIAL_NOT_READY_CODE, "interstitial_not_ready"))
                            }
                        }

                        override fun onInterstitialAdLoadFail(adError: AdError) {
                            if (requestExpired || delivered) return
                            mainHandler.removeCallbacks(requestTimeout)
                            Log.w(TAG, "Interstitial load failed: logical=$logicalPlacement, error=${adError.fullErrorInfo}")
                            auditEvent("LOAD_FAILED", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "NO_AD", errorCode = errorCode(adError), detail = "topon_no_ad")
                            deliver(FullscreenResult.Failed(errorCode(adError), adError.fullErrorInfo.orEmpty()))
                        }

                        override fun onInterstitialAdClicked(entity: ATAdInfo) {
                            notifyAdClicked(onAdClicked)
                        }

                        override fun onInterstitialAdShow(entity: ATAdInfo) {
                            if (delivered) return
                            if (interstitialShown) return
                            interstitialShown = true
                            mainHandler.removeCallbacks(requestTimeout)
                            Log.i(TAG, "Interstitial shown: logical=$logicalPlacement, network=${entity.adNetworkType}")
                            auditAdInfo("SHOWN", logicalPlacement, settings.interstitialPlacementId, "interstitial", entity, "DISPLAYED")
                            policy.recordShown()
                            globalFullscreenPolicy.recordShown()
                            analytics.reportTopOnImpression(logicalPlacement, settings.interstitialPlacementId, entity)
                            runCatching(onShown).onFailure {
                                Log.w(TAG, "Interstitial shown callback failed: logical=$logicalPlacement", it)
                            }
                            mainHandler.postDelayed(closeTimeout, INTERSTITIAL_CLOSE_TIMEOUT_MS)
                        }

                        override fun onInterstitialAdClose(entity: ATAdInfo) {
                            mainHandler.removeCallbacks(closeTimeout)
                            Log.i(TAG, "Interstitial closed: logical=$logicalPlacement")
                            auditAdInfo("CLOSED", logicalPlacement, settings.interstitialPlacementId, "interstitial", entity, "DISMISSED")
                            deliver(FullscreenResult.Shown)
                        }

                        // v6.6.21 exposes rewards from reward-capable interstitial demand. This
                        // product never grants business rewards from an interstitial callback.
                        override fun onInterstitialAdReward(entity: ATAdInfo) = Unit

                        override fun onInterstitialAdVideoStart(entity: ATAdInfo) = Unit
                        override fun onInterstitialAdVideoEnd(entity: ATAdInfo) = Unit
                        override fun onInterstitialAdVideoError(adError: AdError) = Unit
                        override fun onDeeplinkCallback(adInfo: ATAdInfo, isSuccess: Boolean) = Unit
                        override fun onDownloadConfirm(context: Context, adInfo: ATAdInfo, networkConfirmInfo: ATNetworkConfirmInfo) = Unit
                    })
                    // Required sequence: entryAdScenario(Default) -> isAdReady -> show; only
                    // request a new creative after an explicit initial NOT_READY result.
                    if (!showInterstitialIfReady("before_load")) {
                        ad.load()
                    }
                }
                Initialization.NotConfigured -> deliver(FullscreenResult.Skipped("尚未配置 TopOn 广告参数"))
                is Initialization.Failed -> deliver(FullscreenResult.Failed(init.code, init.message))
            }
        }
    }

    /**
     * TopOn's required rewarded path: enter Default scenario, inspect Ready, then load only when
     * necessary. The application attempts its guarded interstitial fallback only after TopOn load
     * fails, times out, or returns a non-ready loaded object.
     */
    private fun loadAndShowRewarded(
        activity: Activity,
        logicalPlacement: String,
        onWillShow: () -> Unit,
        onAdClicked: () -> Unit,
        onRewardVerified: () -> Unit,
        deliver: (RewardedResult) -> Unit,
    ) {
        auditEvent("SCENARIO_REACHED", logicalPlacement, settings.rewardedPlacementId, "rewarded", result = "ENTERED", detail = DEFAULT_SCENARIO_DETAIL)
        val ad = ATRewardVideoAd(applicationContext, settings.rewardedPlacementId)
        recordRewardedScenarioArrival(ad)
        var rewardState = RewardState.NOT_REWARDED
        var latestInfo: ATAdInfo? = null
        var requestExpired = false
        var showRequested = false
        var loadTimeout: Runnable? = null

        fun clearLoadTimeout() {
            loadTimeout?.let(mainHandler::removeCallbacks)
            loadTimeout = null
        }

        fun showIfReady() {
            if (showRequested) return
            val ready = ad.isAdReady
            auditEvent("READY_CHECK", logicalPlacement, settings.rewardedPlacementId, "rewarded", result = if (ready) "READY" else "NOT_READY", detail = DEFAULT_SCENARIO_DETAIL)
            if (!ready) {
                clearLoadTimeout()
                requestExpired = true
                deliver(RewardedResult.Failed(REWARDED_NOT_READY_CODE, "rewarded_not_ready"))
                return
            }
            showRequested = true
            showOnActiveActivity(activity, { reason ->
                clearLoadTimeout()
                requestExpired = true
                deliver(RewardedResult.Unavailable(reason))
            }, onWillShow) {
                ad.show(activity, defaultScenarioShowConfig())
            }
        }

        ad.setAdRevenueListener(TopOnRevenueListener(logicalPlacement, settings.rewardedPlacementId, "rewarded", analytics, adNetworkAudit, adMediationReporter))
        ad.setAdListener(object : ATRewardVideoExListener {
            override fun onRewardedVideoAdAgainPlayStart(entity: ATAdInfo) = Unit
            override fun onRewardedVideoAdAgainPlayEnd(entity: ATAdInfo) = Unit
            override fun onRewardedVideoAdAgainPlayFailed(adError: AdError, entity: ATAdInfo) = Unit
            override fun onRewardedVideoAdAgainPlayClicked(entity: ATAdInfo) = Unit
            override fun onAgainReward(entity: ATAdInfo) = Unit
            override fun onAgainRewardFailed(entity: ATAdInfo) = Unit

            override fun onRewardedVideoAdLoaded() {
                if (requestExpired) {
                    auditEvent("LOADED_LATE", logicalPlacement, settings.rewardedPlacementId, "rewarded", result = "IGNORED", detail = "client_timeout_already_delivered")
                    return
                }
                clearLoadTimeout()
                auditEvent("LOADED", logicalPlacement, settings.rewardedPlacementId, "rewarded", result = if (ad.isAdReady) "READY" else "NOT_READY")
                showIfReady()
            }

            override fun onRewardedVideoAdFailed(adError: AdError) {
                if (requestExpired) return
                clearLoadTimeout()
                requestExpired = true
                Log.w(TAG, "Rewarded no-ad error: ${adError.fullErrorInfo}")
                auditEvent("LOAD_FAILED", logicalPlacement, settings.rewardedPlacementId, "rewarded", result = "NO_AD", errorCode = errorCode(adError), detail = "topon_no_ad")
                deliver(RewardedResult.Failed(errorCode(adError), adError.fullErrorInfo.orEmpty()))
            }

            override fun onRewardedVideoAdPlayStart(entity: ATAdInfo) {
                latestInfo = entity
                Log.i(TAG, "Rewarded shown: ${entity.adNetworkType}/${entity.adsourceId}")
                auditAdInfo("SHOWN", logicalPlacement, settings.rewardedPlacementId, "rewarded", entity, "DISPLAYED")
                rewardedPolicy.recordShown()
                globalFullscreenPolicy.recordShown()
                analytics.reportTopOnImpression(logicalPlacement, settings.rewardedPlacementId, entity)
            }

            override fun onRewardedVideoAdPlayEnd(entity: ATAdInfo) {
                latestInfo = entity
            }

            override fun onRewardedVideoAdPlayFailed(adError: AdError, entity: ATAdInfo) {
                latestInfo = entity
                if (rewardState == RewardState.REWARDED) return
                rewardState = RewardState.REWARD_FAILED
                auditAdInfo("PLAY_FAILED", logicalPlacement, settings.rewardedPlacementId, "rewarded", entity, "FAILED", errorCode(adError), "topon_play_failed")
                deliver(RewardedResult.Failed(errorCode(adError), adError.fullErrorInfo.orEmpty()))
            }

            override fun onRewardedVideoAdClosed(entity: ATAdInfo) {
                latestInfo = entity
                auditAdInfo("CLOSED", logicalPlacement, settings.rewardedPlacementId, "rewarded", entity, if (rewardState == RewardState.REWARDED) "REWARDED" else "CANCELLED")
                if (rewardState == RewardState.REWARDED) deliver(RewardedResult.RewardEarned) else deliver(RewardedResult.Cancelled)
            }

            override fun onRewardedVideoAdPlayClicked(entity: ATAdInfo) {
                notifyAdClicked(onAdClicked)
            }

            override fun onReward(entity: ATAdInfo) {
                if (rewardState == RewardState.REWARDED) return
                rewardState = RewardState.REWARDED
                latestInfo = entity
                Log.i(TAG, "Rewarded completed: ${entity.adNetworkType}/${entity.adsourceId}")
                auditAdInfo("REWARD_COMPLETED", logicalPlacement, settings.rewardedPlacementId, "rewarded", entity, "COMPLETED")
                analytics.reportRewardedCompleted(logicalPlacement, settings.rewardedPlacementId, latestInfo)
                // A creative can immediately send the user to Google Play and skip its normal
                // close callback. This is the authoritative TopOn completion signal, so notify
                // the app to persist and begin the idempotent server settlement now.
                onRewardVerified()
            }

            override fun onRewardFailed(entity: ATAdInfo) {
                latestInfo = entity
                if (rewardState == RewardState.REWARDED) return
                rewardState = RewardState.REWARD_FAILED
            }

            override fun onDeeplinkCallback(adInfo: ATAdInfo, isSuccess: Boolean) = Unit
            override fun onDownloadConfirm(context: Context, adInfo: ATAdInfo, networkConfirmInfo: ATNetworkConfirmInfo) = Unit
        })

        // Required sequence: entryAdScenario(Default) -> isAdReady -> show, otherwise load first.
        if (ad.isAdReady) {
            showIfReady()
        } else {
            auditEvent("READY_CHECK", logicalPlacement, settings.rewardedPlacementId, "rewarded", result = "NOT_READY", detail = DEFAULT_SCENARIO_DETAIL)
            loadTimeout = Runnable {
                if (showRequested || requestExpired) return@Runnable
                requestExpired = true
                auditEvent("LOAD_FAILED", logicalPlacement, settings.rewardedPlacementId, "rewarded", result = "TIMEOUT", errorCode = REWARDED_LOAD_TIMEOUT_CODE, detail = "client_timeout_ms=${settings.rewardedLoadTimeoutMillis}")
                deliver(RewardedResult.Failed(REWARDED_LOAD_TIMEOUT_CODE, "rewarded_load_timeout"))
            }
            mainHandler.postDelayed(requireNotNull(loadTimeout), settings.rewardedLoadTimeoutMillis)
            ad.load()
        }
    }

    private fun splashHost(activity: Activity): FrameLayout? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return FrameLayout(activity).also { root.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
    }

    private fun showOnActiveActivity(
        activity: Activity,
        onInactive: (String) -> Unit,
        onBeforePrepare: () -> Unit = {},
        show: () -> Unit,
    ) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                // Pause video / exit immersive mode first. Doing this after prepareSafeWindowForAd would
                // accidentally restore edge-to-edge geometry and expose the network close control to a cutout.
                onBeforePrepare()
                prepareSafeWindowForAd(activity)
                show()
            } else {
                onInactive("当前页面已关闭，无法展示广告")
            }
        }
    }

    /**
     * Use system-bar-aware host geometry before TopOn presents its own full-screen activity. This leaves
     * a black safe band around edge-to-edge content on devices that honour the host window, so the SDK's
     * own close affordance is not trapped under a curved display corner. The ad creative remains unchanged.
     */
    private fun prepareSafeWindowForAd(activity: Activity) {
        val window = activity.window ?: return
        if (safeAdWindows.containsKey(activity)) return
        val originalCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode else 0
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        safeAdWindows[activity] = SafeAdWindowState(
            statusBarColor = window.statusBarColor,
            navigationBarColor = window.navigationBarColor,
            cutoutMode = originalCutoutMode,
            edgeToEdge = hostEdgeToEdge[activity] == true,
            lightStatusBars = controller.isAppearanceLightStatusBars,
            lightNavigationBars = controller.isAppearanceLightNavigationBars,
        )
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER }
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun restoreAppWindowAfterAd(activity: Activity) {
        val state = safeAdWindows.remove(activity) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val window: Window = activity.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = state.cutoutMode }
        }
        window.statusBarColor = state.statusBarColor
        window.navigationBarColor = state.navigationBarColor
        WindowCompat.setDecorFitsSystemWindows(window, !state.edgeToEdge)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = state.lightStatusBars
            isAppearanceLightNavigationBars = state.lightNavigationBars
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun auditAdInfo(
        event: String,
        logicalPlacement: String,
        topOnPlacementId: String,
        adFormat: String,
        adInfo: ATAdInfo,
        result: String,
        errorCode: Int? = null,
        detail: String = "",
    ) {
        val identity = AdNetworkIdentity.from(adInfo)
        val sourceDetail = listOf(identity.detail, detail).filter(String::isNotBlank).joinToString("; ")
        auditEvent(
            event = event,
            logicalPlacement = logicalPlacement,
            topOnPlacementId = topOnPlacementId,
            adFormat = adFormat,
            adNetwork = identity.networkName,
            adSourceId = identity.sourceId,
            result = result,
            errorCode = errorCode,
            revenue = if (event == "SHOWN") AdRevenueAuditValues.fromPaidImpression(adInfo.currency, adInfo.ecpm) else AdRevenueAuditValues(),
            detail = sourceDetail,
        )
        if (event == "SHOWN") {
            // This is a durable internal operations/reconciliation record only. It does not call
            // AppsFlyer, Firebase, PSSDK, Xuanyu, or a paid-impression/revenue reporting API.
            // Keep splash SHOWN here so the advertising-operations dashboard reconciles all
            // observed TopOn displays by format and returned network.
            adMediationReporter?.recordShown(logicalPlacement, topOnPlacementId, adFormat, adInfo)
        }
    }

    private fun auditEvent(
        event: String,
        logicalPlacement: String,
        topOnPlacementId: String,
        adFormat: String,
        adNetwork: String = "",
        adSourceId: String = "",
        result: String = "",
        errorCode: Int? = null,
        revenue: AdRevenueAuditValues = AdRevenueAuditValues(),
        detail: String = "",
    ) {
        adNetworkAudit.record(
            event = event,
            logicalPlacement = logicalPlacement,
            topOnPlacementId = topOnPlacementId,
            adFormat = adFormat,
            adNetwork = adNetwork,
            adSourceId = adSourceId,
            result = result,
            errorCode = errorCode,
            revenue = revenue,
            detail = detail,
        )
    }

    private fun errorCode(error: AdError): Int = error.code.toIntOrNull() ?: -1

    /** An advertiser callback must never crash the mediation callback thread. */
    private fun notifyAdClicked(callback: () -> Unit) {
        runCatching(callback).onFailure { error ->
            Log.w(TAG, "Ad click callback failed", error)
        }
    }

    /** Cooperation policy: every TopOn call is recorded and shown in the Default scenario. */
    private fun defaultScenarioShowConfig(): ATShowConfig = ATShowConfig.Builder()
        .scenarioId(null)
        .build()

    private fun recordRewardedScenarioArrival(ad: ATRewardVideoAd) {
        ad.entryAdScenario(null)
    }

    private fun recordInterstitialScenarioArrival(ad: ATInterstitial) {
        ad.entryAdScenario(null)
    }

    private fun recordSplashScenarioArrival(ad: ATSplashAd) {
        ad.entryAdScenario(null)
    }

    private class TopOnRevenueListener(
        private val logicalPlacement: String,
        private val adUnitId: String,
        private val adFormat: String,
        private val analytics: EngagementAnalytics,
        private val adNetworkAudit: AdNetworkAuditLog,
        private val adMediationReporter: AdMediationEventReporter?,
    ) : ATAdRevenueListener {
        private val paidReported = AtomicBoolean(false)

        override fun onAdRevenuePaid(adInfo: ATAdInfo) {
            if (!paidReported.compareAndSet(false, true)) return
            adNetworkAudit.recordPaidRevenue(logicalPlacement, adUnitId, adFormat, adInfo)
            analytics.reportTopOnRevenue(logicalPlacement, adUnitId, adInfo)
            val impressionValue = adInfo.publisherRevenue
            PssdkRevenueReporter.reportAdImpression(
                adnName = AdNetworkIdentity.from(adInfo).networkName,
                currency = adInfo.currency,
                revenue = impressionValue,
                adFormat = adFormat,
                aboutUnlock = logicalPlacement == "rv_episode_pass" || logicalPlacement == "int_rewarded_fallback",
            )
        }
    }

    private companion object {
        const val TAG = "TopOnAdsManager"
        // TopOn documents 6–10 seconds as the effective splash-load timeout window; use the
        // minimum recommended value to preserve JoyeDrama's fast-start requirement.
        const val SPLASH_TIMEOUT_MS = 6_000
        const val INTERSTITIAL_LOAD_TIMEOUT_MS = 8_000L
        const val INTERSTITIAL_LOAD_TIMEOUT_CODE = -408
        const val INTERSTITIAL_NOT_READY_CODE = -409
        const val INTERSTITIAL_CLOSE_TIMEOUT_MS = 120_000L
        const val INTERSTITIAL_CLOSE_TIMEOUT_CODE = -410
        const val REWARDED_LOAD_TIMEOUT_CODE = -408
        const val REWARDED_NOT_READY_CODE = -409
        const val GLOBAL_FULLSCREEN_MIN_INTERVAL_MS = 5_000L
        const val DEFAULT_SCENARIO_DETAIL = "scenario=default_null"
    }
}

/** 本地展示频控；最终展示频控仍由 Rewards API、TopOn 和广告平台共同控制。 */
class FullscreenAdPolicy(context: Context, private val key: String, private val minIntervalMillis: Long) {
    private val preferences = context.getSharedPreferences("dramaflow.fullscreen_ad_policy", Context.MODE_PRIVATE)

    fun canShow(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - preferences.getLong(key, 0L) >= minIntervalMillis

    fun recordShown(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(key, nowMillis).apply()
    }
}
