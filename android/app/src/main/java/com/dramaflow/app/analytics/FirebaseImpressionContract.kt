package com.dramaflow.app.analytics

/**
 * Firebase ad-revenue callback contract.
 *
 * The standard `ad_impression` event is emitted per valid paid impression. The
 * partner's custom `ad_impression_001` event mirrors the existing AppsFlyer
 * cumulative-revenue threshold, so its value never represents only the final
 * ad source in a mixed batch.
 */
internal object FirebaseImpressionContract {
    const val DEFAULT_CUMULATIVE_EVENT_NAME = "ad_impression_001"

    fun cumulativeEventName(configuredName: String): String {
        val candidate = configuredName.trim()
        return candidate.takeIf { EVENT_NAME.matches(it) } ?: DEFAULT_CUMULATIVE_EVENT_NAME
    }

    private val EVENT_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
}
