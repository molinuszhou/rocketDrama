package com.dramaflow.app.analytics

import com.appsflyer.AFInAppEventParameterName
import java.math.BigDecimal
import java.util.Locale

/**
 * Pure, testable contract for the partner-defined AppsFlyer advertising-revenue events.
 *
 * TopOn supplies USD eCPM in its paid-impression callback. This contract converts it to
 * one-impression revenue and creates the standard AppsFlyer revenue parameter payload.
 * No state is persisted: process restarts intentionally discard any sub-threshold remainder.
 */
internal object AppsFlyerRevenueContract {
    const val USD = "USD"
    val cumulativeThresholdUsd: BigDecimal = BigDecimal("0.01")

    fun usdRevenueFromEcpm(currency: String, ecpm: Double): BigDecimal? {
        if (currency.trim().uppercase(Locale.ROOT) != USD || !ecpm.isFinite() || ecpm <= 0.0) return null
        return BigDecimal.valueOf(ecpm).movePointLeft(3).takeIf { it.signum() > 0 }
    }

    fun eventValues(
        revenueUsd: BigDecimal,
        adSource: String,
        adSourceId: String,
        adUnitId: String,
        adPlacement: String,
        adType: String,
    ): HashMap<String, Any> = revenueValues(revenueUsd).apply {
        put("ad_platform", "TopOn")
        put("ad_source", adSource)
        put("ad_source_id", adSourceId)
        put("ad_unit_id", adUnitId)
        put("ad_placement", adPlacement)
        put("ad_type", adType)
    }

    /** A batch may contain more than one placement or network, so it intentionally has no ad-source metadata. */
    fun revenueValues(revenueUsd: BigDecimal): HashMap<String, Any> = hashMapOf(
        AFInAppEventParameterName.REVENUE to revenueUsd.toDouble(),
        AFInAppEventParameterName.CURRENCY to USD,
    )

    /** Returns the post-event remainder and the full batch to send, if the batch reached US$0.01. */
    fun appendAndTakeReadyBatch(pendingUsd: BigDecimal, revenueUsd: BigDecimal): Pair<BigDecimal, BigDecimal?> {
        val accumulated = pendingUsd.add(revenueUsd)
        return if (accumulated >= cumulativeThresholdUsd) BigDecimal.ZERO to accumulated else accumulated to null
    }
}
