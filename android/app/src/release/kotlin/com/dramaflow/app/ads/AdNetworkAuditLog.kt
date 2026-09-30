package com.dramaflow.app.ads

import android.content.Context
import com.secmtp.sdk.core.api.ATAdInfo

/**
 * Release source set deliberately has no device-local ADN audit trail.
 *
 * This no-op preserves the shared ad-lifecycle call graph while ensuring the production
 * package cannot create, retain, export, or share mediation audit files.
 */
class AdNetworkAuditLog private constructor() {
    data class Summary(
        val eventCount: Int,
        val lastRecordedAtUtc: String?,
    )

    fun recordConfiguration(settings: TopOnSettings) = Unit

    fun record(
        event: String,
        logicalPlacement: String,
        topOnPlacementId: String,
        adFormat: String,
        adNetwork: String = "",
        adSourceId: String = "",
        result: String = "",
        errorCode: Int? = null,
        revenue: AdRevenueAuditValues = AdRevenueAuditValues(),
        detail: String = "",
    ) = Unit

    fun recordPaidRevenue(
        logicalPlacement: String,
        topOnPlacementId: String,
        adFormat: String,
        adInfo: ATAdInfo,
    ) = Unit

    fun summary(): Summary = Summary(eventCount = 0, lastRecordedAtUtc = null)

    companion object {
        private val instance = AdNetworkAuditLog()

        fun get(context: Context): AdNetworkAuditLog = instance
    }
}
