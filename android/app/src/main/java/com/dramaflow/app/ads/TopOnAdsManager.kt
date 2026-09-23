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
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.analytics.EngagementAnalytics
import com.secmtp.sdk.core.api.ATAdInfo
import com.secmtp.sdk.core.api.ATAdRevenueListener
import com.secmtp.sdk.core.api.ATNetworkConfirmInfo
import com.secmtp.sdk.core.api.ATNetworkConfig
import com.secmtp.sdk.core.api.ATSDK
import com.secmtp.sdk.core.api.ATSDKInitListener
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

    private var rewardedRequestInFlight = false
    private var interstitialRequestInFlight = false
    private var splashRequestInFlight = false
    private var topOnInitialized = false
    private var topOnInitializationInFlight = false
    private val pendingInitializationCallbacks = mutableListOf<(Initialization) -> Unit>()
    private val initializationLock = Any()
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
        adNetworkAudit.recordConfiguration(settings)
        if (!settings.isConfigured) {
            auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "NOT_CONFIGURED")
            onResult(Initialization.NotConfigured)
            return
        }

        var startInitialization = false
        synchronized(initializationLock) {
            if (topOnInitialized) {
                mainHandler.post { onResult(Initialization.Ready) }
                return
            }
            pendingInitializationCallbacks += onResult
            if (!topOnInitializationInFlight) {
                topOnInitializationInFlight = true
                startInitialization = true
            }
        }
        if (!startInitialization) return

        runCatching {
            auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "STARTED")
            ATSDK.setNetworkLogDebug(settings.enableNetworkDebug)
            ATSDK.init(
                applicationContext,
                settings.appId,
                settings.appKey,
                ATNetworkConfig(),
                object : ATSDKInitListener {
                    override fun onSuccess() = completeInitialization(Initialization.Ready)

                    override fun onFail(errorMessage: String) =
                        completeInitialization(Initialization.Failed(-1, errorMessage))
                },
            )
        }.onFailure {
            Log.w(TAG, "TopOn initialization invocation failed", it)
            auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "FAILED", errorCode = -1, detail = "initialization_exception")
            completeInitialization(Initialization.Failed(-1, it.message.orEmpty()))
        }
    }

    private fun completeInitialization(result: Initialization) {
        when (result) {
            Initialization.Ready -> auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "READY")
            Initialization.NotConfigured -> Unit
            is Initialization.Failed -> auditEvent("INITIALIZATION", "sdk", "", "sdk", result = "FAILED", errorCode = result.code, detail = "sdk_initialization_failed")
        }
        val callbacks = synchronized(initializationLock) {
            topOnInitialized = result is Initialization.Ready
            topOnInitializationInFlight = false
            pendingInitializationCallbacks.toList().also { pendingInitializationCallbacks.clear() }
        }
        mainHandler.post { callbacks.forEach { it(result) } }
    }

    fun showRewarded(
        activity: Activity,
        onWillShow: () -> Unit = {},
        onResult: (RewardedResult) -> Unit,
    ) {
        adNetworkAudit.recordConfiguration(settings)
        if (!settings.canShowRewarded) {
            auditEvent("REQUEST", "rewarded", settings.rewardedPlacementId, "rewarded", result = "NOT_CONFIGURED")
            onResult(RewardedResult.Unavailable("尚未配置 TopOn App ID、App Key 或激励广告位"))
            return
        }
        if (rewardedRequestInFlight) {
            auditEvent("REQUEST", "rewarded", settings.rewardedPlacementId, "rewarded", result = "BLOCKED_IN_FLIGHT")
            onResult(RewardedResult.Unavailable("广告请求正在进行中，请勿重复点击"))
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
                Initialization.Ready -> loadAndShowRewarded(activity, onWillShow, ::deliver)
                Initialization.NotConfigured -> deliver(RewardedResult.Unavailable("尚未配置 TopOn 激励广告参数"))
                is Initialization.Failed -> deliver(RewardedResult.Failed(init.code, init.message))
            }
        }
    }

    fun showInterstitialAtNaturalBreak(
        activity: Activity,
        onWillShow: () -> Unit = {},
        onResult: (FullscreenResult) -> Unit,
    ) = showInterstitial(activity, interstitialPolicy, "int_natural_break", onWillShow, onResult)

    /** 锁集解锁采用独立频控；每个锁定分集均可尝试 TopOn 激励广告，不使用插屏。 */
    fun showInterstitialForEpisodeUnlock(
        activity: Activity,
        onWillShow: () -> Unit = {},
        onResult: (FullscreenResult) -> Unit,
    ) = showInterstitial(activity, episodeUnlockPolicy, "int_episode_unlock_legacy", onWillShow, onResult)

    /** 适用于剧中、拖拽和倍速等明确操作；服务端仍负责 v8 最终频控。 */
    fun showInterstitialForPlaybackAction(
        activity: Activity,
        onWillShow: () -> Unit = {},
        onResult: (FullscreenResult) -> Unit,
    ) = showInterstitial(activity, playbackActionPolicy, "int_playback_action", onWillShow, onResult)

    fun showAppOpenIfEligible(activity: Activity, onResult: (FullscreenResult) -> Unit) {
        adNetworkAudit.recordConfiguration(settings)
        if (!settings.canShowSplash) {
            auditEvent("REQUEST", "splash", settings.splashPlacementId, "splash", result = "NOT_CONFIGURED")
            onResult(FullscreenResult.Skipped("尚未配置 TopOn App ID、App Key 或开屏广告位"))
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
                    var splash: ATSplashAd? = null
                    var host: FrameLayout? = null
                    splash = ATSplashAd(activity, settings.splashPlacementId, object : ATSplashExListener {
                        override fun onAdLoaded(isTimeout: Boolean) {
                            val ready = splash?.isAdReady == true
                            Log.i(TAG, "Splash loaded: isTimeout=$isTimeout, ready=$ready")
                            auditEvent("LOADED", "splash", settings.splashPlacementId, "splash", result = if (ready) "READY" else "NOT_READY", detail = "timeout_callback=$isTimeout")
                            if (splashRequestExpired || delivered) {
                                Log.i(TAG, "Splash loaded after startup timeout; discarded to keep startup dialogs ordered")
                                return
                            }
                            host = splashHost(activity)
                            if (host == null || splash == null || !ready) {
                                Log.w(TAG, "Splash cannot show: host=${host != null}, ad=${splash != null}, ready=$ready")
                                deliver(FullscreenResult.Skipped("TopOn 未返回可展示的开屏广告"), host)
                                return
                            }
                            prepareSafeWindowForAd(activity)
                            splash!!.show(activity, host)
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
                            analytics.reportTopOnImpression("splash", settings.splashPlacementId, entity)
                        }

                        override fun onAdClick(entity: ATAdInfo) = Unit

                        override fun onAdDismiss(entity: ATAdInfo, splashAdExtraInfo: ATSplashAdExtraInfo) {
                            Log.i(TAG, "Splash dismissed")
                            auditAdInfo("CLOSED", "splash", settings.splashPlacementId, "splash", entity, "DISMISSED")
                            deliver(FullscreenResult.Shown, host)
                        }

                        override fun onDeeplinkCallback(adInfo: ATAdInfo, isSuccess: Boolean) = Unit

                        override fun onDownloadConfirm(context: Context, adInfo: ATAdInfo, networkConfirmInfo: ATNetworkConfirmInfo) = Unit
                    }, SPLASH_TIMEOUT_MS)
                    splash!!.setAdRevenueListener(TopOnRevenueListener("splash", settings.splashPlacementId, analytics))
                    splash!!.loadAd()
                }
                Initialization.NotConfigured -> deliver(FullscreenResult.Skipped("尚未配置 TopOn 开屏广告参数"))
                is Initialization.Failed -> deliver(FullscreenResult.Failed(init.code, init.message))
            }
        }
    }

    /**
     * 当前合作方测试参数只包含开屏、激励和插屏广告位，未提供 TopOn 原生暂停广告位。
     * 保持播放器暂停状态并回调不可用，不将全屏插屏伪装为可随时关闭的暂停原生广告。
     */
    fun showPauseNativeAd(
        activity: Activity,
        overlayHost: ViewGroup,
        onShown: () -> Unit,
        onClosed: () -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        onUnavailable("TopOn 暂停原生广告位尚未配置")
    }

    private fun showInterstitial(
        activity: Activity,
        policy: FullscreenAdPolicy,
        logicalPlacement: String,
        onWillShow: () -> Unit,
        onResult: (FullscreenResult) -> Unit,
    ) {
        adNetworkAudit.recordConfiguration(settings)
        if (!settings.canShowInterstitial) {
            auditEvent("REQUEST", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "NOT_CONFIGURED")
            onResult(FullscreenResult.Skipped("尚未配置 TopOn App ID、App Key 或插屏广告位"))
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
        interstitialRequestInFlight = true
        auditEvent("REQUEST", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "STARTED")
        Log.i(TAG, "Interstitial request started: logical=$logicalPlacement, placement=${settings.interstitialPlacementId}")
        var delivered = false
        fun deliver(result: FullscreenResult) {
            if (delivered) return
            delivered = true
            interstitialRequestInFlight = false
            restoreAppWindowAfterAd(activity)
            onResult(result)
        }

        initialize { init ->
            when (init) {
                Initialization.Ready -> {
                    val ad = ATInterstitial(activity, settings.interstitialPlacementId)
                    ad.setAdRevenueListener(TopOnRevenueListener(logicalPlacement, settings.interstitialPlacementId, analytics))
                    ad.setAdListener(object : ATInterstitialExListener {
                        override fun onInterstitialAdLoaded() {
                            Log.i(TAG, "Interstitial loaded: logical=$logicalPlacement, ready=${ad.isAdReady}")
                            auditEvent("LOADED", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = if (ad.isAdReady) "READY" else "NOT_READY")
                            showOnActiveActivity(activity, { deliver(FullscreenResult.Skipped(it)) }, onWillShow) {
                                ad.show(activity)
                            }
                        }

                        override fun onInterstitialAdLoadFail(adError: AdError) {
                            Log.w(TAG, "Interstitial load failed: logical=$logicalPlacement, error=${adError.fullErrorInfo}")
                            auditEvent("LOAD_FAILED", logicalPlacement, settings.interstitialPlacementId, "interstitial", result = "NO_AD", errorCode = errorCode(adError), detail = "topon_no_ad")
                            deliver(FullscreenResult.Failed(errorCode(adError), adError.fullErrorInfo.orEmpty()))
                        }

                        override fun onInterstitialAdClicked(entity: ATAdInfo) = Unit

                        override fun onInterstitialAdShow(entity: ATAdInfo) {
                            Log.i(TAG, "Interstitial shown: logical=$logicalPlacement, network=${entity.adNetworkType}")
                            auditAdInfo("SHOWN", logicalPlacement, settings.interstitialPlacementId, "interstitial", entity, "DISPLAYED")
                            policy.recordShown()
                            analytics.reportTopOnImpression(logicalPlacement, settings.interstitialPlacementId, entity)
                        }

                        override fun onInterstitialAdClose(entity: ATAdInfo) {
                            Log.i(TAG, "Interstitial closed: logical=$logicalPlacement")
                            auditAdInfo("CLOSED", logicalPlacement, settings.interstitialPlacementId, "interstitial", entity, "DISMISSED")
                            deliver(FullscreenResult.Shown)
                        }

                        override fun onInterstitialAdVideoStart(entity: ATAdInfo) = Unit
                        override fun onInterstitialAdVideoEnd(entity: ATAdInfo) = Unit
                        override fun onInterstitialAdVideoError(adError: AdError) = Unit
                        override fun onDeeplinkCallback(adInfo: ATAdInfo, isSuccess: Boolean) = Unit
                        override fun onDownloadConfirm(context: Context, adInfo: ATAdInfo, networkConfirmInfo: ATNetworkConfirmInfo) = Unit
                    })
                    ad.load()
                }
                Initialization.NotConfigured -> deliver(FullscreenResult.Skipped("尚未配置 TopOn 插屏广告参数"))
                is Initialization.Failed -> deliver(FullscreenResult.Failed(init.code, init.message))
            }
        }
    }

    private fun loadAndShowRewarded(
        activity: Activity,
        onWillShow: () -> Unit,
        deliver: (RewardedResult) -> Unit,
    ) {
        val ad = ATRewardVideoAd(activity, settings.rewardedPlacementId)
        var completed = false
        var latestInfo: ATAdInfo? = null
        var timedOut = false
        val rewardedTimeout = Runnable {
            if (timedOut) return@Runnable
            timedOut = true
            auditEvent(
                "LOAD_FAILED",
                "rewarded",
                settings.rewardedPlacementId,
                "rewarded",
                result = "TIMEOUT",
                errorCode = REWARDED_LOAD_TIMEOUT_CODE,
                detail = "client_timeout_ms=$REWARDED_LOAD_TIMEOUT_MS",
            )
            Log.w(TAG, "Rewarded load timed out after ${REWARDED_LOAD_TIMEOUT_MS}ms; using fallback path")
            deliver(RewardedResult.Failed(REWARDED_LOAD_TIMEOUT_CODE, "rewarded_load_timeout"))
        }
        mainHandler.postDelayed(rewardedTimeout, REWARDED_LOAD_TIMEOUT_MS)
        ad.setAdRevenueListener(TopOnRevenueListener("rewarded", settings.rewardedPlacementId, analytics))
        ad.setAdListener(object : ATRewardVideoExListener {
            override fun onRewardedVideoAdAgainPlayStart(entity: ATAdInfo) = Unit
            override fun onRewardedVideoAdAgainPlayEnd(entity: ATAdInfo) = Unit
            override fun onRewardedVideoAdAgainPlayFailed(adError: AdError, entity: ATAdInfo) = Unit
            override fun onRewardedVideoAdAgainPlayClicked(entity: ATAdInfo) = Unit
            override fun onAgainReward(entity: ATAdInfo) = Unit
            override fun onAgainRewardFailed(entity: ATAdInfo) = Unit

            override fun onRewardedVideoAdLoaded() {
                if (timedOut) {
                    auditEvent("LOADED_LATE", "rewarded", settings.rewardedPlacementId, "rewarded", result = "IGNORED", detail = "client_timeout_already_delivered")
                    return
                }
                mainHandler.removeCallbacks(rewardedTimeout)
                auditEvent("LOADED", "rewarded", settings.rewardedPlacementId, "rewarded", result = "READY")
                showOnActiveActivity(activity, { deliver(RewardedResult.Unavailable(it)) }, onWillShow) {
                    ad.show(activity)
                }
            }

            override fun onRewardedVideoAdFailed(adError: AdError) {
                if (timedOut) return
                mainHandler.removeCallbacks(rewardedTimeout)
                Log.w(TAG, "Rewarded no-ad error: ${adError.fullErrorInfo}")
                auditEvent("LOAD_FAILED", "rewarded", settings.rewardedPlacementId, "rewarded", result = "NO_AD", errorCode = errorCode(adError), detail = "topon_no_ad")
                deliver(RewardedResult.Failed(errorCode(adError), adError.fullErrorInfo.orEmpty()))
            }

            override fun onRewardedVideoAdPlayStart(entity: ATAdInfo) {
                latestInfo = entity
                Log.i(TAG, "Rewarded shown: ${entity.adNetworkType}/${entity.adsourceId}")
                auditAdInfo("SHOWN", "rewarded", settings.rewardedPlacementId, "rewarded", entity, "DISPLAYED")
                rewardedPolicy.recordShown()
                analytics.reportTopOnImpression("rewarded", settings.rewardedPlacementId, entity)
            }

            override fun onRewardedVideoAdPlayEnd(entity: ATAdInfo) {
                latestInfo = entity
            }

            override fun onRewardedVideoAdPlayFailed(adError: AdError, entity: ATAdInfo) {
                latestInfo = entity
                auditAdInfo("PLAY_FAILED", "rewarded", settings.rewardedPlacementId, "rewarded", entity, "FAILED", errorCode(adError), "topon_play_failed")
                deliver(RewardedResult.Failed(errorCode(adError), adError.fullErrorInfo.orEmpty()))
            }

            override fun onRewardedVideoAdClosed(entity: ATAdInfo) {
                latestInfo = entity
                auditAdInfo("CLOSED", "rewarded", settings.rewardedPlacementId, "rewarded", entity, if (completed) "REWARDED" else "CANCELLED")
                if (completed) deliver(RewardedResult.RewardEarned) else deliver(RewardedResult.Cancelled)
            }

            override fun onRewardedVideoAdPlayClicked(entity: ATAdInfo) = Unit

            override fun onReward(entity: ATAdInfo) {
                completed = true
                latestInfo = entity
                Log.i(TAG, "Rewarded completed: ${entity.adNetworkType}/${entity.adsourceId}")
                auditAdInfo("REWARD_COMPLETED", "rewarded", settings.rewardedPlacementId, "rewarded", entity, "COMPLETED")
                analytics.reportRewardedCompleted("rewarded", settings.rewardedPlacementId, latestInfo)
            }

            override fun onRewardFailed(entity: ATAdInfo) {
                latestInfo = entity
            }

            override fun onDeeplinkCallback(adInfo: ATAdInfo, isSuccess: Boolean) = Unit
            override fun onDownloadConfirm(context: Context, adInfo: ATAdInfo, networkConfirmInfo: ATNetworkConfirmInfo) = Unit
        })
        ad.load()
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
        // The TopOn→MAX custom adapter places the final MAX network in ATAdInfo.extInfoMap.
        // Prefer that nested ADN over the parent custom-network name (“Max”) so exported CSVs can
        // reconcile TopOn's custom source with MAX's actual Google/Pangle/Unity/etc. return.
        val extInfo = adInfo.extInfoMap.orEmpty()
        val maxNetwork = extInfo.valueFor("network_name", "NetworkName")
        val maxAdUnitId = extInfo.valueFor("ad_unit_id", "AdUnitId")
        val maxNetworkPlacementId = extInfo.valueFor("network_placement_id", "NetworkPlacement")
        val topOnNetwork = adInfo.networkName.orEmpty().ifBlank { adInfo.adNetworkType.orEmpty() }.ifBlank { "TopOn" }
        val network = if (maxNetwork.isNotBlank()) "MAX > $maxNetwork" else topOnNetwork
        val sourceId = when {
            maxNetworkPlacementId.isNotBlank() -> maxNetworkPlacementId
            maxAdUnitId.isNotBlank() -> maxAdUnitId
            else -> adInfo.adsourceId.orEmpty()
        }
        val sourceDetail = buildList {
            if (maxAdUnitId.isNotBlank()) add("max_ad_unit_id=$maxAdUnitId")
            if (maxNetworkPlacementId.isNotBlank()) add("max_network_placement_id=$maxNetworkPlacementId")
            if (detail.isNotBlank()) add(detail)
        }.joinToString("; ")
        auditEvent(
            event = event,
            logicalPlacement = logicalPlacement,
            topOnPlacementId = topOnPlacementId,
            adFormat = adFormat,
            adNetwork = network,
            adSourceId = sourceId,
            result = result,
            errorCode = errorCode,
            detail = sourceDetail,
        )
    }

    private fun Map<String, Any>.valueFor(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> this[key]?.toString()?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun auditEvent(
        event: String,
        logicalPlacement: String,
        topOnPlacementId: String,
        adFormat: String,
        adNetwork: String = "",
        adSourceId: String = "",
        result: String = "",
        errorCode: Int? = null,
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
            detail = detail,
        )
    }

    private fun errorCode(error: AdError): Int = error.code.toIntOrNull() ?: -1

    private class TopOnRevenueListener(
        private val logicalPlacement: String,
        private val adUnitId: String,
        private val analytics: EngagementAnalytics,
    ) : ATAdRevenueListener {
        override fun onAdRevenuePaid(adInfo: ATAdInfo) {
            analytics.reportTopOnRevenue(logicalPlacement, adUnitId, adInfo)
        }
    }

    private companion object {
        const val TAG = "TopOnAdsManager"
        const val SPLASH_TIMEOUT_MS = 5_000
        /** Do not leave a user waiting for a mediation no-fill response indefinitely. */
        const val REWARDED_LOAD_TIMEOUT_MS = 5_000L
        const val REWARDED_LOAD_TIMEOUT_CODE = -408
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
