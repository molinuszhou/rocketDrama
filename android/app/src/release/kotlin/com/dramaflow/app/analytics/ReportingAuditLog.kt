package com.dramaflow.app.analytics

import android.content.Context
import com.dramaflow.app.ads.AdRevenueAuditValues

/**
 * Release source set deliberately has no device-local AppsFlyer/Firebase audit trail.
 *
 * Third-party reporting remains active in [EngagementAnalytics]. A privacy-minimised delivery
 * outcome can be mirrored to the Rewards server for protected operations review, but this class
 * never creates a device CSV, exporter, share intent, or any local reconciliation record.
 */
class ReportingAuditLog private constructor(context: Context) {
    private val deliveryReporter = ReportingDeliveryEventReporter(context.applicationContext)
    data class Summary(val eventCount: Int, val lastRecordedAtUtc: String?)

    fun record(
        provider: String,
        eventName: String,
        status: String,
        placement: String = "",
        adUnitId: String = "",
        errorCode: String = "",
        revenue: AdRevenueAuditValues = AdRevenueAuditValues(),
        detail: String = "",
    ) = deliveryReporter.record(provider, eventName, status, placement, adUnitId, errorCode, revenue)

    fun summary(): Summary = Summary(eventCount = 0, lastRecordedAtUtc = null)

    companion object {
        @Volatile private var instance: ReportingAuditLog? = null

        fun get(context: Context): ReportingAuditLog = instance ?: synchronized(this) {
            instance ?: ReportingAuditLog(context).also { instance = it }
        }
    }
}

internal enum class ReportingProvider(val wireName: String) {
    APPSFLYER("AppsFlyer"),
    FIREBASE("Firebase"),
    REWARDS_OPERATIONS("Rewards Operations"),
}

internal object ReportingStatus {
    const val SKIPPED_DISABLED = "SKIPPED_DISABLED"
    const val SKIPPED_NOT_CONFIGURED = "SKIPPED_NOT_CONFIGURED"
    const val STARTED = "STARTED"
    const val QUEUED = "QUEUED"
    const val SUCCESS = "SUCCESS"
    const val RETRYING = "RETRYING"
    const val FAILED = "FAILED"
    const val FINAL_FAILED = "FINAL_FAILED"
}

internal fun ReportingAuditLog.recordProvider(
    provider: ReportingProvider,
    eventName: String,
    status: String,
    placement: String = "",
    adUnitId: String = "",
    errorCode: String = "",
    revenue: AdRevenueAuditValues = AdRevenueAuditValues(),
    detail: String = "",
) = record(provider.wireName, eventName, status, placement, adUnitId, errorCode, revenue, detail)
