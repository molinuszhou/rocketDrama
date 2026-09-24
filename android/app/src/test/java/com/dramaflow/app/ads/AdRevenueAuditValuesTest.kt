package com.dramaflow.app.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AdRevenueAuditValuesTest {
    @Test
    fun `retains TopOn currency eCPM and per-impression value`() {
        val values = AdRevenueAuditValues.fromPaidImpression("usd", 0.47732848713635506)

        assertEquals("USD", values.currency)
        assertEquals("0.477328487136", values.ecpm)
        assertEquals("0.000477328487", values.value)
    }

    @Test
    fun `does not invent values when the paid impression is invalid`() {
        val values = AdRevenueAuditValues.fromPaidImpression("USD", Double.NaN)

        assertTrue(values.currency.isEmpty())
        assertTrue(values.ecpm.isEmpty())
        assertTrue(values.value.isEmpty())
    }

    @Test
    fun `marks AppsFlyer cumulative value without a misleading eCPM`() {
        val values = AdRevenueAuditValues.fromReportedValue("usd", BigDecimal("0.012"))

        assertEquals("USD", values.currency)
        assertTrue(values.ecpm.isEmpty())
        assertEquals("0.012", values.value)
    }
}
