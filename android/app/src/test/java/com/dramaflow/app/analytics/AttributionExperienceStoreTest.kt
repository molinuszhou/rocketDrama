package com.dramaflow.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AttributionExperienceStoreTest {
    @Test
    fun `organic and unresolved installs use A surface`() {
        assertEquals(MonetizationExperience.ORGANIC_A, AttributionExperienceStore.select("Organic"))
        assertEquals(MonetizationExperience.ORGANIC_A, AttributionExperienceStore.select(null))
        assertEquals(MonetizationExperience.ORGANIC_A, AttributionExperienceStore.select(""))
    }

    @Test
    fun `non organic install uses B surface`() {
        assertEquals(MonetizationExperience.ATTRIBUTED_B, AttributionExperienceStore.select("Non-organic"))
        assertEquals(MonetizationExperience.ATTRIBUTED_B, AttributionExperienceStore.select("non-organic"))
    }

    @Test
    fun `conversion record keeps the minimal server attribution fields`() {
        val record = AttributionExperienceStore.recordFor(
            mapOf(
                "af_status" to "Non-organic",
                "media_source" to "facebook",
                "campaign" to "launch-campaign",
                "af_channel" to "paid-social",
            ),
        )
        assertEquals(MonetizationExperience.ATTRIBUTED_B, record.experience)
        assertEquals("facebook", record.mediaSource)
        assertEquals("launch-campaign", record.campaign)
        assertEquals("paid-social", record.channel)
    }
}
