package com.dramaflow.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dramaflow.app.BuildConfig
import com.bytedance.sdk.openadsdk.api.init.PAGConfig
import com.bytedance.sdk.openadsdk.api.init.PAGSdk
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialAd
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialAdInteractionListener
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialAdLoadListener
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialRequest
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenAd
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenAdInteractionListener
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenAdLoadListener
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenRequest
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardItem
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedAd
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedAdInteractionListener
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedAdLoadListener
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedRequest

/**
 * Pangle 的唯一 SDK 调用入口。
 *
 * 初始化不会发生在首帧绘制之前。所有全屏广告均要求调用方先完成隐私同意，
 * 采用单请求保护、展示频控和不可用时继续业务流程的降级策略。激励奖励仅在
 * onUserEarnedReward 事件交付；正式环境仍须通过服务端回调或验签落账。
 */
class PangleAdsManager(
    private val applicationContext: Context,
    private val settings: PangleSettings,
    private val rewardedPolicy: RewardedAdPolicy = RewardedAdPolicy(applicationContext),
    private val interstitialPolicy: FullscreenAdPolicy = FullscreenAdPolicy(
        applicationContext,
        key = "interstitial_last_show",
        minIntervalMillis = if (BuildConfig.DEBUG) 10_000L else 60_000L
    ),
    // 每次冷启动均尝试请求开屏广告；实际展示仍由 Pangle 填充、其服务端策略和设备网络决定。
    // 避免在客户端额外设置六小时频控，导致测试或正常冷启动没有请求机会。
    private val appOpenPolicy: FullscreenAdPolicy = FullscreenAdPolicy(
        applicationContext,
        key = "app_open_last_show",
        minIntervalMillis = 0L
    )
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

    private var initializationStarted = false
    private var rewardedRequestInFlight = false
    private var interstitialRequestInFlight = false
    private var appOpenRequestInFlight = false
    private var initCallbacks = mutableListOf<(Initialization) -> Unit>()

    fun initialize(onResult: (Initialization) -> Unit) {
        if (!settings.isConfigured) {
            onResult(Initialization.NotConfigured)
            return
        }
        if (PAGSdk.isInitSuccess()) {
            onResult(Initialization.Ready)
            return
        }
        initCallbacks += onResult
        if (initializationStarted) return

        initializationStarted = true
        val config = PAGConfig.Builder()
            .appId(settings.appId)
            .debugLog(BuildConfig.DEBUG)
            .build()
        PAGSdk.init(applicationContext, config, object : PAGSdk.PAGInitCallback {
            override fun success() = dispatchInitialization(Initialization.Ready)
            override fun fail(code: Int, message: String?) = dispatchInitialization(Initialization.Failed(code, message.orEmpty()))
        })
    }

    fun showRewarded(activity: Activity, onResult: (RewardedResult) -> Unit) {
        if (!settings.canShowRewarded) {
            onResult(RewardedResult.Unavailable("尚未配置 Pangle App ID 或激励广告位 ID"))
            return
        }
        if (rewardedRequestInFlight) {
            onResult(RewardedResult.Unavailable("广告请求正在进行中，请勿重复点击"))
            return
        }
        when (val decision = rewardedPolicy.canStart()) {
            is RewardedAdPolicy.Decision.Blocked -> {
                onResult(RewardedResult.Unavailable(decision.message))
                return
            }
            RewardedAdPolicy.Decision.Allowed -> Unit
        }

        rewardedRequestInFlight = true
        var delivered = false
        fun deliver(result: RewardedResult) {
            if (delivered) return
            delivered = true
            rewardedRequestInFlight = false
            onResult(result)
        }

        initialize { init ->
            when (init) {
                Initialization.Ready -> loadAndShowRewarded(activity, ::deliver)
                Initialization.NotConfigured -> deliver(RewardedResult.Unavailable("尚未配置 Pangle 广告参数"))
                is Initialization.Failed -> deliver(RewardedResult.Failed(init.code, init.message))
            }
        }
    }

    /** 仅应在看完一集等自然内容断点调用；失败或限频时必须继续内容播放。 */
    fun showInterstitialAtNaturalBreak(activity: Activity, onResult: (FullscreenResult) -> Unit) {
        if (!settings.canShowInterstitial) {
            onResult(FullscreenResult.Skipped("未配置 Pangle 插屏广告位"))
            return
        }
        if (interstitialRequestInFlight) {
            onResult(FullscreenResult.Skipped("插屏广告请求正在进行中"))
            return
        }
        if (!interstitialPolicy.canShow()) {
            onResult(FullscreenResult.Skipped("插屏广告频控中"))
            return
        }

        interstitialRequestInFlight = true
        var delivered = false
        fun deliver(result: FullscreenResult) {
            if (delivered) return
            delivered = true
            interstitialRequestInFlight = false
            onResult(result)
        }
        initialize { init ->
            when (init) {
                Initialization.Ready -> PAGInterstitialAd.loadAd(
                    settings.interstitialPlacementId,
                    PAGInterstitialRequest(),
                    object : PAGInterstitialAdLoadListener {
                        override fun onError(code: Int, message: String?) = deliver(FullscreenResult.Failed(code, message.orEmpty()))
                        override fun onAdLoaded(ad: PAGInterstitialAd?) {
                            if (ad == null) {
                                deliver(FullscreenResult.Skipped("Pangle 未返回可展示的插屏广告"))
                                return
                            }
                            ad.setAdInteractionListener(object : PAGInterstitialAdInteractionListener {
                                override fun onAdShowed() = interstitialPolicy.recordShown()
                                override fun onAdClicked() = Unit
                                override fun onAdDismissed() = deliver(FullscreenResult.Shown)
                            })
                            showOnActiveActivity(activity, onInactive = { deliver(FullscreenResult.Skipped(it)) }) { ad.show(activity) }
                        }
                    }
                )
                Initialization.NotConfigured -> deliver(FullscreenResult.Skipped("尚未配置 Pangle 广告参数"))
                is Initialization.Failed -> deliver(FullscreenResult.Failed(init.code, init.message))
            }
        }
    }

    /** 仅用于已取得广告隐私同意后的冷启动/回前台场景；每次冷启动均尝试请求，展示失败直接进入主界面。 */
    fun showAppOpenIfEligible(activity: Activity, onResult: (FullscreenResult) -> Unit) {
        if (!settings.canShowAppOpen) {
            onResult(FullscreenResult.Skipped("未配置 Pangle 开屏广告位"))
            return
        }
        if (appOpenRequestInFlight) {
            onResult(FullscreenResult.Skipped("开屏广告请求正在进行中"))
            return
        }
        if (!appOpenPolicy.canShow()) {
            onResult(FullscreenResult.Skipped("开屏广告频控中"))
            return
        }

        appOpenRequestInFlight = true
        var delivered = false
        fun deliver(result: FullscreenResult) {
            if (delivered) return
            delivered = true
            appOpenRequestInFlight = false
            onResult(result)
        }
        initialize { init ->
            when (init) {
                Initialization.Ready -> PAGAppOpenAd.loadAd(
                    settings.appOpenPlacementId,
                    PAGAppOpenRequest(),
                    object : PAGAppOpenAdLoadListener {
                        override fun onError(code: Int, message: String?) = deliver(FullscreenResult.Failed(code, message.orEmpty()))
                        override fun onAdLoaded(ad: PAGAppOpenAd?) {
                            if (ad == null) {
                                deliver(FullscreenResult.Skipped("Pangle 未返回可展示的开屏广告"))
                                return
                            }
                            ad.setAdInteractionListener(object : PAGAppOpenAdInteractionListener {
                                override fun onAdShowed() = appOpenPolicy.recordShown()
                                override fun onAdClicked() = Unit
                                override fun onAdDismissed() = deliver(FullscreenResult.Shown)
                            })
                            showOnActiveActivity(activity, onInactive = { deliver(FullscreenResult.Skipped(it)) }) { ad.show(activity) }
                        }
                    }
                )
                Initialization.NotConfigured -> deliver(FullscreenResult.Skipped("尚未配置 Pangle 广告参数"))
                is Initialization.Failed -> deliver(FullscreenResult.Failed(init.code, init.message))
            }
        }
    }

    private fun loadAndShowRewarded(activity: Activity, deliver: (RewardedResult) -> Unit) {
        PAGRewardedAd.loadAd(
            settings.rewardedPlacementId,
            PAGRewardedRequest(),
            object : PAGRewardedAdLoadListener {
                override fun onError(code: Int, message: String?) = deliver(RewardedResult.Failed(code, message.orEmpty()))

                override fun onAdLoaded(ad: PAGRewardedAd?) {
                    if (ad == null) {
                        deliver(RewardedResult.Unavailable("Pangle 未返回可展示的激励广告"))
                    } else {
                        showLoadedRewarded(activity, ad, deliver)
                    }
                }
            }
        )
    }

    private fun showLoadedRewarded(activity: Activity, ad: PAGRewardedAd, deliver: (RewardedResult) -> Unit) {
        // Pangle 可能在广告尚未关闭时先回调 onUserEarnedReward。若立刻执行解锁并播放新一集，
        // 视频画面和声音会在广告下方继续播放。统一在 onAdDismissed 后才把结果交给业务层。
        var completed = false
        var rewardFailure: RewardedResult.Failed? = null
        ad.setAdInteractionListener(object : PAGRewardedAdInteractionListener {
            override fun onAdShowed() = rewardedPolicy.recordShown()
            override fun onAdClicked() = Unit
            override fun onAdDismissed() {
                val result = rewardFailure ?: if (completed) RewardedResult.RewardEarned else RewardedResult.Cancelled
                deliver(result)
            }
            override fun onUserEarnedReward(item: PAGRewardItem?) {
                completed = true
            }
            override fun onUserEarnedRewardFail(errorCode: Int, errorMsg: String?) {
                rewardFailure = RewardedResult.Failed(errorCode, errorMsg.orEmpty())
            }
        })
        showOnActiveActivity(activity, onInactive = { deliver(RewardedResult.Unavailable(it)) }) { ad.show(activity) }
    }

    private fun showOnActiveActivity(activity: Activity, onInactive: (String) -> Unit, show: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) show() else onInactive("当前页面已关闭，无法展示广告")
        }
    }

    private fun dispatchInitialization(result: Initialization) {
        initializationStarted = false
        val callbacks = initCallbacks.toList()
        initCallbacks.clear()
        callbacks.forEach { it(result) }
        if (result is Initialization.Failed) Log.w(TAG, "Pangle initialization failed: ${result.code} ${result.message}")
    }

    private companion object {
        const val TAG = "PangleAdsManager"
    }
}

/** 简单的本地展示频控；正式环境仍应由服务端根据用户、国家和合规策略控制。 */
class FullscreenAdPolicy(context: Context, private val key: String, private val minIntervalMillis: Long) {
    private val preferences = context.getSharedPreferences("dramaflow.fullscreen_ad_policy", Context.MODE_PRIVATE)

    fun canShow(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - preferences.getLong(key, 0L) >= minIntervalMillis

    fun recordShown(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(key, nowMillis).apply()
    }
}
