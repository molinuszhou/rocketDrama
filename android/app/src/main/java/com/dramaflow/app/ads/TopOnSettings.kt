package com.dramaflow.app.ads

import android.content.Context
import com.dramaflow.app.BuildConfig

/**
 * TopOn 正式广告配置。
 *
 * 真实广告参数通过受控构建配置注入，禁止写入客户端仓库中。
 */
data class TopOnSettings(
    val appId: String,
    val appKey: String,
    val rewardedPlacementId: String,
    val interstitialPlacementId: String,
    val splashPlacementId: String,
    /** Configured through TOPON_REWARDED_LOAD_TIMEOUT_MS; Gradle clamps it to 1–60 seconds. */
    val rewardedLoadTimeoutMillis: Long,
    val isDemoMode: Boolean,
    val enableNetworkDebug: Boolean,
) {
    val isConfigured: Boolean
        get() = appId.isNotBlank() && appKey.isNotBlank()

    val canShowRewarded: Boolean
        get() = isConfigured && rewardedPlacementId.isNotBlank()

    val canShowInterstitial: Boolean
        get() = isConfigured && interstitialPlacementId.isNotBlank()

    val canShowSplash: Boolean
        get() = isConfigured && splashPlacementId.isNotBlank()

    companion object {
        fun fromBuildConfig(): TopOnSettings = TopOnSettings(
            appId = BuildConfig.TOPON_APP_ID.trim(),
            appKey = BuildConfig.TOPON_APP_KEY.trim(),
            rewardedPlacementId = BuildConfig.TOPON_REWARDED_PLACEMENT_ID.trim(),
            interstitialPlacementId = BuildConfig.TOPON_INTERSTITIAL_PLACEMENT_ID.trim(),
            splashPlacementId = BuildConfig.TOPON_SPLASH_PLACEMENT_ID.trim(),
            rewardedLoadTimeoutMillis = BuildConfig.TOPON_REWARDED_LOAD_TIMEOUT_MS,
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
