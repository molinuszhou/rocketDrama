package com.dramaflow.app.ads

import android.content.Context
import com.dramaflow.app.BuildConfig

data class PangleSettings(
    val appId: String,
    val rewardedPlacementId: String,
    val interstitialPlacementId: String,
    val appOpenPlacementId: String,
    val pauseNativePlacementId: String
) {
    val isConfigured: Boolean
        get() = appId.isNotBlank() && rewardedPlacementId.isNotBlank()

    val canShowRewarded: Boolean
        get() = isConfigured

    val canShowInterstitial: Boolean
        get() = appId.isNotBlank() && interstitialPlacementId.isNotBlank()

    val canShowAppOpen: Boolean
        get() = appId.isNotBlank() && appOpenPlacementId.isNotBlank()

    /** 暂停广告采用原生卡片，可由应用自己的“返回播放”按钮随时关闭。 */
    val canShowPauseNative: Boolean
        get() = appId.isNotBlank() && pauseNativePlacementId.isNotBlank()

    companion object {
        fun fromBuildConfig(): PangleSettings = PangleSettings(
            appId = BuildConfig.PANGLE_APP_ID.trim(),
            rewardedPlacementId = BuildConfig.PANGLE_REWARDED_SLOT_ID.trim(),
            interstitialPlacementId = BuildConfig.PANGLE_INTERSTITIAL_SLOT_ID.trim(),
            appOpenPlacementId = BuildConfig.PANGLE_APP_OPEN_SLOT_ID.trim(),
            pauseNativePlacementId = BuildConfig.PANGLE_PAUSE_NATIVE_SLOT_ID.trim()
        )
    }
}

enum class PangleConsent { UNKNOWN, GRANTED, DECLINED }

class PangleConsentStore(context: Context) {
    private val preferences = context.getSharedPreferences("dramaflow.pangle", Context.MODE_PRIVATE)

    fun read(): PangleConsent = when (preferences.getString(KEY_CONSENT, null)) {
        PangleConsent.GRANTED.name -> PangleConsent.GRANTED
        PangleConsent.DECLINED.name -> PangleConsent.DECLINED
        else -> PangleConsent.UNKNOWN
    }

    fun save(consent: PangleConsent) {
        preferences.edit().putString(KEY_CONSENT, consent.name).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_CONSENT).apply()
    }

    private companion object {
        const val KEY_CONSENT = "ads_consent"
    }
}
