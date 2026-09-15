package com.dramaflow.app.analytics

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.appsflyer.AppsFlyerLib
import com.dramaflow.app.BuildConfig
import com.google.firebase.analytics.FirebaseAnalytics
import com.secmtp.sdk.core.api.ATAdInfo
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 白包统一数据链路。
 *
 * - AppsFlyer：仅在 TopOn paid impression 回调中，按合作方口径上报 ad_impression
 *   和 ad_impression_001 两个收入自定义事件；不在加载、展示、关闭或奖励回调估算收入。
 * - Firebase：在同一个 paid impression 回调中上报标准 ad_impression，奖励完成时发送一个
 *   自定义事件。
 * - 炫羽：按《海外事件上报》在广告实际展示回调中请求 /event/w_ad_show。
 *
 * 奖励、解锁和宝石账本仍只由 Rewards API 结算。任何上报失败均不会阻断广告关闭、内容播放
 * 或权益结算。
 */
class PartnerAnalytics private constructor(private val appContext: Context) {
    private val appsFlyer = AppsFlyerLib.getInstance()
    private val firebase by lazy { FirebaseAnalytics.getInstance(appContext) }
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 仅保存在内存；进程结束后清零，符合合作方 AppsFlyer 收入事件规范。 */
    private var pendingAppsFlyerRevenueUsd = BigDecimal.ZERO

    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        if (BuildConfig.APPSFLYER_DEV_KEY.isBlank()) {
            Log.w(TAG, "AppsFlyer Dev Key is not configured; attribution events are skipped")
            return
        }
        appsFlyer.init(BuildConfig.APPSFLYER_DEV_KEY, null, appContext)
        appsFlyer.start(appContext)
        Log.i(TAG, "AppsFlyer initialization requested; uid=${appsFlyerUid().orEmpty()}")
    }

    fun appsFlyerUid(): String? = runCatching { appsFlyer.getAppsFlyerUID(appContext) }
        .getOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)

    /** 广告真实展示：炫羽展示上报使用此时机；收入事件等待 paid impression 回调。 */
    fun reportTopOnImpression(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo) {
        reportXuanyuAdShow(payloadFor(logicalPlacement, adUnitId, adInfo))
    }

    /**
     * TopOn paid impression / onAdRevenuePaid 回调。
     *
     * TopOn 此处提供的是 eCPM，因此需要除以 1,000 后才是本次展示真实 USD 收入。仅 USD、
     * 正数、有限值参与 AppsFlyer 单次和累计收入事件；每次 paid impression 只发一次单次事件。
     */
    @Synchronized
    fun reportTopOnRevenue(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo) {
        val payload = payloadFor(logicalPlacement, adUnitId, adInfo)
        val currency = payload.currency.trim().uppercase(Locale.ROOT)
        if (currency != USD || !payload.ecpm.isFinite() || payload.ecpm <= 0.0) {
            debug("广告收入不满足 AppsFlyer USD paid-impression 条件，跳过：$payload")
            return
        }

        val revenueUsd = BigDecimal.valueOf(payload.ecpm).movePointLeft(3)
        if (revenueUsd.signum() <= 0) return

        // Firebase 标准 ad_impression 同样以真实 paid impression 收入为准，而不是 onShow 估算。
        logFirebaseImpression(payload, revenueUsd)

        if (BuildConfig.APPSFLYER_DEV_KEY.isBlank()) return
        val singleValues = appsFlyerRevenueValues(payload, revenueUsd)
        appsFlyer.logEvent(appContext, BuildConfig.APPSFLYER_SINGLE_REVENUE_EVENT_NAME, singleValues)
        debug("AppsFlyer ${BuildConfig.APPSFLYER_SINGLE_REVENUE_EVENT_NAME}: $singleValues")

        pendingAppsFlyerRevenueUsd = pendingAppsFlyerRevenueUsd.add(revenueUsd)
        if (pendingAppsFlyerRevenueUsd.compareTo(APPSFLYER_CUMULATIVE_THRESHOLD_USD) >= 0) {
            val accumulated = pendingAppsFlyerRevenueUsd
            val accumulatedValues = hashMapOf<String, Any>(
                "currency" to USD,
                "value" to accumulated.toDouble(),
            )
            appsFlyer.logEvent(appContext, BuildConfig.APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME, accumulatedValues)
            debug("AppsFlyer ${BuildConfig.APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME}: $accumulatedValues")
            pendingAppsFlyerRevenueUsd = BigDecimal.ZERO
        }
    }

    /** Firebase 的唯一自定义事件：只在激励广告完整回调后发送。 */
    fun reportRewardedCompleted(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo?) {
        val payload = adInfo?.let { payloadFor(logicalPlacement, adUnitId, it) }
            ?: AdPayload(logicalPlacement, adUnitId, "rewarded", "TopOn", 0.0, USD)
        firebase.logEvent(BuildConfig.FIREBASE_REWARDED_EVENT_NAME, Bundle().apply {
            putString("ad_placement", payload.logicalPlacement)
            putString("ad_unit_id", payload.adUnitId)
            putString("ad_type", payload.adType)
            putString("ad_source", payload.adNetwork)
        })
        debug("Firebase ${BuildConfig.FIREBASE_REWARDED_EVENT_NAME}: $payload")
    }

    private fun payloadFor(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo): AdPayload = AdPayload(
        logicalPlacement = logicalPlacement,
        adUnitId = adUnitId,
        adType = adInfo.topOnAdFormat.orEmpty().ifBlank { adInfo.format.orEmpty().ifBlank { logicalPlacement } },
        adNetwork = adInfo.networkName.orEmpty().ifBlank { adInfo.adNetworkType.orEmpty().ifBlank { "TopOn" } },
        ecpm = adInfo.ecpm,
        currency = adInfo.currency.orEmpty().ifBlank { USD },
    )

    private fun appsFlyerRevenueValues(payload: AdPayload, revenueUsd: BigDecimal): HashMap<String, Any> = hashMapOf(
        "currency" to USD,
        "value" to revenueUsd.toDouble(),
        "ad_platform" to "TopOn",
        "ad_source" to payload.adNetwork,
        "ad_unit_id" to payload.adUnitId,
        "ad_placement" to payload.logicalPlacement,
        "ad_type" to payload.adType,
    )

    private fun logFirebaseImpression(payload: AdPayload, revenueUsd: BigDecimal) {
        firebase.logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "TopOn")
            putString(FirebaseAnalytics.Param.AD_SOURCE, payload.adNetwork)
            putString(FirebaseAnalytics.Param.AD_FORMAT, payload.adType)
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, payload.adUnitId)
            putString(FirebaseAnalytics.Param.CURRENCY, USD)
            putDouble(FirebaseAnalytics.Param.VALUE, revenueUsd.toDouble())
            putString("ad_placement", payload.logicalPlacement)
        })
        debug("Firebase ad_impression: placement=${payload.logicalPlacement}, value=${revenueUsd.toPlainString()}")
    }

    private fun reportXuanyuAdShow(payload: AdPayload, uidAttempt: Int = 0, networkAttempt: Int = 0) {
        val baseUrl = BuildConfig.XUANYU_EVENT_BASE_URL.trim().removeSuffix("/")
        val uid = appsFlyerUid()
        if (baseUrl.isBlank()) {
            debug("炫羽广告展示上报跳过：baseUrl 未配置")
            return
        }
        if (uid.isNullOrBlank()) {
            if (uidAttempt < XUANYU_UID_RETRY_COUNT) {
                mainHandler.postDelayed({ reportXuanyuAdShow(payload, uidAttempt + 1, networkAttempt) }, XUANYU_UID_RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "炫羽广告展示上报跳过：AppsFlyer uid 未就绪")
            }
            return
        }
        val url = ("$baseUrl/event/w_ad_show").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("uid", uid)
            ?.addQueryParameter("app_ver", BuildConfig.VERSION_NAME)
            ?.addQueryParameter("pkg_name", appContext.packageName)
            ?.addQueryParameter("ad_type", payload.adType)
            ?.addQueryParameter("ad_network", payload.adNetwork)
            ?.addQueryParameter("ecpm", formatDecimal(payload.ecpm.coerceAtLeast(0.0)))
            ?.build()
        if (url == null) {
            Log.w(TAG, "炫羽广告展示上报地址无效")
            return
        }
        httpClient.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (networkAttempt < XUANYU_NETWORK_RETRY_COUNT) {
                    val nextAttempt = networkAttempt + 1
                    Log.w(TAG, "炫羽广告展示上报失败（第 ${nextAttempt} 次重试前）：${error.message}")
                    mainHandler.postDelayed(
                        { reportXuanyuAdShow(payload, uidAttempt, nextAttempt) },
                        XUANYU_NETWORK_RETRY_DELAY_MS,
                    )
                } else {
                    Log.w(TAG, "炫羽广告展示上报最终失败：${error.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        debug("炫羽广告展示上报成功：${payload.logicalPlacement}")
                    } else if (networkAttempt < XUANYU_NETWORK_RETRY_COUNT) {
                        val nextAttempt = networkAttempt + 1
                        Log.w(TAG, "炫羽广告展示上报失败：HTTP ${it.code}，将重试")
                        mainHandler.postDelayed(
                            { reportXuanyuAdShow(payload, uidAttempt, nextAttempt) },
                            XUANYU_NETWORK_RETRY_DELAY_MS,
                        )
                    } else {
                        Log.w(TAG, "炫羽广告展示上报最终失败：HTTP ${it.code}")
                    }
                }
            }
        })
    }

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun debug(message: String) {
        if (BuildConfig.DEBUG || BuildConfig.ANALYTICS_DEBUG) Log.d(TAG, message)
    }

    data class AdPayload(
        val logicalPlacement: String,
        val adUnitId: String,
        val adType: String,
        val adNetwork: String,
        val ecpm: Double,
        val currency: String,
    )

    companion object {
        private const val TAG = "WhiteLabelAnalytics"
        private const val USD = "USD"
        private const val XUANYU_UID_RETRY_COUNT = 3
        private const val XUANYU_UID_RETRY_DELAY_MS = 750L
        private const val XUANYU_NETWORK_RETRY_COUNT = 2
        private const val XUANYU_NETWORK_RETRY_DELAY_MS = 1_500L
        private val APPSFLYER_CUMULATIVE_THRESHOLD_USD = BigDecimal("0.01")

        @Volatile
        private var instance: PartnerAnalytics? = null

        fun get(context: Context): PartnerAnalytics = instance ?: synchronized(this) {
            instance ?: PartnerAnalytics(context.applicationContext).also { instance = it }
        }
    }
}
