package com.dramaflow.app.analytics

import android.content.Context
import com.dramaflow.app.ads.AdRevenueAuditValues

/**
 * Release source set deliberately has no device-local AppsFlyer/Firebase/Xuanyu audit trail.
 *
 * Third-party reporting remains active in [EngagementAnalytics]. This no-op only removes the
 * internal CSV reconciliation record and its export surface from the production package.
 */
class ReportingAuditLog private constructor() {
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
    ) = Unit

    fun summary(): Summary = Summary(eventCount = 0, lastRecordedAtUtc = null)

    companion object {
        private val instance = ReportingAuditLog()

        fun get(context: Context): ReportingAuditLog = instance
    }
}

internal enum class ReportingProvider(val wireName: String) {
    APPSFLYER("AppsFlyer"),
    FIREBASE("Firebase"),
    XUANYU("Xuanyu"),
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
) = Unit
