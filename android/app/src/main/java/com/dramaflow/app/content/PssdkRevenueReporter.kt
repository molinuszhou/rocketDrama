package com.dramaflow.app.content

import android.util.Log
import com.bytedance.sdk.shortplay.api.PSSDK
import com.dramaflow.app.BuildConfig
import java.util.Locale

/**
 * Bridges a real TopOn paid impression to the PSSDK IAA revenue contract.
 *
 * This intentionally reports only advertising revenue (scenario 1). Purchase/IAP
 * reporting is not synthesized because JoyeDrama has no billing transaction here.
 */
object PssdkRevenueReporter {
    private const val TAG = "PssdkRevenueReporter"

    /**
     * Reports one displayed ad after TopOn has supplied its paid-impression metadata.
     * [revenue] is the value of one impression, not the eCPM amount.
     */
    fun reportAdImpression(
        adnName: String,
        currency: String?,
        revenue: Double,
        adFormat: String,
        aboutUnlock: Boolean,
    ) {
        if (!BuildConfig.SHORT_DRAMA_SDK_ENABLED || !PSSDK.hasInitialized()) {
            Log.i(TAG, "Skip PSSDK IAA report: short-drama SDK is not ready")
            return
        }
        if (!revenue.isFinite() || revenue < 0.0) {
            Log.w(TAG, "Skip PSSDK IAA report: invalid revenue=$revenue")
            return
        }
        val currencyType = currencyType(currency) ?: run {
            Log.w(TAG, "Skip PSSDK IAA report: unsupported currency=${currency.orEmpty()}")
            return
        }
        val format = adFormat(adFormat) ?: run {
            Log.w(TAG, "Skip PSSDK IAA report: unsupported ad format=$adFormat")
            return
        }
        runCatching {
            val info = PSSDK.RevenueInfo(PSSDK.RevenueInfo.RevenueType.IAA, currencyType)
                .revenue(revenue.toFloat())
                .adnName(adnName.ifBlank { "TopOn" })
                .adFormat(format)
                .aboutUnlock(aboutUnlock)
            PSSDK.reportRevenueInfo(info)
            Log.i(TAG, "PSSDK IAA revenue reported: adn=${adnName.ifBlank { "TopOn" }}, currency=${currencyType.name}, revenue=$revenue, format=$format, aboutUnlock=$aboutUnlock")
        }.onFailure { error ->
            // PSSDK reporting must never block ad display, reward settlement, or playback.
            Log.w(TAG, "PSSDK IAA revenue report failed", error)
        }
    }

    private fun currencyType(raw: String?): PSSDK.RevenueInfo.CurrencyType? = when (raw.orEmpty().trim().uppercase(Locale.ROOT)) {
        "USD" -> PSSDK.RevenueInfo.CurrencyType.USD
        "VND" -> PSSDK.RevenueInfo.CurrencyType.VND
        "CNY" -> PSSDK.RevenueInfo.CurrencyType.CNY
        "THP", "THB" -> PSSDK.RevenueInfo.CurrencyType.THP
        "RM", "MYR" -> PSSDK.RevenueInfo.CurrencyType.RM
        "IDR" -> PSSDK.RevenueInfo.CurrencyType.IDR
        "SGD" -> PSSDK.RevenueInfo.CurrencyType.SGD
        else -> null
    }

    private fun adFormat(raw: String): PSSDK.RevenueInfo.AdFormat? = when (raw.trim().lowercase(Locale.ROOT)) {
        "rewarded", "reward_video" -> PSSDK.RevenueInfo.AdFormat.REWARD_VIDEO
        "interstitial", "interstitial_video" -> PSSDK.RevenueInfo.AdFormat.INTERSTITIAL_VIDEO
        "splash", "app_open" -> PSSDK.RevenueInfo.AdFormat.APP_OPEN
        else -> null
    }
}
