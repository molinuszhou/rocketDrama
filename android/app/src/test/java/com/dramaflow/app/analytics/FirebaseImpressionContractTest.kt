package com.dramaflow.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseImpressionContractTest {
    @Test
    fun `default cumulative event is partner ad impression 001`() {
        assertEquals(
            "ad_impression_001",
            FirebaseImpressionContract.cumulativeEventName("ad_impression_001"),
        )
    }

    @Test
    fun `blank cumulative event falls back to partner ad impression 001`() {
        assertEquals(
            "ad_impression_001",
            FirebaseImpressionContract.cumulativeEventName("   "),
        )
    }

    @Test
    fun `invalid configured name falls back to safe partner event`() {
        assertEquals(
            "ad_impression_001",
            FirebaseImpressionContract.cumulativeEventName("../w_ad_show"),
        )
    }
}
