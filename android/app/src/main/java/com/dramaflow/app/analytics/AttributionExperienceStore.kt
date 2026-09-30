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

    fun current(): MonetizationExperience = when (preferences.getString(EXPERIENCE_KEY, MonetizationExperience.ORGANIC_A.wireValue)) {
        MonetizationExperience.ATTRIBUTED_B.wireValue -> MonetizationExperience.ATTRIBUTED_B
        else -> MonetizationExperience.ORGANIC_A
    }

    /** Updates the surface only from the documented AppsFlyer `af_status` conversion field. */
    fun applyConversionData(conversionData: Map<String, Any>?): MonetizationExperience {
        val afStatus = conversionData?.get(AF_STATUS_KEY)?.toString()?.trim().orEmpty()
        val mediaSource = conversionData?.get(MEDIA_SOURCE_KEY)?.toString()?.trim().orEmpty()
        val experience = select(afStatus)
        preferences.edit()
            .putString(EXPERIENCE_KEY, experience.wireValue)
            .putString(LAST_AF_STATUS_KEY, afStatus.take(MAX_DIAGNOSTIC_LENGTH))
            .putString(LAST_MEDIA_SOURCE_KEY, mediaSource.take(MAX_DIAGNOSTIC_LENGTH))
            .apply()
        return experience
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
        private const val AF_STATUS_KEY = "af_status"
        private const val MEDIA_SOURCE_KEY = "media_source"
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
    }
}
