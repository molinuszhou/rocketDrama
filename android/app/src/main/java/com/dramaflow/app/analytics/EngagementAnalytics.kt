package com.dramaflow.app.analytics

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.appsflyer.AFAdRevenueData
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.MediationNetwork
import com.appsflyer.attribution.AppsFlyerRequestListener
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.ads.AdNetworkIdentity
import com.dramaflow.app.ads.AdRevenueAuditValues
import com.dramaflow.app.analytics.ReportingProvider.APPSFLYER
import com.dramaflow.app.analytics.ReportingProvider.FIREBASE
import com.dramaflow.app.analytics.ReportingProvider.XUANYU
import com.dramaflow.app.rewards.RewardsApiClient
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
 * - Firebase：在每个 paid-impression 回调中上报标准 ad_impression；累计达到合作方
 *   阈值后上报自定义 ad_impression_001。奖励完成另行发送完成事件。
 * - 炫羽：严格按《海外事件上报》文档，仅在广告实际展示时请求 /event/w_ad_show。
 *
 * 奖励、解锁和宝石账本仍只由 Rewards API 结算。任何上报失败均不会阻断广告关闭、内容播放
 * 或权益结算。
 */
class EngagementAnalytics private constructor(private val appContext: Context) {
    private val appsFlyer = AppsFlyerLib.getInstance()
    private val reportingAudit = ReportingAuditLog.get(appContext)
    private val attributionExperience = AttributionExperienceStore.get(appContext)
    /** Firebase auto-initializes from google-services.json; guard the side-channel so an accidental
     * missing test configuration never blocks ads, AppsFlyer events, or rewards. */
    private val firebase: FirebaseAnalytics? by lazy {
        runCatching { FirebaseAnalytics.getInstance(appContext) }
            .onFailure { Log.w(TAG, "Firebase Analytics is unavailable; Firebase events are skipped", it) }
            .getOrNull()
    }
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 仅保存在内存；进程结束后清零，符合合作方 AppsFlyer 收入事件规范。 */
    private var pendingAppsFlyerRevenueUsd = BigDecimal.ZERO

    @Volatile
    private var initialized = false

    @Volatile
    private var attributionSyncListener: ((RewardsApiClient.AttributionPayload) -> Unit)? = null

    /** Raw conversion data remains only in process memory until the encrypted Rewards audit request. */
    @Volatile
    private var latestRawConversionPayload: Map<String, Any?>? = null

    fun initialize() = safely("initialize external reporting") { initializeInternal() }

    private fun initializeInternal() {
        if (initialized) return
        initialized = true
        if (!BuildConfig.EXTERNAL_REPORTING_ENABLED) {
            reportingAudit.recordProvider(APPSFLYER, "sdk_initialization", ReportingStatus.SKIPPED_DISABLED)
            reportingAudit.recordProvider(FIREBASE, "sdk_initialization", ReportingStatus.SKIPPED_DISABLED)
            reportingAudit.recordProvider(XUANYU, "sdk_initialization", ReportingStatus.SKIPPED_DISABLED)
            Log.i(TAG, "外部数据上报已关闭：AppsFlyer、Firebase 与炫羽事件不会发送")
            return
        }
        if (BuildConfig.APPSFLYER_DEV_KEY.isBlank()) {
            reportingAudit.recordProvider(APPSFLYER, "sdk_initialization", ReportingStatus.SKIPPED_NOT_CONFIGURED)
            Log.w(TAG, "AppsFlyer Dev Key is not configured; attribution events are skipped")
        } else {
            reportingAudit.recordProvider(APPSFLYER, "sdk_initialization", ReportingStatus.STARTED)
            appsFlyer.setDebugLog(BuildConfig.ANALYTICS_DEBUG)
            appsFlyer.init(BuildConfig.APPSFLYER_DEV_KEY, attributionConversionListener(), appContext)
            appsFlyer.start(appContext, BuildConfig.APPSFLYER_DEV_KEY, object : AppsFlyerRequestListener {
                override fun onSuccess() = safely("record AppsFlyer initialization") {
                    reportingAudit.recordProvider(
                        APPSFLYER,
                        "sdk_initialization",
                        ReportingStatus.SUCCESS,
                        detail = "launch_http_200; ${attributionExperience.diagnosticSummary()}",
                    )
                    UserBehaviorEventReporter.get(appContext).record("SDK", "APPSFLYER_INITIALIZED")
                }

                override fun onError(errorCode: Int, errorDescription: String) = safely("record AppsFlyer initialization failure") {
                    reportingAudit.recordProvider(
                        APPSFLYER,
                        "sdk_initialization",
                        ReportingStatus.FAILED,
                        errorCode = errorCode.toString(),
                        detail = errorDescription,
                    )
                    UserBehaviorEventReporter.get(appContext).record(
                        "SDK",
                        "APPSFLYER_INITIALIZATION_FAILED",
                        mapOf("error_code" to errorCode, "reason" to errorDescription),
                    )
                    Log.w(TAG, "AppsFlyer launch failed: code=$errorCode, message=$errorDescription")
                }
            })
        }
        // getInstance() and OkHttp construction prove only that a local client object exists. Neither
        // SDK exposes a remote initialization acknowledgement here, so do not claim delivery success.
        reportingAudit.recordProvider(FIREBASE, "sdk_initialization", ReportingStatus.STARTED, detail = "firebase_lazy_unverified; remote_delivery_unconfirmed")
        reportingAudit.recordProvider(XUANYU, "sdk_initialization", ReportingStatus.STARTED, detail = "http_client_created; awaiting_actual_http_response")
        Log.i(TAG, "AppsFlyer initialization requested")
    }

    fun appsFlyerUid(): String? = if (!BuildConfig.EXTERNAL_REPORTING_ENABLED) null else runCatching { appsFlyer.getAppsFlyerUID(appContext) }
        .getOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun currentAttributionForRewards(): RewardsApiClient.AttributionPayload? = runCatching {
        attributionExperience.currentRecord()?.toRewardsPayload()
    }.onFailure { Log.w(TAG, "Attribution snapshot is unavailable", it) }.getOrNull()

    fun setAttributionSyncListener(listener: ((RewardsApiClient.AttributionPayload) -> Unit)?) {
        safely("set attribution listener") {
            attributionSyncListener = listener
            currentAttributionForRewards()?.let { payload ->
                listener?.invoke(payload.copy(rawConversionPayload = latestRawConversionPayload))
            }
        }
    }

    private fun attributionConversionListener(): AppsFlyerConversionListener = object : AppsFlyerConversionListener {
        override fun onConversionDataSuccess(conversionData: MutableMap<String, Any>?) = safely("process AppsFlyer conversion") {
            val experience = attributionExperience.applyConversionData(conversionData)
            // Keep the SDK map in memory only. It is delivered to the server's separately
            // encrypted, retention-bounded audit channel and is never written to preferences.
            latestRawConversionPayload = conversionData?.toMap()
            reportingAudit.recordProvider(
                APPSFLYER,
                "attribution_surface",
                ReportingStatus.SUCCESS,
                detail = "${attributionExperience.diagnosticSummary()}; selected=${experience.wireValue}",
            )
            UserBehaviorEventReporter.get(appContext).record(
                "SDK",
                "APPSFLYER_CONVERSION_RECEIVED",
                mapOf("surface" to experience.wireValue),
            )
            currentAttributionForRewards()?.let { payload ->
                attributionSyncListener?.invoke(payload.copy(rawConversionPayload = latestRawConversionPayload))
            }
            Log.i(TAG, "AppsFlyer attribution selected release surface ${experience.wireValue}")
        }

        override fun onConversionDataFail(errorMessage: String?) = safely("process AppsFlyer conversion failure") {
            reportingAudit.recordProvider(
                APPSFLYER,
                "attribution_surface",
                ReportingStatus.FAILED,
                detail = "conversion_data_unavailable; default_surface=${attributionExperience.current().wireValue}",
            )
            UserBehaviorEventReporter.get(appContext).record(
                "SDK",
                "APPSFLYER_CONVERSION_UNAVAILABLE",
                mapOf("default_surface" to attributionExperience.current().wireValue),
            )
            Log.w(TAG, "AppsFlyer conversion data unavailable; retaining safe default surface", errorMessage?.let(::IllegalStateException))
        }

        override fun onAppOpenAttribution(attributionData: MutableMap<String, String>?) = Unit

        override fun onAttributionFailure(errorMessage: String?) = safely("process AppsFlyer app-open attribution failure") {
            reportingAudit.recordProvider(
                APPSFLYER,
                "app_open_attribution",
                ReportingStatus.FAILED,
                detail = "attribution_failure",
            )
        }
    }

    /** 广告真实展示：炫羽展示上报使用此时机；收入事件等待 paid impression 回调。 */
    fun reportTopOnImpression(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo) =
        safely("report TopOn impression") { reportTopOnImpressionInternal(logicalPlacement, adUnitId, adInfo) }

    private fun reportTopOnImpressionInternal(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo) {
        if (!BuildConfig.EXTERNAL_REPORTING_ENABLED) {
            recordAllDisabled("ad_impression", logicalPlacement, adUnitId)
            debug("外部上报已关闭：跳过展示事件 $logicalPlacement")
            return
        }
        reportXuanyuAdShow(payloadFor(logicalPlacement, adUnitId, adInfo))
    }

    /**
     * TopOn paid impression / onAdRevenuePaid 回调。
     *
     * TopOn 在 paid-impression 回调提供 publisherRevenue（单次真实收入）与 eCPM。所有有效
     * ISO 货币的 publisherRevenue 都会通过 AppsFlyer 官方 IAA API 和 Firebase ad_impression
     * 发送；既有合作方自定义累计事件仍仅以 USD 口径参与。
     */
    @Synchronized
    fun reportTopOnRevenue(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo) =
        safely("report TopOn revenue") { reportTopOnRevenueInternal(logicalPlacement, adUnitId, adInfo) }

    private fun reportTopOnRevenueInternal(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo) {
        if (!BuildConfig.EXTERNAL_REPORTING_ENABLED) {
            recordAllDisabled("ad_revenue", logicalPlacement, adUnitId)
            debug("外部上报已关闭：跳过收入事件 $logicalPlacement")
            return
        }
        val payload = payloadFor(logicalPlacement, adUnitId, adInfo)
        val revenueAudit = payload.revenueAuditValues()
        val impressionRevenue = payload.publisherRevenue.takeIf { it.isFinite() && it > 0.0 }
        if (impressionRevenue == null || payload.currency.length != 3) {
            reportingAudit.recordProvider(APPSFLYER, "ad_revenue", ReportingStatus.SKIPPED_NOT_CONFIGURED, logicalPlacement, adUnitId, revenue = revenueAudit, detail = "invalid_publisher_revenue_or_currency")
            reportingAudit.recordProvider(FIREBASE, "ad_impression", ReportingStatus.SKIPPED_NOT_CONFIGURED, logicalPlacement, adUnitId, revenue = revenueAudit, detail = "invalid_publisher_revenue_or_currency")
            debug("广告收入缺少有效 publisherRevenue 或 ISO 货币，跳过：$payload")
            return
        }

        // Firebase 标准 ad_impression 以真实 paid-impression 收入为准，而不是 onShow 估算。
        logFirebaseImpression(payload, impressionRevenue, revenueAudit)
        logAppsFlyerAdRevenue(payload, impressionRevenue, revenueAudit)

        val revenueUsd = AppsFlyerRevenueContract.usdRevenueFromImpression(payload.currency, impressionRevenue)
        if (revenueUsd == null) {
            // The legacy partner custom events use a USD-only aggregation contract. Do not invent
            // a conversion rate; the official AppsFlyer IAA event above keeps source currency.
            reportingAudit.recordProvider(APPSFLYER, BuildConfig.APPSFLYER_SINGLE_REVENUE_EVENT_NAME, ReportingStatus.SKIPPED_NOT_CONFIGURED, logicalPlacement, adUnitId, revenue = revenueAudit, detail = "custom_event_usd_only")
            return
        }
        if (BuildConfig.APPSFLYER_DEV_KEY.isBlank()) return
        val singleValues = appsFlyerRevenueValues(payload, revenueUsd)
        logAppsFlyerRevenueEvent(BuildConfig.APPSFLYER_SINGLE_REVENUE_EVENT_NAME, singleValues, revenueAudit)

        val (remainder, readyBatch) = AppsFlyerRevenueContract.appendAndTakeReadyBatch(pendingAppsFlyerRevenueUsd, revenueUsd)
        pendingAppsFlyerRevenueUsd = remainder
        readyBatch?.let { accumulated ->
            val cumulativeRevenueAudit = AdRevenueAuditValues.fromReportedValue(AppsFlyerRevenueContract.USD, accumulated)
            logAppsFlyerRevenueEvent(
                BuildConfig.APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME,
                AppsFlyerRevenueContract.revenueValues(accumulated),
                cumulativeRevenueAudit,
                auditDetail = "cumulative_value_no_single_ecpm",
            )
            logFirebaseCumulativeRevenue(accumulated, cumulativeRevenueAudit)
        }
    }

    /** Firebase 的唯一自定义事件：只在激励广告完整回调后发送。 */
    fun reportRewardedCompleted(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo?) =
        safely("report rewarded completion") { reportRewardedCompletedInternal(logicalPlacement, adUnitId, adInfo) }

    private fun reportRewardedCompletedInternal(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo?) {
        if (!BuildConfig.EXTERNAL_REPORTING_ENABLED) {
            reportingAudit.recordProvider(FIREBASE, BuildConfig.FIREBASE_REWARDED_EVENT_NAME, ReportingStatus.SKIPPED_DISABLED, logicalPlacement, adUnitId)
            debug("外部上报已关闭：跳过激励完成事件 $logicalPlacement")
            return
        }
        val payload = adInfo?.let { payloadFor(logicalPlacement, adUnitId, it) }
            ?: AdPayload(logicalPlacement, adUnitId, "rewarded", "TopOn", "", 0.0, 0.0, USD)
        val revenueAudit = payload.revenueAuditValues()
        val analytics = firebase ?: run {
            reportingAudit.recordProvider(FIREBASE, BuildConfig.FIREBASE_REWARDED_EVENT_NAME, ReportingStatus.FAILED, logicalPlacement, adUnitId, revenue = revenueAudit, detail = "firebase_unavailable")
            debug("Firebase Analytics unavailable; rewarded-completion event is skipped")
            return
        }
        reportingAudit.recordProvider(FIREBASE, BuildConfig.FIREBASE_REWARDED_EVENT_NAME, ReportingStatus.QUEUED, logicalPlacement, adUnitId, revenue = revenueAudit)
        analytics.logEvent(BuildConfig.FIREBASE_REWARDED_EVENT_NAME, Bundle().apply {
            putString("ad_placement", payload.logicalPlacement)
            putString("ad_unit_id", payload.adUnitId)
            putString("ad_type", payload.adType)
            putString("ad_source", payload.adNetwork)
            putString("ad_source_id", payload.adSourceId)
        })
        reportingAudit.recordProvider(FIREBASE, BuildConfig.FIREBASE_REWARDED_EVENT_NAME, ReportingStatus.SUCCESS, logicalPlacement, adUnitId, revenue = revenueAudit, detail = "sdk_accepted_event; paid_impression_reference")
        debug("Firebase ${BuildConfig.FIREBASE_REWARDED_EVENT_NAME}: $payload")
    }

    private fun payloadFor(logicalPlacement: String, adUnitId: String, adInfo: ATAdInfo): AdPayload {
        val identity = AdNetworkIdentity.from(adInfo)
        return AdPayload(
            logicalPlacement = logicalPlacement,
            adUnitId = adUnitId,
            adType = adInfo.topOnAdFormat.orEmpty().ifBlank { adInfo.format.orEmpty().ifBlank { logicalPlacement } },
            adNetwork = identity.networkName,
            adSourceId = identity.sourceId,
            ecpm = adInfo.ecpm,
            publisherRevenue = adInfo.publisherRevenue,
            currency = adInfo.currency.orEmpty().ifBlank { USD },
        )
    }

    private fun appsFlyerRevenueValues(payload: AdPayload, revenueUsd: BigDecimal): HashMap<String, Any> =
        AppsFlyerRevenueContract.eventValues(
            revenueUsd = revenueUsd,
            adSource = payload.adNetwork,
            adSourceId = payload.adSourceId,
            adUnitId = payload.adUnitId,
            adPlacement = payload.logicalPlacement,
            adType = payload.adType,
        )

    /** SDK 6.15+ requires logAdRevenue for AppsFlyer's impression-level revenue pipeline. */
    private fun logAppsFlyerAdRevenue(payload: AdPayload, revenue: Double, revenueAudit: AdRevenueAuditValues) {
        if (BuildConfig.APPSFLYER_DEV_KEY.isBlank()) {
            reportingAudit.recordProvider(APPSFLYER, "ad_revenue", ReportingStatus.SKIPPED_NOT_CONFIGURED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "dev_key_blank")
            return
        }
        val additionalParameters = hashMapOf<String, Any>(
            "ad_source_id" to payload.adSourceId,
            "ad_unit_id" to payload.adUnitId,
            "ad_placement" to payload.logicalPlacement,
            "ad_type" to payload.adType,
        )
        reportingAudit.recordProvider(APPSFLYER, "ad_revenue", ReportingStatus.QUEUED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "sdk_log_ad_revenue")
        runCatching {
            appsFlyer.logAdRevenue(
                AFAdRevenueData(payload.adNetwork, MediationNetwork.TOPON, payload.currency, revenue),
                additionalParameters,
            )
        }.onSuccess {
            reportingAudit.recordProvider(APPSFLYER, "ad_revenue", ReportingStatus.SUCCESS, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "sdk_accepted_log_ad_revenue")
        }.onFailure { error ->
            reportingAudit.recordProvider(APPSFLYER, "ad_revenue", ReportingStatus.FAILED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = error.message.orEmpty())
            Log.w(TAG, "AppsFlyer ad-revenue submission failed", error)
        }
    }

    /** Request callbacks distinguish a locally queued logEvent call from a successful SDK submission. */
    private fun logAppsFlyerRevenueEvent(
        eventName: String,
        values: HashMap<String, Any>,
        revenueAudit: AdRevenueAuditValues,
        auditDetail: String = "single_paid_impression_value",
    ) {
        reportingAudit.recordProvider(APPSFLYER, eventName, ReportingStatus.QUEUED, values["ad_placement"]?.toString().orEmpty(), values["ad_unit_id"]?.toString().orEmpty(), revenue = revenueAudit)
        appsFlyer.logEvent(appContext, eventName, values, object : AppsFlyerRequestListener {
            override fun onSuccess() = safely("record AppsFlyer event delivery") {
                reportingAudit.recordProvider(APPSFLYER, eventName, ReportingStatus.SUCCESS, values["ad_placement"]?.toString().orEmpty(), values["ad_unit_id"]?.toString().orEmpty(), revenue = revenueAudit, detail = "sdk_callback_success; $auditDetail")
                debug("AppsFlyer $eventName submitted: $values")
            }

            override fun onError(errorCode: Int, errorDescription: String) = safely("record AppsFlyer event failure") {
                reportingAudit.recordProvider(
                    APPSFLYER,
                    eventName,
                    ReportingStatus.FAILED,
                    values["ad_placement"]?.toString().orEmpty(),
                    values["ad_unit_id"]?.toString().orEmpty(),
                    errorCode = errorCode.toString(),
                    revenue = revenueAudit,
                    detail = errorDescription,
                )
                Log.w(TAG, "AppsFlyer $eventName submission failed: code=$errorCode, message=$errorDescription")
            }
        })
    }

    private fun logFirebaseImpression(payload: AdPayload, revenue: Double, revenueAudit: AdRevenueAuditValues) {
        val analytics = firebase ?: run {
            reportingAudit.recordProvider(FIREBASE, "ad_impression", ReportingStatus.FAILED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "firebase_unavailable")
            debug("Firebase Analytics unavailable; paid-impression event is skipped")
            return
        }
        reportingAudit.recordProvider(FIREBASE, "ad_impression", ReportingStatus.QUEUED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit)
        analytics.logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "TopOn")
            putString(FirebaseAnalytics.Param.AD_SOURCE, payload.adNetwork)
            putString("ad_source_id", payload.adSourceId)
            putString(FirebaseAnalytics.Param.AD_FORMAT, payload.adType)
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, payload.adUnitId)
            putString(FirebaseAnalytics.Param.CURRENCY, payload.currency)
            putDouble(FirebaseAnalytics.Param.VALUE, revenue)
            putString("ad_placement", payload.logicalPlacement)
        })
        reportingAudit.recordProvider(FIREBASE, "ad_impression", ReportingStatus.SUCCESS, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "sdk_accepted_event; standard_paid_impression")
        debug("Firebase ad_impression: placement=${payload.logicalPlacement}, value=$revenue")
    }

    private fun logFirebaseCumulativeRevenue(revenueUsd: BigDecimal, revenueAudit: AdRevenueAuditValues) {
        val eventName = FirebaseImpressionContract.cumulativeEventName(BuildConfig.FIREBASE_CUMULATIVE_REVENUE_EVENT_NAME)
        val analytics = firebase ?: run {
            reportingAudit.recordProvider(FIREBASE, eventName, ReportingStatus.FAILED, revenue = revenueAudit, detail = "firebase_unavailable; cumulative_paid_impression")
            return
        }
        reportingAudit.recordProvider(FIREBASE, eventName, ReportingStatus.QUEUED, revenue = revenueAudit, detail = "cumulative_paid_impression")
        analytics.logEvent(eventName, Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "TopOn")
            putString(FirebaseAnalytics.Param.CURRENCY, USD)
            putDouble(FirebaseAnalytics.Param.VALUE, revenueUsd.toDouble())
            putString("revenue_scope", "cumulative_paid_impression")
        })
        reportingAudit.recordProvider(FIREBASE, eventName, ReportingStatus.SUCCESS, revenue = revenueAudit, detail = "sdk_accepted_event; cumulative_paid_impression")
        debug("Firebase $eventName: cumulative_value=${revenueUsd.toPlainString()}")
    }

    private fun reportXuanyuAdShow(payload: AdPayload, uidAttempt: Int = 0, networkAttempt: Int = 0) =
        safely("report Xuanyu ad show") { reportXuanyuAdShowInternal(payload, uidAttempt, networkAttempt) }

    private fun reportXuanyuAdShowInternal(payload: AdPayload, uidAttempt: Int, networkAttempt: Int) {
        val revenueAudit = payload.revenueAuditValues()
        if (!BuildConfig.EXTERNAL_REPORTING_ENABLED) {
            reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.SKIPPED_DISABLED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit)
            return
        }
        val baseUrl = BuildConfig.XUANYU_EVENT_BASE_URL.trim().removeSuffix("/").toHttpUrlOrNull()
        val uid = appsFlyerUid()
        if (baseUrl == null || baseUrl.host != XUANYU_HOST || baseUrl.scheme != "http") {
            reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.SKIPPED_NOT_CONFIGURED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "base_url_not_allowlisted")
            debug("炫羽广告展示上报跳过：地址未通过生产白名单")
            return
        }
        if (!payload.ecpm.isFinite() || payload.ecpm < 0.0) {
            reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.SKIPPED_NOT_CONFIGURED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "invalid_ecpm")
            debug("炫羽广告展示上报跳过：eCPM 无效")
            return
        }
        if (uid.isNullOrBlank()) {
            if (uidAttempt < XUANYU_UID_RETRY_COUNT) {
                mainHandler.postDelayed({ reportXuanyuAdShow(payload, uidAttempt + 1, networkAttempt) }, XUANYU_UID_RETRY_DELAY_MS)
            } else {
                reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.FINAL_FAILED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "appsflyer_uid_not_ready")
                Log.w(TAG, "炫羽广告展示上报跳过：AppsFlyer uid 未就绪")
            }
            return
        }
        val url = baseUrl.newBuilder()
            .addPathSegments("event/w_ad_show")
            .addQueryParameter("uid", uid)
            .addQueryParameter("app_ver", BuildConfig.VERSION_NAME)
            .addQueryParameter("pkg_name", appContext.packageName)
            .addQueryParameter("ad_type", payload.adType)
            .addQueryParameter("ad_network", payload.adNetwork)
            .addQueryParameter("ad_source_id", payload.adSourceId)
            .addQueryParameter("ecpm", formatDecimal(payload.ecpm.coerceAtLeast(0.0)))
            .build()
        reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.STARTED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "http_request_ecpm")
        httpClient.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = safely("handle Xuanyu HTTP failure") {
                if (networkAttempt < XUANYU_NETWORK_RETRY_COUNT) {
                    val nextAttempt = networkAttempt + 1
                    reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.RETRYING, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "attempt=$nextAttempt")
                    Log.w(TAG, "炫羽广告展示上报失败（第 ${nextAttempt} 次重试前）：${error.message}")
                    mainHandler.postDelayed(
                        { reportXuanyuAdShow(payload, uidAttempt, nextAttempt) },
                        XUANYU_NETWORK_RETRY_DELAY_MS,
                    )
                } else {
                    reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.FINAL_FAILED, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = error.message.orEmpty())
                    Log.w(TAG, "炫羽广告展示上报最终失败：${error.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) = safely("handle Xuanyu HTTP response") {
                response.use {
                    if (it.isSuccessful) {
                        reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.SUCCESS, payload.logicalPlacement, payload.adUnitId, revenue = revenueAudit, detail = "http_${it.code}; sent_ecpm; derived_value")
                        debug("炫羽广告展示上报成功：${payload.logicalPlacement}")
                    } else if (it.code >= 500 && networkAttempt < XUANYU_NETWORK_RETRY_COUNT) {
                        val nextAttempt = networkAttempt + 1
                        reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.RETRYING, payload.logicalPlacement, payload.adUnitId, errorCode = it.code.toString(), revenue = revenueAudit, detail = "attempt=$nextAttempt")
                        Log.w(TAG, "炫羽广告展示上报失败：HTTP ${it.code}，将重试")
                        mainHandler.postDelayed(
                            { reportXuanyuAdShow(payload, uidAttempt, nextAttempt) },
                            XUANYU_NETWORK_RETRY_DELAY_MS,
                        )
                    } else {
                        reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.FINAL_FAILED, payload.logicalPlacement, payload.adUnitId, errorCode = it.code.toString(), revenue = revenueAudit)
                        Log.w(TAG, "炫羽广告展示上报最终失败：HTTP ${it.code}")
                    }
                }
            }
        })
    }

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun AdPayload.revenueAuditValues(): AdRevenueAuditValues =
        AdRevenueAuditValues.fromPaidImpression(currency, ecpm, publisherRevenue)

    private fun safely(operation: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            Log.w(TAG, "Analytics telemetry skipped: $operation", error)
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG || BuildConfig.ANALYTICS_DEBUG) Log.d(TAG, message)
    }

    private fun AttributionExperienceStore.AttributionRecord.toRewardsPayload(): RewardsApiClient.AttributionPayload =
        RewardsApiClient.AttributionPayload(
            appsFlyerUid = appsFlyerUid(),
            afStatus = afStatus,
            mediaSource = mediaSource,
            campaign = campaign,
            channel = channel,
        )

    private fun recordAllDisabled(eventName: String, placement: String, adUnitId: String) {
        reportingAudit.recordProvider(APPSFLYER, eventName, ReportingStatus.SKIPPED_DISABLED, placement, adUnitId)
        reportingAudit.recordProvider(FIREBASE, eventName, ReportingStatus.SKIPPED_DISABLED, placement, adUnitId)
        reportingAudit.recordProvider(XUANYU, "w_ad_show", ReportingStatus.SKIPPED_DISABLED, placement, adUnitId)
    }

    data class AdPayload(
        val logicalPlacement: String,
        val adUnitId: String,
        val adType: String,
        val adNetwork: String,
        val adSourceId: String,
        val ecpm: Double,
        val publisherRevenue: Double,
        val currency: String,
    )

    companion object {
        private const val TAG = "WhiteLabelAnalytics"
        private const val USD = "USD"
        private const val XUANYU_UID_RETRY_COUNT = 3
        private const val XUANYU_UID_RETRY_DELAY_MS = 750L
        private const val XUANYU_NETWORK_RETRY_COUNT = 2
        private const val XUANYU_NETWORK_RETRY_DELAY_MS = 1_500L
        private const val XUANYU_HOST = "api.eventtrack.top"
        @Volatile
        private var instance: EngagementAnalytics? = null

        fun get(context: Context): EngagementAnalytics = instance ?: synchronized(this) {
            instance ?: EngagementAnalytics(context.applicationContext).also { instance = it }
        }
    }
}
