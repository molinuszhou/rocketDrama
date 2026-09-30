package com.dramaflow.app.analytics

import android.content.Context
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.ads.AdRevenueAuditValues
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Local, shareable audit trail for AppsFlyer, Firebase and Xuanyu reporting. */
class ReportingAuditLog private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val directory = File(appContext.filesDir, DIRECTORY_NAME)
    private val file = File(directory, FILE_NAME)

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
    ) {
        synchronized(lock) {
            ensureHeaderLocked()
            val row = listOf(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                BuildConfig.VERSION_NAME,
                provider,
                eventName,
                status,
                placement,
                adUnitId,
                errorCode,
                revenue.currency,
                revenue.ecpm,
                revenue.value,
                detail.take(MAX_DETAIL_LENGTH),
            ).joinToString(",") { csvCell(it) }
            file.appendText("$row\n", Charsets.UTF_8)
            trimIfNeededLocked()
        }
    }

    fun summary(): Summary = synchronized(lock) {
        if (!file.exists()) return@synchronized Summary(0, null)
        var count = 0
        var last: String? = null
        file.useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isNotBlank()) {
                    count += 1
                    last = line.substringBefore(',').trim('"')
                }
            }
        }
        Summary(count, last)
    }

    fun exportFile(): File = synchronized(lock) {
        ensureHeaderLocked()
        file
    }

    fun clear() = synchronized(lock) {
        if (file.exists()) file.delete()
    }

    private fun ensureHeaderLocked() {
        if (!directory.exists()) directory.mkdirs()
        if (!file.exists() || file.length() == 0L) {
            file.writeText(
                "recorded_at_utc,app_version,provider,event_name,status,placement,ad_unit_id,error_code,currency,ecpm,value,detail\n",
                Charsets.UTF_8,
            )
        }
    }

    private fun trimIfNeededLocked() {
        if (file.length() <= MAX_FILE_BYTES) return
        val rows = file.readLines(Charsets.UTF_8)
        val header = rows.firstOrNull().orEmpty()
        val retained = rows.drop(1).takeLast(MAX_EVENTS)
        file.writeText((listOf(header) + retained).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun csvCell(value: String): String =
        "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""

    companion object {
        private const val DIRECTORY_NAME = "reporting_audit"
        // Preserve the historical CSV instead of mixing its original nine-column rows with
        // per-channel currency/eCPM/value data produced after this app update.
        private const val FILE_NAME = "joyedrama_reporting_audit_v2.csv"
        private const val MAX_FILE_BYTES = 1_000_000L
        private const val MAX_EVENTS = 2_000
        private const val MAX_DETAIL_LENGTH = 300

        @Volatile private var instance: ReportingAuditLog? = null

        fun get(context: Context): ReportingAuditLog = instance ?: synchronized(this) {
            instance ?: ReportingAuditLog(context).also { instance = it }
        }
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
) = record(provider.wireName, eventName, status, placement, adUnitId, errorCode, revenue, detail)
