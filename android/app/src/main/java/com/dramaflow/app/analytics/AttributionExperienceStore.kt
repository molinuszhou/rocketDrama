package com.dramaflow.app.analytics

import android.content.Context

/**
 * Selects the release monetization surface from AppsFlyer's immutable install attribution.
 *
 * A (organic) is deliberately the safe default while AppsFlyer is still resolving an install.
 * B is selected only after AppsFlyer reports a non-organic install. The selected surface is
 * stored locally because AppsFlyer invokes the conversion callback again on foreground opens.
 */
enum class MonetizationExperience(val wireValue: String) {
    ORGANIC_A("A"),
    ATTRIBUTED_B("B"),
}

class AttributionExperienceStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    data class AttributionRecord(
        val afStatus: String,
        val mediaSource: String?,
        val campaign: String?,
        val channel: String?,
        val experience: MonetizationExperience,
    )

    fun current(): MonetizationExperience = when (preferences.getString(EXPERIENCE_KEY, MonetizationExperience.ORGANIC_A.wireValue)) {
        MonetizationExperience.ATTRIBUTED_B.wireValue -> MonetizationExperience.ATTRIBUTED_B
        else -> MonetizationExperience.ORGANIC_A
    }

    /** Updates the surface only from the documented AppsFlyer `af_status` conversion field. */
    fun applyConversionData(conversionData: Map<String, Any>?): MonetizationExperience {
        val record = recordFor(conversionData)
        preferences.edit()
            .putString(EXPERIENCE_KEY, record.experience.wireValue)
            .putString(LAST_AF_STATUS_KEY, record.afStatus)
            .putString(LAST_MEDIA_SOURCE_KEY, record.mediaSource.orEmpty())
            .putString(LAST_CAMPAIGN_KEY, record.campaign.orEmpty())
            .putString(LAST_CHANNEL_KEY, record.channel.orEmpty())
            .apply()
        return record.experience
    }

    /** Returns the current minimal record for Rewards syncing; unresolved installs return null. */
    fun currentRecord(): AttributionRecord? {
        val status = preferences.getString(LAST_AF_STATUS_KEY, "").orEmpty().trim()
        if (status.isBlank()) return null
        return AttributionRecord(
            afStatus = status,
            mediaSource = preferences.getString(LAST_MEDIA_SOURCE_KEY, "").orEmpty().ifBlank { null },
            campaign = preferences.getString(LAST_CAMPAIGN_KEY, "").orEmpty().ifBlank { null },
            channel = preferences.getString(LAST_CHANNEL_KEY, "").orEmpty().ifBlank { null },
            experience = current(),
        )
    }

    fun diagnosticSummary(): String {
        val status = preferences.getString(LAST_AF_STATUS_KEY, "pending").orEmpty()
        val source = preferences.getString(LAST_MEDIA_SOURCE_KEY, "").orEmpty()
        return if (source.isBlank()) "af_status=$status; surface=${current().wireValue}" else {
            "af_status=$status; media_source=$source; surface=${current().wireValue}"
        }
    }

    companion object {
        private const val PREFERENCES = "joyedrama.attribution_experience"
        private const val EXPERIENCE_KEY = "experience"
        private const val LAST_AF_STATUS_KEY = "last_af_status"
        private const val LAST_MEDIA_SOURCE_KEY = "last_media_source"
        private const val LAST_CAMPAIGN_KEY = "last_campaign"
        private const val LAST_CHANNEL_KEY = "last_channel"
        private const val AF_STATUS_KEY = "af_status"
        private const val MEDIA_SOURCE_KEY = "media_source"
        private const val CAMPAIGN_KEY = "campaign"
        private const val CHANNEL_KEY = "af_channel"
        private const val NON_ORGANIC = "Non-organic"
        private const val MAX_DIAGNOSTIC_LENGTH = 120

        @Volatile
        private var instance: AttributionExperienceStore? = null

        fun get(context: Context): AttributionExperienceStore = instance ?: synchronized(this) {
            instance ?: AttributionExperienceStore(context).also { instance = it }
        }

        internal fun select(afStatus: String?): MonetizationExperience =
            if (afStatus?.trim().equals(NON_ORGANIC, ignoreCase = true)) {
                MonetizationExperience.ATTRIBUTED_B
            } else {
                // Organic and unresolved conversion data share the low-risk A surface.
                MonetizationExperience.ORGANIC_A
            }

        internal fun recordFor(conversionData: Map<String, Any>?): AttributionRecord {
            fun value(key: String): String? = conversionData?.get(key)?.toString()?.trim()
                ?.take(MAX_DIAGNOSTIC_LENGTH)
                ?.ifBlank { null }
            val afStatus = value(AF_STATUS_KEY).orEmpty()
            return AttributionRecord(
                afStatus = afStatus,
                mediaSource = value(MEDIA_SOURCE_KEY),
                campaign = value(CAMPAIGN_KEY),
                channel = value(CHANNEL_KEY),
                experience = select(afStatus),
            )
        }
    }
}
