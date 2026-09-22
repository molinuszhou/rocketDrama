package com.dramaflow.app.ads

import android.content.Context
import com.dramaflow.app.BuildConfig

/**
 * 版本 1.6 白包广告配置。
 *
 * 测试参数可以来自 local.properties、Gradle 参数或环境变量；正式环境必须改用各
 * 合作方独立的安全配置，不应把正式密钥写死在客户端仓库中。
 */
data class TopOnSettings(
    val appId: String,
    val appKey: String,
    val rewardedPlacementId: String,
    val interstitialPlacementId: String,
    val splashPlacementId: String,
    val isDemoMode: Boolean,
    val enableNetworkDebug: Boolean,
) {
    val isConfigured: Boolean
        get() = BuildConfig.ADS_ENABLED && appId.isNotBlank() && appKey.isNotBlank()

    val canShowRewarded: Boolean
        get() = BuildConfig.ADS_ENABLED && isConfigured && rewardedPlacementId.isNotBlank()

    val canShowInterstitial: Boolean
        get() = BuildConfig.ADS_ENABLED && isConfigured && interstitialPlacementId.isNotBlank()

    val canShowSplash: Boolean
        get() = BuildConfig.ADS_ENABLED && isConfigured && splashPlacementId.isNotBlank()

    companion object {
        fun fromBuildConfig(): TopOnSettings = TopOnSettings(
            appId = BuildConfig.TOPON_APP_ID.trim(),
            appKey = BuildConfig.TOPON_APP_KEY.trim(),
            rewardedPlacementId = BuildConfig.TOPON_REWARDED_PLACEMENT_ID.trim(),
            interstitialPlacementId = BuildConfig.TOPON_INTERSTITIAL_PLACEMENT_ID.trim(),
            splashPlacementId = BuildConfig.TOPON_SPLASH_PLACEMENT_ID.trim(),
            isDemoMode = BuildConfig.TOPON_DEMO_MODE,
            enableNetworkDebug = BuildConfig.DEBUG || BuildConfig.ANALYTICS_DEBUG,
        )
    }
}

enum class AdConsent { UNKNOWN, GRANTED, DECLINED }

/** 广告展示授权的本地记录；生产环境还应按适用地区接入正式的 UMP/GDPR 流程。 */
class AdConsentStore(context: Context) {
    private val preferences = context.getSharedPreferences("dramaflow.ad_consent", Context.MODE_PRIVATE)

    fun read(): AdConsent = when (preferences.getString(KEY_CONSENT, null)) {
        AdConsent.GRANTED.name -> AdConsent.GRANTED
        AdConsent.DECLINED.name -> AdConsent.DECLINED
        else -> AdConsent.UNKNOWN
    }

    fun save(consent: AdConsent) {
        preferences.edit().putString(KEY_CONSENT, consent.name).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_CONSENT).apply()
    }

    private companion object {
        const val KEY_CONSENT = "ads_consent"
    }
}
