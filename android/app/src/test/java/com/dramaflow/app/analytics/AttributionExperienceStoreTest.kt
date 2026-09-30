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
}
