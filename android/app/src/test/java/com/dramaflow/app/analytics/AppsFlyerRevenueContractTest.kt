package com.dramaflow.app.analytics

import com.appsflyer.AFInAppEventParameterName
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppsFlyerRevenueContractTest {
    @Test
    fun `converts USD eCPM into one paid-impression revenue`() {
        assertEquals(0, BigDecimal("0.004").compareTo(AppsFlyerRevenueContract.usdRevenueFromEcpm("USD", 4.0)))
        assertEquals(0, BigDecimal("0.00047732848713635506").compareTo(AppsFlyerRevenueContract.usdRevenueFromEcpm("usd", 0.47732848713635506)))
    }

    @Test
    fun `excludes non USD invalid and nonpositive paid-impression values`() {
        assertNull(AppsFlyerRevenueContract.usdRevenueFromEcpm("BRL", 10.0))
        assertNull(AppsFlyerRevenueContract.usdRevenueFromEcpm("USD", 0.0))
        assertNull(AppsFlyerRevenueContract.usdRevenueFromEcpm("USD", Double.NaN))
        assertNull(AppsFlyerRevenueContract.usdRevenueFromEcpm("USD", Double.POSITIVE_INFINITY))
    }

    @Test
    fun `uses standard AppsFlyer revenue and currency keys`() {
        val values = AppsFlyerRevenueContract.eventValues(
            revenueUsd = BigDecimal("0.012"),
            adSource = "Topon Adx",
            adUnitId = "unit",
            adPlacement = "splash",
            adType = "Splash",
        )
        assertEquals(0.012, values[AFInAppEventParameterName.REVENUE])
        assertEquals("USD", values[AFInAppEventParameterName.CURRENCY])
        assertEquals("TopOn", values["ad_platform"])
        assertEquals("splash", values["ad_placement"])
    }

    @Test
    fun `cumulative revenue batch avoids attributing mixed placements to its final impression`() {
        val values = AppsFlyerRevenueContract.revenueValues(BigDecimal("0.012"))
        assertEquals(0.012, values[AFInAppEventParameterName.REVENUE])
        assertEquals("USD", values[AFInAppEventParameterName.CURRENCY])
        assertEquals(setOf(AFInAppEventParameterName.REVENUE, AFInAppEventParameterName.CURRENCY), values.keys)
    }

    @Test
    fun `sends cumulative event at exactly one cent and resets memory balance`() {
        val first = AppsFlyerRevenueContract.appendAndTakeReadyBatch(BigDecimal.ZERO, BigDecimal("0.004"))
        assertEquals(BigDecimal("0.004"), first.first)
        assertNull(first.second)

        val second = AppsFlyerRevenueContract.appendAndTakeReadyBatch(first.first, BigDecimal("0.006"))
        assertEquals(BigDecimal.ZERO, second.first)
        assertEquals(BigDecimal("0.010"), second.second)
    }

    @Test
    fun `cumulative event contains the complete accumulated amount before reset`() {
        val first = AppsFlyerRevenueContract.appendAndTakeReadyBatch(BigDecimal("0.009"), BigDecimal("0.003"))
        assertEquals(BigDecimal.ZERO, first.first)
        assertEquals(BigDecimal("0.012"), first.second)
    }
}
