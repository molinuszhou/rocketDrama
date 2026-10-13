package com.dramaflow.app.ads

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Exact revenue values retained in local audit records. TopOn's paid callback exposes both eCPM
 * and publisherRevenue; publisherRevenue is the canonical per-impression value. eCPM / 1,000 is
 * retained only as a backward-compatible fallback for non-paid display diagnostics.
 */
data class AdRevenueAuditValues(
    val currency: String = "",
    val ecpm: String = "",
    val value: String = "",
) {
    companion object {
        fun fromPaidImpression(currency: String?, ecpm: Double, publisherRevenue: Double? = null): AdRevenueAuditValues {
            val normalizedCurrency = currency.orEmpty().trim().uppercase(Locale.ROOT)
            if (normalizedCurrency.isBlank() || !ecpm.isFinite() || ecpm < 0.0) return AdRevenueAuditValues()
            val rawEcpm = BigDecimal.valueOf(ecpm)
            val rawValue = publisherRevenue
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let(BigDecimal::valueOf)
                ?: rawEcpm.movePointLeft(3)
            return AdRevenueAuditValues(
                currency = normalizedCurrency,
                ecpm = format(rawEcpm),
                value = format(rawValue),
            )
        }

        /** For the AppsFlyer accumulated event, which deliberately has no single-source eCPM. */
        fun fromReportedValue(currency: String, value: BigDecimal): AdRevenueAuditValues =
            AdRevenueAuditValues(
                currency = currency.trim().uppercase(Locale.ROOT),
                value = format(value),
            )

        private fun format(value: BigDecimal): String =
            value.setScale(12, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
